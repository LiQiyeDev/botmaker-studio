package com.botmaker.studio.project.seed;

import com.botmaker.sdk.plugin.SdkPlugin;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pass that decides, per seed, between writing a file, updating one and moving one.
 *
 * <p>It runs against a real directory and the <b>bundled</b> plugin set, so what is exercised is the whole
 * path a save takes: a plugin asked what files it wants, a ledger consulted for where each went last time,
 * and the three outcomes told apart by that ledger alone. There is no fixture plugin, because a fixture
 * plugin would be a second answer to "what does a plugin look like" and this pass is only interesting when
 * the plugin is a real one.
 *
 * <p><b>The rename cases are the reason the ledger exists.</b> Without it, an activity called {@code Mining}
 * becoming {@code Smelting} is indistinguishable from {@code Mining} being deleted and {@code Smelting}
 * created — and the safe answer to that is to leave the user's body in an orphaned file and hand them an
 * empty one. Every assertion about a moved file is really an assertion about that.
 */
class SeedSyncTest {

    @TempDir
    Path root;

    private ProjectConfig project() throws IOException {
        ProjectConfig config = ProjectConfig.forProject("mybot", root);
        Files.createDirectories(config.mainPackageDir());
        return config;
    }

    private static void store(ProjectConfig config, ActivityDefinition... activities) throws IOException {
        ActivitiesConfig.of(List.of(activities), List.of()).write(config.resourcesRoot());
    }

    private static ActivityDefinition activity(String name) {
        return ActivityDefinition.create(name, "does " + name);
    }

    private Path activityFile(ProjectConfig config, String name) {
        return config.activitiesPackageDir().resolve(name + ".java");
    }

    // ---- create -----------------------------------------------------------------------------------------

    @Test
    void anActivityWithNoFileGetsOne() throws IOException {
        ProjectConfig config = project();
        store(config, activity("Mining"));

        SeedSync.Result result = SeedSync.sync(config, null);

        assertEquals(List.of(), result.problems());
        Path written = activityFile(config, "Mining");
        assertTrue(Files.exists(written), result.problems().toString());
        String source = Files.readString(written);
        assertTrue(source.contains("public class Mining extends Activity<Mining.Outcome>"), source);
        assertTrue(source.contains("package com.mybot.activities;"), source);
    }

    @Test
    void theSeedsThatAreNotActivitiesLandToo() throws IOException {
        ProjectConfig config = project();
        store(config, activity("Mining"));

        SeedSync.sync(config, null);

        assertTrue(Files.exists(config.mainPackageDir().resolve("GoHome.java")));
        assertTrue(Files.exists(config.mainPackageDir().resolve("Popups.java")));
    }

    @Test
    void aProjectWithNoActivitiesGetsNothing() throws IOException {
        ProjectConfig config = project();
        store(config);

        SeedSync.Result result = SeedSync.sync(config, null);

        assertTrue(result.isEmpty());
        assertFalse(Files.exists(config.mainPackageDir().resolve("GoHome.java")));
    }

    @Test
    void aFileAlreadySittingThereIsNeverOverwritten() throws IOException {
        ProjectConfig config = project();
        store(config, activity("Mining"));
        Files.createDirectories(config.activitiesPackageDir());
        Files.writeString(activityFile(config, "Mining"), "package com.mybot.activities;\nclass Mining {}\n");

        SeedSync.sync(config, null);

        // Reconciled at most — never replaced. Whose file it is is not a question this pass can answer, and
        // the destructive reading of an unknown file is the one that cannot be undone. So the seed's own body
        // never lands on top of it; what the reconciler may add is the substituted enum, and only that.
        String source = Files.readString(activityFile(config, "Mining"));
        assertFalse(source.contains("extends Activity"), source);
        assertFalse(source.contains("TODO"), source);
        assertTrue(source.contains("enum Outcome"), source);
    }

    // ---- reconcile --------------------------------------------------------------------------------------

    @Test
    void aSecondPassChangesNothing() throws IOException {
        ProjectConfig config = project();
        store(config, activity("Mining"));
        SeedSync.sync(config, null);
        String first = Files.readString(activityFile(config, "Mining"));

        SeedSync.Result again = SeedSync.sync(config, null);

        assertTrue(again.isEmpty(), "a save with nothing to do must write nothing");
        assertEquals(first, Files.readString(activityFile(config, "Mining")));
    }

