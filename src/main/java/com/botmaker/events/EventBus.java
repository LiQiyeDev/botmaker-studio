package com.botmaker.events;

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

    /** Subscribe to events of a specific type (or family), delivered on the publishing thread. */
    public <T extends ApplicationEvent> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribe(eventType, handler, false);
    }

    /** Subscribe with the option to run the handler on the JavaFX application thread. */
    public <T extends ApplicationEvent> void subscribe(
            Class<T> eventType,
            Consumer<T> handler,
            boolean runOnFxThread) {

        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new EventHandler<>(handler, runOnFxThread));

        if (enableLogging) {
            LOGGER.info("Subscribed to " + eventType.getSimpleName());
        }
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
                    LOGGER.log(Level.SEVERE, "Error handling event: " + eventClass.getSimpleName(), e);
                }
            }
        }
    }

    private record EventHandler<T extends ApplicationEvent>(Consumer<T> handler, boolean runOnFxThread) {

        @SuppressWarnings("unchecked")
            void handle(ApplicationEvent event) {
                if (runOnFxThread && !Platform.isFxApplicationThread()) {
                    Platform.runLater(() -> handler.accept((T) event));
                } else {
                    handler.accept((T) event);
                }
            }
        }
}
