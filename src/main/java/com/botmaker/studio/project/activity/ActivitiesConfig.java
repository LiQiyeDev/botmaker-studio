package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The activities configuration for a project, persisted as {@code activities.json} under the project's
 * {@code src/main/resources} (so it is on the runtime classpath and read by the generated
 * {@code Activities} class). Two-tier:
 * <ul>
 *   <li>{@link #activities()} — the {@link ActivityDefinition}s (each with an enable flag + its own params)</li>
 *   <li>{@link #globals()} — free-standing global config variables not tied to any activity</li>
 * </ul>
 *
 * <p>{@link #allVariables()} flattens everything into the referenceable {@code Activities.<field>} leaves
 * (enable flags, {@code <Activity>_<param>} params, then globals) — the single list the code generator and
 * the expression menu consume. Old flat {@code activities.json} files (a bare list of {@link ActivityVariable}
 * under {@code "activities"}) still load: their variables come back as {@link #globals()}.
 *
 * <p>An activity is retired by {@link ActivityDefinition#archived() archiving} it, never by deleting it: its
 * definition stays here in full, but it drops out of {@link #orderedActivities()} <em>and</em> out of
 * {@link #allVariables()}, so it stops generating code entirely. Its hand-written
 * {@code activities/<Name>.java} is moved aside rather than compiled against fields that no longer exist
 * (see {@code ProjectConfig.archivedActivitiesDir}); restoring puts both back.
 *
 * <p>{@link #flow()} is the optional visual chain (node placements + wires) built on the Activity Flow
 * canvas; when present it defines the <em>run order</em> ({@link #orderedActivities()}). {@link #presets()}
 * are named on/off selections the user can apply. Both are back-compatible additions — an
 * {@code activities.json} without them loads with an {@link ActivityFlow#empty() empty flow} and no presets.
 *
 * @param activities      the activity definitions (each an enable flag + its params)
 * @param globals         free-standing global config variables not tied to any activity
 * @param flow            the visual flow (node placements + wires); empty means "no chosen chain, use list order"
 * @param presets         named on/off selections of activities (user-saved; built-ins are offered on top)
 * @param goHomeByDefault whether a newly added activity starts with {@link ActivityDefinition#goHome()} ticked;
 *                        boxed for the same reason as that field — absent must mean {@code true}, not
 *                        {@code false}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivitiesConfig(List<ActivityDefinition> activities, List<ActivityVariable> globals,
                               ActivityFlow flow, List<ActivityPreset> presets, Boolean goHomeByDefault) {

    public static final String FILE_NAME = "activities.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ActivitiesConfig {
        activities = activities == null ? List.of() : List.copyOf(activities);
        globals = globals == null ? List.of() : List.copyOf(globals);
        flow = flow == null ? ActivityFlow.empty() : flow;
        presets = presets == null ? List.of() : List.copyOf(presets);
        if (goHomeByDefault == null) goHomeByDefault = Boolean.TRUE;
    }

    /** Convenience for callers that don't touch the go-home default; a pre-goHome file loads this way. */
    public ActivitiesConfig(List<ActivityDefinition> activities, List<ActivityVariable> globals,
                            ActivityFlow flow, List<ActivityPreset> presets) {
        this(activities, globals, flow, presets, Boolean.TRUE);
    }

    /** Convenience for callers that don't touch the flow/presets (they default to empty). */
    public ActivitiesConfig(List<ActivityDefinition> activities, List<ActivityVariable> globals) {
        this(activities, globals, ActivityFlow.empty(), List.of());
    }

    public static ActivitiesConfig empty() {
        return new ActivitiesConfig(List.of(), List.of());
    }

    /** True when there are no activities and no globals (nothing to generate). */
    public boolean isEmpty() {
        return activities.isEmpty() && globals.isEmpty();
    }

    /**
     * The activities a run can actually reach: everything reachable from the {@link #flow()}'s start node when
     * one is wired, else the plain definition order. Excluded are orphans (placed but unreachable) and
     * {@link ActivityDefinition#archived() archived} activities — neither runs. This is what the generated
     * {@code ActivityRegistry} instantiates and the generated {@code FlowDriver} can route to.
     *
     * <p>The order is breadth-first from the start and is presentational only — with branching there is no
     * single run order any more; the driver picks the next node from the outcome each activity reports.
     *
     * <p>The two exclusions differ in what they cost. An orphan is still a <em>live</em> activity: it keeps its
     * {@code Activities.<field>} flags ({@link #allVariables()} spans orphans) and its stub, because wiring it
     * up is one drag away. An archived one is gone from generation altogether — see the class javadoc.
     */
    public List<ActivityDefinition> orderedActivities() {
        List<ActivityDefinition> live = liveActivities();
        if (flow.isEmpty()) return live;
        Map<String, ActivityDefinition> byName = new LinkedHashMap<>();
        for (ActivityDefinition a : live) byName.put(a.name(), a);
        List<ActivityDefinition> ordered = new ArrayList<>();
        for (String name : flow.reachable(live.stream().map(ActivityDefinition::name).toList())) {
            ActivityDefinition a = byName.get(name);
            if (a != null) ordered.add(a);
        }
        return ordered;
    }

    /** The activities that have not been archived — what the canvas shows and the registry runs. */
    public List<ActivityDefinition> liveActivities() {
        return activities.stream().filter(a -> !a.archived()).toList();
    }

    /** The archived activities, for the editor's restore list. */
    public List<ActivityDefinition> archivedActivities() {
        return activities.stream().filter(ActivityDefinition::archived).toList();
    }

    /** A copy with each activity's enable flag set from {@code preset} (in it → on, else off). */
    public ActivitiesConfig applyPreset(ActivityPreset preset) {
        List<ActivityDefinition> updated = activities.stream()
                .map(a -> a.withEnabled(preset.enables(a.name())))
                .toList();
        return new ActivitiesConfig(updated, globals, flow, presets, goHomeByDefault);
    }

    public ActivitiesConfig withFlow(ActivityFlow newFlow) {
        return new ActivitiesConfig(activities, globals, newFlow, presets, goHomeByDefault);
    }

    public ActivitiesConfig withPresets(List<ActivityPreset> newPresets) {
        return new ActivitiesConfig(activities, globals, flow, newPresets, goHomeByDefault);
    }

    /**
     * Every referenceable {@code Activities.<field>} value, in generation order: each live activity's enable
     * flag then its params ({@code <Activity>_<param>}), followed by the globals. Names here are exactly the
     * generated field names (and what the expression menu inserts).
     *
     * <p>Archived activities contribute nothing: their fields would be settings for something that cannot run,
     * offered by the expression menu to code that must not call it.
     */
    public List<ActivityVariable> allVariables() {
        List<ActivityVariable> all = new ArrayList<>();
        for (ActivityDefinition a : liveActivities()) {
            all.add(a.enabledVariable());
            for (ActivityVariable p : a.params()) {
                all.add(new ActivityVariable(a.paramFieldName(p), p.type(), p.value(), p.description()));
            }
        }
        all.addAll(globals);
        return all;
    }

    /**
     * Reads {@code activities.json} from {@code resourcesDir}; returns {@link #empty()} if absent/invalid.
     * Transparently migrates the legacy flat shape (a list of {@link ActivityVariable} under
     * {@code "activities"}) by loading those variables as {@link #globals()}.
     */
    public static ActivitiesConfig read(Path resourcesDir) {
        Path file = resourcesDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return empty();
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            if (isLegacyFlat(root)) {
                List<ActivityVariable> legacy = new ArrayList<>();
                for (JsonNode n : root.path("activities")) {
                    legacy.add(MAPPER.treeToValue(n, ActivityVariable.class));
                }
                return new ActivitiesConfig(List.of(), legacy);
            }
            return MAPPER.treeToValue(root, ActivitiesConfig.class);
        } catch (Exception e) {
            System.err.println("Failed to read " + FILE_NAME + " in " + resourcesDir + ": " + e.getMessage());
            return empty();
        }
    }

    /** Legacy shape: {@code activities} entries carry a {@code "type"} (an ActivityVariable), not {@code "params"}. */
    private static boolean isLegacyFlat(JsonNode root) {
        JsonNode acts = root.path("activities");
        return acts.isArray() && !acts.isEmpty() && acts.get(0).has("type") && !acts.get(0).has("params");
    }

    /** Writes (overwrites) {@code activities.json} into {@code resourcesDir}, creating it if needed. */
    public void write(Path resourcesDir) throws IOException {
        Files.createDirectories(resourcesDir);
        MAPPER.writeValue(resourcesDir.resolve(FILE_NAME).toFile(), this);
    }
}
