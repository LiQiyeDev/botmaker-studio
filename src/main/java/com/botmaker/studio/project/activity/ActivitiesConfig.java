package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The activities configuration for a project, persisted as {@code activities.json} under the project's
 * {@code src/main/resources} — and the whole of it, values included. The file is on the runtime classpath
 * because the generated {@code Activities} class reads it at startup.
 *
 * <p>Two halves that answer different questions:
 * <ul>
 *   <li>{@link #activities()} — the {@link ActivityDefinition}s: what the bot can do, in what order
 *       ({@link #flow()}), and which of them are on ({@link #presets()})</li>
 *   <li>{@link #variables()} — every configured value the bot reads, one flat project-wide list. A variable
 *       is filed under a {@link ActivityVariable#tag() tag} for the reader's benefit; the tag is never a
 *       scope, so a value tagged after one activity is readable from all of them.</li>
 * </ul>
 *
 * <p>{@link #allVariables()} is what the code generator and the expression menu consume: each live activity's
 * enable flag, then the variables, with the field name on the generated class being exactly the variable's
 * own name. One flat namespace means {@link #nameClash} has one question to answer.
 *
 * <p><b>One public constructor.</b> Every copy goes through a {@code with…} method, because the four
 * convenience constructors this record used to carry were how a save came to drop the flow, the presets and
 * every value at once: {@code new ActivitiesConfig(updated, globals)} compiled, ran, and silently wrote a
 * project half away. A wither cannot omit a field it was not asked about.
 *
 * <p>Every activity listed here is a live one. There used to be an <em>archived</em> third state that dropped
 * an activity out of generation while keeping its definition; see {@link ActivityDefinition} for why it is
 * gone. An activity the user is done with is either disabled or deleted.
 *
 * @param activities      the activity definitions
 * @param variables       every configured value the bot reads, project-wide
 * @param flow            the visual flow (node placements + wires); empty means "no chosen chain, use list order"
 * @param presets         named on/off selections of activities (user-saved; built-ins are offered on top)
 * @param goHomeByDefault whether a newly added activity starts with {@link ActivityDefinition#goHome()} ticked;
 *                        boxed for the same reason as that field — absent must mean {@code true}, not
 *                        {@code false}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivitiesConfig(List<ActivityDefinition> activities, List<ActivityVariable> variables,
                               ActivityFlow flow, List<ActivityPreset> presets, Boolean goHomeByDefault) {

    public static final String FILE_NAME = "activities.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ActivitiesConfig {
        activities = activities == null ? List.of() : List.copyOf(activities);
        variables = variables == null ? List.of() : List.copyOf(variables);
        flow = flow == null ? ActivityFlow.empty() : flow;
        presets = presets == null ? List.of() : List.copyOf(presets);
        if (goHomeByDefault == null) goHomeByDefault = Boolean.TRUE;
    }

    public static ActivitiesConfig empty() {
        return of(List.of(), List.of());
    }

    /**
     * A fresh config over {@code activities} and {@code variables}, with no flow and no presets.
     *
     * <p>For <em>building</em> one — a new project, a test. Never for editing an existing one: that is what
     * the {@code with…} methods are for, and the difference is the whole reason the old convenience
     * constructors are gone. A static factory says "this is a new config" at the call site; a two-argument
     * constructor said nothing, and was read as "the config, with these two things changed".
     */
    public static ActivitiesConfig of(List<ActivityDefinition> activities, List<ActivityVariable> variables) {
        return new ActivitiesConfig(activities, variables, ActivityFlow.empty(), List.of(), Boolean.TRUE);
    }

    /** True when there are no activities and no variables (nothing to generate). */
    @JsonIgnore
    public boolean isEmpty() {
        return activities.isEmpty() && variables.isEmpty();
    }

    // ---- copies -----------------------------------------------------------------------------------------

    public ActivitiesConfig withActivities(List<ActivityDefinition> newActivities) {
        return new ActivitiesConfig(newActivities, variables, flow, presets, goHomeByDefault);
    }

    public ActivitiesConfig withVariables(List<ActivityVariable> newVariables) {
        return new ActivitiesConfig(activities, newVariables, flow, presets, goHomeByDefault);
    }

    public ActivitiesConfig withFlow(ActivityFlow newFlow) {
        return new ActivitiesConfig(activities, variables, newFlow, presets, goHomeByDefault);
    }

    public ActivitiesConfig withPresets(List<ActivityPreset> newPresets) {
        return new ActivitiesConfig(activities, variables, flow, newPresets, goHomeByDefault);
    }

    public ActivitiesConfig withGoHomeByDefault(boolean newDefault) {
        return new ActivitiesConfig(activities, variables, flow, presets, newDefault);
    }

    /** A copy with each activity's enable flag set from {@code preset} (in it → on, else off). */
    public ActivitiesConfig applyPreset(ActivityPreset preset) {
        return withActivities(activities.stream().map(a -> a.withEnabled(preset.enables(a.name()))).toList());
    }

    // ---- activities -------------------------------------------------------------------------------------

    /**
     * The activities a run can actually reach: everything reachable from the {@link #flow()}'s start node when
     * one is wired, else the plain definition order. Orphans — placed but unreachable — are excluded. This is
     * what the generated {@code ActivityRegistry} instantiates and the generated {@code FlowDriver} can route
     * to.
     *
     * <p>The order is breadth-first from the start and is presentational only — with branching there is no
     * single run order any more; the driver picks the next node from the outcome each activity reports.
     *
     * <p>An orphan is excluded from the run order but is still an activity in every other sense: it keeps its
     * {@code Activities.<field>} flag ({@link #allVariables()} spans orphans) and its stub, because wiring it
     * up is one drag away.
     */
    @JsonIgnore
    public List<ActivityDefinition> orderedActivities() {
        List<ActivityDefinition> live = activities;
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

    // ---- variables --------------------------------------------------------------------------------------

    /**
     * Every referenceable {@code Activities.<field>}, in generation order: each activity's enable flag, then
     * the project's variables. Names here are exactly the generated field names, and what the expression menu
     * inserts.
     */
    @JsonIgnore
    public List<ActivityVariable> allVariables() {
        List<ActivityVariable> all = new ArrayList<>();
        for (ActivityDefinition a : activities) all.add(a.enabledVariable());
        all.addAll(variables);
        return all;
    }

    /**
     * The variables the person running the bot is offered, grouped under their tag headings and in
     * declaration order within each — so the Runner shows "Mining" and "General" rather than a flat list.
     */
    @JsonIgnore
    public Map<String, List<ActivityVariable>> sharedVariables() {
        Map<String, List<ActivityVariable>> byTag = new LinkedHashMap<>();
        for (ActivityVariable v : variables) {
            if (v.isPublic()) byTag.computeIfAbsent(v.tagOrGeneral(), t -> new ArrayList<>()).add(v);
        }
        return byTag;
    }

    /**
     * Whether {@code name} is already taken, ignoring {@code except} (the variable being renamed, or null).
     *
     * <p>One namespace, one check. Activity names and variable names both become fields on the same generated
     * class, so an activity called {@code Mining} and a variable called {@code Mining} are the same field
     * declared twice — a project that saves and then will not compile. Case-insensitive, because the
     * generated stub files are named after activities and a case-insensitive filesystem cannot tell
     * {@code Mining.java} from {@code mining.java}.
     */
    public boolean nameClash(String name, String except) {
        if (name == null || name.isBlank()) return false;
        String candidate = name.trim().toLowerCase(Locale.ROOT);
        if (except != null && candidate.equals(except.trim().toLowerCase(Locale.ROOT))) return false;
        Set<String> taken = new LinkedHashSet<>();
        for (ActivityDefinition a : activities) taken.add(a.name().toLowerCase(Locale.ROOT));
        for (ActivityVariable v : variables) taken.add(v.name().toLowerCase(Locale.ROOT));
        return taken.contains(candidate);
    }

    // ---- persistence ------------------------------------------------------------------------------------

    /** Reads {@code activities.json} from {@code resourcesDir}; returns {@link #empty()} if absent/invalid. */
    public static ActivitiesConfig read(Path resourcesDir) {
        Path file = resourcesDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return empty();
        try {
            return MAPPER.readValue(file.toFile(), ActivitiesConfig.class);
        } catch (Exception e) {
            System.err.println("Failed to read " + FILE_NAME + " in " + resourcesDir + ": " + e.getMessage());
            return empty();
        }
    }

    /** Writes (overwrites) {@code activities.json} into {@code resourcesDir}, creating it if needed. */
    public void write(Path resourcesDir) throws IOException {
        Files.createDirectories(resourcesDir);
        MAPPER.writeValue(resourcesDir.resolve(FILE_NAME).toFile(), this);
    }
}
