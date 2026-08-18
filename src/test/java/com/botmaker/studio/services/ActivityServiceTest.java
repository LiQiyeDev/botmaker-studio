package com.botmaker.studio.services;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectRepair;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.ActivityPreset;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.project.activity.FlowNode;
import com.botmaker.studio.project.activity.VariableWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the variable type mapping, JSON round-trip, and generated-class source. */
public class ActivityServiceTest {

    private static ActivityVariable variable(String name, BotType type) {
        return ActivityVariable.create(name, BotType.Choice.of(type));
    }

    @Test
    void variableTypeMappings() {
        assertEquals("boolean", VariableWire.javaType(BotType.Choice.of(BotType.YES_NO)));
        assertEquals("int", VariableWire.javaType(BotType.Choice.of(BotType.WHOLE_NUMBER)));
        assertEquals("java.time.LocalTime", VariableWire.javaType(BotType.Choice.of(BotType.TIME_OF_DAY)));
        assertEquals("java.util.List<Integer>",
                VariableWire.javaType(new BotType.Choice(BotType.WHOLE_NUMBER, true)),
                "a list of a primitive is a list of its box");

        assertEquals("whole(one(\"n\"))", VariableWire.loadExpression(BotType.Choice.of(BotType.WHOLE_NUMBER), "n"));
        assertEquals("flag(one(\"n\"))", VariableWire.loadExpression(BotType.Choice.of(BotType.YES_NO), "n"));
        assertEquals("many(\"n\", Activities::whole)",
                VariableWire.loadExpression(new BotType.Choice(BotType.WHOLE_NUMBER, true), "n"));

        assertTrue(VariableWire.resolvedType(BotType.Choice.of(BotType.WHOLE_NUMBER)).isNumeric());
        assertTrue(VariableWire.resolvedType(BotType.Choice.of(BotType.YES_NO)).isBoolean());
        for (BotType type : BotType.storableTypes()) {
            assertNotNull(VariableWire.defaultWire(BotType.Choice.of(type)), type.toString());
            assertNotNull(VariableWire.helper(type), type.toString());
        }
    }

