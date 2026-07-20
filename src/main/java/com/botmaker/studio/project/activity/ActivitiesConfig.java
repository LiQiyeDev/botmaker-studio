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
 * <p>{@link #flow()} is the optional visual chain (node placements + wires) built on the Activity Flow
 * canvas; when present it defines the <em>run order</em> ({@link #orderedActivities()}). {@link #presets()}
 * are named on/off selections the user can apply. Both are back-compatible additions — an
 * {@code activities.json} without them loads with an {@link ActivityFlow#empty() empty flow} and no presets.
 *
 * @param activities the activity definitions (each an enable flag + its params)
 * @param globals    free-standing global config variables not tied to any activity
 * @param flow       the visual flow (node placements + wires); empty means "no chosen chain, use list order"
 * @param presets    named on/off selections of activities (user-saved; built-ins are offered on top)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivitiesConfig(List<ActivityDefinition> activities, List<ActivityVariable> globals,
                               ActivityFlow flow, List<ActivityPreset> presets) {

    public static final String FILE_NAME = "activities.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ActivitiesConfig {
        activities = activities == null ? List.of() : List.copyOf(activities);
        globals = globals == null ? List.of() : List.copyOf(globals);
        flow = flow == null ? ActivityFlow.empty() : flow;
        presets = presets == null ? List.of() : List.copyOf(presets);
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
     * The activities in <em>run order</em>: the {@link #flow()} chain (root → …) when one is wired, else
     * the plain definition order. Orphan activities (placed but not reachable from the chain root) are
     * excluded — they won't run. This is what the generated {@code ActivityRegistry.ALL} iterates.
     *
     * <p>Note {@link #allVariables()} still spans <em>all</em> activities (orphans included) so their
     * {@code Activities.<field>} flags exist and every generated stub compiles — only running is gated here.
     */
    public List<ActivityDefinition> orderedActivities() {
        if (flow.isEmpty()) return activities;
        Map<String, ActivityDefinition> byName = new LinkedHashMap<>();
        for (ActivityDefinition a : activities) byName.put(a.name(), a);
        List<ActivityDefinition> ordered = new ArrayList<>();
        for (String name : flow.order(activities.stream().map(ActivityDefinition::name).toList())) {
            ActivityDefinition a = byName.get(name);
            if (a != null) ordered.add(a);
        }
        return ordered;
    }

    /** A copy with each activity's enable flag set from {@code preset} (in it → on, else off). */
    public ActivitiesConfig applyPreset(ActivityPreset preset) {
        List<ActivityDefinition> updated = activities.stream()
                .map(a -> a.withEnabled(preset.enables(a.name())))
                .toList();
        return new ActivitiesConfig(updated, globals, flow, presets);
    }

    public ActivitiesConfig withFlow(ActivityFlow newFlow) {
        return new ActivitiesConfig(activities, globals, newFlow, presets);
    }

    public ActivitiesConfig withPresets(List<ActivityPreset> newPresets) {
        return new ActivitiesConfig(activities, globals, flow, newPresets);
    }

    /**
     * Every referenceable {@code Activities.<field>} value, in generation order: each activity's enable
     * flag then its params ({@code <Activity>_<param>}), followed by the globals. Names here are exactly the
     * generated field names (and what the expression menu inserts).
     */
    public List<ActivityVariable> allVariables() {
        List<ActivityVariable> all = new ArrayList<>();
        for (ActivityDefinition a : activities) {
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
