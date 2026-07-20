package com.botmaker.studio.services;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.ActivityPreset;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.project.activity.FlowNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the activity type mapping, JSON round-trip, and generated-class source. */
public class ActivityServiceTest {

    @Test
    void activityTypeMappings() {
        assertEquals("boolean", ActivityType.BOOL.javaType());
        assertEquals("int", ActivityType.INT.javaType());
        assertEquals("java.time.LocalTime", ActivityType.TIME.javaType());

        assertEquals("v.asInt(0)", ActivityType.INT.loadExpression("v"));
        assertEquals("v.asBoolean(false)", ActivityType.BOOL.loadExpression("v"));
        assertEquals("parseTime(v)", ActivityType.TIME.loadExpression("v"));

        assertTrue(ActivityType.INT.resolvedType().isNumeric());
        assertTrue(ActivityType.BOOL.resolvedType().isBoolean());
        for (ActivityType t : ActivityType.values()) assertNotNull(t.defaultValue());
    }

    @Test
    void configRoundTrip(@TempDir Path dir) throws Exception {
        ActivitiesConfig cfg = new ActivitiesConfig(List.of(), List.of(
                ActivityVariable.create("maxRetries", ActivityType.INT),
                ActivityVariable.create("startTime", ActivityType.TIME)));
        cfg.write(dir);

        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(2, read.globals().size());
        ActivityVariable first = read.globals().get(0);
        assertEquals("maxRetries", first.name());
        assertEquals(ActivityType.INT, first.type());
        assertEquals(0, first.value().asInt(-1));
        assertEquals("00:00", read.globals().get(1).value().asText());
    }

    @Test
    void twoTierRoundTripAndFlattening(@TempDir Path dir) throws Exception {
        ActivityDefinition resources = new ActivityDefinition("Resources", true, "collect stuff",
                List.of(ActivityVariable.create("maxRuns", ActivityType.INT)));
        ActivitiesConfig cfg = new ActivitiesConfig(List.of(resources),
                List.of(ActivityVariable.create("serverRegion", ActivityType.TEXT)));
        cfg.write(dir);

        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(1, read.activities().size());
        assertTrue(read.activities().get(0).enabled());
        assertEquals("maxRuns", read.activities().get(0).params().get(0).name());

        // Flattened leaves: enable flag, param field, then globals — these are the generated field names.
        List<String> names = read.allVariables().stream().map(ActivityVariable::name).toList();
        assertEquals(List.of("Resources", "Resources_maxRuns", "serverRegion"), names);
    }

    @Test
    void legacyFlatFileMigratesToGlobals(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "activities": [ { "name": "maxRetries", "type": "INT", "value": 5, "description": "" } ] }
                """);
        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertTrue(read.activities().isEmpty());
        assertEquals(1, read.globals().size());
        assertEquals("maxRetries", read.globals().get(0).name());
        assertEquals(5, read.globals().get(0).value().asInt(-1));
    }

    @Test
    void readsEmptyWhenMissing(@TempDir Path dir) {
        assertTrue(ActivitiesConfig.read(dir).isEmpty());
    }

    @Test
    void generatedSourceDeclaresAndLoadsFields(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivitiesConfig cfg = new ActivitiesConfig(List.of(), List.of(
                ActivityVariable.create("maxRetries", ActivityType.INT),
                ActivityVariable.create("startTime", ActivityType.TIME)));
        String src = service.generateSource(cfg);

        assertTrue(src.contains("package com.mybot;"), src);
        assertTrue(src.contains("public static final int maxRetries;"), src);
        assertTrue(src.contains("public static final java.time.LocalTime startTime;"), src);
        assertTrue(src.contains("maxRetries = node(v, \"maxRetries\").asInt(0);"), src);
        assertTrue(src.contains("startTime = parseTime(node(v, \"startTime\"));"), src);
        assertTrue(src.contains("private static java.time.LocalTime parseTime(JsonNode n)"), src);
        assertTrue(src.contains("getResourceAsStream(\"/activities.json\")"), src);
    }

    @Test
    void generatedRegistryListsActivitySubclasses(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivitiesConfig cfg = new ActivitiesConfig(List.of(
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
        ActivitiesConfig cfg = new ActivitiesConfig(List.of(
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
    void anUnwiredFlowKeepsPlainDefinitionOrder() {
        // Legacy / not-yet-wired projects must keep running every activity in list order.
        ActivitiesConfig cfg = new ActivitiesConfig(List.of(
                ActivityDefinition.create("Resources", ""),
                ActivityDefinition.create("Alchemy", "")), List.of());
        assertEquals(List.of("Resources", "Alchemy"),
                cfg.orderedActivities().stream().map(ActivityDefinition::name).toList());
    }

    @Test
    void applyingAPresetFlipsEnableFlagsWithoutTouchingTheFlow() {
        ActivityFlow flow = new ActivityFlow(List.of(new FlowNode("Resources", 0, 0)),
                List.of(new FlowEdge("Resources", "Alchemy")));
        ActivitiesConfig cfg = new ActivitiesConfig(List.of(
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
        ActivitiesConfig cfg = new ActivitiesConfig(List.of(ActivityDefinition.create("Resources", "")),
                List.of(),
                new ActivityFlow(List.of(new FlowNode("Resources", 12.5, 34)), List.of()),
                List.of(new ActivityPreset("Quick", List.of("Resources"))));
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
    void emptyRegistryHasNoActivitiesImport(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));
        String reg = service.generateRegistrySource(ActivitiesConfig.empty());
        assertTrue(reg.contains("List.of("), reg);
        assertTrue(!reg.contains("import com.mybot.activities.*;"), reg);
    }
}
