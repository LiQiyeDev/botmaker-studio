package com.botmaker.studio.events;

import javafx.application.Platform;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central event bus for application-wide event communication.
 *
 * <p>Dispatch is by supertype: publishing an event notifies every handler registered on the
 * event's class <em>or any supertype/interface it implements</em>. So subscribing to a sealed
 * family (e.g. {@code DebugControlRequest}) receives all of its members, and subscribing to
 * {@link ApplicationEvent} receives every event.
 *
 * <p>Thread-safe; each subscription chooses whether its handler runs on the JavaFX thread.
 *
 * <p>A handler that throws is logged at {@code SEVERE} with the event's name and the cause, and the
 * publish continues — <em>on both branches</em>, whichever thread the handler ends up running on.
 */
public class EventBus {
    private static final Logger LOGGER = Logger.getLogger(EventBus.class.getName());

    private final Map<Class<? extends ApplicationEvent>, List<EventHandler<?>>> handlers = new ConcurrentHashMap<>();
    private final boolean enableLogging;

    public EventBus() {
        this(false);
    }

    public EventBus(boolean enableLogging) {
        this.enableLogging = enableLogging;
    }

    /**
     * A registered subscription, kept so it can be undone. Most subscribers live as long as the project and
     * ignore the returned handle; a subscriber with a shorter life than the bus — a dialog or overlay that is
     * opened and closed repeatedly — must {@link #close()} it, or every reopen stacks another live handler on
     * the same bus.
     */
    public interface Subscription extends AutoCloseable {
        /** Removes the handler. Idempotent; never throws. */
        @Override
        void close();
    }

    /** Subscribe to events of a specific type (or family), delivered on the publishing thread. */
    public <T extends ApplicationEvent> Subscription subscribe(Class<T> eventType, Consumer<T> handler) {
        return subscribe(eventType, handler, false);
    }

    /** Subscribe with the option to run the handler on the JavaFX application thread. */
    public <T extends ApplicationEvent> Subscription subscribe(
            Class<T> eventType,
            Consumer<T> handler,
            boolean runOnFxThread) {

        List<EventHandler<?>> registered =
                handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        EventHandler<T> entry = new EventHandler<>(handler, runOnFxThread);
        registered.add(entry);

        if (enableLogging) {
            LOGGER.info("Subscribed to " + eventType.getSimpleName());
        }
        return () -> registered.remove(entry);
    }

    /** Publish an event to all handlers registered on its type or any of its supertypes. */
    public void publish(ApplicationEvent event) {
        if (event == null) {
            return;
        }

        if (enableLogging) {
            LOGGER.info("Publishing: " + event.getClass().getSimpleName());
        }

        Class<?> eventClass = event.getClass();
        for (Map.Entry<Class<? extends ApplicationEvent>, List<EventHandler<?>>> entry : handlers.entrySet()) {
            if (!entry.getKey().isAssignableFrom(eventClass)) {
                continue;
            }
            for (EventHandler<?> handler : entry.getValue()) {
                try {
                    handler.handle(event);
                } catch (Exception e) {
                    // A handler's own failure is caught by the delivery, below; what is left here is a failure
                    // to *dispatch* — Platform.runLater rejecting the delivery because the toolkit is gone.
                    LOGGER.log(Level.SEVERE, "Error dispatching event: " + eventClass.getSimpleName(), e);
                }
            }
        }
    }

    private record EventHandler<T extends ApplicationEvent>(Consumer<T> handler, boolean runOnFxThread) {

        void handle(ApplicationEvent event) {
            if (runOnFxThread && !Platform.isFxApplicationThread()) {
                Platform.runLater(() -> deliver(event));
            } else {
                deliver(event);
            }
        }

        /**
         * The guard travels with the delivery rather than staying at the call site: on the {@code runLater}
         * branch the handler runs on the FX thread long after {@code publish} has returned, so a try/catch
         * around {@code handle} cannot see it. That gap covered the UI subscriptions — the half most likely
         * to throw — and it is the whole of Studio's error logging. See {@code docs/refactor} SC4.
         */
        @SuppressWarnings("unchecked")
        private void deliver(ApplicationEvent event) {
            try {
                handler.accept((T) event);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error handling event: " + event.getClass().getSimpleName(), e);
            }
        }
    }
}
