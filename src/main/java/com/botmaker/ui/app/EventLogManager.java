package com.botmaker.ui.app;

import com.botmaker.events.ApplicationEvent;
import com.botmaker.events.CoreApplicationEvents;
import com.botmaker.events.EventBus;
import javafx.application.Platform;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Event Log pane.
 * Buffers events to prevent UI flooding and filters out high-frequency noise.
 */
public class EventLogManager {

    private final ListView<String> eventListView;
    private final ConcurrentLinkedQueue<String> pendingLogs = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService uiUpdater;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private static final int MAX_LOG_ENTRIES = 1000;
    private static final int UPDATE_INTERVAL_MS = 250; // Update UI max 4 times per second

    public EventLogManager(EventBus eventBus) {
        this.eventListView = new ListView<>();
        this.eventListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        this.eventListView.getStyleClass().add("event-log-list");

        // Use a monospaced font style via CSS or inline for now
        this.eventListView.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 11px;");

        // Subscribe to everything
        eventBus.subscribe(ApplicationEvent.class, this::handleEvent, false);

        // Start the UI update thread
        this.uiUpdater = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EventLog-Updater");
            t.setDaemon(true);
            return t;
        });

        startUiLoop();
    }

    public ListView<String> getView() {
        return eventListView;
    }

    private void handleEvent(ApplicationEvent event) {
        // 1. FILTERING: Ignore extremely frequent events that have their own displays
        if (event instanceof CoreApplicationEvents.OutputAppendedEvent) return;

        // Optional: Filter CodeUpdatedEvent if it's too noisy (happens on every keypress)
        // if (event instanceof CoreApplicationEvents.CodeUpdatedEvent) return;

        // 2. FORMATTING
        String timestamp = timeFormatter.format(Instant.now());
        String eventName = event.getClass().getSimpleName();

        String details = "";

        // Extract interesting details for specific events
        if (event instanceof CoreApplicationEvents.StatusMessageEvent e) {
            details = ": " + e.message();
        } else if (event instanceof CoreApplicationEvents.DebugSessionPausedEvent e) {
            details = " @ Line " + e.lineNumber();
        } else if (event instanceof CoreApplicationEvents.BreakpointToggledEvent bp) {
            details = " (" + (bp.enabled() ? "ON" : "OFF") + ") ID: " + bp.block().getId();
        }

        String logEntry = String.format("[%s] %-25s%s", timestamp, eventName, details);

        // 3. QUEUING
        pendingLogs.offer(logEntry);
    }

    private void startUiLoop() {
        uiUpdater.scheduleAtFixedRate(() -> {
            if (pendingLogs.isEmpty()) return;

            // Drain the queue into a temporary list
            List<String> batch = new ArrayList<>();
            String log;
            while ((log = pendingLogs.poll()) != null) {
                batch.add(log);
            }

            if (!batch.isEmpty()) {
                Platform.runLater(() -> {
                    eventListView.getItems().addAll(batch);

                    // Prune old entries
                    if (eventListView.getItems().size() > MAX_LOG_ENTRIES) {
                        eventListView.getItems().remove(0, eventListView.getItems().size() - MAX_LOG_ENTRIES);
                    }

                    // Auto-scroll to bottom
                    eventListView.scrollTo(eventListView.getItems().size() - 1);
                });
            }
        }, UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        uiUpdater.shutdownNow();
    }
}