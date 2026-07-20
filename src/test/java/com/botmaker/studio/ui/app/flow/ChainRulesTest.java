package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.FlowEdge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules that keep the Activity Flow canvas a single linear chain. Pure — no JavaFX involved. */
public class ChainRulesTest {

    private static final List<FlowEdge> A_TO_B = List.of(new FlowEdge("A", "B"));

    @Test
    void aFreshWireBetweenUnconnectedActivitiesIsAllowed() {
        assertNull(ChainRules.rejectionFor(List.of(), "A", "B"));
        assertNull(ChainRules.rejectionFor(A_TO_B, "B", "C"));
    }

    @Test
    void anActivityCannotFollowItself() {
        assertNotNull(ChainRules.rejectionFor(List.of(), "A", "A"));
    }

    @Test
    void forksAndJoinsAreRejectedSoTheFlowStaysLinear() {
        // A already has a next activity — a second wire out of A would be a fork.
        assertNotNull(ChainRules.rejectionFor(A_TO_B, "A", "C"));
        // B already runs after A — a second wire into B would be a join.
        assertNotNull(ChainRules.rejectionFor(A_TO_B, "C", "B"));
    }

    @Test
    void loopsAreRejected() {
        List<FlowEdge> chain = List.of(new FlowEdge("A", "B"), new FlowEdge("B", "C"));
        // C → A closes the ring; the flow runs top to bottom once, so there is nothing to loop back to.
        String rejection = ChainRules.rejectionFor(chain, "C", "A");
        assertNotNull(rejection);
        assertTrue(rejection.contains("loop"), rejection);
    }

    @Test
    void theChainRunsFromTheNodeNothingWiresInto() {
        // Placement order deliberately differs from run order: the wiring is what decides.
        List<String> placed = List.of("B", "C", "A");
        List<FlowEdge> edges = List.of(new FlowEdge("A", "B"), new FlowEdge("B", "C"));
        assertEquals(List.of("A", "B", "C"), ChainRules.chain(placed, edges));
    }

    @Test
    void activitiesTheChainNeverReachesAreOrphans() {
        List<String> placed = List.of("A", "B", "Idle");
        assertEquals(List.of("Idle"), ChainRules.orphans(placed, A_TO_B));
    }

    @Test
    void withNoWiresNothingIsAnOrphan() {
        // Nothing is wired yet, so there is no chain to be outside of — everything still runs, in list order.
        assertEquals(List.of(), ChainRules.orphans(List.of("A", "B"), List.of()));
    }

    @Test
    void aSecondDisconnectedChainCountsAsOrphaned() {
        // Only one chain can run, so the canvas warns about the other rather than silently picking one.
        List<String> placed = List.of("A", "B", "X", "Y");
        List<FlowEdge> edges = List.of(new FlowEdge("A", "B"), new FlowEdge("X", "Y"));
        assertEquals(List.of("A", "B"), ChainRules.chain(placed, edges));
        assertEquals(List.of("X", "Y"), ChainRules.orphans(placed, edges));
    }
}
