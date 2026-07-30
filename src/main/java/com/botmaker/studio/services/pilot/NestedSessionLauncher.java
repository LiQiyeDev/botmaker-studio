package com.botmaker.studio.services.pilot;

import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.session.impl.NestedSession;
import com.botmaker.studio.project.launch.QuickLaunch;
import com.botmaker.studio.services.launch.BackgroundLauncher;

import java.nio.file.Path;

/**
 * The pilot's view onto the shared {@link BackgroundLauncher}: it brings the project's configured
 * {@code launch.target} up in a nested {@code :N} display and routes the live session to the {@link PilotServer}
 * so the pilot's preview and Interact gestures flow through {@code :N} instead of the user's real {@code :0}
 * desktop. Stopping reaps the whole tree and returns the pilot to {@code :0}.
 *
 * <p>The bring-up and the held session now live in {@link BackgroundLauncher} (one holder per project), so the
 * Studio Launch buttons and this pilot box drive the <em>same</em> session and can't disagree on what's
 * running. This class adds only the pilot-specific wiring: a started/stopped listener that hands the session to
 * (and clears it from) the {@link PilotServer}. A nested session <em>owns the single window it launches</em>
 * (see {@link NestedSession}), so there is no capture <em>target</em> to pick — the launched game is the target.
 */
public final class NestedSessionLauncher implements AutoCloseable {

    /** Nested display size used when the project has no reference resolution configured. */
    public static final int DEFAULT_WIDTH = BackgroundLauncher.DEFAULT_WIDTH;
    public static final int DEFAULT_HEIGHT = BackgroundLauncher.DEFAULT_HEIGHT;

    /** How a call site shows the outcome — its own status label, always invoked on the FX thread. */
    @FunctionalInterface
    public interface Report {
        void accept(boolean ok, String message);
    }

    private final Path resourcesDir;
    private final BackgroundLauncher launcher;
    private final java.util.function.Consumer<NestedSession> onStarted;
    private final Runnable onStopped;

    public NestedSessionLauncher(Path resourcesDir, PilotServer pilotServer) {
        this.resourcesDir = resourcesDir;
        this.launcher = BackgroundLauncher.forProject(resourcesDir);
        this.onStarted = pilotServer::setActiveSession;
        this.onStopped = pilotServer::clearActiveSession;
        launcher.addStartedListener(onStarted);
        launcher.addStoppedListener(onStopped);
    }

    /** True while a nested session is live (so the UI can show Stop rather than Start). */
    public boolean isRunning() {
        return launcher.isRunning();
    }

    /** The live session's display (e.g. {@code :3}), or {@code null} when none is running. For the UI status line. */
    public String activeDisplay() {
        return launcher.activeDisplay();
    }

    /** Title of the window the live session attached, or {@code null} when none is running / nothing attached. */
    public String attachedTitle() {
        return launcher.attachedTitle();
    }

    /** The project's configured launch target, or {@code null} when none is set — for the UI's availability/label. */
    public LaunchSpec configuredTarget() {
        return QuickLaunch.specOf(resourcesDir);
    }

    /**
     * Bring up a nested session on {@code backend}, launch the project's configured target into it, and route
     * the pilot through it. No-ops (reporting why) when a session is already running or no launch target is
     * configured. Runs off the FX thread; {@code report} is marshalled back onto it.
     */
    public void start(NestedSession.Backend backend, int width, int height, Report report) {
        LaunchSpec spec = configuredTarget();
        if (spec == null) {
            report.accept(false, "No launch target configured — set one in the Launch Target dialog first.");
            return;
        }
        launcher.start(backend, spec, width, height, report::accept);
    }

    /**
     * Tear down the live session (reaps its Xephyr/gamescope + WM + game tree) and return the pilot to the real
     * {@code :0} desktop. Safe to call when nothing is running.
     */
    public void stop() {
        launcher.stop();
    }

    @Override
    public void close() {
        launcher.removeStartedListener(onStarted);
        launcher.removeStoppedListener(onStopped);
        launcher.stop();
    }
}