    @Test
    void anOutcomeAddedOnTheCanvasReachesTheUsersFile() throws IOException {
        ProjectConfig config = project();
        ActivityDefinition mining = activity("Mining");
        store(config, mining);
        SeedSync.sync(config, null);

        Path file = activityFile(config, "Mining");
        Files.writeString(file, Files.readString(file)
                .replace("// TODO: how to do this activity.", "int ore = 1;"));
        store(config, mining.withOutcomes(List.of("BAG_FULL")));

        SeedSync.Result result = SeedSync.sync(config, null);

        String source = Files.readString(file);
        assertEquals(List.of(file), result.updated());
        assertTrue(source.contains("BAG_FULL"), source);
        assertTrue(source.contains("int ore = 1;"), "the user's body survives every reconcile");
    }

    // ---- rename -----------------------------------------------------------------------------------------

    @Test
    void aRenamedActivityMovesRatherThanStartingOver() throws IOException {
        ProjectConfig config = project();
        ActivityDefinition mining = activity("Mining");
        store(config, mining);
        SeedSync.sync(config, null);

        Path before = activityFile(config, "Mining");
        Files.writeString(before, Files.readString(before)
                .replace("// TODO: how to do this activity.", "int ore = 1;"));
        store(config, mining.withName("Smelting"));

        SeedSync.Result result = SeedSync.sync(config, null);

        Path after = activityFile(config, "Smelting");
        assertEquals(List.of(after), result.renamed());
        assertFalse(Files.exists(before), "the old file is moved, not left behind as an orphan");
        String source = Files.readString(after);
        assertTrue(source.contains("public class Smelting extends Activity<Smelting.Outcome>"), source);
        assertTrue(source.contains("int ore = 1;"), "the body is why a rename is not a delete plus a create");
    }

    @Test
    void everyOtherFileThatNamedItIsRepointed() throws IOException {
        ProjectConfig config = project();
        ActivityDefinition mining = activity("Mining");
        store(config, mining);
        SeedSync.sync(config, null);

        Path caller = config.mainPackageDir().resolve("Helper.java");
        Files.writeString(caller, """
                package com.mybot;

                import com.mybot.activities.Mining;

                public class Helper {
                    Mining mining = new Mining();
                }
                """);
        store(config, mining.withName("Smelting"));

        SeedSync.sync(config, null);

        String source = Files.readString(caller);
        assertFalse(source.contains("Mining"), source);
        assertTrue(source.contains("import com.mybot.activities.Smelting;"), source);
        assertTrue(source.contains("Smelting mining = new Smelting();"), source);
    }

    // ---- the ledger -------------------------------------------------------------------------------------

    @Test
    void theLedgerRecordsWhereEachSeedWent() throws IOException {
        ProjectConfig config = project();
        store(config, activity("Mining"));

        SeedSync.sync(config, null);

        SeedLedger ledger = SeedLedger.read(config.projectPath());
        assertFalse(ledger.isEmpty());
        assertEquals("src/main/java/com/mybot/activities/Mining.java",
                ledger.pathFor(SdkPlugin.ID, "sdk:activity:" + storedId(config, "Mining")));
    }

    @Test
    void aLostLedgerCostsNothingBecauseNothingIsOverwritten() throws IOException {
        ProjectConfig config = project();
        store(config, activity("Mining"));
        SeedSync.sync(config, null);

        Path file = activityFile(config, "Mining");
        Files.writeString(file, Files.readString(file)
                .replace("// TODO: how to do this activity.", "int ore = 1;"));
        Files.delete(config.projectPath().resolve(SeedLedger.PATH));

        SeedSync.sync(config, null);

        assertTrue(Files.readString(file).contains("int ore = 1;"),
                "with no ledger every seed looks new, and a new seed never overwrites a file that exists");
    }

    /** The id the stored model minted, which is what the seeding's key is built from. */
    private static String storedId(ProjectConfig config, String name) {
        return ActivitiesConfig.read(config.resourcesRoot()).activities().stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow().id();
    }
}
