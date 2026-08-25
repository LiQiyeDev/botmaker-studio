package com.botmaker.studio.project.migration;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * The {@code activities.json} 1 → 2 step: the values leave {@code Activities} for {@code Parameters}.
 *
 * <p>This is the first migration in this repository whose <em>reason</em> is a scaffold shape change rather
 * than a model change, so what it has to prove is the whole round trip: the two generated classes end up
 * holding the right halves, the bot's own source is repointed to match, and the flags — which did not move —
 * are left alone. The last of those is the one a careless implementation gets wrong, since a flag and a value
 * are the same kind of field and differ only in which list of the model they came from.
 */
class ParametersSplitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A project at {@code activities.json} version 1: one activity, one value, and a hand-written file that
     * reads both through {@code Activities}.
     */
    private static ProjectConfig atVersionOne(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Files.createDirectories(config.mainPackageDir());
        Files.createDirectories(config.resourcesRoot());

        ActivitiesConfig model = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", "")),
                List.of(ActivityVariable.create("rest", BotType.Choice.of(BotType.DURATION))));
        model.write(config.resourcesRoot());
        stampAt(config, 1);

        // The file the split has to leave behind, in the shape it had before the split: one class, both kinds.
        Files.writeString(config.activitiesSourceFile(), """
                package com.mybot;

                public final class Activities {
                    public static final boolean Mining = true;
                    public static final java.time.Duration rest = java.time.Duration.ZERO;
                }
                """);
        return config;
    }

    /** Rewrites the stamp {@link ActivitiesConfig#write} just made, so the step is still owed. */
    private static void stampAt(ProjectConfig config, int version) throws IOException {
        Path file = SchemaFile.ACTIVITIES.in(config.resourcesRoot());
        ObjectNode json = (ObjectNode) MAPPER.readTree(file.toFile());
        json.put(SchemaFile.JSON_FIELD, version);
        Files.writeString(file, MAPPER.writeValueAsString(json));
    }

    @Test
    void theValuesMoveAndTheFlagsStay(@TempDir Path root) throws IOException {
        ProjectConfig config = atVersionOne(root);

        ProjectSchema.migrate(config, ignored -> { });

        String activities = Files.readString(config.activitiesSourceFile());
        String parameters = Files.readString(config.parametersSourceFile());

        assertTrue(activities.contains("boolean Mining;"), activities);
        assertFalse(activities.contains("rest"), "a value has no business in Activities any more:\n" + activities);
        assertTrue(parameters.contains("rest = Wire.duration("), parameters);
        assertFalse(parameters.contains("Mining"), "a flag has no business in Parameters:\n" + parameters);
    }

    @Test
    void theBotsOwnSourceIsRepointed(@TempDir Path root) throws IOException {
        ProjectConfig config = atVersionOne(root);
        Path goHome = config.mainPackageDir().resolve("GoHome.java");
        Files.writeString(goHome, """
                package com.mybot;

                public final class GoHome {
                    public void run() {
                        if (Activities.Mining) {
                            System.out.println(Activities.rest);
                        }
                    }
                }
                """);

        ProjectSchema.migrate(config, ignored -> { });

        String source = Files.readString(goHome);
        assertTrue(source.contains("Parameters.rest"), source);
        assertTrue(source.contains("Activities.Mining"),
                "the enable flag did not move, so its qualifier must not have either:\n" + source);
        assertFalse(source.contains("NeedsReview"),
                "every field kept its name and its type, so there is nothing for the user to look at");
    }

    @Test
    void aSecondOpenDoesNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = atVersionOne(root);

        List<String> first = ProjectSchema.migrate(config, ignored -> { });
        String parameters = Files.readString(config.parametersSourceFile());

        List<String> second = ProjectSchema.migrate(config, ignored -> { });

        assertEquals(1, first.size(), first.toString());
        assertTrue(second.isEmpty(), "the version was stamped, so the step is not owed again");
        assertEquals(parameters, Files.readString(config.parametersSourceFile()));
    }

    @Test
    void aProjectThatNeverGeneratedActivitiesIsLeftAlone(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Files.createDirectories(config.mainPackageDir());
        Files.createDirectories(config.resourcesRoot());
        ActivitiesConfig.empty().write(config.resourcesRoot());
        stampAt(config, 1);

        assertTrue(ProjectSchema.migrate(config, ignored -> { }).isEmpty());
        assertFalse(Files.exists(config.parametersSourceFile()),
                "nothing is generated into a project that has never had a generated holder class");
    }
}
