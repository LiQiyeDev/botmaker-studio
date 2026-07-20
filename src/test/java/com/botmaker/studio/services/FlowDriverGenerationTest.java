package com.botmaker.studio.services;

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
 * <p>The important test here is {@link #aBranchingFlowGeneratesAProjectThatCompiles}: string assertions on
 * generated source prove it says what we meant, but only a compiler proves it is <em>valid Java</em>, which is
 * the failure mode that actually reaches a user. Studio deliberately does not depend on the SDK
 * (see {@code CLAUDE.md}), so the test emits its own minimal stand-ins for the four SDK types the generated
 * code touches. Those stand-ins mirror real signatures — if {@code Activity} changes shape, update
 * {@link #SDK_STAND_INS} to match or this test quietly stops proving anything.
 */
class FlowDriverGenerationTest {

    /**
     * Minimal stand-ins for the SDK types generated code binds to. Only the members the generator emits are
     * present; anything else would be scope this test has no business asserting.
     */
    private static final List<String> SDK_STAND_INS = List.of("""
            package com.botmaker.sdk.api;
            public final class Debug {
                public static void error(String message) {}
            }
            """, """
            package com.botmaker.sdk.api.bot;
            public abstract class Activity<O extends Enum<O>> {
                public abstract boolean isEnabled();
                public abstract O run();
                public final boolean active() { return isEnabled(); }
                public final O execute() { return run(); }
            }
            """, """
            package com.botmaker.sdk.api.bot;
            public final class Bot {
                public static void stop() {}
            }
            """, """
            package com.botmaker.sdk.api.bot;
            public final class Watchdog {
                public static void checkpoint() {}
            }
            """);

    private static FlowNode at(String activity) {
        return new FlowNode(activity, 0, 0);
    }

    private static ActivityDefinition activity(String name, String... outcomes) {
        return ActivityDefinition.create(name, "").withOutcomes(List.of(outcomes));
    }

    /**
     * Mining branches on two outcomes; Travel loops back to Mining; Smelt wires nothing, so reaching it ends
     * the run. Idle is placed but unreachable. That is every shape the driver has to emit, in one flow.
     */
    private static ActivitiesConfig branchingFlow() {
        return new ActivitiesConfig(
                List.of(activity("Mining", "BAG_FULL", "NO_ORE"),
                        activity("Smelt"),
                        activity("Travel"),
                        activity("Idle")),
                List.of(),
                new ActivityFlow(
                        List.of(at("Mining"), at("Smelt"), at("Travel"), at("Idle")),
                        List.of(new FlowEdge("Mining", "Smelt", "BAG_FULL"),
                                new FlowEdge("Mining", "Travel", "NO_ORE"),
                                new FlowEdge("Travel", "Mining", "")),
                        "Mining", 250),
                List.of());
    }

    private static ActivityService serviceFor(Path root) {
        return new ActivityService(ProjectConfig.forProject("actbot", root), null, null);
    }

    @Test
    void theDriverStartsAtTheFlowsStartAndCarriesItsStepBudget(@TempDir Path root) {
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        assertTrue(source.contains("String node = \"Mining\";"), source);
        assertTrue(source.contains("MAX_STEPS = 250;"), "the flow's own budget, not the default:\n" + source);
    }

    @Test
    void eachOutcomeRoutesToItsOwnTarget(@TempDir Path root) {
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        assertTrue(source.contains("case BAG_FULL: return \"Smelt\";"), source);
        assertTrue(source.contains("case NO_ORE: return \"Travel\";"), source);
        assertTrue(source.contains("case NEXT: return \"Mining\";"),
                "Travel's implicit wire loops back — a cycle is a legal flow:\n" + source);
    }

    @Test
    void anOutcomeWithNoWireEndsTheRun(@TempDir Path root) {
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        // Smelt wires nothing. That is the only way a run ends now — there is no terminal node, so nothing
        // like "@stop" should ever appear as a node id in generated source.
        assertFalse(source.contains("@stop"), "there is no terminal node to visit");
        assertTrue(source.contains("if (!ActivityRegistry.SMELT.active()) return null;"), source);
        assertTrue(source.contains("default: return null;   // nothing wired — the run ends here"), source);
    }

    @Test
    void goingHomeIsPerActivityAndOnlyWhenTheActivityWillRun(@TempDir Path root) {
        ActivitiesConfig cfg = branchingFlow();
        ActivitiesConfig mixed = new ActivitiesConfig(
                List.of(cfg.activities().getFirst().withGoHome(false), cfg.activities().get(1),
                        cfg.activities().get(2), cfg.activities().get(3)),
                List.of(), cfg.flow(), List.of());

        String source = serviceFor(root).generateDriverSource(mixed);

        // Travel keeps the default tick; Mining has it off. And the call sits after the active() check: there
        // is nothing to go home for if the activity isn't going to run.
        assertTrue(source.contains(afterActiveCheck("TRAVEL", "\"Mining\"")), source);
        assertFalse(source.contains(afterActiveCheck("MINING", "\"Smelt\"")), source);
    }

    /** The generated "skip if disabled, else go home" pair, at the driver's own indentation. */
    private static String afterActiveCheck(String constant, String fallthrough) {
        String indent = " ".repeat(16);
        return indent + "if (!ActivityRegistry." + constant + ".active()) return " + fallthrough + ";\n"
                + indent + "GoHome.INSTANCE.execute();";
    }

    @Test
    void anUnreachableActivityIsNotInTheDriverAtAll(@TempDir Path root) {
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        assertFalse(source.contains("\"Idle\""), "an orphan can't be routed to, so it has no case:\n" + source);
    }

    @Test
    void aFlowWithNoActivitiesGeneratesADriverThatSimplyStops(@TempDir Path root) {
        String source = serviceFor(root).generateDriverSource(ActivitiesConfig.empty());

        assertTrue(source.contains("String node = null;"), source);
        assertTrue(source.contains("return null;"), source);
    }

    @Test
    void aDisabledActivityFollowsItsDefaultWireRatherThanStoppingTheFlow(@TempDir Path root) {
        // Turning an activity off must not sever the flow at that card — everything downstream still runs.
        String source = serviceFor(root).generateDriverSource(branchingFlow());

        assertTrue(source.contains("if (!ActivityRegistry.TRAVEL.active()) return \"Mining\";"), source);
    }

    @Test
    void aBranchingFlowGeneratesAProjectThatCompiles(@TempDir Path root) throws IOException {
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
        for (String standIn : SDK_STAND_INS) {
            sources.add(write(config.sourceRoot().resolve(standInPath(standIn)), standIn));
        }
        // The driver calls GoHome.INSTANCE.execute() for every activity with the tick on. GoHome is an Activity
        // subclass scaffolded into the project by ProjectCreator rather than generated here, so the test supplies
        // the same shape.
        sources.add(write(config.mainSourceFile().getParent().resolve("GoHome.java"),
                "package com." + config.packageName() + ";\n"
                        + "import com.botmaker.sdk.api.bot.Activity;\n"
                        + "public class GoHome extends Activity<GoHome.Outcome> {\n"
                        + "    public static final GoHome INSTANCE = new GoHome();\n"
                        + "    public enum Outcome { NEXT }\n"
                        + "    public boolean isEnabled() { return true; }\n"
                        + "    public Outcome run() { return Outcome.NEXT; }\n"
                        + "}\n"));

        assertEquals("", compile(root, sources), "the generated project must compile");
    }

    /** Compiles {@code sources}; returns the compiler's diagnostics, or "" when it succeeded. */
    private static String compile(Path root, List<Path> sources) throws IOException {
        Path classes = Files.createDirectories(root.resolve("classes"));
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc;
        try (PrintStream out = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            rc = compiler.run(null, null, out, Stream.concat(
                    Stream.of("-d", classes.toString()),
                    sources.stream().map(Path::toString)).toArray(String[]::new));
        }
        return rc == 0 ? "" : diagnostics.toString(StandardCharsets.UTF_8);
    }

    /** {@code com/botmaker/sdk/api/bot/Activity.java} from a stand-in's package and public type name. */
    private static String standInPath(String source) {
        String pkg = source.substring(source.indexOf("package ") + 8, source.indexOf(';')).replace('.', '/');
        int nameAt = source.indexOf("class ") + 6;
        String name = source.substring(nameAt).split("[ <]")[0];
        return pkg + "/" + name + ".java";
    }

    private static Path write(Path file, String source) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
