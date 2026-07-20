package com.botmaker.studio.services;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saving the flow all the way to disk: {@link ActivityService#update} and the files it leaves behind.
 *
 * <p>{@code ActivityStubSyncTest} covers the AST edits in isolation and was passing while outcomes added in the
 * dialog were reported as never reaching {@code activities/<Name>.java} — a unit test on the transform cannot
 * see a break in the wiring around it. This one drives the real service against a real directory, which is the
 * only level at which "I added an outcome and the file didn't change" is a thing a test can observe.
 */
class ActivityStubWriteThroughTest {

    private static ActivityService serviceFor(ProjectConfig config) {
        return new ActivityService(config, new ProjectState(), new EventBus());
    }

    private static ActivitiesConfig configOf(ActivityDefinition... activities) {
        return new ActivitiesConfig(List.of(activities), List.of());
    }

    /** Runs {@code update} and waits, so a failure surfaces here rather than as a silently lost future. */
    private static void save(ActivityService service, ActivitiesConfig cfg)
            throws ExecutionException, InterruptedException {
        service.update(cfg).get();
    }

    @Test
    void anOutcomeAddedInTheEditorReachesTheActivitysJavaFile(@TempDir Path root)
            throws IOException, ExecutionException, InterruptedException {
        ProjectConfig config = ProjectConfig.forProject("actbot", root);
        ActivityService service = serviceFor(config);
        Path stub = config.activitiesPackageDir().resolve("Mining.java");

        save(service, configOf(ActivityDefinition.create("Mining", "")));
        assertTrue(Files.exists(stub), "the first save creates the stub");

        save(service, configOf(ActivityDefinition.create("Mining", "").withOutcomes(List.of("BAG_FULL"))));

        String source = Files.readString(stub);
        assertTrue(source.contains("BAG_FULL"), "the second save has to reconcile the enum:\n" + source);
        assertTrue(source.contains("enum Outcome { NEXT, BAG_FULL }")
                || source.contains("enum Outcome {NEXT, BAG_FULL}"), source);
    }

    @Test
    void anOutcomeRemovedInTheEditorLeavesTheJavaFile(@TempDir Path root)
            throws IOException, ExecutionException, InterruptedException {
        ProjectConfig config = ProjectConfig.forProject("actbot", root);
        ActivityService service = serviceFor(config);
        Path stub = config.activitiesPackageDir().resolve("Mining.java");

        save(service, configOf(ActivityDefinition.create("Mining", "").withOutcomes(List.of("BAG_FULL"))));
        save(service, configOf(ActivityDefinition.create("Mining", "")));

        assertFalse(Files.readString(stub).contains("BAG_FULL"));
    }

    @Test
    void theUsersBodySurvivesEverySave(@TempDir Path root)
            throws IOException, ExecutionException, InterruptedException {
        ProjectConfig config = ProjectConfig.forProject("actbot", root);
        ActivityService service = serviceFor(config);
        Path stub = config.activitiesPackageDir().resolve("Mining.java");

        save(service, configOf(ActivityDefinition.create("Mining", "")));
        Files.writeString(stub, Files.readString(stub)
                .replace("// TODO: how to do Mining", "ImageClicker.click(ore);"));

        save(service, configOf(ActivityDefinition.create("Mining", "").withOutcomes(List.of("NO_ORE"))));

        String source = Files.readString(stub);
        assertTrue(source.contains("ImageClicker.click(ore);"), "the file is the user's:\n" + source);
        assertTrue(source.contains("NO_ORE"), source);
    }

    @Test
    void theGeneratedDriverAndRegistryAreWrittenToo(@TempDir Path root)
            throws IOException, ExecutionException, InterruptedException {
        ProjectConfig config = ProjectConfig.forProject("actbot", root);
        save(serviceFor(config), configOf(ActivityDefinition.create("Mining", "")));

        assertTrue(Files.readString(config.flowDriverSourceFile()).contains("case \"Mining\":"));
        assertEquals(1, Files.readString(config.activityRegistrySourceFile())
                .split("public static final Mining MINING", -1).length - 1);
    }
}
