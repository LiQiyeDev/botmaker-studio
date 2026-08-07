package com.botmaker.studio.services.pilot;

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
 *   <li><b>text</b> server→client: telemetry events ({@code {"type":"telemetry",…}}) and run-state
 *       ({@code {"type":"state","run":"running|stopped|paused","backgroundInput":true}}).</li>
 *   <li><b>text</b> client→server: control commands ({@code {"cmd":"start|stop|pause|resume"}}), the
 *       Interact arm/disarm ({@code {"cmd":"interact","on":true}}) and, once armed, manual gestures
 *       ({@code {"cmd":"input","kind":"tap|down|move|up|scroll","x":…,"y":…,"button":1,"amount":-3}} in
 *       absolute screen coordinates — see {@link PilotInputService}).</li>
 * </ul>
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

    private static final int FRAME_FPS = 12;

    private final EventBus eventBus;
    /** Answers which bot-owned {@code :N} session is live — the highest-priority {@link PilotRoute}. */
    private final PilotSession session;
    /** Decides which surface is streamed and driven ({@code :0} / {@code :N} / an emulator) and owns its connection. */
    private final PilotRoutes routes;
    private final TargetCapture capture;
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
    }

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
                if (client != null) handleCommand(client, ctx.message());
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

    private void handleCommand(Client client, String message) {
        JsonNode node;
        try {
            node = json.readTree(message);
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
        return TelemetrySerializer.stateJson(runState, input.supportsBackgroundInput(lastRoute));
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

    // --- Frame loop (binary messages, per-client backpressure) ---

    private void startFrameLoop() {
        frameExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pilot-frame");
            t.setDaemon(true);
            return t;
        });
        // Fixed *delay*, not fixed rate: an emulator frame is a full-frame PNG pulled over ADB and is routinely
        // slower than the period. At a fixed rate those pile up back-to-back on this single thread, which is a
        // backlog rather than a frame rate; a delay lets each route run at whatever its transport sustains.
        frameExec.scheduleWithFixedDelay(this::pushFrame, 300, 1000 / FRAME_FPS, TimeUnit.MILLISECONDS);
    }

    private void pushFrame() {
        if (clients.isEmpty()) return;
        TargetCapture.Resolved resolved = capture.resolve(routes.current(), lastTarget);
        if (resolved == null) return;
        TargetCapture.Capture cap = resolved.cap();
        byte[] jpeg = TargetCapture.jpegBytes(cap.img());
        if (jpeg == null) return;

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
            AtomicBoolean inFlight = e.getValue().frameInFlight;
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
