package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.ActivitiesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.project.activity.ActivityPreset;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the project's <em>activities</em> — a two-tier model of game tasks. Each
 * {@link ActivityDefinition} owns an enable flag and its own config {@link ActivityVariable params};
 * free-standing {@link ActivitiesConfig#globals() globals} may also exist. Persistence + generation:
 * <ul>
 *   <li>{@code src/main/resources/activities.json} — the schema + values (read at runtime)</li>
 *   <li>generated {@code Activities.java} — {@code public static final} typed fields (enable flags,
 *       {@code <Activity>_<param>} params, globals) loaded from that JSON</li>
 *   <li>generated {@code ActivityRegistry.java} — {@code List<Activity> ALL} of the per-activity subclass
 *       instances the macro loop iterates (replaces a hand-maintained if-chain)</li>
 *   <li>editable {@code activities/<Name>.java} — one {@code Activity} subclass stub per activity, created
 *       once and never overwritten (the user's "how to do it" lives here)</li>
 * </ul>
 *
 * All I/O lives here at the service edge. {@link #update} runs off the calling thread and publishes
 * {@link ActivitiesChangedEvent} once state is refreshed.
 */
public final class ActivityService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;

    public ActivityService(ProjectConfig config, ProjectState state, EventBus eventBus) {
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
    }

    /** The current activities (from project state, loaded at open and refreshed on change). */
    public ActivitiesConfig current() {
        ActivitiesConfig c = state.getActivities();
        return c != null ? c : ActivitiesConfig.empty();
    }

    /** Loads activities from disk into project state (called once at project open). */
    public ActivitiesConfig load() {
        ActivitiesConfig loaded = ActivitiesConfig.read(config.resourcesRoot());
        state.setActivities(loaded);
        return loaded;
    }

    /**
     * Persists {@code newConfig} (writes {@code activities.json}, regenerates {@code Activities.java} and
     * {@code ActivityRegistry.java}, and creates any missing per-activity stub files), refreshes project
     * state and publishes {@link ActivitiesChangedEvent}. Runs asynchronously; the returned future
     * completes exceptionally if writing fails.
     */
    public CompletableFuture<Void> update(ActivitiesConfig newConfig) {
        return CompletableFuture.runAsync(() -> {
            try {
                newConfig.write(config.resourcesRoot());
                writeActivitiesClass(newConfig);
                writeRegistryClass(newConfig);
                writeDriverClass(newConfig);
                ensureStubs(newConfig);
                ActivityStubSync.sync(config, newConfig);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save activities: " + e.getMessage(), e);
            }
            state.setActivities(newConfig);
            eventBus.publish(new ActivitiesChangedEvent(newConfig));
        });
    }

    /**
     * Applies {@code preset} to the current config — each activity is enabled iff the preset names it — and
     * persists through the normal {@link #update} path, so {@code activities.json}, {@code Activities.java}
     * and the registry all regenerate. Wiring and order are untouched: a preset only says <em>which</em>
     * activities run, never in what order.
     */
    public CompletableFuture<Void> applyPreset(ActivityPreset preset) {
        return update(current().applyPreset(preset));
    }

    /**
     * Persists a new canvas {@link ActivityFlow} (node placements + wires) — this is what changes the run
     * order, so the registry regenerates from the new chain.
     */
    public CompletableFuture<Void> updateFlow(ActivityFlow flow) {
        return update(current().withFlow(flow));
    }

    /** Writes (or deletes, when there are no referenceable fields) the generated {@code Activities.java}. */
    private void writeActivitiesClass(ActivitiesConfig cfg) throws IOException {
        Path file = config.activitiesSourceFile();
        if (cfg.allVariables().isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, generateSource(cfg));
    }

    /** Writes the generated {@code ActivityRegistry.java} (empty {@code ALL} when there are no activities). */
    private void writeRegistryClass(ActivitiesConfig cfg) throws IOException {
        Path file = config.activityRegistrySourceFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, generateRegistrySource(cfg));
    }

    /** Writes the generated {@code FlowDriver.java} — the walk over the drawn flow. */
    private void writeDriverClass(ActivitiesConfig cfg) throws IOException {
        Path file = config.flowDriverSourceFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, generateDriverSource(cfg));
    }

    /** Creates a subclass stub for each activity if it does not already exist (never overwrites user edits). */
    private void ensureStubs(ActivitiesConfig cfg) throws IOException {
        if (cfg.activities().isEmpty()) return;
        Path dir = config.activitiesPackageDir();
        Files.createDirectories(dir);
        for (ActivityDefinition a : cfg.activities()) {
            Path stub = dir.resolve(a.name() + ".java");
            if (!Files.exists(stub)) {
                Files.writeString(stub, generateStubSource(a));
            }
        }
    }

    /** Builds the source of the generated {@code Activities} class. */
    String generateSource(ActivitiesConfig cfg) {
        StringBuilder fields = new StringBuilder();
        StringBuilder inits = new StringBuilder();
        boolean needsTime = false;
        boolean needsDate = false;
        for (ActivityVariable a : cfg.allVariables()) {
            String nodeExpr = "node(v, \"" + a.name() + "\")";
            if (a.description() != null && !a.description().isBlank()) {
                fields.append("    /** ").append(a.description().replace("*/", "*\\/")).append(" */\n");
            }
            fields.append("    public static final ").append(a.type().javaType())
                    .append(' ').append(a.name()).append(";\n");
            inits.append("        ").append(a.name()).append(" = ")
                    .append(a.type().loadExpression(nodeExpr)).append(";\n");
            needsTime |= a.type() == ActivityType.TIME;
            needsDate |= a.type() == ActivityType.DATE;
        }
        return String.format("""
                package com.%s;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.node.MissingNode;

                import java.io.InputStream;
                import java.util.HashMap;
                import java.util.Map;

                /**
                 * Global activities for this bot. GENERATED by BotMaker Studio — do not edit by hand;
                 * manage via Project &rarr; Activity Flow. Values are loaded at startup from
                 * {@code /activities.json} on the classpath. Missing file / missing key / wrong-type or
                 * unparseable values all fall back to each type's default — a bot never fails to start
                 * because of its activities file.
                 */
                public final class Activities {
                %s
                    static {
                        Map<String, JsonNode> v = new HashMap<>();
                        try (InputStream in = Activities.class.getResourceAsStream("/%s")) {
                            if (in != null) {
                                JsonNode root = new ObjectMapper().readTree(in);
                                for (JsonNode a : root.path("activities")) {
                                    String an = a.path("name").asText();
                                    v.put(an, a.path("enabled"));
                                    for (JsonNode p : a.path("params")) {
                                        v.put(an + "_" + p.path("name").asText(), p.path("value"));
                                    }
                                }
                                for (JsonNode g : root.path("globals")) {
                                    v.put(g.path("name").asText(), g.path("value"));
                                }
                            }
                        } catch (Exception e) {
                            // Degrade gracefully: keep the defaults rather than crash the bot at startup.
                            System.err.println("Activities: could not load /%s (" + e.getMessage()
                                    + "); using defaults.");
                        }
                %s    }

                    private static JsonNode node(Map<String, JsonNode> v, String name) {
                        return v.getOrDefault(name, MissingNode.getInstance());
                    }
                %s%s
                    private Activities() {}
                }
                """, config.packageName(), fields.toString().stripTrailing(),
                ActivitiesConfig.FILE_NAME, ActivitiesConfig.FILE_NAME, inits,
                needsTime ? TIME_HELPER : "", needsDate ? DATE_HELPER : "");
    }

    /**
     * Builds the source of the generated {@code ActivityRegistry} class: one typed singleton per activity the
     * flow can reach ({@link ActivitiesConfig#orderedActivities()}), plus {@code ALL} over them.
     *
     * <p>The singletons are typed ({@code public static final Mining MINING}) rather than only living in
     * {@code ALL}, because {@code FlowDriver} switches over each activity's <em>own</em> outcome enum — which
     * {@code List<Activity<?>>} erases. {@code ALL} is still generated because constructing an activity is
     * what registers it by name for {@code Activity.disable("Mining")}.
     *
     * <p>Orphans (placed but unreachable) and archived activities are left out: they don't run. They still get
     * a stub and their {@code Activities.<field>} flags, so the project keeps compiling.
     */
    String generateRegistrySource(ActivitiesConfig cfg) {
        List<ActivityDefinition> reachable = cfg.orderedActivities();
        StringBuilder singletons = new StringBuilder();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < reachable.size(); i++) {
            String name = reachable.get(i).name();
            singletons.append("    public static final ").append(name).append(' ').append(constantName(name))
                    .append(" = new ").append(name).append("();\n");
            all.append("            ").append(constantName(name)).append(i < reachable.size() - 1 ? ",\n" : "\n");
        }
        String activitiesImport = cfg.activities().isEmpty()
                ? "" : "import com." + config.packageName() + ".activities.*;\n";
        return String.format("""
                package com.%s;

                import com.botmaker.sdk.api.bot.Activity;
                %simport java.util.List;

                /**
                 * The activities this bot can run. GENERATED by BotMaker Studio — do not edit by hand; manage
                 * via Project &rarr; Activity Flow. Each is built once here, which is also what registers it
                 * by name for {@code Activity.disable("Name")}. {@link FlowDriver} routes between them using
                 * the outcome each one reports; {@link #ALL} is the flat view for anything that just needs
                 * every activity.
                 */
                public final class ActivityRegistry {

                %s
                    public static final List<Activity<?>> ALL = List.of(
                %s    );

                    private ActivityRegistry() {}
                }
                """, config.packageName(), activitiesImport,
                singletons.toString().stripTrailing(), all.toString().stripTrailing());
    }

    /**
     * The registry field name for an activity — its name upper-cased, the usual shape for a constant.
     *
     * <p>Two activities whose names differ only in case would collide here; the flow editor rejects that when
     * naming, so a clash can't be saved. (It would be a broken project anyway: their stub files differ only in
     * case, which doesn't survive a case-insensitive filesystem.)
     */
    static String constantName(String activityName) {
        return activityName.toUpperCase();
    }

    /**
     * Builds the source of the generated {@code FlowDriver} — the state machine over the drawn flow, and the
     * thing that makes conditional edges mean anything at runtime.
     *
     * <p>It holds a current node, runs its activity, and picks the next node from the outcome that activity
     * reported. That is the whole difference from what came before: the old generated loop iterated a flat
     * list and ran everything once, so the drawn flow only ever decided the list's <em>order</em> — there was
     * no current node for a branch to branch.
     *
     * <p>Three ways a run ends, all of them {@code null} from {@code step}: an edge into the STOP card, an
     * outcome with no wire, and a node that isn't in the switch. Cycles are legal — that is how a bot repeats
     * — so the step budget is what stops a flow that loops with no way out. It counts transitions <em>between</em>
     * activities, which nothing previously bounded; {@code Watchdog} covers being stuck inside one.
     */
    String generateDriverSource(ActivitiesConfig cfg) {
        List<ActivityDefinition> reachable = cfg.orderedActivities();
        ActivityFlow flow = cfg.flow();
        String start = flow.resolvedStart(reachable.stream().map(ActivityDefinition::name).toList());

        StringBuilder cases = new StringBuilder();
        for (ActivityDefinition a : reachable) {
            cases.append(driverCase(a, flow));
        }
        String activitiesImport = cfg.activities().isEmpty()
                ? "" : "import com." + config.packageName() + ".activities.*;\n";
        return String.format("""
                package com.%s;

                import com.botmaker.sdk.api.Debug;
                import com.botmaker.sdk.api.bot.Bot;
                import com.botmaker.sdk.api.bot.Watchdog;
                %s
                /**
                 * Walks the Activity Flow drawn in BotMaker Studio. GENERATED — do not edit by hand; manage via
                 * Project &rarr; Activity Flow.
                 *
                 * <p>Runs the current activity, then picks the next one from the outcome it reported. The run
                 * ends when it reaches the Stop card, or an outcome with no wire leaving it.
                 */
                public final class FlowDriver {

                    /**
                     * How many activities one run may hand off to before giving up. A flow is allowed to loop —
                     * that is how a bot repeats — so this is what separates &quot;farming all night&quot; from a
                     * cycle with no way out. Change it in Project &rarr; Activity Flow.
                     */
                    private static final int MAX_STEPS = %d;

                    public static void run() {
                        String node = %s;
                        for (int steps = 0; node != null; steps++) {
                            if (steps >= MAX_STEPS) {
                                Debug.error("[Flow] Gave up after " + MAX_STEPS
                                        + " steps at '" + node + "' — the flow is probably looping with no exit.");
                                Bot.stop();
                            }
                            node = step(node);
                            Watchdog.checkpoint();
                        }
                        Bot.stop();
                    }

                    /** The next node after {@code node}, or null to end the run. */
                    private static String step(String node) {
                %s    }

                    private FlowDriver() {}
                }
                """, config.packageName(), activitiesImport, flow.maxSteps(),
                start.isEmpty() ? "null" : '"' + start + '"',
                cases.isEmpty() ? "        return null;\n" : "        switch (node) {\n" + cases
                        + "            default:\n                return null;\n        }\n");
    }

    /** One activity's branch of the driver's dispatch: run it, then route on what it reported. */
    private String driverCase(ActivityDefinition a, ActivityFlow flow) {
        String constant = "ActivityRegistry." + constantName(a.name());
        StringBuilder out = new StringBuilder();
        out.append("            case \"").append(a.name()).append("\":\n");
        // A disabled activity isn't skipped out of the flow — the flow still passes through it, it just
        // doesn't do anything, so it follows the wire it would have taken with nothing to report.
        FlowEdge fallthrough = edgeFor(flow, a.name(), FlowEdge.DEFAULT_OUTCOME);
        out.append("                if (!").append(constant).append(".active()) return ")
                .append(fallthrough == null ? "null" : target(fallthrough)).append(";\n");
        out.append("                switch (").append(constant).append(".execute()) {\n");
        for (String outcome : a.allOutcomes()) {
            FlowEdge wire = edgeFor(flow, a.name(), outcome);
            if (wire == null) continue;   // nothing drawn for it: the default below ends the run
            out.append("                    case ").append(outcome).append(": return ").append(target(wire));
            // A Stop wire and an unwired outcome both end the run, but only one of them is something the user
            // drew — say so, or the generated source reads as if they forgot to wire it.
            out.append(ActivityFlow.STOP_ID.equals(wire.to()) ? ";   // Stop\n" : ";\n");
        }
        out.append("                    default: return null;   // nothing wired — the run ends here\n");
        out.append("                }\n");
        return out.toString();
    }

    /** The wire drawn for one {@code (activity, outcome)} pair, or null when that outcome goes nowhere. */
    private static FlowEdge edgeFor(ActivityFlow flow, String from, String outcome) {
        for (FlowEdge e : flow.edges()) {
            if (e.from().equals(from) && e.outcomeOrDefault().equals(outcome)) return e;
        }
        return null;
    }

    /**
     * The quoted next-node name a wire leads to. An edge into the Stop card yields {@code null} — ending the
     * run is already "no next node", so the terminal needs no representation of its own at runtime.
     */
    private static String target(FlowEdge wire) {
        return ActivityFlow.STOP_ID.equals(wire.to()) ? "null" : '"' + wire.to() + '"';
    }

    /**
     * Builds the initial editable stub for one activity's {@code Activity} subclass.
     *
     * <p>No constructor: {@code Activity}'s no-arg constructor names the activity after its own class, so the
     * only thing the generated stub asks the user for is {@link #run()} — {@code isEnabled()} is wiring to the
     * generated {@code Activities} flag and the Studio marks it read-only ({@code MethodLock.FULL}).
     */
    // Public because recovery needs it too: an activity's isEnabled() is generated against that activity's own
    // flag, so this is the only thing that can say what the stub *should* look like when repairing a mangled one.
    public String generateStubSource(ActivityDefinition a) {
        return String.format("""
                package com.%1$s.activities;

                import com.%1$s.Activities;
                import com.botmaker.sdk.api.bot.Activity;

                /**
                 * Activity: %2$s. Fill in {@link #run()} with how to do it — that method is the whole point of
                 * this file, and this file is yours to edit (BotMaker Studio creates it once and never
                 * overwrites it). {@link #isEnabled()} is wired to the enable flag {@code Activities.%2$s} and
                 * is managed for you; any config params are {@code Activities.%2$s_<param>}.
                 */
                public class %2$s extends Activity<%2$s.Outcome> {

                    /**
                     * What this activity can report having happened. Return one from {@link #run()} and the flow
                     * drawn in the Studio decides where each one goes — so this says what happened here, never
                     * where to go next. GENERATED from Project &rarr; Activity Flow; edit it there, not here.
                     */
                    public enum Outcome { %3$s }

                    @Override
                    public boolean isEnabled() {
                        return Activities.%2$s;
                    }

                    @Override
                    public Outcome run() {
                        // TODO: how to do %2$s
                        return Outcome.DEFAULT;
                    }
                }
                """, config.packageName(), a.name(), String.join(", ", a.allOutcomes()));
    }

    /** Generated helper: parse a {@code LocalTime}, defaulting on a missing/invalid/wrong-type node. */
    private static final String TIME_HELPER = """

            private static java.time.LocalTime parseTime(JsonNode n) {
                    try {
                        return java.time.LocalTime.parse(n.asText("00:00"));
                    } catch (Exception e) {
                        return java.time.LocalTime.MIDNIGHT;
                    }
                }
        """;

    /** Generated helper: parse a {@code LocalDate}, defaulting on a missing/invalid/wrong-type node. */
    private static final String DATE_HELPER = """

            private static java.time.LocalDate parseDate(JsonNode n) {
                    try {
                        return java.time.LocalDate.parse(n.asText("2000-01-01"));
                    } catch (Exception e) {
                        return java.time.LocalDate.of(2000, 1, 1);
                    }
                }
        """;
}
