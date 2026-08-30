package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.Runs;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.runtime.CodeExecutionService;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Studio's side of {@link Runs} — the open project's bot, as a plugin is allowed to see it.
 *
 * <p>Nothing here is new capability. Starting and stopping are the events the Run and Stop buttons already
 * publish, the pid is {@link CodeExecutionService#runningBotPid()}, and the two listener channels are
 * {@link EventBus} subscriptions Studio already makes. What the class adds is the shape: a plugin gets the
 * bot's process without seeing a Studio type, which is what lets the Remote Pilot be a plugin's feature
 * rather than the host's.
 *
 * <p><b>Installed per project, static, one at a time</b> — the same shape as {@link PluginHost}, and for the
 * same reason: {@code HostServices} is built ad hoc from a {@code ProjectConfig} at three call sites that
 * have no event bus in scope, and Studio holds one open project. {@link #install} is called from the
 * composition root and {@link #clear} from the project's close, so between projects a plugin asking gets
 * {@link Runs#NONE} rather than a channel into the project the user just left.
 *
 * <h2>Two things about the listeners that are not incidental</h2>
 *
 * <p><b>The {@code EventBus} subscription is made once and never removed</b> — it has no unsubscribe — so
 * what a plugin registers goes in a list of Studio's own, and the handle it gets back removes it from
 * <em>that</em>. Registering per plugin listener directly on the bus would accumulate one dead handler per
 * project opened, which is the leak the handle exists to prevent.
 *
 * <p><b>Telemetry crosses as its own wire bytes, re-encoded.</b> Studio decoded the frame on the way in
 * ({@link com.botmaker.shared.ipc.TelemetryServer}) and holds a {@code TelemetryEvent}, which is a shared
 * type and therefore one the contract may not name — so the frame is written back out with
 * {@link com.botmaker.shared.ipc.TelemetryFrame} and the plugin decodes it with the same class. That is one
 * definition of the format rather than two, which a text rendering invented for the contract would not have
 * been: it would be owned by neither end and would drift from both. The re-encode costs a few hundred bytes
 * per event, and telemetry is a handful of events a second, not a video stream.
 */
public final class HostRuns implements Runs {

    /** The live channel, or null between projects. */
    private static volatile HostRuns current;

    private final EventBus eventBus;
    private final CodeExecutionService execution;

    /** Plugin listeners, held here rather than on the bus, which cannot unsubscribe. */
    private final List<Consumer<Boolean>> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<byte[]>> telemetryListeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;

    private HostRuns(EventBus eventBus, CodeExecutionService execution) {
        this.eventBus = eventBus;
        this.execution = execution;
        // false: these must not be marshalled onto the FX thread. A plugin is told in the contract that the
        // thread is not promised and that it must hop for UI itself, and a pilot pushing a frame has no
        // business queueing behind the editor's rendering.
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> fireState(true), false);
        eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> fireState(false), false);
        eventBus.subscribe(CoreApplicationEvents.ViewFeedbackEvent.class,
                e -> fireTelemetry(encode(e.feedback())), false);
    }

    /** Makes this project's bot the one a plugin reaches, replacing whatever was installed before. */
    public static synchronized void install(EventBus eventBus, CodeExecutionService execution) {
        if (eventBus == null || execution == null) {
            clear();
            return;
        }
        current = new HostRuns(eventBus, execution);
    }

    /** No project: a plugin asking now gets {@link Runs#NONE}. */
    public static synchronized void clear() {
        current = null;
    }

    /** The live channel, or {@link Runs#NONE} when no project is open. Never null. */
    public static Runs live() {
        HostRuns live = current;
        return live == null ? Runs.NONE : live;
    }

    /** Says one line in the status bar, or does nothing when no project is open. */
    public static void status(String message) {
        HostRuns live = current;
        if (live == null || message == null) return;
        live.eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }

    @Override
    public void start() {
        eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent());
    }

    @Override
    public void stop() {
        eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public OptionalLong pid() {
        return execution.runningBotPid();
    }

    @Override
    public AutoCloseable onStateChanged(Consumer<Boolean> listener) {
        if (listener == null) return () -> { };
        stateListeners.add(listener);
        return () -> stateListeners.remove(listener);
    }

    @Override
    public AutoCloseable onTelemetry(Consumer<byte[]> listener) {
        if (listener == null) return () -> { };
        telemetryListeners.add(listener);
        return () -> telemetryListeners.remove(listener);
    }

    /**
     * The event as one encoded frame, or null when it cannot be written.
     *
     * <p>Skipped rather than reported: an event that will not encode is a bug on this side, and the bot is
     * running. Dropping one frame of telemetry costs a plugin one stale overlay; taking the run down over it
     * would cost the user their session.
     */
    private static byte[] encode(com.botmaker.shared.ipc.TelemetryEvent event) {
        if (event == null) return null;
        var bytes = new java.io.ByteArrayOutputStream(256);
        try (var out = new java.io.DataOutputStream(bytes)) {
            com.botmaker.shared.ipc.TelemetryFrame.write(out, event);
        } catch (java.io.IOException impossible) {
            return null;   // a ByteArrayOutputStream does not fail; the checked type says otherwise
        }
        return bytes.toByteArray();
    }

    private void fireState(boolean nowRunning) {
        running = nowRunning;
        for (Consumer<Boolean> listener : stateListeners) {
            deliver(() -> listener.accept(nowRunning));
        }
    }

    private void fireTelemetry(byte[] frame) {
        if (frame == null) return;
        for (Consumer<byte[]> listener : telemetryListeners) {
            deliver(() -> listener.accept(frame));
        }
    }

    /**
     * Runs one listener, total.
     *
     * <p>The same rule the {@link EventBus} keeps for its own handlers, for the same reason: a plugin that
     * throws while being told a bot started must not stop the next plugin being told, and must not surface as
     * the Run button appearing to fail.
     */
    private static void deliver(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: a plugin's run listener threw: " + e);
        }
    }
}
