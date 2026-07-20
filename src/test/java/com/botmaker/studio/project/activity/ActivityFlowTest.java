package com.botmaker.studio.project.activity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branching flow model: reachability from an explicit start, and the JSON back-compatibility that lets an
 * {@code activities.json} written before outcomes existed keep loading as the flow it always was.
 */
class ActivityFlowTest {

    private static FlowNode at(String activity) {
        return new FlowNode(activity, 0, 0);
    }

    @Test
    void reachabilityFollowsEveryBranchAndTerminatesOnACycle() {
        ActivityFlow flow = new ActivityFlow(
                List.of(at("Mining"), at("Smelt"), at("Travel"), at("Idle")),
                List.of(new FlowEdge("Mining", "Smelt", "BAG_FULL"),
                        new FlowEdge("Mining", "Travel", "NO_ORE"),
                        new FlowEdge("Travel", "Mining", ""),          // loops back
                        new FlowEdge("Smelt", ActivityFlow.STOP_ID, "")),
                "Mining", ActivityFlow.DEFAULT_MAX_STEPS);

        assertEquals(List.of("Mining", "Smelt", "Travel"),
                flow.reachable(List.of("Mining", "Smelt", "Travel", "Idle")),
                "both branches are reachable, the cycle terminates, and STOP is not something that runs");
    }

    @Test
    void theStartDecidesWhatRunsNotThePlacementOrder() {
        // Smelt is placed first but is not the entry point; nothing may be inferred from placement.
        ActivityFlow flow = new ActivityFlow(
                List.of(at("Smelt"), at("Mining")),
                List.of(new FlowEdge("Mining", "Smelt")),
                "Mining", 0);

        assertEquals(List.of("Mining", "Smelt"), flow.reachable(List.of("Mining", "Smelt")));
    }

    @Test
    void anUnsetStartFallsBackToTheFirstPlacedActivity() {
        // How a flow drawn before start nodes existed keeps generating something that runs.
        ActivityFlow flow = new ActivityFlow(
                List.of(at("Mining"), at("Smelt")), List.of(new FlowEdge("Mining", "Smelt")));

        assertEquals("Mining", flow.resolvedStart(List.of("Mining", "Smelt")));
        assertEquals(List.of("Mining", "Smelt"), flow.reachable(List.of("Mining", "Smelt")));
    }

    @Test
    void aFlowWithNoWiresRunsEverythingInListOrder() {
        assertEquals(List.of("A", "B"), ActivityFlow.empty().reachable(List.of("A", "B")));
    }

    @Test
    void anInvalidMaxStepsFallsBackToTheDefaultRatherThanNeverStepping() {
        // A zero budget would generate a driver that stops before running anything at all.
        assertEquals(ActivityFlow.DEFAULT_MAX_STEPS, new ActivityFlow(List.of(), List.of(), "A", 0).maxSteps());
        assertEquals(ActivityFlow.DEFAULT_MAX_STEPS, new ActivityFlow(List.of(), List.of(), "A", -5).maxSteps());
    }

    @Test
    void theStopNodeIsNotAnActivityAndCarriesItsOwnKind() {
        FlowNode stop = FlowNode.stop(120, 40);
        assertTrue(stop.isStop());
        assertEquals(ActivityFlow.STOP_ID, stop.activity());
        assertFalse(at("Mining").isStop(), "an activity card is never the terminal");
    }

    @Test
    void anOlderActivitiesJsonLoadsWithDefaultOutcomesAndAnUnsetStart(@TempDir Path dir) throws IOException {
        // Written by a Studio that had no outcomes, no node kinds, no start and no step budget.
        Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                {
                  "activities" : [ {
                    "name" : "Mining", "enabled" : true, "description" : "", "params" : [ ]
                  } ],
                  "globals" : [ ],
                  "flow" : {
                    "nodes" : [ { "activity" : "Mining", "x" : 60.0, "y" : 40.0 } ],
                    "edges" : [ { "from" : "Mining", "to" : "Mining" } ]
                  },
                  "presets" : [ ]
                }
                """);

        ActivitiesConfig loaded = ActivitiesConfig.read(dir);
        ActivityDefinition mining = loaded.activities().getFirst();

        assertEquals(List.of(), mining.outcomes(), "no declared outcomes");
        assertEquals(List.of(FlowEdge.DEFAULT_OUTCOME), mining.allOutcomes(),
                "but the implicit default always exists, so the card still has its one output port");
        assertEquals(FlowNodeKind.ACTIVITY, loaded.flow().nodes().getFirst().kind());
        assertTrue(loaded.flow().edges().getFirst().isDefault(), "a pre-outcome wire is the default wire");
        assertEquals("", loaded.flow().start(), "unset, so resolvedStart falls back");
        assertEquals("Mining", loaded.flow().resolvedStart(List.of("Mining")));
        assertEquals(ActivityFlow.DEFAULT_MAX_STEPS, loaded.flow().maxSteps());
    }

    @Test
    void outcomesAndTheStopNodeSurviveAJsonRoundTrip(@TempDir Path dir) throws IOException {
        ActivitiesConfig saved = new ActivitiesConfig(
                List.of(ActivityDefinition.create("Mining", "").withOutcomes(List.of("BAG_FULL", "NO_ORE"))),
                List.of(),
                new ActivityFlow(List.of(at("Mining"), FlowNode.stop(400, 40)),
                        List.of(new FlowEdge("Mining", ActivityFlow.STOP_ID, "BAG_FULL")),
                        "Mining", 250),
                List.of());
        saved.write(dir);

        ActivitiesConfig loaded = ActivitiesConfig.read(dir);

        assertEquals(List.of("BAG_FULL", "NO_ORE"), loaded.activities().getFirst().outcomes());
        assertEquals("BAG_FULL", loaded.flow().edges().getFirst().outcome());
        assertEquals("Mining", loaded.flow().start());
        assertEquals(250, loaded.flow().maxSteps());
        assertTrue(loaded.flow().stopNode().isPresent(), "the terminal card's kind survived the round trip");
    }

    @Test
    void aStoredDefaultOutcomeDoesNotDuplicateTheImplicitOne() {
        ActivityDefinition a = ActivityDefinition.create("Mining", "")
                .withOutcomes(List.of(FlowEdge.DEFAULT_OUTCOME, "BAG_FULL"));
        assertEquals(List.of(FlowEdge.DEFAULT_OUTCOME, "BAG_FULL"), a.allOutcomes(),
                "the generated enum must not declare DEFAULT twice — that would not compile");
    }
}
