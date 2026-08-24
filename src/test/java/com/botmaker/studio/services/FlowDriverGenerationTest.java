package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.project.activity.FlowNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated {@code FlowDriver} — the thing that makes a drawn branch mean something at runtime.
 *
 * <h2>What is being asserted, now that the walk is the SDK's</h2>
 *
 * <p>Studio no longer generates the state machine: it generates the <b>table</b> the SDK's
 * {@link com.botmaker.sdk.api.flow.FlowGraph} walks. So the behaviour of a branch, a join, a loop, an unwired
 * outcome and a disabled fall-through is tested over there, against a compiled walker, once. What is left
 * here is the half that is genuinely Studio's: <em>does the drawn flow become the right table</em> — the
 * right start node, the right {@code PopupCheck}, the right {@code Recovery}, a route per wire and no row at
 * all for an activity nothing can reach.
 *
 * <p>String assertions prove the source says what we meant; only a compiler proves it is valid Java, which is
 * the failure mode that actually reaches a user. That half used to live here as one more test —
 * {@code aBranchingFlowGeneratesAProjectThatCompiles}, over this one flow. It moved to
 * {@code ScaffoldCompileTest}, which compiles this shape and three others as <em>whole projects</em>: the
 * driver is not the only generated file and its interesting failures are between files, not inside one.
 */
class FlowDriverGenerationTest {

    private static FlowNode at(String activity) {
        return new FlowNode(activity, 0, 0);
    }

    private static ActivityDefinition activity(String name, String... outcomes) {
        return ActivityDefinition.create(name, "").withOutcomes(List.of(outcomes));
    }

    /**
     * Mining branches on two outcomes; Travel loops back to Mining; Smelt wires nothing, so reaching it ends
     * the run. Idle is placed but unreachable. That is every shape the table has to express, in one flow.
     */
    private static ActivitiesConfig branchingFlow() {
        return ActivitiesConfig.of(
                List.of(activity("Mining", "BAG_FULL", "NO_ORE"),
                        activity("Smelt"),
                        activity("Travel"),
                        activity("Idle")),
                List.of())
                .withFlow(new ActivityFlow(
                        List.of(at("Mining"), at("Smelt"), at("Travel"), at("Idle")),
                        List.of(new FlowEdge("Mining", "Smelt", "BAG_FULL"),
                                new FlowEdge("Mining", "Travel", "NO_ORE"),
                                new FlowEdge("Travel", "Mining", "")),
                        "Mining", 250));
    }

    private static ActivityService serviceFor(Path root) {
        return new ActivityService(ProjectConfig.forProject("actbot", root), null, null);
    }

    @Test
    void theDriverStartsAtTheFlowsStartAndCarriesItsStepBudget(@TempDir Path root) throws Exception {
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        assertTrue(source.contains("FlowGraph.of(\n            \"Mining\","),
                "the start node is the graph's first argument:\n" + source);
        assertTrue(source.contains("MAX_STEPS = 250;"), "the flow's own budget, not the default:\n" + source);
    }

    @Test
    void eachOutcomeRoutesToItsOwnTarget(@TempDir Path root) throws Exception {
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        // Spelled through the activity's own Outcome enum, which is what makes the route checked: node() is
        // generic in it, so a constant of another activity's enum would not compile.
        assertTrue(source.contains("FlowGraph.route(Mining.Outcome.BAG_FULL, \"Smelt\")"), source);
        assertTrue(source.contains("FlowGraph.route(Mining.Outcome.NO_ORE, \"Travel\")"), source);
        assertTrue(source.contains("FlowGraph.route(Travel.Outcome.NEXT, \"Mining\")"),
                "Travel's implicit wire loops back — a cycle is a legal flow:\n" + source);
    }

