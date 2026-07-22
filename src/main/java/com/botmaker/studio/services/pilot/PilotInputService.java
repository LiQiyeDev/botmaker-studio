package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;

/**
 * Replays the pilot's manual "Interact" gestures onto the real desktop.
 *
 * <p>The pilot streams the bot's target surface; with Interact armed, a tap/drag/scroll on that stream
 * arrives here as absolute screen coordinates and is synthesized through {@link NativeController} — the same
 * plumbing a running bot's {@code Mouse} calls use, so a hand gesture and a bot action reach the game by
 * exactly one path.
 *
 * <p><b>Reliability beats cursor safety here.</b> The cursor-preserving Linux default ({@code XSendEvent})
 * sends synthetic events flagged {@code send_event=True}, which every Wine/Proton game — the targets Interact
 * exists for — ignores, so taps silently did nothing. Windows had the same bug in a different dialect:
 * {@code PostMessage} puts a click in the window's message queue, which a game reading raw input never looks
 * at. The controller is therefore asked once, lazily, for {@link NativeController#useReliableInput()}, which
 * on both platforms switches to real device input. After escalating, {@link #supportsBackgroundInput()}
 * honestly reports {@code false}, which {@code PilotServer} forwards as the state message's
 * {@code backgroundInput} flag and the pilot renders as its "moves the computer's real cursor" warning. The
 * escalation is process-wide and sticky, so a bot run in the same Studio session after Interact was used also
 * drives the real pointer.
 *
 * <p><b>Every gesture now drives the real pointer</b>, and each puts it back where it started. A tap used to
 * take {@code postLeftClickScreen} on the theory that it was the one gesture with a cursor-preserving
 * direct-to-window path — that path is exactly the one games drop, which is why taps did nothing while drags
 * (already on {@code mouseMove}/{@code mouseButton}) worked.
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

    /** Resolved lazily: constructing a controller probes X11/Win32 and must not run at Studio startup. */
    private NativeController controller;

    /** Where the pointer was when the current drag started, restored on {@code UP}. Null when not dragging. */
    private java.awt.Point dragOrigin;

    /**
     * Applies one gesture at absolute screen coordinates, ignoring anything outside {@code bounds}.
     *
     * @return true if the gesture was dispatched, false if it was rejected (out of bounds, no controller)
     */
    public synchronized boolean apply(Kind kind, int x, int y, int button, int amount, Bounds bounds) {
        if (bounds == null || !bounds.contains(x, y)) return false;
        NativeController nc = controller();
        if (nc == null) return false;

        int btn = button <= 0 ? 1 : button;
        try {
            switch (kind) {
                // A tap is self-contained, so it can put the pointer back afterwards. The drag gestures
                // can't: the cursor has to stay with the gesture until the button is released, so UP is
                // where the pointer is restored (see dragOrigin).
                case TAP -> nc.clickRestoringCursor(x, y, btn);
                case DOWN -> {
                    dragOrigin = nc.cursorPosition();
                    nc.mouseMove(x, y);
                    nc.mouseButton(btn, true);
                }
                case MOVE -> nc.mouseMove(x, y);
                case UP -> {
                    nc.mouseMove(x, y);
                    nc.mouseButton(btn, false);
                    if (dragOrigin != null) {
                        nc.mouseMove(dragOrigin.x, dragOrigin.y);
                        dragOrigin = null;
                    }
                }
                case SCROLL -> { nc.mouseMove(x, y); nc.scroll(amount); }
            }
            return true;
        } catch (Exception e) {
            System.err.println("Pilot interact " + kind + " failed: " + e.getMessage());
            return false;
        }
    }

    /** True when synthesized input leaves the user's real cursor alone (see {@link NativeController}). */
    public synchronized boolean supportsBackgroundInput() {
        NativeController nc = controller();
        return nc != null && nc.supportsBackgroundInput();
    }

    /**
     * The shared controller, resolved (and escalated to a reliable input path) on first use. The escalation
     * happens exactly once, here, so Studio startup and bot-only sessions never pay for it — and never lose
     * the cursor-safe default unless Interact was actually used.
     */
    private NativeController controller() {
        if (controller == null) {
            try {
                NativeController nc = NativeControllerFactory.get();
                if (!nc.useReliableInput()) {
                    System.err.println("Pilot interact: no reliable input backend available — taps may not "
                            + "reach the game (see LinuxController.useReliableInput).");
                }
                controller = nc;
            } catch (Exception e) {
                System.err.println("Pilot interact unavailable: " + e.getMessage());
            }
        }
        return controller;
    }
}
