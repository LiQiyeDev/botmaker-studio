package com.botmaker.studio.services;

import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.events.EventBus;
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
import com.botmaker.studio.project.activity.ValueWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the variable type mapping, JSON round-trip, and generated-class source. */
public class ActivityServiceTest {

    /** Keyed by the persisted id, which is what a type <em>is</em> since the vocabulary opened. */
    private static ActivityVariable variable(String name, String typeId) {
        return ActivityVariable.create(name, ValueWire.one(typeId));
    }

    @Test
    void variableTypeMappings() {
        assertEquals("boolean", ValueWire.javaType(ValueWire.one("YES_NO")));
        assertEquals("int", ValueWire.javaType(ValueWire.one("WHOLE_NUMBER")));
        assertEquals("java.time.LocalTime", ValueWire.javaType(ValueWire.one("TIME_OF_DAY")));
        assertEquals("java.util.List<Integer>",
                ValueWire.javaType(ValueChoice.listOf(ValueWire.type("WHOLE_NUMBER"))),
                "a list of a primitive is a list of its box");

        // A generated field's *initialiser* used to be asserted here — `Wire.whole(Wire.one("n"))`, and one
        // reflective check per storable type that the SDK really declared the reader being named. Both are
        // gone with `Wire`: a value is now baked in as a literal by the SDK's own generator, so what that
        // line says is `ScaffoldEmitTest`'s to assert, against a file it compiles.

        assertTrue(ValueWire.resolvedType(ValueWire.one("WHOLE_NUMBER")).isNumeric());
        assertTrue(ValueWire.resolvedType(ValueWire.one("YES_NO")).isBoolean());
        for (ValueType type : ValueWire.registered()) {
            assertNotNull(ValueWire.defaultWire(ValueChoice.of(type)), type.toString());
        }
    }

    @Test
    void configRoundTrip(@TempDir Path dir) throws Exception {
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(), List.of(
                variable("maxRetries", "WHOLE_NUMBER"),
                variable("startTime", "TIME_OF_DAY")));
        cfg.write(dir);

        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(2, read.variables().size());
        ActivityVariable first = read.variables().get(0);
        assertEquals("maxRetries", first.name());
        assertEquals(ValueWire.one("WHOLE_NUMBER"), first.type());
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
                variable("maxRuns", "WHOLE_NUMBER").withTag("Resources"),
                variable("serverRegion", "TEXT")));
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

    /**
     * The flow's reachability rule, which used to be asserted through the generated registry it fed.
     *
     * <p>Definition order is Resources, Alchemy, Idle — the canvas wires Alchemy → Resources and leaves Idle
     * unwired, so the run order is Alchemy then Resources and Idle does not run at all. The three tests that
     * read this off {@code generateRegistrySource}, {@code generateParametersSource} and
     * {@code generateStubSource} went on 2026-08-25 with those generators; the model's own answer is the half
     * that was ever Studio's, and it is the half that survives the inversion.
     */
    @Test
    void theFlowChainDecidesWhatRunsAndDropsOrphans() {
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
                        List.of(variable("speed", "WHOLE_NUMBER").withTag("Mining")))
                .withFlow(new ActivityFlow(List.of(new FlowNode("Mining", 10, 20)), List.of()))
                .withPresets(List.of(new ActivityPreset("Quick", List.of("Mining"))))
                .withGoHomeByDefault(false);
        service.update(full).join();

        // The file-tree path: add an activity, touching nothing else.
        service.update(service.current().withActivities(
                List.of(ActivityDefinition.create("Mining", ""), ActivityDefinition.create("Smelt", "")))).join();
        // The Parameters dialog path: replace the variables, touching nothing else.
        service.update(service.current().withVariables(
                List.of(variable("speed", "WHOLE_NUMBER"), variable("ore", "TEXT")))).join();

        ActivitiesConfig read = ActivitiesConfig.read(config.resourcesRoot());
        assertEquals(List.of("Mining", "Smelt"),
                read.activities().stream().map(ActivityDefinition::name).toList());
        assertEquals(List.of("speed", "ore"), read.variables().stream().map(ActivityVariable::name).toList());
        assertEquals(10, read.flow().node("Mining").orElseThrow().x(), "the flow is nobody else's to drop");
        assertEquals(List.of("Quick"), read.presets().stream().map(ActivityPreset::name).toList());
        assertFalse(read.goHomeByDefault());
    }

    /**
     * A deleted activity's file stays, and that is a deliberate reversal of what this class used to assert.
     *
     * <p>{@code deleteRemovedStubs} removed it, and had to: a dropped activity stopped generating its
     * {@code Activities.<Name>} field, so a file left behind read a field that no longer existed — a project
     * that did not compile, in a file the user never opened. Nothing is generated now; a seed asks the
     * project's own configuration at run time ({@code Wire.enabled(name())}), so the file goes on compiling
     * whether or not anything still names it.
     *
     * <p>With the reason gone, deleting is simply destroying the user's code because they unchecked something
     * on a canvas — and an activity removed by accident is then unrecoverable outside Project History. The
     * file is theirs from the moment it is written; all that goes is the ledger's claim that BotMaker put it
     * there.
     */
    @Test
    void deletingAnActivityLeavesItsFileAlone(@TempDir Path dir) throws Exception {
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

        assertTrue(java.nio.file.Files.exists(idleStub), "the file is the user's, deleted activity or not");
        assertTrue(java.nio.file.Files.exists(
                config.activitiesPackageDir().resolve("Resources.java")), "and the live one is still there");
        assertTrue(java.nio.file.Files.exists(helper),
                "a file that was never an activity was never this pass's business either");
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

    /**
     * A save writes {@code activities.json} <em>and</em> the Java that describes it (2026-08-26).
     *
     * <p>This test has now said three different things, and the middle one is worth remembering. It asserted
     * generation, then — for one day, deliberately — that there was <b>none</b>, because the templates had
     * left the SDK (phase 0b) and Studio's own emitters were gone with them. Phase 4 reconnects the path, so
     * it asserts generation again, from the other side of the inversion: the files appear because the
     * project's own SDK produced them from the JSON this save had just written.
     *
     * <p>What is asserted is the files' <em>appearance</em>, never a byte of what they say. The emitted text
     * belongs to the generator and is tested where the generator lives, in the SDK's {@code ScaffoldEmitTest}.
     */
    @Test
    void aSaveWritesTheModelAndTheJavaThatDescribesIt(@TempDir Path dir) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        service.update(ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", "")), List.of())).join();

        assertTrue(java.nio.file.Files.exists(config.resourcesRoot().resolve(ActivitiesConfig.FILE_NAME)),
                "the model is still saved");
        // Activities.java and FlowDriver.java were asserted here too, until both stopped being written: an
        // activity's tick and the drawn flow are read from the JSON above at run time. A save's whole output
        // is now the model and any stub that did not exist yet.
        assertTrue(java.nio.file.Files.exists(config.activitiesPackageDir().resolve("Mining.java")),
                "and the new activity's stub with them");
    }
}
