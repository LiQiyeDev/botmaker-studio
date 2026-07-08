package com.botmaker.studio.services.preview;

import com.botmaker.studio.project.ProjectPreferences;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.FileDescriptor;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Opens (once) a persistent {@code org.freedesktop.portal.ScreenCast} session over D-Bus and hands back the
 * live <b>PipeWire</b> stream (fd + node id) plus the monitor's virtual-desktop origin/size. The session is
 * negotiated with {@code persist_mode=2} and the saved <b>restore token</b> (global, in
 * {@link ProjectPreferences}), so GNOME/Wayland shows its share picker <b>at most once ever</b> — every later
 * open reuses the grant silently and independently of the user's own OBS/recording.
 *
 * <p>This is Linux/Wayland plumbing and best-effort: any failure (no session bus, no portal, marshalling
 * error, timeout) throws {@link PortalUnavailableException} so the caller falls back to AWT {@code Robot}.
 * The handshake is CreateSession → SelectSources(persist) → Start → OpenPipeWireRemote, each request
 * completing via the portal's {@code Request.Response} signal on a predicted object path.
 */
public final class PortalScreenCast implements AutoCloseable {

    /** Thrown when the portal path is unavailable; the caller applies its own fallback. */
    public static final class PortalUnavailableException extends Exception {
        public PortalUnavailableException(String message, Throwable cause) { super(message, cause); }
        public PortalUnavailableException(String message) { super(message); }
    }

    /** A live PipeWire screen stream: the socket {@code fd}, the {@code nodeId} to play, and screen geometry. */
    public record Stream(int fd, int nodeId, int originX, int originY, int width, int height) {}

    private static final String PORTAL_BUS = "org.freedesktop.portal.Desktop";
    private static final String PORTAL_PATH = "/org/freedesktop/portal/desktop";
    private static final long REQUEST_TIMEOUT_SECONDS = 120; // generous: covers the one-time user prompt

    private final DBusConnection connection;
    private final String senderToken; // unique bus name, ':'-stripped and '.'→'_', for request path prediction
    private Stream stream;
    private boolean usedRestoreToken;

    private PortalScreenCast(DBusConnection connection) {
        this.connection = connection;
        String unique = connection.getUniqueName(); // e.g. ":1.234"
        this.senderToken = (unique.startsWith(":") ? unique.substring(1) : unique).replace('.', '_');
    }

    /**
     * Connects to the session bus and negotiates the persistent screen-cast session. Returns the live
     * {@code PortalScreenCast}: the caller <b>must keep it open</b> for as long as it plays the stream and
     * {@link #close()} it when done. The D-Bus connection backs the PipeWire session, so letting this handle
     * be garbage-collected (as an earlier version did by returning only the {@link Stream}) tears down the
     * socket mid-use — surfacing as a fatal EOF on the connection thread.
     */
    public static PortalScreenCast open() throws PortalUnavailableException {
        DBusConnection conn;
        try {
            // A dedicated (non-shared) connection: the portal session is bound to this connection's lifetime,
            // so it must not be a process-wide shared bus that another consumer could close underneath us.
            conn = DBusConnectionBuilder.forSessionBus().withShared(false).build();
        } catch (Throwable t) {
            throw new PortalUnavailableException("No D-Bus session bus for the portal", t);
        }
        PortalScreenCast portal = new PortalScreenCast(conn);
        try {
            portal.stream = portal.negotiate();
            return portal;
        } catch (PortalUnavailableException e) {
            portal.onNegotiationFailed();
            throw e;
        } catch (Throwable t) {
            portal.onNegotiationFailed();
            throw new PortalUnavailableException("ScreenCast negotiation failed: " + t.getMessage(), t);
        }
    }

    /** The negotiated live PipeWire stream (valid while this handle is open). */
    public Stream stream() {
        return stream;
    }

