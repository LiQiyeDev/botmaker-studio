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
                        new FlowEdge("Travel", "Mining", "")),         // loops back
                "Mining", ActivityFlow.DEFAULT_MAX_STEPS);

        assertEquals(List.of("Mining", "Smelt", "Travel"),
                flow.reachable(List.of("Mining", "Smelt", "Travel", "Idle")),
                "both branches are reachable, the cycle terminates, and the unwired Idle never runs");
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
        assertEquals(List.of(FlowEdge.NEXT_OUTCOME), mining.allOutcomes(),
                "but the implicit NEXT always exists, so the card still has its one output port");
        assertTrue(loaded.flow().edges().getFirst().isNext(), "a pre-outcome wire is the implicit wire");
        assertEquals("", loaded.flow().start(), "unset, so resolvedStart falls back");
        assertEquals("Mining", loaded.flow().resolvedStart(List.of("Mining")));
        assertEquals(ActivityFlow.DEFAULT_MAX_STEPS, loaded.flow().maxSteps());
        assertTrue(mining.goHome(), "an activity written before the field existed still goes home first");
        assertTrue(loaded.goHomeByDefault());
    }

    @Test
    void aFlowContainingTheOldStopCardStillLoadsAndIgnoresIt(@TempDir Path dir) throws IOException {
        // Written by the Studio that had a Stop card. The node names no activity and the edge points at it, so
        // both have to vanish quietly — the run now ends at BAG_FULL simply by having nowhere to go.
        Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                {
                  "activities" : [ {
                    "name" : "Mining", "enabled" : true, "description" : "", "params" : [ ],
                    "outcomes" : [ "BAG_FULL" ]
                  } ],
                  "globals" : [ ],
                  "flow" : {
                    "nodes" : [ { "activity" : "Mining", "x" : 60.0, "y" : 40.0, "kind" : "activity" },
                                { "activity" : "@stop", "x" : 400.0, "y" : 40.0, "kind" : "stop" } ],
                    "edges" : [ { "from" : "Mining", "to" : "@stop", "outcome" : "BAG_FULL" } ],
                    "start" : "Mining"
                  },
                  "presets" : [ ]
                }
                """);

        ActivitiesConfig loaded = ActivitiesConfig.read(dir);

        assertEquals(List.of("Mining"), loaded.flow().reachable(List.of("Mining")),
                "the terminal is not an activity, so it is not something the run reaches");
        assertEquals(List.of("Mining"),
                loaded.orderedActivities().stream().map(ActivityDefinition::name).toList());
    }

    @Test
    void outcomesSurviveAJsonRoundTrip(@TempDir Path dir) throws IOException {
        ActivitiesConfig saved = new ActivitiesConfig(
                List.of(ActivityDefinition.create("Mining", "").withOutcomes(List.of("BAG_FULL", "NO_ORE"))
                        .withGoHome(false)),
                List.of(),
                new ActivityFlow(List.of(at("Mining")),
                        List.of(new FlowEdge("Mining", "Mining", "BAG_FULL")),
                        "Mining", 250),
                List.of(), false);
        saved.write(dir);

        ActivitiesConfig loaded = ActivitiesConfig.read(dir);

        assertEquals(List.of("BAG_FULL", "NO_ORE"), loaded.activities().getFirst().outcomes());
        assertEquals("BAG_FULL", loaded.flow().edges().getFirst().outcome());
        assertEquals("Mining", loaded.flow().start());
        assertEquals(250, loaded.flow().maxSteps());
        assertFalse(loaded.activities().getFirst().goHome(), "an explicit false is not the absent default");
        assertFalse(loaded.goHomeByDefault());
    }

    @Test
    void aStoredNextOutcomeDoesNotDuplicateTheImplicitOne() {
        ActivityDefinition a = ActivityDefinition.create("Mining", "")
                .withOutcomes(List.of(FlowEdge.NEXT_OUTCOME, "BAG_FULL"));
        assertEquals(List.of(FlowEdge.NEXT_OUTCOME, "BAG_FULL"), a.allOutcomes(),
                "the generated enum must not declare NEXT twice — that would not compile");
    }
}
