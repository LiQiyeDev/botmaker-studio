package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.PointerPolicy;
import com.botmaker.studio.emulator.EmulatorSurface;

/**
 * Replays the pilot's manual "Interact" gestures onto whichever surface the bot is acting on.
 *
 * <p>The pilot streams that surface; with Interact armed, a tap/drag/scroll on the stream arrives here in the
 * coordinates of the frame the user touched, and is replayed on the {@link PilotRoute} that produced it — the
 * same plumbing a running bot's {@code Mouse} calls use, so a hand gesture and a bot action reach the game by
 * exactly one path. Two of the three routes go through a {@link NativeController} (a real {@code :0} desktop
 * or a nested {@code :N} display); the third is an emulator, where there is no display and no pointer and the
 * verbs are Android's own whole-gesture ones (see {@link #applyToEmulator}).
 *
 * <p><b>Reliability beats cursor safety here.</b> The cursor-preserving Linux default ({@code XSendEvent})
 * sends synthetic events flagged {@code send_event=True}, which every Wine/Proton game — the targets Interact
 * exists for — ignores, so taps silently did nothing. Windows had the same bug in a different dialect:
 * {@code PostMessage} puts a click in the window's message queue, which a game reading raw input never looks
 * at. The controller is therefore asked once, lazily, for {@link NativeController#useReliableInput()}, which
 * on both platforms switches to real device input. After escalating, {@link #supportsBackgroundInput}
 * honestly reports {@code false}, which {@code PilotServer} forwards as the state message's
 * {@code backgroundInput} flag and the pilot renders as its "moves the computer's real cursor" warning. The
 * escalation is process-wide and sticky, so a bot run in the same Studio session after Interact was used also
 * drives the real pointer.
 *
 * <p><b>Every display-backed gesture drives the real pointer</b>, and on the host {@code :0} each puts it back where it
 * started. A tap used to take the old {@code postLeftClickScreen} on the theory that it was the one gesture
 * with a cursor-preserving direct-to-window path — that path is exactly the one games drop, which is why taps
 * did nothing while drags (already on {@code mouseMove}/{@code mouseButton}) worked. In a session the
 * restoring warp is dropped as well ({@link PointerPolicy}): there is no user cursor to hand back.
 *
 * <p><b>Bounds are not optional.</b> Every coordinate is clamped to the rect the client was actually shown
 * (the last pushed frame's surface). A pilot session is reachable over a public Funnel URL; without the clamp
 * a client could drive the pointer anywhere on the host's desktop, including over the Studio itself.
 */
public final class PilotInputService {

    /** The surface the client is currently being shown — the only region input may land in. */
    public record Bounds(int sx, int sy, int sw, int sh) {
        boolean contains(int x, int y) {
            return x >= sx && x < sx + sw && y >= sy && y < sy + sh;
        }
    }

    /** One gesture step from the client. {@code amount} is only read for {@link Kind#SCROLL}. */
    public enum Kind { TAP, DOWN, MOVE, UP, SCROLL }

    /**
     * The host {@code :0} controller, resolved lazily (constructing it probes X11/Win32 and must not run at
     * Studio startup) and only when the route actually is the desktop. Its escalation is sticky by design.
     */
    private NativeController host;

    /** Where the pointer was when the current drag started, restored on {@code UP}. Null when not dragging. */
    private java.awt.Point dragOrigin;

    /** Where on the surface the current drag started — the emulator route's swipe needs both ends at once. */
    private java.awt.Point touchOrigin;

    /** When the current drag started, so an emulator swipe lasts as long as the gesture actually did. */
    private long touchStartedAt;