    /**
     * Cleanup after a failed negotiation: close the connection and, if we sent a saved restore token, drop it
     * — a stale/invalid token is a common cause of the portal rejecting the session, so the next attempt
     * should re-prompt cleanly rather than replay the bad grant.
     */
    private void onNegotiationFailed() {
        if (usedRestoreToken) ProjectPreferences.updateScreenCastToken("");
        try { connection.close(); } catch (Exception ignored) {}
    }

    private Stream negotiate() throws Exception {
        step("getRemoteObject");
        ScreenCast screenCast = connection.getRemoteObject(PORTAL_BUS, PORTAL_PATH, ScreenCast.class);

        // 1. CreateSession — reserve a session_handle.
        step("CreateSession");
        String sessionToken = "bm_sess_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Variant<?>> createResults = awaitRequest(options -> {
            options.put("session_handle_token", new Variant<>(sessionToken));
            return screenCast.CreateSession(options);
        });
        Object sessionHandle = value(createResults, "session_handle");
        if (sessionHandle == null) throw new PortalUnavailableException("Portal returned no session_handle");
        DBusPath session = new DBusPath(sessionHandle.toString());

        // 2. SelectSources — monitors, embedded cursor, persistent grant with any saved restore token.
        step("SelectSources");
        awaitRequest(options -> {
            options.put("types", new Variant<>(new UInt32(1)));         // 1 = MONITOR
            options.put("multiple", new Variant<>(Boolean.FALSE));
            options.put("cursor_mode", new Variant<>(new UInt32(2)));   // 2 = embedded in the stream
            options.put("persist_mode", new Variant<>(new UInt32(2)));  // 2 = persist until explicitly revoked
            String saved = ProjectPreferences.getScreenCastToken();
            if (isValidToken(saved)) {
                options.put("restore_token", new Variant<>(saved));
                usedRestoreToken = true;
            }
            return screenCast.SelectSources(session, options);
        });

        // 3. Start — triggers the (one-time) picker; returns the stream node + the new restore token.
        step("Start");
        Map<String, Variant<?>> startResults = awaitRequest(options ->
                screenCast.Start(session, "", options));

        Object token = value(startResults, "restore_token");
        if (token != null) ProjectPreferences.updateScreenCastToken(token.toString());

        StreamInfo info = parseFirstStream(startResults);
        if (info == null) throw new PortalUnavailableException("Portal returned no PipeWire stream");

        // 4. OpenPipeWireRemote — a live socket fd to feed pipewiresrc. This reply carries a unix fd, so the
        // transport must support SCM_RIGHTS fd passing (the native-unixsocket transport does); a mismatch here
        // is a prime suspect for the connection being dropped mid-handshake.
        step("OpenPipeWireRemote");
        FileDescriptor fd = screenCast.OpenPipeWireRemote(session, new HashMap<>());
        if (fd == null) throw new PortalUnavailableException("Portal returned no PipeWire fd");

        step("done nodeId=" + info.nodeId);
        return new Stream(fd.getIntFileDescriptor(), info.nodeId, info.originX, info.originY, info.width, info.height);
    }

    /** One-line progress marker so a live run shows exactly which handshake step precedes a disconnect. */
    private static void step(String name) {
        System.out.println("[preview-screencast] step=" + name);
    }

    /**
     * A restore token is an opaque portal string; we only guard against sending an obviously-bad one (blank,
     * or the literal {@code "null"} a prior bug could have persisted) that would make the portal reject and
     * drop the session. A rejected token is also cleared on failure (see {@link #onNegotiationFailed()}).
     */
    private static boolean isValidToken(String token) {
        return token != null && !token.isBlank() && !"null".equalsIgnoreCase(token.trim());
    }

    /**
     * Registers a one-shot handler on the predicted {@code Request} object path, runs {@code call} (which
     * receives a fresh options map with a matching {@code handle_token}), then blocks until the portal's
     * {@code Response} signal arrives (or times out). Returns the response's results dictionary.
     */
    private Map<String, Variant<?>> awaitRequest(RequestCall call) throws Exception {
        String handleToken = "bm_req_" + UUID.randomUUID().toString().replace("-", "");
        String requestPath = PORTAL_PATH + "/request/" + senderToken + "/" + handleToken;

        CompletableFuture<Map<String, Variant<?>>> future = new CompletableFuture<>();
        DBusSigHandler<Request.Response> handler = signal -> {
            if (!requestPath.equals(signal.getPath())) return;
            if (signal.response.intValue() != 0) {
                future.completeExceptionally(new PortalUnavailableException(
                        "Portal request denied/cancelled (code " + signal.response.intValue() + ")"));
            } else {
                future.complete(signal.results);
            }
        };
        connection.addSigHandler(Request.Response.class, handler);
        try {
            Map<String, Variant<?>> options = new HashMap<>();
            options.put("handle_token", new Variant<>(handleToken));
            call.invoke(options);
            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            try { connection.removeSigHandler(Request.Response.class, handler); } catch (Exception ignored) {}
        }
    }

    /** Geometry + node id pulled out of the {@code streams} array in a Start response. */
    private record StreamInfo(int nodeId, int originX, int originY, int width, int height) {}

    /** Parses the first entry of {@code streams} (typed {@code a(ua{sv})}) → node id + position/size. */
    @SuppressWarnings("unchecked")
    private static StreamInfo parseFirstStream(Map<String, Variant<?>> results) {
        Object streams = value(results, "streams");
        if (!(streams instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Object[] struct) || struct.length < 2) return null;

        int nodeId = (struct[0] instanceof UInt32 u) ? u.intValue() : ((Number) struct[0]).intValue();
        int ox = 0, oy = 0, w = 0, h = 0;
        if (struct[1] instanceof Map<?, ?> props) {
            int[] pos = intPair(((Map<String, Variant<?>>) props).get("position"));
            int[] size = intPair(((Map<String, Variant<?>>) props).get("size"));
            if (pos != null) { ox = pos[0]; oy = pos[1]; }
            if (size != null) { w = size[0]; h = size[1]; }
        }
        return new StreamInfo(nodeId, ox, oy, w, h);
    }

    /** A portal {@code (ii)} pair variant → {@code int[2]}, or null. */
    private static int[] intPair(Variant<?> v) {
        if (v == null) return null;
        Object val = v.getValue();
        if (val instanceof Object[] arr && arr.length >= 2 && arr[0] instanceof Number a && arr[1] instanceof Number b) {
            return new int[]{a.intValue(), b.intValue()};
        }
        return null;
    }

    /** Unwraps a {@code Variant<?>} results value to its raw object, or null when absent. */
    private static Object value(Map<String, Variant<?>> results, String key) {
        Variant<?> v = results.get(key);
        return v == null ? null : v.getValue();
    }

    @Override
    public void close() {
        try { connection.close(); } catch (Exception ignored) {}
    }

    // --- D-Bus interface + signal definitions ---

    @FunctionalInterface
    private interface RequestCall { DBusPath invoke(Map<String, Variant<?>> options) throws Exception; }

    @DBusInterfaceName("org.freedesktop.portal.ScreenCast")
    public interface ScreenCast extends DBusInterface {
        DBusPath CreateSession(Map<String, Variant<?>> options);
        DBusPath SelectSources(DBusPath sessionHandle, Map<String, Variant<?>> options);
        DBusPath Start(DBusPath sessionHandle, String parentWindow, Map<String, Variant<?>> options);
        FileDescriptor OpenPipeWireRemote(DBusPath sessionHandle, Map<String, Variant<?>> options);
    }

    @DBusInterfaceName("org.freedesktop.portal.Request")
    public interface Request extends DBusInterface {
        /** {@code Response(u response, a{sv} results)} — response 0 = success, 1 = cancelled, 2 = ended. */
        class Response extends DBusSignal {
            public final UInt32 response;
            public final Map<String, Variant<?>> results;

            public Response(String path, UInt32 response, Map<String, Variant<?>> results) throws org.freedesktop.dbus.exceptions.DBusException {
                super(path, response, results);
                this.response = response;
                this.results = results;
            }
        }
    }
}