    @Test
    void anOutcomeWithNoWireEndsTheRun(@TempDir Path root) throws Exception {
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        // Smelt wires nothing, so its row carries no route at all — an outcome with nowhere to go *is* the
        // stop. There is no terminal node, so nothing like "@stop" should ever appear in generated source.
        assertFalse(source.contains("@stop"), "there is no terminal node to visit");
        assertTrue(source.contains(
                        "FlowGraph.node(\"Smelt\", ActivityRegistry.SMELT, PopupCheck.ON, Recovery.GO_HOME, null)"),
                source);
        assertFalse(source.contains("Smelt.Outcome"), "nothing is routed out of Smelt:\n" + source);
    }

    @Test
    void goingHomeIsPerActivityAndOnlyRecordedOnItsOwnRow(@TempDir Path root) throws Exception {
        ActivitiesConfig cfg = branchingFlow();
        ActivitiesConfig mixed = ActivitiesConfig.of(
                List.of(cfg.activities().getFirst().withGoHome(false), cfg.activities().get(1),
                        cfg.activities().get(2), cfg.activities().get(3)),
                List.of()).withFlow(cfg.flow());

        String source = serviceFor(root).generateDriverSource(mixed);

        // Travel keeps the default tick; Mining has it off. *When* the SDK runs goHome — after the activity
        // is known to be active, never before — is the walker's business and is asserted there.
        assertTrue(source.contains("ActivityRegistry.MINING, PopupCheck.ON, Recovery.NONE,"), source);
        assertTrue(source.contains("ActivityRegistry.TRAVEL, PopupCheck.ON, Recovery.GO_HOME,"), source);
    }

    /**
     * The popup setting is written on every row, not only the ones that opt out. {@code PopupGuard}'s flag is
     * process-global, so an activity whose row said nothing would run under whatever the activity before it
     * left set — the one behaviour a per-activity tick must not have. Carrying it per node is what lets the
     * walker set it every time.
     */
    @Test
    void thePopupSettingIsOnEveryRowIncludingTheOnesThatKeepIt(@TempDir Path root) throws Exception {
        ActivitiesConfig cfg = branchingFlow();
        ActivitiesConfig mixed = ActivitiesConfig.of(
                List.of(cfg.activities().getFirst().withPopupCheck(false), cfg.activities().get(1),
                        cfg.activities().get(2), cfg.activities().get(3)),
                List.of()).withFlow(cfg.flow());

        String source = serviceFor(root).generateDriverSource(mixed);

        assertTrue(source.contains("ActivityRegistry.MINING, PopupCheck.OFF,"),
                "the activity that opted out must say so:\n" + source);
        assertTrue(source.contains("ActivityRegistry.TRAVEL, PopupCheck.ON,"),
                "and the next one must say so too rather than inherit:\n" + source);
        assertTrue(source.contains("import com.botmaker.sdk.api.flow.PopupCheck;"), source);
    }

    @Test
    void anUnreachableActivityIsNotInTheDriverAtAll(@TempDir Path root) throws Exception {
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        assertFalse(source.contains("\"Idle\""), "an orphan can't be routed to, so it has no row:\n" + source);
    }

    @Test
    void aFlowWithNoActivitiesGeneratesAGraphThatEndsAtOnce(@TempDir Path root) throws Exception {
        String source = serviceFor(root).generateDriverSource(ActivitiesConfig.empty());

        assertTrue(source.contains("FlowGraph.of(\n            null)"),
                "a start of null is an empty flow, which ends immediately:\n" + source);
    }

    @Test
    void aDisabledActivityFollowsItsDefaultWireRatherThanStoppingTheFlow(@TempDir Path root) throws Exception {
        // Turning an activity off must not sever the flow at that card — everything downstream still runs.
        // That is the fifth argument: where the walker goes when the activity reports nothing because it
        // never ran. Travel's implicit wire leads to Mining, so that is where a disabled Travel goes.
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        assertTrue(source.contains("ActivityRegistry.TRAVEL, PopupCheck.ON, Recovery.GO_HOME, \"Mining\""),
                source);
        assertTrue(source.contains("ActivityRegistry.MINING, PopupCheck.ON, Recovery.GO_HOME, null,"),
                "Mining has no implicit wire, so a disabled Mining ends the run:\n" + source);
    }

}
