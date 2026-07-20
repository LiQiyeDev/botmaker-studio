package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.FlowEdge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules the Activity Flow canvas enforces while wiring. Pure — no JavaFX involved.
 *
 * <p>Most of this file is about what is <em>no longer</em> rejected: forks, joins, self-wires and cycles were
 * all vetoed while the flow had to be a single linear chain, and each is now a shape the user is meant to
 * draw. Asserting they're allowed is the point — they are exactly what a silent regression would take away.
 */
public class FlowRulesTest {

    private static final List<FlowEdge> A_TO_B = List.of(new FlowEdge("A", "B"));

    @Test
    void aFreshWireBetweenUnconnectedActivitiesIsAllowed() {
        assertNull(FlowRules.rejectionFor(List.of(), "A", "", "B"));
        assertNull(FlowRules.rejectionFor(A_TO_B, "B", "", "C"));
    }

    @Test
    void anActivityMayNowWireToItself() {
        // "Didn't work — try again" is a self-wire. The step budget is what stops it spinning, not the editor.
        assertNull(FlowRules.rejectionFor(List.of(), "A", "FAILED", "A"));
    }

    @Test
    void aForkOnDifferentOutcomesIsTheWholePoint() {
        // Two wires out of A, one per outcome — this is branching, and it used to be rejected outright.
        List<FlowEdge> edges = List.of(new FlowEdge("A", "B", "BAG_FULL"));
        assertNull(FlowRules.rejectionFor(edges, "A", "NO_ORE", "C"));
        assertNull(FlowRules.rejectionFor(edges, "A", "", "D"), "the default outcome is its own wire too");
    }

    @Test
    void aJoinIsAllowedSoBranchesCanMeetAgain() {
        List<FlowEdge> edges = List.of(new FlowEdge("A", "C", "BAG_FULL"));
        assertNull(FlowRules.rejectionFor(edges, "B", "", "C"));
    }

    @Test
    void aCycleIsAllowedBecauseItIsHowABotRepeats() {
        List<FlowEdge> chain = List.of(new FlowEdge("A", "B"), new FlowEdge("B", "C"));
        assertNull(FlowRules.rejectionFor(chain, "C", "DONE", "A"));
    }

    @Test
    void oneOutcomeCannotLeadToTwoPlaces() {
        List<FlowEdge> edges = List.of(new FlowEdge("A", "B", "BAG_FULL"));
        String rejection = FlowRules.rejectionFor(edges, "A", "BAG_FULL", "C");
        assertNotNull(rejection);
        assertTrue(rejection.contains("BAG_FULL"), rejection);
    }

    @Test
    void aBlankOutcomeIsTheSameWireAsAnExplicitDefault() {
        // Persisted blank vs. "DEFAULT" must not become two competing wires out of the same port.
        assertNotNull(FlowRules.rejectionFor(List.of(new FlowEdge("A", "B", "")), "A", "DEFAULT", "C"));
        assertNotNull(FlowRules.rejectionFor(List.of(new FlowEdge("A", "B", "DEFAULT")), "A", "", "C"));
    }

    @Test
    void nothingCanComeAfterStop() {
        String rejection = FlowRules.rejectionFor(List.of(), ActivityFlow.STOP_ID, "", "A");
        assertNotNull(rejection);
        assertTrue(rejection.contains("Stop"), rejection);
    }

    @Test
    void reachabilityStartsAtTheNamedStartNotAtWhateverWasPlacedFirst() {
        // Placement order is canvas insertion order and says nothing about the flow; the start node decides.
        List<String> placed = List.of("B", "C", "A");
        List<FlowEdge> edges = List.of(new FlowEdge("A", "B"), new FlowEdge("B", "C"));
        assertEquals(List.of("A", "B", "C"), FlowRules.reachable(placed, edges, "A"));
    }

    @Test
    void activitiesTheFlowNeverReachesAreOrphans() {
        List<String> placed = List.of("A", "B", "Idle");
        assertEquals(List.of("Idle"), FlowRules.orphans(placed, A_TO_B, "A"));
    }

    @Test
    void withNoWiresNothingIsAnOrphan() {
        // Nothing is wired yet, so there is no flow to be outside of — everything still runs, in list order.
        assertEquals(List.of(), FlowRules.orphans(List.of("A", "B"), List.of(), "A"));
    }

    @Test
    void anUnwiredCardOrphansOnlyItself() {
        // The old regression, now structurally impossible: the root used to be *inferred* as "a node nothing
        // wires into", so a lone un-wired card could outrank the real chain and orphan every wired activity.
        // With an explicit start there is nothing to infer, so placement order cannot matter.
        List<FlowEdge> edges = List.of(new FlowEdge("A", "B"), new FlowEdge("B", "C"));
        for (List<String> placed : List.of(
                List.of("D", "A", "B", "C"),   // the un-wired card first — the case that used to fail
                List.of("A", "B", "C", "D"),
                List.of("A", "D", "B", "C"))) {
            assertEquals(List.of("A", "B", "C"), FlowRules.reachable(placed, edges, "A"), "placed: " + placed);
            assertEquals(List.of("D"), FlowRules.orphans(placed, edges, "A"), "placed: " + placed);
        }
    }

    @Test
    void aSecondDisconnectedChainCountsAsOrphaned() {
        // Only what the start can reach runs, so the canvas warns about the rest rather than silently
        // picking one — even though both halves are perfectly well-formed.
        List<String> placed = List.of("A", "B", "X", "Y");
        List<FlowEdge> edges = List.of(new FlowEdge("A", "B"), new FlowEdge("X", "Y"));
        assertEquals(List.of("A", "B"), FlowRules.reachable(placed, edges, "A"));
        assertEquals(List.of("X", "Y"), FlowRules.orphans(placed, edges, "A"));
    }

    @Test
    void aCyclicFlowStillTerminatesTheWalk() {
        List<String> placed = List.of("A", "B", "C");
        List<FlowEdge> edges = List.of(
                new FlowEdge("A", "B"), new FlowEdge("B", "C"), new FlowEdge("C", "A", "AGAIN"));
        assertEquals(List.of("A", "B", "C"), FlowRules.reachable(placed, edges, "A"));
        assertEquals(List.of(), FlowRules.orphans(placed, edges, "A"));
    }

    @Test
    void aStartThatNoLongerExistsFallsBackToTheFirstPlacedCard() {
        // The start activity was archived or renamed out from under the flow: still generate something that
        // runs, rather than reporting every card an orphan.
        List<String> placed = List.of("A", "B");
        assertEquals(List.of("A", "B"), FlowRules.reachable(placed, A_TO_B, "Deleted"));
    }
}
