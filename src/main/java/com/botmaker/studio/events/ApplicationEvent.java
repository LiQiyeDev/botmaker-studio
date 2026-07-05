package com.botmaker.studio.events;

/**
 * Marker interface for all application events. Events represent things that have happened
 * in the application and are dispatched through the {@link EventBus}.
 *
 * <p>A handler may subscribe to {@code ApplicationEvent} itself to receive every event (used by
 * the event log), or to any sealed sub-family (e.g. {@code DebugControlRequest}) to receive that
 * group — the bus dispatches by supertype.
 */
public interface ApplicationEvent {
}