    @Test
    void configRoundTrip(@TempDir Path dir) throws Exception {
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(), List.of(
                variable("maxRetries", BotType.WHOLE_NUMBER),
                variable("startTime", BotType.TIME_OF_DAY)));
        cfg.write(dir);

        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(2, read.variables().size());
        ActivityVariable first = read.variables().get(0);
        assertEquals("maxRetries", first.name());
        assertEquals(BotType.Choice.of(BotType.WHOLE_NUMBER), first.type());
        assertEquals("0", first.singleValue());
        assertEquals("00:00", read.variables().get(1).singleValue());
    }

    /**
     * One flat namespace: an activity contributes its enable flag and nothing else, and a variable tagged
     * after it is a project variable that happens to be filed there — the generated field name is its own
     * name, with no {@code <Activity>_} prefix anywhere.
     */
    @Test
    void oneFlatNamespaceOfEnableFlagsThenVariables(@TempDir Path dir) throws Exception {
        ActivityDefinition resources = ActivityDefinition.create("Resources", "collect stuff")
                .withEnabled(true);
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(resources), List.of(
                variable("maxRuns", BotType.WHOLE_NUMBER).withTag("Resources"),
                variable("serverRegion", BotType.TEXT)));
        cfg.write(dir);

        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(1, read.activities().size());
        assertTrue(read.activities().get(0).enabled());
        assertEquals("Resources", read.variables().get(0).tag());

        List<String> names = read.allVariables().stream().map(ActivityVariable::name).toList();
        assertEquals(List.of("Resources", "maxRuns", "serverRegion"), names);
    }

    @Test
    void readsEmptyWhenMissing(@TempDir Path dir) {
        assertTrue(ActivitiesConfig.read(dir).isEmpty());
    }

    @Test
    void generatedSourceDeclaresAndLoadsFields(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(), List.of(
                variable("maxRetries", BotType.WHOLE_NUMBER),
                variable("startTime", BotType.TIME_OF_DAY)));
        String src = service.generateSource(cfg);

        assertTrue(src.contains("package com.mybot;"), src);
        assertTrue(src.contains("public static final int maxRetries;"), src);
        assertTrue(src.contains("public static final java.time.LocalTime startTime;"), src);
        assertTrue(src.contains("maxRetries = whole(one(\"maxRetries\"));"), src);
        assertTrue(src.contains("startTime = time(one(\"startTime\"));"), src);
        assertTrue(src.contains("private static java.time.LocalTime time(String s)"), src);
        assertTrue(src.contains("getResourceAsStream(\"/activities.json\")"), src);
    }

    @Test
    void generatedRegistryListsActivitySubclasses(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(
                ActivityDefinition.create("Resources", ""),
                ActivityDefinition.create("Alchemy", "")), List.of());
        String reg = service.generateRegistrySource(cfg);

        assertTrue(reg.contains("import com.mybot.activities.*;"), reg);
        assertTrue(reg.contains("new Resources()"), reg);
        assertTrue(reg.contains("new Alchemy()"), reg);

        String stub = service.generateStubSource(ActivityDefinition.create("Resources", ""));
        assertTrue(stub.contains("public class Resources extends Activity"), stub);
        assertTrue(stub.contains("return Activities.Resources;"), stub);

        // No constructor: Activity's no-arg ctor names the activity after its class, so the stub asks the user
        // for nothing but run(). `new Resources()` in the registry above binds that inherited constructor.
        assertFalse(stub.contains("public Resources()"), stub);
        assertFalse(stub.contains("super("), stub);
    }

    @Test
    void generatedRegistryFollowsTheFlowChainAndDropsOrphans(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        // Definition order is Resources, Alchemy, Idle — the canvas wires Alchemy → Resources and leaves
        // Idle unwired, so the registry must read Alchemy, Resources and Idle must not run at all.
        ActivityFlow flow = new ActivityFlow(
                List.of(new FlowNode("Alchemy", 0, 0), new FlowNode("Resources", 200, 0),
                        new FlowNode("Idle", 0, 200)),
                List.of(new FlowEdge("Alchemy", "Resources")));
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(
                ActivityDefinition.create("Resources", ""),
                ActivityDefinition.create("Alchemy", ""),
                ActivityDefinition.create("Idle", "")), List.of()).withFlow(flow);

        assertEquals(List.of("Alchemy", "Resources"),
                cfg.orderedActivities().stream().map(ActivityDefinition::name).toList());

        String reg = service.generateRegistrySource(cfg);
        assertTrue(reg.indexOf("new Alchemy()") < reg.indexOf("new Resources()"), reg);
        assertFalse(reg.contains("new Idle()"), reg);
    }

    @Test
    void anArchivedActivityStopsBeingGeneratedAtAll(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivityDefinition retired = ActivityDefinition.create("Idle", "")
                .withEnabled(true).withArchived(true);
        ActivitiesConfig cfg = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Resources", ""), retired), List.of());

        assertEquals(List.of("Resources"),
                cfg.orderedActivities().stream().map(ActivityDefinition::name).toList());
        assertFalse(service.generateRegistrySource(cfg).contains("new Idle()"));

        // ...and neither its enable flag nor its params are generated any more. They were settings for
        // something that cannot run, offered by the expression menu to code that must not call it. The stub
        // that referred to them is moved out of the source tree in the same save — see the lifecycle test.
        List<String> fields = cfg.allVariables().stream().map(ActivityVariable::name).toList();
        assertEquals(List.of("Resources"), fields);
        assertFalse(service.generateSource(cfg).contains("Idle"), "no field for an archived activity");
    }

    @Test
    void archivingMovesTheUsersFileAsideAndRestoringBringsItBackUnchanged(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));
        Path live = config.activitiesPackageDir().resolve("Idle.java");
        Path aside = config.archivedActivitiesDir().resolve("Idle.java");

        ActivityDefinition idle = ActivityDefinition.create("Idle", "");
        service.update(ActivitiesConfig.of(List.of(idle), List.of())).join();
        assertTrue(java.nio.file.Files.exists(live), "a live activity gets its stub");

        // The bit worth protecting: the body is the user's, and it has to survive the round trip verbatim.
        String written = java.nio.file.Files.readString(live)
                .replace("// TODO: how to do Idle", "System.out.println(\"mine\");");
        java.nio.file.Files.writeString(live, written);

        service.update(ActivitiesConfig.of(List.of(idle.withArchived(true)), List.of())).join();
        assertFalse(java.nio.file.Files.exists(live), "archiving takes the stub out of the source tree");
        assertTrue(java.nio.file.Files.exists(aside), "and puts it aside rather than deleting it");

        service.update(ActivitiesConfig.of(List.of(idle), List.of())).join();
        assertFalse(java.nio.file.Files.exists(aside));
        assertEquals(written, java.nio.file.Files.readString(live),
                "restoring returns the activity as it was written, not as a fresh stub");
    }

    /**
     * The reported defect: the stub came back on the next save, while the flow still called the activity
     * archived. Archiving is not one event, it is a state — every save after it has to leave the file in the
     * attic, and nothing may ask for it back.
     */
    @Test
    void anArchivedStubStaysInTheAtticAcrossEveryLaterSave(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));
        Path live = config.activitiesPackageDir().resolve("Idle.java");
        Path aside = config.archivedActivitiesDir().resolve("Idle.java");

        ActivityDefinition idle = ActivityDefinition.create("Idle", "");
        service.update(ActivitiesConfig.of(List.of(idle), List.of())).join();
        ActivitiesConfig archived = ActivitiesConfig.of(List.of(idle.withArchived(true)), List.of());
        service.update(archived).join();

        // Three more saves — a variable edit, a flow edit, a no-op — none of which mention the activity.
        service.update(archived.withVariables(List.of(variable("speed", BotType.WHOLE_NUMBER)))).join();
        service.update(service.current().withFlow(new ActivityFlow(List.of(), List.of()))).join();
        service.update(service.current()).join();

        assertFalse(java.nio.file.Files.exists(live), "the stub must not come back on a later save");
        assertTrue(java.nio.file.Files.exists(aside));
        assertTrue(service.current().archivedActivities().stream()
                .anyMatch(a -> a.name().equals("Idle")), "and the config still calls it archived");

        // Nor may the repair pass offer to write it: that is the other way it used to reappear.
        assertTrue(ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, service.current()).stream()
                .noneMatch(m -> m.fileName().equals("Idle.java")));
    }

    /**
     * Every save is a {@code with…} on the current config, so no dialog can drop the half of the project it
     * does not edit. This is the defect that produced an {@code activities.json} with no variables in it: a
     * save built from two fields silently wrote everything else away.
     */
    @Test
    void savingOnePartOfTheConfigKeepsAllTheOthers(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivitiesConfig full = ActivitiesConfig.of(
                        List.of(ActivityDefinition.create("Mining", "")),
                        List.of(variable("speed", BotType.WHOLE_NUMBER).withTag("Mining")))
                .withFlow(new ActivityFlow(List.of(new FlowNode("Mining", 10, 20)), List.of()))
                .withPresets(List.of(new ActivityPreset("Quick", List.of("Mining"))))
                .withGoHomeByDefault(false);
        service.update(full).join();

        // The file-tree path: add an activity, touching nothing else.
        service.update(service.current().withActivities(
                List.of(ActivityDefinition.create("Mining", ""), ActivityDefinition.create("Smelt", "")))).join();
        // The Parameters dialog path: replace the variables, touching nothing else.
        service.update(service.current().withVariables(
                List.of(variable("speed", BotType.WHOLE_NUMBER), variable("ore", BotType.TEXT)))).join();

        ActivitiesConfig read = ActivitiesConfig.read(config.resourcesRoot());
        assertEquals(List.of("Mining", "Smelt"),
                read.activities().stream().map(ActivityDefinition::name).toList());
        assertEquals(List.of("speed", "ore"), read.variables().stream().map(ActivityVariable::name).toList());
        assertEquals(10, read.flow().node("Mining").orElseThrow().x(), "the flow is nobody else's to drop");
        assertEquals(List.of("Quick"), read.presets().stream().map(ActivityPreset::name).toList());
        assertFalse(read.goHomeByDefault());
    }

    @Test
    void archivedStateSurvivesTheJsonRoundTripAndOlderFilesLoadUnarchived(@TempDir Path dir) throws Exception {
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(
                ActivityDefinition.create("Resources", ""),
                new ActivityDefinition("Idle", false, "", true, List.of(), true, true)), List.of());
        cfg.write(dir);

        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(List.of("Resources"), read.liveActivities().stream()
                .map(ActivityDefinition::name).toList());
        assertEquals(List.of("Idle"), read.archivedActivities().stream()
                .map(ActivityDefinition::name).toList());

        // An activities.json written before the field existed must load with everything live.
        java.nio.file.Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "activities": [ { "name": "Resources", "enabled": true, "description": "", "params": [] } ] }
                """);
        ActivitiesConfig legacy = ActivitiesConfig.read(dir);
        assertEquals(1, legacy.liveActivities().size());
        assertTrue(legacy.archivedActivities().isEmpty());
    }

    @Test
    void anUnwiredFlowKeepsPlainDefinitionOrder() {
        // Legacy / not-yet-wired projects must keep running every activity in list order.
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(
                ActivityDefinition.create("Resources", ""),
                ActivityDefinition.create("Alchemy", "")), List.of());
        assertEquals(List.of("Resources", "Alchemy"),
                cfg.orderedActivities().stream().map(ActivityDefinition::name).toList());
    }

    @Test
    void applyingAPresetFlipsEnableFlagsWithoutTouchingTheFlow() {
        ActivityFlow flow = new ActivityFlow(List.of(new FlowNode("Resources", 0, 0)),
                List.of(new FlowEdge("Resources", "Alchemy")));
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(
                ActivityDefinition.create("Resources", ""),
                ActivityDefinition.create("Alchemy", "")), List.of()).withFlow(flow);

        ActivitiesConfig only = cfg.applyPreset(new ActivityPreset("Alchemy only", List.of("Alchemy")));
        assertFalse(only.activities().get(0).enabled());
        assertTrue(only.activities().get(1).enabled());
        assertEquals(flow, only.flow(), "a preset says which activities run, never in what order");

        assertTrue(cfg.applyPreset(ActivityPreset.nothing()).activities().stream()
                .noneMatch(ActivityDefinition::enabled));
    }

    @Test
    void configRoundTripCarriesFlowAndPresets(@TempDir Path dir) throws Exception {
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(ActivityDefinition.create("Resources", "")),
                        List.of())
                .withFlow(new ActivityFlow(List.of(new FlowNode("Resources", 12.5, 34)), List.of()))
                .withPresets(List.of(new ActivityPreset("Quick", List.of("Resources"))));
        cfg.write(dir);

        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(12.5, read.flow().node("Resources").orElseThrow().x());
        assertEquals(List.of("Resources"), read.presets().get(0).enabledActivities());
    }

    @Test
    void anActivitiesFileWithoutFlowOrPresetsStillLoads(@TempDir Path dir) throws Exception {
        // Back-compat: files written before the Activity Flow canvas existed have neither field.
        java.nio.file.Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "activities": [ { "name": "Resources", "enabled": true, "description": "", "params": [] } ] }
                """);
        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(1, read.activities().size());
        assertTrue(read.flow().isEmpty());
        assertTrue(read.presets().isEmpty());
    }

    @Test
    void archivingTheLastActivityLeavesNothingImportingAnEmptyPackage(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        // Every activity archived: their stubs have been moved out of com.mybot.activities, so the package has
        // no source in it and an import-on-demand of it does not compile. The import used to be keyed on
        // activities() — which counts the archived ones — and archiving the last activity broke the build.
        ActivitiesConfig cfg = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Idle", "").withArchived(true)), List.of());

        assertFalse(service.generateRegistrySource(cfg).contains("import com.mybot.activities.*;"));
        assertFalse(service.generateDriverSource(cfg).contains("import com.mybot.activities.*;"));

        // One live activity is enough to make the package real again.
        ActivitiesConfig mixed = ActivitiesConfig.of(List.of(
                ActivityDefinition.create("Idle", "").withArchived(true),
                ActivityDefinition.create("Resources", "")), List.of());
        assertTrue(service.generateRegistrySource(mixed).contains("import com.mybot.activities.*;"));
    }

    @Test
    void theActivitiesClassIsWrittenEvenWhenItHoldsNothing(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        // Archiving the only activity leaves no fields to generate. The class used to be deleted at that
        // point, which compile-errors anything still saying `import com.mybot.Activities;`.
        service.update(ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Idle", "").withArchived(true)), List.of())).join();

        assertTrue(java.nio.file.Files.exists(config.activitiesSourceFile()),
                "an empty Activities class costs nothing and cannot break a build");
        assertTrue(java.nio.file.Files.readString(config.activitiesSourceFile())
                .contains("public final class Activities"));
    }

    @Test
    void aStubThatEndedUpInBothPlacesReconcilesToTheSideItIsLeaving(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));
        Path live = config.activitiesPackageDir().resolve("Idle.java");
        Path aside = config.archivedActivitiesDir().resolve("Idle.java");

        // The state the old skip-if-the-destination-exists guard used to freeze: the same activity on both
        // sides, every later archive and restore silently doing nothing.
        java.nio.file.Files.createDirectories(live.getParent());
        java.nio.file.Files.createDirectories(aside.getParent());
        java.nio.file.Files.writeString(live, "// what the user last edited");
        java.nio.file.Files.writeString(aside, "// a stale copy from an earlier attempt");

        ActivityDefinition idle = ActivityDefinition.create("Idle", "");
        service.update(ActivitiesConfig.of(List.of(idle.withArchived(true)), List.of())).join();

        assertFalse(java.nio.file.Files.exists(live), "the side it is leaving is always emptied");
        assertEquals("// what the user last edited", java.nio.file.Files.readString(aside),
                "the file being moved wins — it is the one the user last edited");

        // ...and the pair is no longer stuck: restoring moves it straight back.
        service.update(ActivitiesConfig.of(List.of(idle), List.of())).join();
        assertFalse(java.nio.file.Files.exists(aside));
        assertEquals("// what the user last edited", java.nio.file.Files.readString(live));
    }

    @Test
    void archivingIsRefusedWhileAnotherActivityReadsItsEnableFlag(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivityDefinition idle = ActivityDefinition.create("Idle", "").withEnabled(true);
        Path activities = config.activitiesPackageDir();
        java.nio.file.Files.createDirectories(activities);

        // Its own stub reads its own flag — that file is moved out of the tree, so it is never a blocker.
        java.nio.file.Files.writeString(activities.resolve("Idle.java"),
                "class Idle { boolean on = Activities.Idle; }");
        assertEquals(List.of(), service.archiveBlockers(idle));

        // A sibling reading it is: archiving would leave that file referring to a field that stops being
        // generated, and the user would meet it as a compile error in a file they never touched. A variable
        // merely tagged "Idle" is not in this set — it is project-wide, and outlives the activity.
        java.nio.file.Files.writeString(activities.resolve("Resources.java"),
                "class Resources { boolean p = Activities.Idle; }");
        assertEquals(List.of("Resources.java"), service.archiveBlockers(idle));

        // A field whose name merely starts with one of ours is not a reference to it.
        java.nio.file.Files.writeString(activities.resolve("Resources.java"),
                "class Resources { int p = Activities.IdleOther; }");
        assertEquals(List.of(), service.archiveBlockers(idle));
    }

    @Test
    void emptyRegistryHasNoActivitiesImport(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));
        String reg = service.generateRegistrySource(ActivitiesConfig.empty());
        assertTrue(reg.contains("List.of("), reg);
        assertTrue(!reg.contains("import com.mybot.activities.*;"), reg);
    }
}
