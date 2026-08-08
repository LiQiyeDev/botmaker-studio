package com.botmaker.studio.services.pilot;

import com.botmaker.session.DesktopSession;
import com.botmaker.session.video.VideoPacket;
import com.botmaker.shared.Diag;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.ProjectSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import javafx.application.Platform;
import org.eclipse.jetty.websocket.api.WriteCallback;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The remote <b>BotPilot</b> server: one Javalin (embedded Jetty) instance that serves the pilot web app as
 * static files and speaks a WebSocket protocol at {@code /ws?token=…} carrying, over a single connection:
 *
 * <ul>
 *   <li><b>binary</b> server→client: a live JPEG frame of the bot's target surface, prefixed with a 16-byte
 *       header ({@code sx,sy,sw,sh} as big-endian int32) so the client can map overlays. Loss-tolerant —
 *       a frame is <em>dropped</em> for any client whose previous send is still in flight (backpressure).</li>
 *   <li><b>binary</b> server→client, <em>instead of</em> the above for a client that asked: one H.264 access
 *       unit behind a {@link #BINARY_H264} tag byte, at the encoder's own pace rather than the frame loop's —
 *       see {@link #followVideo}.</li>
 *   <li><b>text</b> server→client: telemetry events ({@code {"type":"telemetry",…}}), run-state
 *       ({@code {"type":"state","run":"running|stopped|paused","backgroundInput":true}}, plus a
 *       {@code "reason"} sentence while the stream has no frames to send — see {@link #reportEmpty}) and the
 *       video stream's start/stop ({@code {"type":"video","codec":"avc1.42E01E","sx":…}}).</li>
 *   <li><b>text</b> client→server: control commands ({@code {"cmd":"start|stop|pause|resume"}}), the
 *       Interact arm/disarm ({@code {"cmd":"interact","on":true}}), once armed, manual gestures
 *       ({@code {"cmd":"input","kind":"tap|down|move|up|scroll","x":…,"y":…,"button":1,"amount":-3}} in
 *       absolute screen coordinates — see {@link PilotInputService}) and the codec negotiation
 *       ({@code {"cmd":"hello","accept":["h264"]}} — see {@link #handleHello}).</li>
 * </ul>
 *
 * <p><b>Two frame formats, and only one of them is negotiated.</b> JPEG is what every client gets and what
 * every client got before there was anything to negotiate; H.264 is offered only to a client that asks for it
 * by name, only on a session route, and only while an encoder is actually producing. Every step of that can
 * fail — no {@code ffmpeg}, no working encoder, no {@code VideoDecoder} in the phone's WebView, a route that is
 * an emulator — and every failure lands on the JPEG path, which is why it is not a fallback so much as the
 * floor.
 *
 * <p>Interact is <b>armed per connection</b> and starts disarmed: a passive viewer must never poke the game
 * because someone brushed the screen, and a leaked URL must not become a remote desktop.
 *
 * <p>This is the <b>only</b> live view of what a bot sees — it replaced both the loopback SSE debug
 * dashboard and Studio's in-app preview panel. It is meant to be reachable remotely over a Tailscale tunnel,
 * so the WS handshake is token-gated. Capture and telemetry serialization live in {@link TargetCapture} /
 * {@link TelemetrySerializer}.
 */
public final class PilotServer implements AutoCloseable {

    /**
     * The frame rate the loop <em>aims</em> for. Raised from 12 once a session frame stopped costing three codec
     * passes ({@link com.botmaker.session.Preview}); a route that can't sustain it simply runs slower, because
     * {@link #tick} never queues a frame behind an unfinished one.
     */
    private static final int FRAME_FPS = 24;

    private static final long FRAME_PERIOD_MS = 1000L / FRAME_FPS;

    /** The floor between frames, so a route slower than the period yields the thread rather than spinning. */
    private static final long MIN_FRAME_GAP_MS = 5;

    private final EventBus eventBus;
    /** Answers which bot-owned {@code :N} session is live — the highest-priority {@link PilotRoute}. */
    private final PilotSession session;
    /** Decides which surface is streamed and driven ({@code :0} / {@code :N} / an emulator) and owns its connection. */
    private final PilotRoutes routes;
    private final TargetCapture capture;
    /** The at-most-one H.264 encode of the current session route; see {@link #followVideo}. */
    private final PilotVideo video = new PilotVideo(com.botmaker.session.Preview.MAX_EDGE, FRAME_FPS);
    /** Whether clients have been told a stream is running, so start/stop are each announced exactly once. */
    private boolean videoAnnounced;
    private final PilotControlService control;
    private final PilotInputService input = new PilotInputService();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Per-connection state: a "send in flight" latch for frame backpressure, and whether this client has
     * armed Interact. Both are per-client on purpose — one phone driving the game must not arm a second
     * viewer, and one slow client must not stall everyone's frames.
     */
    private static final class Client {
        final AtomicBoolean frameInFlight = new AtomicBoolean(false);
        final AtomicBoolean interact = new AtomicBoolean(false);
        /** Whether this client said hello and named H.264 — false for every build that predates {@code hello}. */
        volatile boolean h264;
        /**
         * Whether this client has been given a decodable entry point and may therefore be sent inter-coded
         * pictures. Cleared whenever a packet is dropped for it: a decoder fed a picture whose reference is
         * missing does not recover on its own, so a dropped frame has to cost a resync to the next keyframe
         * rather than a stream that stays broken until the route changes.
         */
        final AtomicBoolean decoding = new AtomicBoolean(false);
    }

    /**
     * The first byte of an H.264 binary message. There is no such byte on a JPEG frame, and deliberately so:
     * the JPEG framing is already deployed on phones this Studio cannot update, so the new payload is the one
     * that carries a tag. A client only ever sees this after the {@code video} text message that announced the
     * stream, on the same ordered socket, so the tag is a check rather than the discriminator.
     */
    private static final byte BINARY_H264 = 2;

    /**
     * The same tag for an access unit a decoder can start on. It is a separate value rather than a second byte
     * because the client needs the fact anyway — WebCodecs' {@code EncodedVideoChunk} is constructed with
     * {@code type: "key" | "delta"} and has no way to work it out from the bytes — and the tag byte had 254
     * spare values.
     */
    private static final byte BINARY_H264_KEY = 3;

    /** Connected, authorized clients. */
    private final Map<WsContext, Client> clients = new ConcurrentHashMap<>();

    private Javalin app;
    private ScheduledExecutorService frameExec;
    private volatile TelemetryEvent.Target lastTarget;
    /** The surface of the most recently pushed frame — the only region Interact gestures may land in. */
    private volatile PilotInputService.Bounds lastBounds;
    /**
     * The route that produced the most recently pushed frame. Recorded beside the bounds and replayed on for
     * the same reason the bounds are: a gesture belongs to the frame the user touched, not to whatever the
     * route resolver would answer by the time it arrives.
     */
    private volatile PilotRoute lastRoute = PilotRoute.DESKTOP;
    private volatile String token;
    private volatile String runState = "stopped";
    /**
     * How many ticks in a row produced no frame, and whether that has already been said. Both are touched only
     * from the single {@code pilot-frame} thread, so they need no synchronization; {@link #emptyReason} is
     * volatile because {@link #stateJson()} is also called from the WS threads.
     */
    private int emptyFrames;
    private boolean emptyReported;
    /** The sentence sent with the {@code state} message while frames are missing, or {@code null} when they aren't. */
    private volatile String emptyReason;

    public PilotServer(EventBus eventBus, ProjectSettingsService settings, PilotControlService control,
                       java.nio.file.Path resourcesDir) {
        this.eventBus = eventBus;
        // The project's one nested session, read live — whether the ▶ Launch toolbar or the pilot's own
        // Background-mode box started it. Nobody has to remember to tell this server about it.
        this.session = PilotSession.forProject(resourcesDir);
        this.routes = PilotRoutes.forProject(session, resourcesDir, settings);
        this.capture = TargetCapture.forProject(settings, resourcesDir);
        this.control = control;
    }

    /**
     * Endpoint details to surface in the UI. When {@code publicBaseUrl} is non-null the server is fronted by
     * Tailscale Funnel (public HTTPS), so {@link #url()} yields the {@code https://…ts.net} address; otherwise
     * it falls back to the direct {@code http://host:port} bind.
     */
    public record Endpoint(String host, int port, String token, String publicBaseUrl) {
        public String url() {
            return publicBaseUrl != null
                    ? publicBaseUrl + "/?token=" + token
                    : "http://" + host + ":" + port + "/?token=" + token;
        }
    }

    /** Records the Funnel front (if any) so it's torn down together with the server in {@link #close()}. */
    private volatile TailscaleFunnelService funnel;

    /** Guards the one-time EventBus subscription so a stop()+start() doesn't double-register handlers. */
    private boolean subscribed;

    /**
     * Starts the server bound to {@code host} (e.g. {@code 127.0.0.1}, a Tailscale IP, or {@code 0.0.0.0}).
     * Idempotent — returns the existing endpoint if already running.
     */
    public synchronized Endpoint start(String host) {
        if (app != null) return new Endpoint(host, app.port(), token, null);

        // Reuse a persisted token so the pairing URL is stable across Studio restarts — a phone that paired
        // once reconnects without rescanning. Only mint (and persist) a fresh one the very first time.
        token = com.botmaker.studio.project.ProjectPreferences.loadPilotToken();
        if (token == null || token.isBlank()) {
            token = newToken();
            com.botmaker.studio.project.ProjectPreferences.updatePilotToken(token);
        }
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/";
                s.directory = "/pilot";
                s.location = Location.CLASSPATH;
            });
        });
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                if (!authorized(ctx)) { ctx.closeSession(); return; }
                clients.put(ctx, new Client());
                ctx.enableAutomaticPings();
                ctx.send(stateJson()); // let a fresh client render the current run state immediately
            });
            ws.onMessage(ctx -> {
                Client client = clients.get(ctx);
                if (client != null) handleCommand(client, ctx);
            });
            // A phone that vanishes mid-drag never sends its UP, so the release happens here instead. It is
            // unconditional rather than gated on "was it this client's drag": a drag cut short is a gesture
            // the user repeats, a button left down is a desktop they can't click until Studio exits.
            ws.onClose(ctx -> { clients.remove(ctx); input.releaseHeld(); });
            ws.onError(ctx -> { clients.remove(ctx); input.releaseHeld(); });
        });
        // Reuse the last bound port when it's free so the tailnet-direct URL is stable across Studio restarts
        // (mirrors the persisted token). Fall back to an OS-assigned ephemeral port if it's taken, then persist
        // whatever we actually got.
        int desired = com.botmaker.studio.project.ProjectPreferences.loadPilotPort();
        try {
            app.start(host, desired); // desired 0 → OS-assigned ephemeral port
        } catch (Exception bindFailed) {
            app.start(host, 0);
        }
        com.botmaker.studio.project.ProjectPreferences.updatePilotPort(app.port());

        // Subscribe once per instance — a stop()+start() (e.g. Funnel-fail rebind) must not double-register
        // these handlers, since the EventBus has no unsubscribe and close() can't remove them.
        if (!subscribed) {
            subscribed = true;
            eventBus.subscribe(CoreApplicationEvents.ViewFeedbackEvent.class, e -> onTelemetry(e.feedback()), false);
            eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> setRunState("running"), false);
            eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> {
                control.onRunStopped();
                setRunState("stopped");
            }, false);
        }

        startFrameLoop();
        return new Endpoint(host, app.port(), token, null);
    }

    /**
     * Records that this (already-{@link #start(String) started}, loopback-bound) server is now fronted by
     * Tailscale Funnel at {@code publicBaseUrl} and returns the public HTTPS endpoint. The {@code funnel} is
     * kept so {@link #close()} tears the public exposure down with the server.
     */
    public synchronized Endpoint attachFunnel(TailscaleFunnelService funnel, String publicBaseUrl) {
        this.funnel = funnel;
        return new Endpoint("127.0.0.1", app != null ? app.port() : 0, token, publicBaseUrl);
    }

    @Override
    public synchronized void close() {
        if (frameExec != null) { frameExec.shutdownNow(); frameExec = null; }
        video.close(); // the ffmpeg is reaped with its session anyway, but not until the session goes
        input.close();  // never hand the desktop back with a mouse button still down
        routes.close(); // drop any held emulator connection with the loop that was using it
        clients.clear();
        if (app != null) { app.stop(); app = null; }
        if (funnel != null) { funnel.disable(); funnel = null; }
    }

    public synchronized boolean isRunning() {
        return app != null;
    }

    /**
     * The machine's Tailscale IPv4 (CGNAT {@code 100.64.0.0/10}) if the tunnel is up, so the pilot binds to
     * the private tailnet rather than every interface. {@code null} if no Tailscale address is found — the
     * caller then decides whether to bind {@code 0.0.0.0} with a warning.
     */
    public static String detectTailscaleHost() {
        try {
            var nics = java.net.NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                for (var addr : java.util.Collections.list(nics.nextElement().getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address) {
                        byte[] b = addr.getAddress();
                        // 100.64.0.0/10 → first octet 100, second octet 64–127.
                        if ((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // --- Auth ---

    private boolean authorized(WsContext ctx) {
        String provided = ctx.queryParam("token");
        if (token == null || provided == null) return false;
        // Constant-time compare: this handshake is reachable over the public internet via Funnel, so avoid
        // leaking the token length/prefix through String.equals's early-out timing.
        return java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String newToken() {
        byte[] b = new byte[24]; // 192 bits — the sole guard once Funnel exposes this publicly.
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Revokes the current pairing token: generates + persists a fresh one so previously-paired phones can no
     * longer authorize (they must rescan). Safe to call while running — new connections use the new token,
     * and any in-flight client keeps its socket until it reconnects. Returns the new token.
     */
    public synchronized String resetToken() {
        token = newToken();
        com.botmaker.studio.project.ProjectPreferences.updatePilotToken(token);
        return token;
    }

    // --- Inbound control commands ---

    private void handleCommand(Client client, io.javalin.websocket.WsMessageContext ctx) {
        JsonNode node;
        try {
            node = json.readTree(ctx.message());
        } catch (Exception e) {
            return;
        }
        PilotCommand cmd = PilotCommand.from(node.path("cmd").asText(null)).orElse(null);
        if (cmd == null) return;   // an older or newer phone build; ignore, never drop the connection
        switch (cmd) {
            case START -> Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent()));
            case STOP -> Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent()));
            case PAUSE -> { control.pause(); refreshPausedState(); }
            case RESUME -> { control.resume(); refreshPausedState(); }
            case INTERACT -> client.interact.set(node.path("on").asBoolean(false));
            case INPUT -> handleInput(client, node);
            case HELLO -> handleHello(client, ctx, node);
        }
    }

    /**
     * The client naming what it can decode. Only {@code h264} means anything today, and saying nothing means
     * JPEG — so an older phone, or one whose WebView has no {@code VideoDecoder}, needs no special case
     * anywhere: it simply never appears in the set of clients the video path sends to.
     *
     * <p>A client may say hello again at any time, and the pilot client does exactly that when configuring its
     * decoder throws — a hardware decoder can refuse a stream it advertised support for, and the only honest
     * recovery is to stop being an H.264 client. Re-sending hello with an empty {@code accept} is that.
     */
    private void handleHello(Client client, WsContext ctx, JsonNode node) {
        boolean wantsH264 = false;
        for (JsonNode codec : node.path("accept")) {
            wantsH264 |= "h264".equals(codec.asText(null));
        }
        client.h264 = wantsH264;
        client.decoding.set(false);
        if (!wantsH264) {
            return;
        }
        // A client that joins a running stream is told about it now rather than at the next route change,
        // which on a stable session would be never.
        if (video.live()) {
            sendVideoStart(ctx);
        }
    }

    /**
     * One manual Interact gesture. Dropped silently unless this connection armed Interact and we have a
     * frame surface to bound it by — an unarmed client's pointer events must never reach the desktop.
     */
    private void handleInput(Client client, JsonNode node) {
        if (!client.interact.get()) return;
        PilotInputService.Kind kind;
        try {
            kind = PilotInputService.Kind.valueOf(node.path("kind").asText("").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknownKind) {
            return;
        }
        input.apply(lastRoute, kind, node.path("x").asInt(), node.path("y").asInt(),
                node.path("button").asInt(1), node.path("amount").asInt(0), lastBounds);
    }

    /** After a pause/resume, reflect it in run-state (only meaningful while a run is active). */
    private void refreshPausedState() {
        if ("stopped".equals(runState)) return;
        setRunState(control.isPaused() ? "paused" : "running");
    }

    // --- Telemetry + state fan-out (text messages) ---

    private void onTelemetry(TelemetryEvent te) {
        if (te == null) return;
        lastTarget = te.target();
        broadcastText(TelemetrySerializer.telemetryJson(te));
    }

    private void setRunState(String state) {
        runState = state;
        broadcastText(stateJson());
    }

    /** Built by {@link TelemetrySerializer}, which owns every text message that leaves here. */
    private String stateJson() {
        return TelemetrySerializer.stateJson(runState, input.supportsBackgroundInput(lastRoute), emptyReason);
    }

    /**
     * How many consecutive frameless ticks are worth mentioning. A grab that misses once is normal — an
     * emulator between screens, a window mid-swap — and saying so would flicker a warning on every route. Two
     * seconds of nothing at {@link #FRAME_FPS} is not a hiccup.
     */
    private static final int EMPTY_FRAMES_BEFORE_SAYING_SO = FRAME_FPS * 2;

    /**
     * A tick produced no frame. Count it, and once the run is long enough to mean something, tell the client
     * <em>why</em> instead of leaving it staring at a canvas that simply stopped updating.
     *
     * <p>The reason is derived from the route, because that is what determines the answer: a session route with
     * nothing on its {@code :N} root is the Wayland-only case ({@code PilotRoutes} rung 1 and
     * {@link com.botmaker.session.DesktopSession#x11Capturable()}); an emulator route is a device that stopped
     * answering ADB. Said once per outage, not once per tick.
     */
    private void reportEmpty(PilotRoute route) {
        if (++emptyFrames < EMPTY_FRAMES_BEFORE_SAYING_SO || emptyReported) return;
        emptyReported = true;
        // Forget what was last logged, so the frames coming back is a line too and not just their stopping.
        loggedSurface = null;
        Diag.log("[Pilot] no frame for " + emptyFrames + " ticks on " + (route == null ? "the desktop" : route));
        emptyReason = switch (route == null ? PilotRoute.DESKTOP : route) {
            case PilotRoute.Session ignored ->
                    "The background session is not showing any pixels on its X display. A Wayland-only app "
                            + "(such as Waydroid) renders where an X11 grab cannot see it — point the project's "
                            + "capture source at the emulator instead.";
            case PilotRoute.Emulator ignored ->
                    "The emulator stopped answering over ADB — check that the instance is still running.";
            case PilotRoute.Desktop ignored -> "The screen could not be captured.";
        };
        broadcastText(stateJson());
    }

    /** The last surface logged, so {@link #logSurface} can print on change instead of 24 times a second. */
    private String loggedSurface;

    /**
     * Say what is being streamed, and from where, <b>whenever that changes</b>.
     *
     * <p>This exists because its absence was expensive. "The pilot is black" was indistinguishable from the
     * outside between a route that had been demoted, a grab that returned nothing, and a grab that returned a
     * frame of a display nothing was painting — and the answer turned out to be the third, which no log line
     * anywhere would have shown. One line per change costs nothing on a stable session and names the surface
     * the next report is about.
     */
    private void logSurface(PilotRoute route, int sx, int sy, int sw, int sh) {
        String line = switch (route == null ? PilotRoute.DESKTOP : route) {
            case PilotRoute.Session ignored -> "session";
            case PilotRoute.Emulator ignored -> "emulator";
            case PilotRoute.Desktop ignored -> "desktop";
        } + " " + sw + "x" + sh + "+" + sx + "+" + sy;
        if (line.equals(loggedSurface)) return;
        loggedSurface = line;
        Diag.log("[Pilot] streaming " + line);
    }

    /** A frame arrived — by either path. Retract the warning, or the client explains a problem it no longer has. */
    private void clearEmptyReport() {
        emptyFrames = 0;
        if (emptyReported) {
            emptyReported = false;
            emptyReason = null;
            broadcastText(stateJson());
        }
    }

    private void broadcastText(String text) {
        for (WsContext ctx : clients.keySet()) {
            try {
                ctx.send(text);
            } catch (Exception e) {
                clients.remove(ctx);
            }
        }
    }

    // --- H.264 (the fast path, when both ends can take it) ---

    /**
     * Brings the H.264 encode in line with {@code asked} and reports whether it now carries <em>every</em>
     * client — in which case this tick has no JPEG to grab at all, which is the saving that makes the video
     * path worth having on Studio's side as well as the phone's.
     *
     * <p>Video is only ever offered on a {@link PilotRoute.Session}: it grabs an X display directly, so the
     * emulator route (pixels that arrive over ADB, never on a display) and the {@code :0} route (the user's own
     * screen, which this must not hand a GPU encoder) have nothing for it to read. Both keep the JPEG path,
     * which is what they had.
     */
    private boolean followVideo(PilotRoute asked) {
        DesktopSession wanted = asked instanceof PilotRoute.Session(DesktopSession s) && anyClientWantsVideo()
                ? s : null;
        boolean live = video.follow(wanted, this::onVideoPacket);
        if (live != videoAnnounced) {
            videoAnnounced = live;
            announceVideo(live);
        }
        if (!live) return false;

        // The video is the frame source now, so it owes the same two facts the JPEG path publishes: an Interact
        // gesture is clamped to the surface the client was shown and replayed on the route that produced it.
        Rectangle surface = video.rect();
        lastRoute = asked;
        lastBounds = new PilotInputService.Bounds(surface.x, surface.y, surface.width, surface.height);
        clearEmptyReport();
        logSurface(asked, surface.x, surface.y, surface.width, surface.height);
        return clients.values().stream().allMatch(c -> c.h264);
    }

    private boolean anyClientWantsVideo() {
        return clients.values().stream().anyMatch(c -> c.h264);
    }

    /** Tells the H.264 clients that a stream started (with its codec and rect) or ended. */
    private void announceVideo(boolean started) {
        for (Map.Entry<WsContext, Client> e : clients.entrySet()) {
            if (!e.getValue().h264) continue;
            e.getValue().decoding.set(false);
            if (started) {
                sendVideoStart(e.getKey());
            } else {
                try {
                    e.getKey().send(TelemetrySerializer.videoStoppedJson());
                } catch (Exception gone) {
                    clients.remove(e.getKey());
                }
            }
        }
    }

    private void sendVideoStart(WsContext ctx) {
        Rectangle r = video.rect();
        try {
            ctx.send(TelemetrySerializer.videoJson(video.codec(), r.x, r.y, r.width, r.height));
        } catch (Exception gone) {
            clients.remove(ctx);
        }
    }

    /**
     * One encoded picture, on the encoder's reader thread. Sent to each H.264 client that has an entry point,
     * with the same in-flight backpressure the JPEG path uses — and one extra consequence: a dropped picture
     * puts that client back to waiting for a keyframe, because a decoder handed a picture whose reference never
     * arrived produces garbage indefinitely rather than one bad frame.
     */
    private void onVideoPacket(VideoPacket packet) {
        if (clients.isEmpty()) return;
        byte[] payload = new byte[1 + packet.annexB().length];
        payload[0] = packet.keyframe() ? BINARY_H264_KEY : BINARY_H264;
        System.arraycopy(packet.annexB(), 0, payload, 1, packet.annexB().length);

        for (Map.Entry<WsContext, Client> e : clients.entrySet()) {
            WsContext ctx = e.getKey();
            Client client = e.getValue();
            if (!client.h264) continue;
            // A client that has not been given a decodable entry point yet waits for the next keyframe; the
            // encoder's GOP is what bounds that wait.
            if (!client.decoding.get() && !packet.keyframe()) continue;
            AtomicBoolean inFlight = client.frameInFlight;
            if (!inFlight.compareAndSet(false, true)) {
                client.decoding.set(false);
                continue;
            }
            client.decoding.set(true);
            try {
                ctx.session.getRemote().sendBytes(ByteBuffer.wrap(payload), new WriteCallback() {
                    @Override public void writeSuccess() { inFlight.set(false); }
                    @Override public void writeFailed(Throwable x) { inFlight.set(false); clients.remove(ctx); }
                });
            } catch (Exception ex) {
                inFlight.set(false);
                clients.remove(ctx);
            }
        }
    }

    // --- Frame loop (binary messages, per-client backpressure) ---

    private void startFrameLoop() {
        frameExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pilot-frame");
            t.setDaemon(true);
            return t;
        });
        frameExec.schedule(this::tick, 300, TimeUnit.MILLISECONDS);
    }

    /**
     * One frame, then reschedule for what is <em>left</em> of the period.
     *
     * <p>This replaced a fixed delay, and it is not the same thing as a fixed rate. A fixed rate queues ticks
     * back-to-back on this single thread the moment a grab runs long — an emulator frame pulled over ADB
     * routinely does — which is a backlog, not a frame rate, and the reason the loop was a fixed *delay* to
     * begin with. But a fixed delay charges the full period <em>on top of</em> the work, so a route that
     * captures in 5 ms still ran at {@link #FRAME_FPS} rather than at what it could sustain. Subtracting the
     * work keeps the no-backlog property (the next tick is only ever scheduled once this one has returned)
     * while letting a fast route reach the target rate. The floor is there so a route that cannot keep up still
     * yields the thread instead of spinning.
     */
    private void tick() {
        long started = System.nanoTime();
        try {
            pushFrame();
        } catch (Throwable ex) {
            // A frame that threw must not silently end the loop — that is a pilot that goes dark forever.
            Diag.error("[Pilot] frame failed: " + ex);
        }
        long workMs = (System.nanoTime() - started) / 1_000_000L;
        long delay = Math.max(MIN_FRAME_GAP_MS, Math.min(FRAME_PERIOD_MS, FRAME_PERIOD_MS - workMs));
        ScheduledExecutorService exec = frameExec;
        if (exec == null || exec.isShutdown()) return;
        try {
            exec.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            // close() won the race; there is nothing left to serve frames to.
        }
    }

    private void pushFrame() {
        if (clients.isEmpty()) {
            followVideo(null);   // nobody to encode for; let the ffmpeg go rather than heat a GPU for no one
            return;
        }
        PilotRoute asked = routes.current();
        if (followVideo(asked)) return;   // every client is on video; there is no JPEG left to grab one for
        TargetCapture.Resolved resolved = capture.resolve(asked, lastTarget);
        if (resolved == null) {
            reportEmpty(asked);
            return;
        }
        TargetCapture.Capture cap = resolved.cap();
        // Already-encoded on the session route: the agent that holds :N produced these bytes, so nothing here
        // decoded a PNG only to re-encode it.
        byte[] jpeg = resolved.bytes();
        if (jpeg == null) {
            reportEmpty(asked);
            return;
        }
        clearEmptyReport();
        logSurface(resolved.route(), cap.sx(), cap.sy(), cap.sw(), cap.sh());

        // Interact gestures are replayed on the route that produced this frame and clamped to what the client
        // was actually shown, so both must be published from here — the one place that knows what went over the
        // wire — and both must come from the *same* resolution. The route is therefore the one the capture
        // reports, never the one we asked for: those differed whenever a grab failed, and the client was then
        // told it was touching a surface it had not been shown. A route change also changes whether input is
        // background-safe, which the client renders as a warning, so tell it rather than leaving a stale one up.
        PilotRoute route = resolved.route();
        boolean routeChanged = lastRoute == null || !lastRoute.getClass().equals(route.getClass());
        lastRoute = route;
        lastBounds = new PilotInputService.Bounds(cap.sx(), cap.sy(), cap.sw(), cap.sh());
        if (routeChanged) broadcastText(stateJson());

        byte[] payload = new byte[16 + jpeg.length];
        ByteBuffer.wrap(payload)
                .putInt(cap.sx()).putInt(cap.sy()).putInt(cap.sw()).putInt(cap.sh())
                .put(jpeg);

        for (Map.Entry<WsContext, Client> e : clients.entrySet()) {
            WsContext ctx = e.getKey();
            Client client = e.getValue();
            // A client being fed H.264 must not also be fed JPEG: it would draw the two interleaved, at two
            // different latencies, which looks exactly like a stutter in the video.
            if (client.h264 && video.live()) continue;
            AtomicBoolean inFlight = client.frameInFlight;
            // Drop this frame for any client still flushing the previous one — keeps real-time feel and
            // stops one slow client from stalling the whole loop.
            if (!inFlight.compareAndSet(false, true)) continue;
            try {
                ctx.session.getRemote().sendBytes(ByteBuffer.wrap(payload), new WriteCallback() {
                    @Override public void writeSuccess() { inFlight.set(false); }
                    @Override public void writeFailed(Throwable x) { inFlight.set(false); clients.remove(ctx); }
                });
            } catch (Exception ex) {
                inFlight.set(false);
                clients.remove(ctx);
            }
        }
    }
}
