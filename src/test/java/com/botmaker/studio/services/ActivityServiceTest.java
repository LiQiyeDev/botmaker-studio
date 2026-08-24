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
import com.botmaker.studio.project.scaffold.ScaffoldUnsupported;
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
                VariableWire.javaType(BotType.Choice.listOf(BotType.WHOLE_NUMBER)),
                "a list of a primitive is a list of its box");

        // Wire.<type>(Wire.one(name)), never a helper generated into the bot: the parsers are compiled SDK
        // methods, and the list form maps the very method reference the single-valued form would have called.
        assertEquals("Wire.whole(Wire.one(\"n\"))",
                VariableWire.loadExpression(BotType.Choice.of(BotType.WHOLE_NUMBER), "n"));
        assertEquals("Wire.flag(Wire.one(\"n\"))",
                VariableWire.loadExpression(BotType.Choice.of(BotType.YES_NO), "n"));
        assertEquals("Wire.many(\"n\", Wire::whole)",
                VariableWire.loadExpression(BotType.Choice.listOf(BotType.WHOLE_NUMBER), "n"));

        assertTrue(VariableWire.resolvedType(BotType.Choice.of(BotType.WHOLE_NUMBER)).isNumeric());
        assertTrue(VariableWire.resolvedType(BotType.Choice.of(BotType.YES_NO)).isBoolean());
        for (BotType type : BotType.storableTypes()) {
            assertNotNull(VariableWire.defaultWire(BotType.Choice.of(type)), type.toString());
            // Every storable type names a Wire method — and the SDK really has it, checked below rather
            // than taken on trust, since the name is now a string crossing a module boundary.
            assertTrue(hasWireMethod(VariableWire.wireMethod(type)), type + " → Wire." + type);
        }
    }

    /** Whether the SDK's {@code Wire} declares a one-argument reader of that name. */
    private static boolean hasWireMethod(String name) {
        for (var m : com.botmaker.sdk.api.config.Wire.class.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) return true;
        }
        return false;
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
    void generatedSourceDeclaresAndLoadsFields(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(), List.of(
                variable("maxRetries", BotType.WHOLE_NUMBER),
                variable("startTime", BotType.TIME_OF_DAY)));
        String src = service.generateSource(cfg);

        assertTrue(src.contains("package com.mybot;"), src);
        assertTrue(src.contains("public static final int maxRetries;"), src);
        assertTrue(src.contains("public static final java.time.LocalTime startTime;"), src);
        assertTrue(src.contains("maxRetries = Wire.whole(Wire.one(\"maxRetries\"));"), src);
        assertTrue(src.contains("startTime = Wire.time(Wire.one(\"startTime\"));"), src);
        // The parsers and the loader are the SDK's now. A generated file that still declared one would be
        // carrying an implementation nobody could test — which is exactly what this used to assert.
        assertFalse(src.contains("private static java.time.LocalTime time(String s)"), src);
        assertFalse(src.contains("getResourceAsStream"), src);
        assertTrue(src.contains("import com.botmaker.sdk.api.config.Wire;"), src);
    }

    @Test
    void generatedRegistryListsActivitySubclasses(@TempDir Path dir) throws Exception {
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
    void generatedRegistryFollowsTheFlowChainAndDropsOrphans(@TempDir Path dir) throws Exception {
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

    /**
     * The removal counterpart of {@code ensureStubs}. A dropped activity stops generating its
     * {@code Activities.<Name>} field, so a stub left behind reads a field that no longer exists — a project
     * that does not compile, in a file the user never opened.
     */
    @Test
    void deletingAnActivityTakesItsStubWithIt(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));
        Path idleStub = config.activitiesPackageDir().resolve("Idle.java");
        Path helper = config.activitiesPackageDir().resolve("MiningHelper.java");

        service.update(ActivitiesConfig.of(List.of(
                ActivityDefinition.create("Idle", ""), ActivityDefinition.create("Resources", "")),
                List.of())).join();
        assertTrue(java.nio.file.Files.exists(idleStub));
        java.nio.file.Files.writeString(helper, "// a class the user parked in the activities package");

        service.update(ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Resources", "")), List.of())).join();

        assertFalse(java.nio.file.Files.exists(idleStub), "the deleted activity's stub goes with it");
        assertTrue(java.nio.file.Files.exists(
                config.activitiesPackageDir().resolve("Resources.java")), "and nothing else does");
        assertTrue(java.nio.file.Files.exists(helper),
                "a file that was never an activity is not this method's business");
    }

    /** An {@code activities.json} written when archiving existed loads with every activity live. */
    @Test
    void anArchivedFlagInAnOlderFileIsIgnored(@TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "activities": [
                    { "name": "Resources", "enabled": true, "description": "", "archived": false },
                    { "name": "Idle", "enabled": false, "description": "", "archived": true } ] }
                """);

        ActivitiesConfig legacy = ActivitiesConfig.read(dir);
        assertEquals(List.of("Resources", "Idle"),
                legacy.activities().stream().map(ActivityDefinition::name).toList());
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
    void theActivitiesClassIsWrittenEvenWhenItHoldsNothing(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        // A project with no activities and no variables generates no fields at all. The class used to be
        // deleted at that point, which compile-errors anything still saying `import com.mybot.Activities;`.
        service.update(ActivitiesConfig.empty()).join();

        assertTrue(java.nio.file.Files.exists(config.activitiesSourceFile()),
                "an empty Activities class costs nothing and cannot break a build");
        assertTrue(java.nio.file.Files.readString(config.activitiesSourceFile())
                .contains("public final class Activities"));
    }

    @Test
    void emptyRegistryHasNoActivitiesImport(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));
        String reg = service.generateRegistrySource(ActivitiesConfig.empty());
        assertTrue(reg.contains("List.of("), reg);
        assertTrue(!reg.contains("import com.mybot.activities.*;"), reg);
    }

    /**
     * A bot pinned below {@link MavenService#MIN_SDK_VERSION} is refused a generated file, by name — the floor
     * reaching the one place that matters, which is a real project on disk rather than a version string.
     *
     * <p>Its jar has neither the templates every generated file is rendered from nor the {@code FlowGraph} /
     * {@code Wire} API those templates call, and Studio keeps one generation path on purpose. So the honest
     * outcome is this sentence; the dishonest one would be a bot that does not compile, written silently.
     * Everything else about the project stays open and editable — see {@code EditorCanvas.sdkFloorBanner}.
     */
    @Test
    void aProjectPinnedBelowTheFloorIsRefusedAGeneratedFile(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        java.nio.file.Files.createDirectories(config.projectPath());
        MavenService.writePom(config.projectPath(), config, "1.0.26");
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ScaffoldUnsupported refusal = assertThrows(ScaffoldUnsupported.class,
                () -> service.generateSource(ActivitiesConfig.empty()));

        assertTrue(refusal.getMessage().contains("1.0.26"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(MavenService.MIN_SDK_VERSION), refusal.getMessage());
    }
}
