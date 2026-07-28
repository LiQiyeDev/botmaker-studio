package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.session.NestedSession;
import com.botmaker.studio.project.launch.QuickLaunch;
import javafx.application.Platform;

import java.nio.file.Path;

/**
 * The producer the bot-owned-display work was missing: it brings up a nested {@code :N}
 * {@link NestedSession}, launches the project's configured {@code launch.target} into it, and hands the live
 * session to the {@link PilotServer} so the pilot's preview and Interact gestures flow through {@code :N}
 * instead of the user's real {@code :0} desktop. Stopping reaps the whole tree and returns the pilot to
 * {@code :0}.
 *
 * <p>This is the one place that connects the two ends the infrastructure already had but never joined: the
 * per-project <b>launch target</b> ({@link QuickLaunch#specOf} → {@link LaunchSpec}) and the session-consuming
 * pilot ({@link PilotServer#setActiveSession}). A nested session <em>owns the single window it launches</em>
 * (see {@link NestedSession}), so there is no capture <em>target</em> to pick — the launched game is the
 * target, and {@code capture.source}'s window-title selector is irrelevant while a session is active.
 *
 * <p>The bring-up runs <b>off the FX thread</b> ({@code NestedSession.start} spawns Xephyr/gamescope and blocks
 * on display readiness, then {@code launch} blocks up to the window timeout), reporting back through
 * {@code report} on the FX thread. Only one session is held at a time; starting a second while one is live is
 * refused rather than silently leaking the first.
 */
public final class NestedSessionLauncher implements AutoCloseable {

    /** Nested display size used when the project has no reference resolution configured. */
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;

    /** How a call site shows the outcome — its own status label, always invoked on the FX thread. */
    @FunctionalInterface
    public interface Report {
        void accept(boolean ok, String message);
    }

    private final Path resourcesDir;
    private final PilotServer pilotServer;

    /** The live nested session, or {@code null} when none is running. Written on the worker, read on FX. */
    private volatile NestedSession active;

    public NestedSessionLauncher(Path resourcesDir, PilotServer pilotServer) {
        this.resourcesDir = resourcesDir;
        this.pilotServer = pilotServer;
    }

    /** True while a nested session is live (so the UI can show Stop rather than Start). */
    public boolean isRunning() {
        return active != null;
    }

    /**
     * Bring up a nested session on {@code backend}, launch the project's configured target into it, and route
     * the pilot through it. No-ops (reporting why) when a session is already running or no launch target is
     * configured. Runs off the FX thread; {@code report} is marshalled back onto it.
     */
    public void start(NestedSession.Backend backend, int width, int height, Report report) {
        if (active != null) {
            report.accept(false, "A nested session is already running — stop it first.");
            return;
        }
        LaunchSpec spec = QuickLaunch.specOf(resourcesDir);
        if (spec == null) {
            report.accept(false, "No launch target configured — set one in the Launch Target dialog first.");
            return;
        }
        report.accept(true, "Bringing up " + backend + " session and launching " + spec.describe() + "…");
        Thread worker = new Thread(() -> runStart(backend, width, height, spec, report), "nested-session-start");
        worker.setDaemon(true);
        worker.start();
    }

    private void runStart(NestedSession.Backend backend, int width, int height, LaunchSpec spec, Report report) {
        NestedSession session = null;
        try {
            session = NestedSession.start(optionsFor(backend, width, height));
            session.launch(spec);
            GenericWindow window = session.attached();
            if (window == null) {
                // The display came up but the game never mapped a window on :N — nothing to preview or drive.
                // Fail LOUDLY and stay off :0: do not leave the pilot on the real desktop thinking it worked.
                String display = session.displayName();
                session.close();
                report(report, false, "Couldn't run " + spec.describe() + " on the private display " + display
                        + " — it didn't map a window there. A host launcher (Heroic/Steam) may have grabbed it "
                        + "on your real desktop instead. The pilot stayed on :0, so Interact would move your real "
                        + "cursor. Close the launcher and try again.");
                return;
            }
            active = session;
            pilotServer.setActiveSession(session);
            report(report, true, "Running " + spec.describe() + " on nested " + backend + " display — the pilot "
                    + "now previews and drives that window.");
        } catch (Exception e) {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) { /* best-effort teardown */ }
            }
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            report(report, false, "Couldn't start nested session: " + why);
        }
    }

    /**
     * Tear down the live session (reaps its Xephyr/gamescope + WM + game tree) and return the pilot to the real
     * {@code :0} desktop. Safe to call when nothing is running.
     */
    public void stop() {
        NestedSession session = active;
        active = null;
        if (session != null) {
            pilotServer.clearActiveSession();
            try {
                session.close();
            } catch (Exception ignored) {
                // best-effort — the session's reaper drops the tree regardless
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * The session shape for a backend at a given size. Xephyr is the 2D default; gamescope is the hardware-3D
     * opt-in. Kept package-visible and pure so the backend selection is unit-tested without a live X server.
     */
    static NestedSession.Options optionsFor(NestedSession.Backend backend, int width, int height) {
        int w = width > 0 ? width : DEFAULT_WIDTH;
        int h = height > 0 ? height : DEFAULT_HEIGHT;
        return switch (backend) {
            case GAMESCOPE -> NestedSession.Options.gamescope(w, h);
            case XEPHYR -> NestedSession.Options.xephyr(w, h);
        };
    }

    private static void report(Report report, boolean ok, String message) {
        Platform.runLater(() -> report.accept(ok, message));
    }
}
