package com.botmaker.studio.project.activity;

import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.migration.SchemaFile;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 *   <li>{@link #variables()} — every configured value the bot reads, in one project-wide list. A variable
 *       is filed under a {@link ActivityVariable#tag() tag} for the reader's benefit; the tag is never a
 *       scope, so a value tagged after one activity is readable from all of them.</li>
 * </ul>
 *
 * <p>{@link #allVariables()} is what the code generator and the expression menu consume: each live activity's
 * enable flag, then the variables, with the field name on the generated class being exactly the variable's
 * own name.
 *
 * <p><b>The list is flat on disk and partitioned by owner, since 2026-08-27.</b> Every variable carries an
 * {@link ActivityVariable#group() group} — the {@link ParameterGroup} of the plugin that owns it — which
 * decides its section in the Parameters window and the generated class it becomes a field of. So a name has
 * to be unique within its group rather than across the project ({@link #nameClash(String, String, String)}),
 * and two plugins may each offer a {@code timeout}. It is a discriminator on the existing array rather than a
 * section per plugin in the file, because an absent group reads as the default plugin's and so every project
 * ever written is already partitioned correctly, with no migration and no new envelope. The envelope and the
 * {@code schemaVersion} ledger stay the host's.
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
     * Every referenceable generated field, in generation order: each activity's enable flag, then the
     * project's variables. Names here are exactly the generated field names, and what the expression menu
     * inserts — but <b>not</b> the class they are written on, which since the two files split is
     * {@link #holderOf} 's answer and no longer one constant.
     */
    @JsonIgnore
    public List<ActivityVariable> allVariables() {
        List<ActivityVariable> all = new ArrayList<>(activityFlags());
        all.addAll(variables);
        return all;
    }

    /**
     * One {@code boolean} per activity — the fields the generated {@code Activities} holds, and the whole of
     * what it holds since 2026-08-25.
     *
     * <p>Every activity, not only the reachable ones: an orphan keeps its flag, because wiring it up is one
     * drag away and its stub's {@code isEnabled()} names the field either way.
     */
    @JsonIgnore
    public List<ActivityVariable> activityFlags() {
        List<ActivityVariable> flags = new ArrayList<>(activities.size());
        for (ActivityDefinition a : activities) flags.add(a.enabledVariable());
        return flags;
    }

    /**
     * Which generated class {@code name} is a field of: {@link VariableHolder#ACTIVITIES} for an activity's
     * enable flag, {@link VariableHolder#PARAMETERS} for anything else.
     *
     * <p>Total by design — an unknown name answers {@code PARAMETERS} rather than null, because the callers
     * are a picker and a menu writing a qualifier into source, and a name they cannot place is one the model
     * has just lost (a variable deleted while a slot still holds it). {@code Parameters.X} on a field that is
     * gone is a compile error naming the field; a null qualifier is a crash on the way to rendering a
     * dropdown.
     *
     * <p>It is only unambiguous because {@link #nameClash} keeps the two sets disjoint — see there.
     */
    public VariableHolder holderOf(String name) {
        if (name == null) return VariableHolder.PARAMETERS;
        for (ActivityDefinition a : activities) {
            if (a.name().equals(name)) return VariableHolder.ACTIVITIES;
        }
        return VariableHolder.PARAMETERS;
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
     * <p>One namespace, one check — <b>still one, now that the fields are declared on two classes</b>. The
     * original reason was javac's: both became fields of {@code Activities}, so an activity called
     * {@code Mining} and a variable called {@code Mining} were the same field declared twice. Split across
     * {@code Activities} and {@code Parameters} that particular collision compiles, and the check stays
     * anyway, because a second reason took over: {@link #holderOf} answers which class a <em>name</em> belongs
     * to, and a name belonging to both has no answer. Relaxing it would make the picker and the expression
     * menu qualify a value with {@code Activities.} and generate a bot that does not compile — a worse failure
     * than the one being permitted, and reachable from the UI rather than only from a hand-edited file.
     *
     * <p>Case-insensitive, because the
     * generated stub files are named after activities and a case-insensitive filesystem cannot tell
     * {@code Mining.java} from {@code mining.java}.
     */
    public boolean nameClash(String name, String except) {
        return nameClash(name, except, ParameterGroup.DEFAULT_ID);
    }

    /**
     * Whether {@code name} is already taken <b>within {@code groupId}</b>, ignoring {@code except}.
     *
     * <p><b>The namespace is the group, since 2026-08-27.</b> The paragraph above describes the flat one it
     * replaced, and both of its reasons hold only inside a group: a group's variables are fields of that
     * group's own generated class, so two plugins may each offer a {@code timeout} without either being a
     * field declared twice or a name whose qualifier is a coin toss. Activities are checked in every group,
     * because the enable flags are the host's and there is only one set of them.
     */
    public boolean nameClash(String name, String except, String groupId) {
        if (name == null || name.isBlank()) return false;
        String candidate = name.trim().toLowerCase(Locale.ROOT);
        if (except != null && candidate.equals(except.trim().toLowerCase(Locale.ROOT))) return false;
        Set<String> taken = new LinkedHashSet<>();
        for (ActivityDefinition a : activities) taken.add(a.name().toLowerCase(Locale.ROOT));
        for (ActivityVariable v : variablesIn(groupId)) taken.add(v.name().toLowerCase(Locale.ROOT));
        return taken.contains(candidate);
    }

    /**
     * The variables filed under one {@link ParameterGroup}, in declaration order.
     *
     * <p>This is the partition the Parameters window renders a section from and the generator writes a file
     * from — one plugin, one group, one class. A blank id is the default plugin's, which is every variable in
     * every project written before groups existed.
     */
    @JsonIgnore
    public List<ActivityVariable> variablesIn(String groupId) {
        return variables.stream().filter(v -> v.isIn(groupId)).toList();
    }

    /** The group ids this project actually has variables in, in the order they first appear in the file. */
    @JsonIgnore
    public List<String> variableGroups() {
        return variables.stream().map(ActivityVariable::group).distinct().toList();
    }

    /**
     * The qualifier a bot writes in front of {@code name} — {@code Parameters.REST}, {@code Activities.Mining},
     * {@code DiscordParameters.WEBHOOK}.
     *
     * <p>{@link #holderOf} answers this for the two classes the host itself knows about, and it stays the
     * answer for every variable in the default group. A variable filed under another plugin's group is a
     * field of <em>that</em> group's class, which only the plugin can name — so the group is asked first and
     * the two-class enum is the fallback, including for a group no loaded plugin claims (whose variables are
     * not being generated anywhere, and for which the old spelling is the least wrong guess).
     *
     * @param pinnedSdkVersion the version the open project pins, for asking the plugins; may be null
     */
    /**
     * {@link #qualifierOf(String, String)} asked of the plugins as they are loaded, without a pin.
     *
     * <p>The plugin <em>set</em> is already this project's — {@code PluginHost.bind} swapped it when the
     * classpath resolved — so the only thing the pin would change is a plugin answering with a different
     * class name at a different version of itself, which is a rename of generated API and not something a
     * menu writing a qualifier could act on anyway. The callers are a picker and a menu, asked per keystroke;
     * re-reading the pom for each would cost more than the question is worth.
     */
    public String qualifierOf(String name) {
        return qualifierOf(name, null);
    }

    public String qualifierOf(String name, String pinnedSdkVersion) {
        for (ActivityVariable v : variables) {
            if (!v.name().equals(name)) continue;
            ParameterGroup group = PluginHost.parameterGroup(pinnedSdkVersion, v.group());
            if (group != null) return group.className();
            break;
        }
        return holderOf(name).className();
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

    /**
     * Writes (overwrites) {@code activities.json} into {@code resourcesDir}, creating it if needed.
     *
     * <p>Every write carries the file's {@code schemaVersion} — see {@link SchemaFile#stamped}. A record
     * serializes to exactly its components, so writing {@code this} directly would drop the number and return
     * the file to version 0, and the next open would re-run every migration step against an already-migrated
     * file. The version is a fact about the file rather than about the model, which is why it is stamped on
     * the way out instead of being a component nine {@code with…} methods would have to remember to carry.
     */
    public void write(Path resourcesDir) throws IOException {
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve(FILE_NAME), json());
    }

    /**
     * The exact text {@link #write} would put on disk.
     *
     * <p>It exists for creation, which renders every file in memory before the first directory is made so
     * that a project is written all at once or not at all ({@code ProjectCreator}). Writing through this
     * rather than beside it is the point: one mapper and one stamp, so the file a project is born with and
     * the file every later save produces cannot be two shapes.
     */
    public String json() throws IOException {
        ObjectNode body = MAPPER.valueToTree(this);
        return MAPPER.writeValueAsString(SchemaFile.ACTIVITIES.stamped(body));
    }
}
