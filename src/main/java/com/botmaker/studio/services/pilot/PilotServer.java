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
    private volatile String token;
    private volatile String runState = "stopped";

    public PilotServer(EventBus eventBus, ProjectSettingsService settings, PilotControlService control) {
        this.eventBus = eventBus;
        this.capture = new TargetCapture(settings);
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
            ws.onClose(ctx -> clients.remove(ctx));
            ws.onError(ctx -> clients.remove(ctx));
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
        String cmd = node.path("cmd").asText(null);
        if (cmd == null) return;
        switch (cmd) {
            case "start" -> Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent()));
            case "stop" -> Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent()));
            case "pause" -> { control.pause(); refreshPausedState(); }
            case "resume" -> { control.resume(); refreshPausedState(); }
            case "interact" -> client.interact.set(node.path("on").asBoolean(false));
            case "input" -> handleInput(client, node);
            default -> { /* ignore unknown */ }
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
        input.apply(kind, node.path("x").asInt(), node.path("y").asInt(),
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
        broadcastText("{\"type\":\"telemetry\",\"event\":" + TelemetrySerializer.eventJson(te) + "}");
    }

    private void setRunState(String state) {
        runState = state;
        broadcastText(stateJson());
    }

    /**
     * {@code backgroundInput} tells the client whether Interact will leave the host's real cursor alone, so
     * it can warn before the user's pointer visibly gets hijacked (Linux uinput/XTest backends).
     */
    private String stateJson() {
        return "{\"type\":\"state\",\"run\":\"" + runState + "\",\"backgroundInput\":"
                + input.supportsBackgroundInput() + "}";
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
        frameExec.scheduleAtFixedRate(this::pushFrame, 300, 1000 / FRAME_FPS, TimeUnit.MILLISECONDS);
    }

    private void pushFrame() {
        if (clients.isEmpty()) return;
        TargetCapture.Capture cap = capture.resolve(lastTarget);
        if (cap == null) return;
        byte[] jpeg = TargetCapture.jpegBytes(cap.img());
        if (jpeg == null) return;

        // Interact gestures are clamped to whatever the client was last actually shown, so the bound must be
        // published from here — the one place that knows what went over the wire.
        lastBounds = new PilotInputService.Bounds(cap.sx(), cap.sy(), cap.sw(), cap.sh());

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
