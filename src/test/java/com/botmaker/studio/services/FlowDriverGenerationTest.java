package com.botmaker.studio.services;

import com.botmaker.sdk.api.bot.Activity;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.project.activity.FlowNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p>{@link #aBranchingFlowGeneratesAProjectThatCompiles} is still the important one, for the same reason as
 * before: string assertions prove the source says what we meant, and only a compiler proves it is valid Java
 * — the failure mode that actually reaches a user. It compiles against the <b>real SDK jar</b> now. It used
 * to supply six hand-written stand-ins, on the grounds that Studio did not depend on the SDK; it has since
 * 2026-08 (for type identity), and the jar is on this module's test classpath — so the stand-ins were a
 * second, hand-maintained copy of signatures the real thing already has, with a comment asking the next
 * reader to keep them in step.
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

    @Test
    void aBranchingFlowGeneratesAProjectThatCompiles(@TempDir Path root) throws Exception {
        ActivitiesConfig cfg = branchingFlow();
        ProjectConfig config = ProjectConfig.forProject("actbot", root);
        ActivityService service = new ActivityService(config, null, null);

        List<Path> sources = new ArrayList<>();
        sources.add(write(config.activitiesSourceFile(), service.generateSource(cfg)));
        sources.add(write(config.activityRegistrySourceFile(), service.generateRegistrySource(cfg)));
        sources.add(write(config.flowDriverSourceFile(), service.generateDriverSource(cfg)));
        for (ActivityDefinition a : cfg.activities()) {
            sources.add(write(config.activitiesPackageDir().resolve(a.name() + ".java"),
                    service.generateStubSource(a)));
        }
        // The driver hands the walker GoHome.INSTANCE::execute. GoHome is scaffolded into the project by
        // ProjectCreator rather than generated here, so the test supplies it — from the same SDK template,
        // which is also the cheapest proof that a seed and a regenerated file agree about the package.
        sources.add(write(config.mainSourceFile().getParent().resolve("GoHome.java"),
                com.botmaker.studio.project.ProjectCreator
                        .gameBotSources("Actbot", config.packageName()).get("GoHome.java")));

        assertEquals("", compile(root, sources), "the generated project must compile");
    }

    /** Compiles {@code sources} against the real SDK; returns the diagnostics, or "" when it succeeded. */
    private static String compile(Path root, List<Path> sources) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path sdk = Path.of(Activity.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc;
        try (PrintStream out = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            rc = compiler.run(null, null, out, Stream.concat(
                    Stream.of("-classpath", sdk.toString(), "-d", classes.toString()),
                    sources.stream().map(Path::toString)).toArray(String[]::new));
        }
        return rc == 0 ? "" : diagnostics.toString(StandardCharsets.UTF_8);
    }

    private static Path write(Path file, String source) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
