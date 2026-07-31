package com.botmaker.studio.events;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 4 (and ui MISSING 5) — {@link EventBus} error handling on <em>both</em>
 * branches.</b> Gates <b>SV11</b>, which gates <b>SU13</b>.
 *
 * <p>The bus is the only inter-service channel Studio has: every service publishes through it and
 * {@code UIManager} subscribes to most of it. Its {@code publish} loop already wraps each handler in a
 * try/catch — which is the whole of Studio's error logging — so a panel that throws while rendering does not
 * take the publisher down with it.
 *
 * <p>That guard covers one of the two ways a handler is invoked. The other, {@code runOnFxThread}, hands the
 * call to {@code Platform.runLater} and returns; the throw then happens on the FX thread, <em>outside</em> the
 * try/catch, long after the publisher has moved on. Roughly half of Studio's subscriptions take that branch —
 * they are the UI ones, the handlers most likely to throw — so the module's only error logging does not cover
 * the half that needs it. The last test is that gap, and it is red.
 */
class EventBusErrorHandlingTest extends FxHeadlessTest {

    /** A trivial event, so the test does not depend on any particular member of {@code CoreApplicationEvents}. */
    private record Ping(String tag) implements ApplicationEvent {}

    private final List<LogRecord> logged = new ArrayList<>();
    private Handler capture;
    private Logger busLogger;

    @Override
    public void start(Stage stage) {
        // No scene under test; ApplicationTest only needs to have started the FX runtime.
    }

    @BeforeEach
    void captureTheBusLog() {
        busLogger = Logger.getLogger(EventBus.class.getName());
        capture = new Handler() {
            @Override public void publish(LogRecord record) { synchronized (logged) { logged.add(record); } }
            @Override public void flush() {}
            @Override public void close() {}
        };
        busLogger.addHandler(capture);
    }

    @AfterEach
    void releaseTheBusLog() {
        busLogger.removeHandler(capture);
        logged.clear();
    }

    private List<LogRecord> severeRecords() {
        synchronized (logged) {
            return logged.stream().filter(r -> r.getLevel() == Level.SEVERE).toList();
        }
    }

    /** Runs {@code work} on the FX thread and waits for it (and for anything it queued behind it) to drain. */
    private static void onFxAndSettle(Runnable work) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> { try { work.run(); } finally { done.countDown(); } });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX thread never ran the work");
        CountDownLatch drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS), "FX queue never drained");
    }

    // ---- The publishing-thread branch ----

    @Test
    void aHandlerThatThrowsDoesNotStopTheOnesBehindIt() {
        EventBus bus = new EventBus();
        List<String> reached = new ArrayList<>();

        bus.subscribe(Ping.class, e -> { throw new IllegalStateException("panel blew up"); });
        bus.subscribe(Ping.class, e -> reached.add(e.tag()));

        bus.publish(new Ping("one"));

        assertEquals(List.of("one"), reached,
                "one bad subscriber must not silence every other service on the bus");
    }

    @Test
    void aHandlerThatThrowsIsLoggedWithTheEventAndTheCause() {
        EventBus bus = new EventBus();
        bus.subscribe(Ping.class, e -> { throw new IllegalStateException("panel blew up"); });

        bus.publish(new Ping("one"));

        List<LogRecord> severe = severeRecords();
        assertEquals(1, severe.size(), "exactly one SEVERE record: " + severe);
        assertTrue(severe.get(0).getMessage().contains("Ping"),
                "the log must name the event, or it is unactionable: " + severe.get(0).getMessage());
        assertTrue(severe.get(0).getThrown() instanceof IllegalStateException,
                "the cause must ride along, not just the message");
    }

    @Test
    void publishingSurvivesAHandlerThatThrowsAndKeepsWorkingAfterwards() {
        EventBus bus = new EventBus();
        List<String> reached = new ArrayList<>();
        bus.subscribe(Ping.class, e -> { throw new IllegalStateException("first"); });
        bus.subscribe(Ping.class, e -> reached.add(e.tag()));

        bus.publish(new Ping("one"));
        bus.publish(new Ping("two"));

        assertEquals(List.of("one", "two"), reached, "the bus must not be poisoned by a bad handler");
    }

    /** A null publish is a no-op rather than an NPE — services publish computed events that can be absent. */
    @Test
    void publishingNothingIsANoOp() {
        EventBus bus = new EventBus();
        bus.subscribe(Ping.class, e -> { throw new AssertionError("must not be called"); });

        bus.publish(null);

        assertTrue(severeRecords().isEmpty());
    }

    // ---- The FX branch ----

    /** The FX branch delivers, which is the precondition for the gap below being about errors and not wiring. */
    @Test
    void anFxSubscriberIsDeliveredOnTheFxThread() throws InterruptedException {
        EventBus bus = new EventBus();
        List<Boolean> onFx = new ArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);

        bus.subscribe(Ping.class, e -> {
            onFx.add(Platform.isFxApplicationThread());
            delivered.countDown();
        }, true);

        bus.publish(new Ping("one")); // published from the test thread, so it takes the runLater branch

        assertTrue(delivered.await(10, TimeUnit.SECONDS), "the FX handler never ran");
        assertEquals(List.of(true), onFx);
    }

    /**
     * Publishing from the FX thread with {@code runOnFxThread} short-circuits {@code runLater} and calls the
     * handler inline — so <em>that</em> path is inside the try/catch and is guarded. Worth pinning, because it
     * is why the gap below is easy to miss: half the FX subscriptions are already covered, by accident of
     * which thread published.
     */
    @Test
    void anFxSubscriberPublishedFromTheFxThreadIsStillGuarded() throws InterruptedException {
        EventBus bus = new EventBus();
        bus.subscribe(Ping.class, e -> { throw new IllegalStateException("panel blew up"); }, true);

        onFxAndSettle(() -> bus.publish(new Ping("one")));

        assertEquals(1, severeRecords().size(),
                "an inline FX delivery goes through the guarded loop: " + severeRecords());
    }

    /**
     * <b>The gap.</b> Same subscription, same throwing handler — published from a background thread, which is
     * where the services that matter publish from. {@code Platform.runLater(() -> handler.accept(event))}
     * escapes the try/catch, so the throw lands on the FX thread with no logging, no event name and no cause.
     * The user sees a panel stop updating and nothing else; Studio's only error logging does not cover it.
     */
    @Test
    @Disabled("SV11/B-adjacent is unfixed: verified red on this commit — EventBus.EventHandler.handle wraps "
            + "the FX delivery in Platform.runLater outside publish()'s try/catch, so a throwing FX handler "
            + "is never logged. Delete this line in Phase 4 with SV11's fix.")
    void anFxSubscriberThatThrowsIsLoggedToo() throws InterruptedException {
        EventBus bus = new EventBus();
        bus.subscribe(Ping.class, e -> { throw new IllegalStateException("panel blew up"); }, true);

        bus.publish(new Ping("one")); // from the test thread → the runLater branch
        onFxAndSettle(() -> { /* let the queued delivery run and throw */ });

        List<LogRecord> severe = severeRecords();
        assertFalse(severe.isEmpty(),
                "a UI handler that throws must be logged like any other — it is the half most likely to");
        assertTrue(severe.get(0).getThrown() instanceof IllegalStateException,
                "and with its cause: " + severe.get(0).getThrown());
    }
}