    /**
     * Applies one gesture at the coordinates of {@code route}'s surface, ignoring anything outside
     * {@code bounds}.
     *
     * <p>The route is passed in rather than read from a holder because it must be the route that produced the
     * frame the user touched — the caller records it beside the bounds when it pushes a frame. Reading "the
     * current route" here instead would let a gesture aimed at an emulator land on the desktop the instant the
     * project's capture source changed under it.
     *
     * @return true if the gesture was dispatched, false if it was rejected (out of bounds, no controller)
     */
    public synchronized boolean apply(PilotRoute route, Kind kind, int x, int y, int button, int amount,
                                      Bounds bounds) {
        if (bounds == null || !bounds.contains(x, y)) return false;
        if (route instanceof PilotRoute.Emulator(EmulatorSurface surface)) {
            return applyToEmulator(surface, kind, x, y, amount);
        }
        NativeController nc = controller(route);
        if (nc == null) return false;

        int btn = button <= 0 ? 1 : button;
        try {
            switch (kind) {
                // Whether a gesture hands the cursor back is PointerPolicy's call, not this switch's. The drag
                // gestures can't restore mid-gesture in any case: the cursor has to stay with the gesture until the
                // button is released, so UP is where the restore belongs (see dragOrigin).
                case TAP -> PointerPolicy.click(nc, sessionOf(route), x, y, btn);
                case DOWN -> {
                    dragOrigin = nc.cursorPosition();
                    nc.mouseMove(x, y);
                    nc.mouseButton(btn, true);
                }
                case MOVE -> nc.mouseMove(x, y);
                case UP -> {
                    nc.mouseMove(x, y);
                    nc.mouseButton(btn, false);
                    PointerPolicy.restoreTo(nc, sessionOf(route), dragOrigin);
                    dragOrigin = null;
                }
                case SCROLL -> { nc.mouseMove(x, y); nc.scroll(amount); }
            }
            return true;
        } catch (Exception e) {
            System.err.println("Pilot interact " + kind + " failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * One gesture against an emulator, which has <b>no pointer to move</b> — the reason this can't be a
     * translation of the desktop path call for call. Android's input verbs are whole gestures:
     *
     * <ul>
     *   <li>{@code TAP} is one {@code input tap};</li>
     *   <li>a drag is one {@code input swipe} carrying <em>both</em> ends, so {@code DOWN} only remembers where
     *       it started, {@code MOVE} dispatches nothing at all (a swipe per move would be a stutter of
     *       unrelated flicks, not a drag), and {@code UP} sends the whole thing — over the time the user
     *       actually took, which is what separates a drag from a fling;</li>
     *   <li>{@code SCROLL} is a swipe centred on the pointer.</li>
     * </ul>
     *
     * <p>An {@code UP} with no remembered {@code DOWN} (a reconnect mid-gesture) degrades to a tap rather than
     * to a swipe from nowhere.
     */
    private boolean applyToEmulator(EmulatorSurface surface, Kind kind, int x, int y, int amount) {
        try {
            switch (kind) {
                case TAP -> surface.tap(x, y);
                case DOWN -> {
                    touchOrigin = new java.awt.Point(x, y);
                    touchStartedAt = System.currentTimeMillis();
                }
                case MOVE -> { /* nothing to move: a touch screen has no hover */ }
                case UP -> {
                    java.awt.Point from = touchOrigin;
                    touchOrigin = null;
                    if (from == null) {
                        surface.tap(x, y);
                    } else {
                        surface.drag(from.x, from.y, x, y,
                                Math.max(1, System.currentTimeMillis() - touchStartedAt));
                    }
                }
                case SCROLL -> surface.scroll(x, y, amount);
            }
            return true;
        } catch (Exception e) {
            System.err.println("Pilot interact " + kind + " on emulator failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * The session a gesture drives, or {@code null} for the host {@code :0} — the input to
     * {@link PointerPolicy}, which decides whether a gesture hands the cursor back. That decision used to live
     * here as a private {@code sessionOwnsPointer()}; it moved to shared once it turned out the SDK's own click
     * path had never implemented it and was throwing every in-session click away.
     */
    private static DesktopSession sessionOf(PilotRoute route) {
        return route instanceof PilotRoute.Session(DesktopSession s) ? s : null;
    }

    /**
     * True when synthesized input on {@code route} leaves the user's real cursor alone. Both non-desktop routes
     * are background-safe by construction — a nested session's {@code :N} pointer is the bot's alone
     * ({@link Capability#BACKGROUND_CLICK}), and an emulator is driven by ADB, which has no host cursor to
     * touch in the first place. Only the {@code :0} path can fail this, once it has escalated to device input
     * (see {@link NativeController}).
     */
    public synchronized boolean supportsBackgroundInput(PilotRoute route) {
        if (route instanceof PilotRoute.Emulator) return true;
        DesktopSession s = sessionOf(route);
        if (s != null) return s.has(Capability.BACKGROUND_CLICK);
        NativeController nc = controller(route);
        return nc != null && nc.supportsBackgroundInput();
    }

    /**
     * The controller a gesture drives on a display-backed route. A nested session's {@code :N} controller is
     * already device-level and background-safe, so it needs no {@code useReliableInput()} escalation — and
     * routing through it keeps Interact in the same coordinate space as the streamed {@code :N} frame. On the
     * desktop route the host {@code :0} controller is resolved (and escalated to a reliable input path) exactly
     * once, lazily, so Studio startup, emulator routes and bot-only sessions never pay for it — and never lose
     * the cursor-safe default unless Interact was actually used on {@code :0}.
     */
    private NativeController controller(PilotRoute route) {
        DesktopSession s = sessionOf(route);
        if (s != null) return s.controller();
        if (host == null) {
            try {
                NativeController nc = NativeControllerFactory.get();
                if (!nc.useReliableInput()) {
                    System.err.println("Pilot interact: no reliable input backend available — taps may not "
                            + "reach the game (see LinuxController.useReliableInput).");
                }
                host = nc;
            } catch (Exception e) {
                System.err.println("Pilot interact unavailable: " + e.getMessage());
            }
        }
        return host;
    }
}
