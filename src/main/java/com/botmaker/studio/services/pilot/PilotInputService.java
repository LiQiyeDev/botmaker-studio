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
 * exists for — ignores, so taps silently did nothing. The controller is therefore asked once, lazily, for
 * {@link NativeController#useReliableInput()}: on Linux that escalates to uinput (or XTest), on Windows it is
 * a no-op because {@code PostMessage} is already both reliable and cursor-safe. After escalating,
 * {@link #supportsBackgroundInput()} honestly reports {@code false}, which {@code PilotServer} forwards as
 * the state message's {@code backgroundInput} flag and the pilot renders as its "moves the computer's real
 * cursor" warning. The escalation is process-wide and sticky, so a bot run in the same Studio session after
 * Interact was used also drives the real pointer.
 *
 * <p>A plain tap still takes {@link NativeController#postLeftClickScreen}, the one gesture with a
 * direct-to-window path. Drags need press/move/release <em>state</em>, so they go through
 * {@code mouseMove}/{@code mouseButton}, which drives the pointer on every backend.
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
                // A plain tap is the one gesture that has a cursor-preserving path — use it.
                case TAP -> nc.postLeftClickScreen(x, y);
                case DOWN -> { nc.mouseMove(x, y); nc.mouseButton(btn, true); }
                case MOVE -> nc.mouseMove(x, y);
                case UP -> { nc.mouseMove(x, y); nc.mouseButton(btn, false); }
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
