package com.botmaker.studio.services.launch;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.launch.LaunchIsolation;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.session.NestedSession;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The one place a game is brought up in a private nested display ({@code :N}) and <em>held</em>, shared by
 * both surfaces that background a launch: Studio's Launch buttons ({@code QuickLaunch}) and the Remote Pilot's
 * "Background mode" box ({@link com.botmaker.studio.services.pilot.NestedSessionLauncher}). Living here — rather
 * than inside the pilot, which is where the bring-up used to live — is what stops the two disagreeing: click
 * "▶ Launch" and the pilot's Stop/status reflect the same live session, because there is exactly one holder per
 * project (keyed by the resources dir, {@link #forProject}).
 *
 * <p>It knows nothing about the pilot. A consumer that wants to react to the session coming up or going away
 * (the pilot routes it to {@code PilotServer}) registers a listener ({@link #addStartedListener} /
 * {@link #addStoppedListener}); a listener added while a session is already live is fired immediately, so a
 * pilot opened <em>after</em> a Launch-button bring-up still picks the session up.
 *
 * <p>The bring-up runs <b>off the FX thread</b> ({@code NestedSession.start} spawns Xephyr/gamescope and blocks
 * on display readiness, then {@code launch} blocks up to the window timeout), reporting back through
 * {@code report} on the FX thread. Only one session is held at a time; starting a second while one is live is
 * refused rather than silently leaking the first. When the game never maps a window on {@code :N} the bring-up
 * fails <b>loudly</b> and stays off {@code :0} — it does not leave a caller thinking it worked on the real desktop.
 */
public final class BackgroundLauncher implements AutoCloseable {

    /** Nested display size used when the caller has no reference resolution to pass. */
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;

    /** How a call site shows the outcome — its own status label, always invoked on the FX thread. */
    @FunctionalInterface
    public interface Report {
        void accept(boolean ok, String message);
    }

    /** One holder per project resources dir, so the Launch buttons and the Remote Pilot share the live session. */
    private static final Map<Path, BackgroundLauncher> INSTANCES = new ConcurrentHashMap<>();

    /** The holder for {@code resourcesDir}, created on first use and reused thereafter. */
    public static BackgroundLauncher forProject(Path resourcesDir) {
        return INSTANCES.computeIfAbsent(resourcesDir.toAbsolutePath(), key -> new BackgroundLauncher());
    }

    /** The live nested session, or {@code null} when none is running. Written on the worker, read on FX. */
    private volatile NestedSession active;
    private final CopyOnWriteArrayList<Consumer<NestedSession>> onStarted = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> onStopped = new CopyOnWriteArrayList<>();

    private BackgroundLauncher() {}

    /** Notified when a session comes up. Fired immediately with the current session if one is already live. */
    public void addStartedListener(Consumer<NestedSession> listener) {
        onStarted.add(listener);
        NestedSession current = active;
        if (current != null) {
            listener.accept(current);
        }
    }

    public void removeStartedListener(Consumer<NestedSession> listener) {
        onStarted.remove(listener);
    }

    /** Notified when the live session is torn down. */
    public void addStoppedListener(Runnable listener) {
        onStopped.add(listener);
    }

    public void removeStoppedListener(Runnable listener) {
        onStopped.remove(listener);
    }

    /** True while a nested session is live (so a UI can show Stop rather than Start). */
    public boolean isRunning() {
        return active != null;
    }

    /** The live session's display (e.g. {@code :3}), or {@code null} when none is running. For a status line. */
    public String activeDisplay() {
        NestedSession session = active;
        return session == null ? null : session.displayName();
    }

    /** Title of the window the live session attached, or {@code null} when none is running / nothing attached. */
    public String attachedTitle() {
        NestedSession session = active;
        if (session == null) {
            return null;
        }
        GenericWindow window = session.attached();
        return window == null ? null : window.getTitle();
    }

    /**
     * Bring up a nested session on {@code backend}, launch {@code spec} into it, and notify the started
     * listeners. No-ops (reporting why) when a session is already running. The caller has already chosen the
     * backend (via {@code SessionBackends}) and confirmed a target — this method owns only the bring-up and the
     * held session. Runs off the FX thread; {@code report} is marshalled back onto it.
     */
    public void start(NestedSession.Backend backend, LaunchSpec spec, int width, int height, Report report) {
        if (active != null) {
            report.accept(false, "A background session is already running — stop it first (Remote Pilot ▸ Stop).");
            return;
        }
        LaunchIsolation.Verdict verdict = LaunchIsolation.check(spec);
        if (!verdict.isolatable()) {
            // Refuse before spending anything. Every way this fails ends the same way if we launch anyway — the
            // private display sits empty for the whole window timeout, then the half-booted child is SIGKILLed
            // (the Electron SIGTRAP coredump that started this work) — and the causes are distinguishable now
            // rather than guessable later. Telling the user immediately costs nothing.
            report.accept(false, verdict.reason());
            return;
        }
        report.accept(true, "Bringing up " + backend + " session and launching " + spec.describe() + "…");
        Thread worker = new Thread(() -> runStart(backend, width, height, spec, report), "background-launch");
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
                // Fail LOUDLY and stay off :0: do not leave a caller thinking it worked on the real desktop.
                String display = session.displayName();
                session.close();
                // The up-front probe already ruled out what it can see, so the backstop reports what the process
                // table says actually happened — escaped to the desktop, or never started — rather than
                // offering both as a guess.
                report(report, false, "Couldn't run " + spec.describe() + " on the private display " + display
                        + " — it didn't map a window there. " + LaunchIsolation.noWindowDiagnosis(spec));
                return;
            }
            active = session;
            fireStarted(session);
            report(report, true, "Running " + spec.describe() + " on the private " + backend + " display "
                    + session.displayName() + " — your real cursor stays free.");
        } catch (Exception e) {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) { /* best-effort teardown */ }
            }
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            report(report, false, "Couldn't start background session: " + why);
        }
    }

    /**
     * Tear down the live session (reaps its Xephyr/gamescope + WM + game tree) and notify the stopped
     * listeners. Safe to call when nothing is running.
     */
    public void stop() {
        NestedSession session = active;
        active = null;
        if (session != null) {
            fireStopped();
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

    private void fireStarted(NestedSession session) {
        for (Consumer<NestedSession> listener : onStarted) {
            listener.accept(session);
        }
    }

    private void fireStopped() {
        for (Runnable listener : onStopped) {
            listener.run();
        }
    }

    private static void report(Report report, boolean ok, String message) {
        Platform.runLater(() -> report.accept(ok, message));
    }
}
