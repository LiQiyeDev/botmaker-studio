package com.botmaker.studio.services.pilot;

import com.botmaker.studio.runtime.CodeExecutionService;

import java.io.IOException;

/**
 * Pause/resume for a running bot, used by the remote pilot. Bots are plain OS processes
 * ({@link CodeExecutionService#runningBotPid()}), so pause is a kernel-level {@code SIGSTOP} and resume a
 * {@code SIGCONT} on that pid.
 *
 * <p><b>Crash-free but crude:</b> {@code SIGSTOP} cannot be caught or ignored by the JVM, so this never
 * corrupts the process — but it freezes the bot wherever it happens to be (possibly mid-action), and any
 * wall-clock timing the bot relies on skews across the pause. <b>Unix-only:</b> Windows has no signal
 * equivalent, so {@link #isSupported()} is false there and pause/resume are no-ops that report why.
 */
public final class PilotControlService {

    private final CodeExecutionService execution;
    private volatile boolean paused;

    public PilotControlService(CodeExecutionService execution) {
        this.execution = execution;
    }

    /** Whether pause/resume can work on this OS (Unix signals). */
    public static boolean isSupported() {
        return !System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public boolean isPaused() {
        return paused;
    }

    /** @return a human-readable result (success or the reason it couldn't). */
    public synchronized String pause() {
        return signal("STOP", true, "Paused");
    }

    public synchronized String resume() {
        return signal("CONT", false, "Resumed");
    }

    private String signal(String sig, boolean nextPaused, String okMessage) {
        if (!isSupported()) return "Pause/resume is not supported on Windows.";
        var pid = execution.runningBotPid();
        if (pid.isEmpty()) return "No bot is running.";
        try {
            int code = new ProcessBuilder("kill", "-" + sig, Long.toString(pid.getAsLong()))
                    .inheritIO().start().waitFor();
            if (code != 0) return "kill -" + sig + " exited " + code;
            paused = nextPaused;
            return okMessage;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "Could not signal bot: " + e.getMessage();
        }
    }

    /** Reset pause state when a run ends (the pid is gone; a fresh run starts unpaused). */
    public void onRunStopped() {
        paused = false;
    }
}
