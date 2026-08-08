package com.botmaker.studio.services.pilot;

import com.botmaker.session.DesktopSession;
import com.botmaker.session.video.VideoPacket;
import com.botmaker.session.video.VideoStream;
import com.botmaker.shared.Diag;

import java.awt.Rectangle;
import java.util.function.Consumer;

/**
 * Keeps at most one H.264 encode running, on the session the pilot is currently streaming — the lifecycle half
 * of the video path, kept out of {@link PilotServer} because it is a small state machine and the server is not.
 *
 * <p>The state is "which session, if any, is being encoded", and every transition is driven by one call:
 * {@link #follow}, made from the frame loop each tick with the session it <em>wants</em> encoded (or
 * {@code null} for none). Opening on a route change, closing when the route leaves the session or the last
 * H.264 client disconnects, and noticing that the encoder died all fall out of comparing that wish against the
 * stream in hand. Nothing here is scheduled, and nothing has to be remembered in order to be undone.
 *
 * <p><b>Confined to the frame thread.</b> {@link #follow} and {@link #close} are called only from
 * {@code pilot-frame}; the sink runs on the encoder's own reader thread and touches nothing here. So the fields
 * need no synchronization except {@link #stream}, which {@link #live()} reads from a WS thread when a client
 * says hello.
 */
final class PilotVideo implements AutoCloseable {

    /**
     * How long a freshly opened stream may stay silent before it is written off. It has to cover
     * {@code FfmpegVideoStream}'s whole walk down its encoder list — three candidates that each get seconds to
     * prove themselves — because a stream closed halfway through that walk would be closed just as the software
     * fallback was about to work.
     */
    private static final long OPEN_BUDGET_MS = 20_000;

    /**
     * How long after a session declined to open a stream before asking it again. A decline is cheap — a null
     * return off a memoised probe, no process — and it is <em>temporary</em>: a session whose game has not
     * mapped its window yet has nothing to encode, and half a minute later it does. Latching that answer for
     * the life of the session, as the encoder-failure case below rightly does, would mean a pilot opened one
     * second too early stayed on JPEG until the session was restarted.
     */
    private static final long DECLINE_RETRY_MS = 2000;

    private final int maxEdge;
    private final int fps;

    /** The session {@link #stream} encodes, compared by identity — a different session is a different display. */
    private DesktopSession source;
    private volatile VideoStream stream;
    /** The surface rect this stream's pictures map to, read once at open so it cannot drift mid-stream. */
    private Rectangle rect = new Rectangle();
    private long openedAt;
    /** Latched the first time the stream speaks, so a later silence reads as "died" rather than "starting". */
    private boolean producedOnce;
    /**
     * Set when this session has been shown it <b>cannot</b> give us video: every encoder it offered failed to
     * produce a frame. Without it the frame loop would start another {@code ffmpeg} every tick, forever.
     * Cleared by a change of session, which is the only thing that could change the answer.
     */
    private boolean declined;
    /** When the session last offered no stream at all — a temporary no, unlike {@link #declined}. */
    private long offeredNothingAt;

    PilotVideo(int maxEdge, int fps) {
        this.maxEdge = maxEdge;
        this.fps = fps;
    }

    /**
     * Make the running encode match {@code wanted}, and report whether video is <b>producing</b>.
     *
     * <p>Producing is not the same as "a stream is open": a stream still choosing its encoder is open and
     * silent, and until it speaks the JPEG path is what the client sees. That is also why a silent stream is
     * given {@link #OPEN_BUDGET_MS} rather than being judged on the tick after it opened.
     */
    boolean follow(DesktopSession wanted, Consumer<VideoPacket> sink) {
        VideoStream open = stream;
        if (open != null) {
            if (wanted != source) {
                stop("the pilot moved to another surface");
                open = null;
            } else if (surfaceMoved(wanted)) {
                stop("the encoded window is no longer the one with the pixels");
                open = null;
            } else if (open.alive()) {
                producedOnce = true;
            } else if (producedOnce) {
                stop("the encoder stopped");
                open = null;
            } else if (System.currentTimeMillis() - openedAt > OPEN_BUDGET_MS) {
                stop("no encoder produced a frame");
                // The session is still the one we want; it just cannot be encoded. That has to be remembered
                // against *this* session, which stop() has already forgotten — otherwise the next tick starts
                // another ffmpeg to fail the same way, forever.
                source = wanted;
                declined = true;
                open = null;
            }
        }
        if (wanted == null || declined && wanted == source) {
            return false;
        }
        if (open == null) {
            return start(wanted, sink);
        }
        return open.alive();
    }

