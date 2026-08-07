package com.botmaker.studio.services.pilot;

import com.botmaker.session.DesktopSession;
import com.botmaker.studio.services.launch.BackgroundLauncher;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Answers "which bot-owned nested {@code :N} {@link DesktopSession} is live right now", or {@code null}.
 *
 * <p>This used to be the whole decision — {@code :N} or the real {@code :0} — which is why it reads like a
 * switch. An emulator surface is a third answer that is neither, so the decision itself moved to
 * {@link PilotRoutes} (where a live session here is still the top of the order) and this became one of its
 * inputs.
 *
 * <p>When a nested session is active, {@link TargetCapture} previews what that session put on {@code :N} and
 * {@link PilotInputService} drives that session's {@code :N}-bound controller — so capture and input share
 * <em>one</em> coordinate space (the nested screen) and every gesture is a flawless background click by
 * construction, with no {@code :0} cursor to hijack and no {@code useReliableInput()} escalation to pay for.
 * Answer {@code null} and the pilot falls straight back to the {@code :0} behaviour.
 *
 * <p><b>It asks; it is not told.</b> This was a settable holder that a launcher <em>pushed</em> the session
 * into — and the push was wired in {@link NestedSessionLauncher}'s constructor, which the pilot dialog only
 * creates when its Background-mode box is first used. Launch the game from Studio's ▶ Launch toolbar instead
 * and nothing ever pushed: the pilot streamed and drove the user's real {@code :0} desktop while the game was
 * running perfectly well on {@code :N}, and Interact clicks landed in gamescope's window on the host. There is
 * exactly one session per project — {@link BackgroundLauncher#forProject} — so the reliable question is asked
 * of that holder on every read ({@link #forProject}), which no call site can forget to answer.
 */
public final class PilotSession {

    private final Supplier<DesktopSession> source;

    /**
     * Ask {@code source} on every read. A supplier rather than a value because the session comes and goes
     * under a running pilot, and reads happen on the frame loop, the WS command thread and the FX thread.
     */
    public PilotSession(Supplier<DesktopSession> source) {
        this.source = source;
    }

    /**
     * The production wiring: the one nested session held for {@code resourcesDir}, whoever started it. A null
     * dir (a server stood up without a project, as in tests) has no holder to ask, so it answers {@code :0}.
     */
    public static PilotSession forProject(Path resourcesDir) {
        if (resourcesDir == null) {
            return new PilotSession(() -> null);
        }
        BackgroundLauncher launcher = BackgroundLauncher.forProject(resourcesDir);
        return new PilotSession(launcher::session);
    }

    /** The active nested session, or {@code null} when the pilot should use the real {@code :0} desktop. */
    public DesktopSession get() {
        try {
            return source.get();
        } catch (Exception e) {
            // A frame loop must not die because the holder answered badly; :0 is the honest fallback.
            return null;
        }
    }
}
