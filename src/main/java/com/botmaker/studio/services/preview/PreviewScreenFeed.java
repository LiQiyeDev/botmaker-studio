package com.botmaker.studio.services.preview;

/**
 * The screen half of the preview's capture backend on Wayland: negotiates the persistent
 * {@link PortalScreenCast} session (one-time consent) and plays its {@link PipeWireVideoSource} live video,
 * delivering each frame — with the monitor's virtual-desktop origin — to a {@link FrameSink}. This replaces
 * the per-frame AWT {@code Robot} grab that re-triggered the portal picker endlessly.
 *
 * <p>Negotiation runs on a dedicated background thread (the first-ever run shows the GNOME picker and may
 * block until the user responds), so callers stay responsive. If the portal or GStreamer stack is
 * unavailable it fails once and reports {@link #hasFailed()}, letting the caller decide on a fallback.
 * Window targets do <em>not</em> use this path (they capture natively via the shared controller).
 */
public final class PreviewScreenFeed implements AutoCloseable {

    /** Receives a live ARGB frame plus the captured monitor's top-left in virtual-screen (absolute) coords. */
    public interface FrameSink { void onFrame(int[] argb, int width, int height, int originX, int originY); }

    private final FrameSink sink;

    private Thread negotiator;
    private PortalScreenCast portal;
    private PipeWireVideoSource video;
    private volatile boolean starting;
    private volatile boolean active;
    private volatile boolean failed;
    private volatile boolean closed;

    public PreviewScreenFeed(FrameSink sink) {
        this.sink = sink;
    }

    /** True on a Wayland session, where per-frame {@code Robot} capture re-prompts and this feed is preferred. */
    public static boolean isWayland() {
        return System.getenv("WAYLAND_DISPLAY") != null;
    }

    /** True once the portal + PipeWire attempt has permanently failed (caller should stop expecting frames). */
    public boolean hasFailed() { return failed; }

    /**
     * Kicks off (once) the portal negotiation + live video on a background thread. Idempotent and
     * non-blocking; frames arrive later via the {@link FrameSink}. No-op if already starting/active/failed.
     */
    public synchronized void ensureStarted() {
        if (closed || starting || active || failed) return;
        starting = true;
        negotiator = new Thread(this::negotiate, "preview-screencast");
        negotiator.setDaemon(true);
        negotiator.start();
    }

    private void negotiate() {
        PortalScreenCast p = null;
        try {
            p = PortalScreenCast.open();
            PortalScreenCast.Stream s = p.stream();
            PipeWireVideoSource v = new PipeWireVideoSource(s.fd(), s.nodeId(),
                    (argb, w, h) -> { if (!closed) sink.onFrame(argb, w, h, s.originX(), s.originY()); });
            v.start();
            synchronized (this) {
                if (closed) { v.close(); p.close(); return; }
                this.portal = p;   // keep the portal (its D-Bus connection backs the PipeWire session) alive
                this.video = v;
                this.active = true;
                this.starting = false;
            }
        } catch (Throwable t) {
            // Single-line report; the portal/GStreamer stack simply isn't available and we fall back to Robot.
            System.err.println("[preview] Wayland live feed unavailable: " + t.getMessage());
            if (p != null) { try { p.close(); } catch (Throwable ignored) {} }
            synchronized (this) { failed = true; starting = false; }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        active = false;
        starting = false;
        if (video != null) {
            try { video.close(); } catch (Throwable ignored) {}
            video = null;
        }
        if (portal != null) {
            try { portal.close(); } catch (Throwable ignored) {}
            portal = null;
        }
    }
}