    /**
     * Whether the session is now painting somewhere other than where the running stream is pointed.
     *
     * <p>This is the case the root grab did not have. A root cannot vanish, so a stream aimed at one only ever
     * ended with its display; a <em>window</em> is destroyed when a store launcher swaps itself for the game
     * it started, and an encoder holding that drawable has no way to say so except by dying — if it dies at
     * all, rather than going on emitting the last thing it saw. Watching the surface makes the swap a
     * reopen with a fresh rect instead of a frozen picture.
     */
    private boolean surfaceMoved(DesktopSession wanted) {
        try {
            Rectangle now = wanted.videoSurface();
            // Nothing painted right now is not a move: mid-swap there is briefly no window at all, and the
            // stream is about to end on its own if the drawable it holds really went away.
            return now != null && !now.isEmpty() && !now.equals(rect);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Opens a stream on {@code wanted}. Always returns false — a stream that just opened has produced nothing. */
    private boolean start(DesktopSession wanted, Consumer<VideoPacket> sink) {
        long now = System.currentTimeMillis();
        if (wanted == source && now - offeredNothingAt < DECLINE_RETRY_MS) {
            return false;
        }
        source = wanted;
        declined = false;
        producedOnce = false;
        openedAt = now;
        stream = wanted.openVideoStream(maxEdge, fps, sink);
        if (stream == null) {
            // Not worth a log line per tick: a session with no ffmpeg, a Wayland-only one, or one whose game
            // has yet to paint anything, is simply a session the JPEG path serves for now.
            offeredNothingAt = now;
            rect = safeScreen(wanted);
            return false;
        }
        // The stream, not the session, says what it is a picture of: on a compositing backend it encodes a
        // client window, and tagging its frames with the screen would misplace every Interact tap by that
        // window's offset. safeScreen is the floor for a stream that cannot answer.
        rect = safeRect(stream.surface(), wanted);
        return false;
    }

    /** Whether the running stream is producing packets right now. Safe to call from any thread. */
    boolean live() {
        VideoStream open = stream;
        return open != null && open.alive();
    }

    /** The codec string a client configures its decoder with, or {@code null} when nothing is streaming. */
    String codec() {
        VideoStream open = stream;
        return open == null ? null : open.codec();
    }

    /**
     * The surface rect the stream's pictures map to — the same rect the JPEG path would tag them with, because
     * both ask the session the same question. Read once at open so it cannot drift under a client that is
     * already fitting its canvas to it; a surface that really changed ends the stream instead.
     */
    Rectangle rect() {
        return rect;
    }

    private void stop(String why) {
        VideoStream open = stream;
        stream = null;
        source = null;
        producedOnce = false;
        declined = false;
        if (open != null) {
            open.close();
            Diag.log("[Pilot] video stream closed — " + why);
        }
    }

    @Override
    public void close() {
        stop("the pilot shut down");
        declined = false;
    }

    private static Rectangle safeRect(Rectangle reported, DesktopSession fallback) {
        return reported == null || reported.isEmpty() ? safeScreen(fallback) : reported;
    }

    private static Rectangle safeScreen(DesktopSession session) {
        try {
            Rectangle r = session.screen();
            return r == null ? new Rectangle() : r;
        } catch (Throwable ex) {
            return new Rectangle();
        }
    }
}
