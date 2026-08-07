package com.botmaker.studio.services.launch;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.launch.LaunchIsolation;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.session.impl.AdoptedSession;
import com.botmaker.session.impl.NestedSession;
import javafx.application.Platform;

import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place a game is brought up in a private nested display ({@code :N}) and <em>held</em>, shared by
 * both surfaces that background a launch: Studio's Launch buttons ({@code QuickLaunch}) and the Remote Pilot's
 * "Background mode" box ({@link com.botmaker.studio.services.pilot.NestedSessionLauncher}). Living here — rather
 * than inside the pilot, which is where the bring-up used to live — is what stops the two disagreeing: click
 * "▶ Launch" and the pilot's Stop/status reflect the same live session, because there is exactly one holder per
 * project (keyed by the resources dir, {@link #forProject}).
 *
 * <p>It knows nothing about the pilot, and it <b>tells nobody</b>: a consumer asks {@link #session()} (or
 * {@link #isRunning()}) when it needs to know. There were started/stopped listeners here instead, and the
 * pilot's subscription to them lived in an object created only when the user opened the Background-mode box —
 * so a game launched from the ▶ Launch toolbar was invisible to the pilot, which then streamed and drove the
 * user's real {@code :0} desktop. A pull cannot be registered too late.
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

    /** How often the held session is checked for a display that has gone away. See {@link #watch}. */
    private static final long DEAD_SESSION_POLL_MS = 2_000;

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

    private BackgroundLauncher() {}

    /**
     * The JVM arguments that offer the live session to a bot we are about to run, or empty when none is live.
     *
     * <p>This is what makes "launch it once, then run the bot" work. Without it the bot brought up a *second*
     * private display and launched the game into it — and every store launcher is single-instance, so that launch
     * was handed to the copy already running in this session and the game appeared on a display nobody was
     * watching. The shape of the hand-off belongs to shared ({@code AdoptedSession}), which also reads it.
     */
    public List<String> handoffArguments() {
        return AdoptedSession.handoffArguments(active);
    }

    /** True while a nested session is live (so a UI can show Stop rather than Start). */
    public boolean isRunning() {
        return active != null;
    }

    /**
     * The live nested session, or {@code null} when none is running — the <em>pull</em> half of this holder.
     *
     * <p>Prefer it to the started/stopped listeners for anything that only needs to know what is live *now*:
     * a listener has to be registered before the session comes up (or by a consumer that exists at all), and
     * that is precisely how the pilot came to stream {@code :0} while a game ran on {@code :N} — its
     * subscription lived in a UI object the user hadn't opened. See {@code PilotSession}.
     */
    public NestedSession session() {
        return active;
    }

    /**
     * The X id of the live session's host window on the real desktop, or {@code 0} — <b>without</b> touching
     * it. The non-mutating half of {@link #revealHostWindow()}, for a caller that needs to *recognise* that
     * window rather than look at it: gamescope renames its output window after the app it hosts, so a
     * title-matched window on {@code :0} can be a session's own container, and driving input into it is what
     * wedged the host pointer. {@code 0} is an ordinary answer for the first seconds of a session.
     */
    public long hostWindowId() {
        NestedSession session = active;
        return session == null ? 0 : session.hostWindowId();
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
     * Un-minimize the live session's host window and return its X id, or {@code 0} when there is no session or its
     * window isn't known yet. The pair is one call because a caller only ever wants an id it can then look at.
     *
     * <p>For Studio's overlay editor, which draws over the session rather than over a window on the real desktop.
     * See {@link NestedSession#hostWindowId} for why the id and not a title, and why {@code 0} is an ordinary
     * answer for the first seconds of a session rather than a failure.
     */
    public long revealHostWindow() {
        NestedSession session = active;
        if (session == null) {
            return 0;
        }
        session.revealHostWindow();
        return session.hostWindowId();
    }

    /**
     * Bring up a nested session on {@code backend}, launch {@code spec} into it, and hold it. No-ops (reporting why) when a session is already running. The caller has already chosen the
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
            watch(session);
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
     * Watch {@code session} for a display that goes away, and let it go when one does.
     *
     * <p>Nothing used to notice. A session whose gamescope/Xephyr died stayed held: {@link #isRunning} kept saying
     * yes, the Launch button kept refusing a second bring-up, and the dead session's slice kept a private
     * {@code dbus-daemon} alive that the launch probes read as a launcher still open — so the *next* launch was
     * refused too, on the strength of a session nobody could use. Found live: a
     * {@code botmaker-sess-…-dbus.scope} still running hours after its display server had gone.
     *
     * <p>A poll rather than a callback because that is what the fact is — a process that exited. The interval only
     * bounds how long a dead session lingers; it costs one {@code isAlive()} per tick.
     */
    private void watch(NestedSession session) {
        Thread watchdog = new Thread(() -> {
            while (active == session) {
                try {
                    Thread.sleep(DEAD_SESSION_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (active != session) {
                    return; // stop() got there first
                }
                if (session.closeIfDead()) {
                    active = null;
                    return;
                }
            }
        }, "background-session-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Tear down the live session (reaps its Xephyr/gamescope + WM + game tree). Safe to call when nothing is
     * running; {@link #session()} answers {@code null} from the first line on, so a consumer mid-frame sees
     * the session go away without being told.
     */
    public void stop() {
        NestedSession session = active;
        active = null;
        if (session != null) {
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
