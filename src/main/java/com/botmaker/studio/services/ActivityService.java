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
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.VariableWire;
import com.botmaker.studio.project.scaffold.TemplateStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the project's <em>activities</em> — the game tasks a bot performs — and the project-wide
 * {@link ActivityVariable variables} they read. Persistence + generation:
 * <ul>
 *   <li>{@code src/main/resources/activities.json} — the whole model, values included, read at runtime</li>
 *   <li>generated {@code Activities.java} — one {@code public static final boolean} per activity, its enable
 *       flag, loaded from that JSON at startup</li>
 *   <li>generated {@code Parameters.java} — one typed {@code public static final} per project variable, from
 *       the same JSON. One class held both until 2026-08-25; {@link #generateParametersSource} says why they
 *       are two</li>
 *   <li>generated {@code ActivityRegistry.java} — {@code List<Activity> ALL} of the per-activity subclass
 *       instances the macro loop iterates (replaces a hand-maintained if-chain)</li>
 *   <li>generated {@code FlowDriver.java} — the walk over the drawn flow</li>
 *   <li>editable {@code activities/<Name>.java} — one {@code Activity} subclass stub per activity, created
 *       once and never overwritten (the user's "how to do it" lives here)</li>
 * </ul>
 *
 * <p>One model, one store. Studio briefly generated a {@code Settings.java} holding every value as a compiled
 * Java literal instead; it is gone, along with the discriminator that chose between the two. What that
 * experiment bought — project-wide variables organised by tag, and a type list as wide as the one methods
 * use — was kept, and lives in {@link ActivitiesConfig#variables()} and {@link VariableWire}.
 *
 * All I/O lives here at the service edge. {@link #update} runs off the calling thread and publishes
 * {@link ActivitiesChangedEvent} once state is refreshed.
 */
public final class ActivityService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;
    /** The pinned SDK's scaffold templates, resolved at most once — see {@link #templates()}. */
    private TemplateStore templates;

    public ActivityService(ProjectConfig config, ProjectState state, EventBus eventBus) {
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
    }

    /** The project these activities belong to — so a caller holding this service needn't also be handed it. */
    public ProjectConfig projectConfig() {
        return config;
    }

    /** The current activities (from project state, loaded at open and refreshed on change). */
    public ActivitiesConfig current() {
        // Null-tolerant on state as well as on its contents: the generators below are pure enough to be
        // exercised with no project state at all, and a null check is cheaper than a second constructor.
        ActivitiesConfig c = state == null ? null : state.getActivities();
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
        // Read on the caller's thread, before the async body: deleteRemovedStubs needs to know which
        // activities this save drops, and state is not for background callers.
        ActivitiesConfig previous = current();
        return CompletableFuture.runAsync(() -> {
            try {
                // Rendered and verified before a byte is written — activities.json included. A regenerated
                // file the pinned SDK cannot carry is a refusal, and a refusal must leave the project exactly
                // as it was: writing the model first and discovering the refusal second would save a flow
                // whose generated driver does not exist for it.
                Emission emission = render(newConfig, true);
                newConfig.write(config.resourcesRoot());
                write(emission);
                deleteRemovedStubs(previous, newConfig);
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

    /**
     * Everything one save will write, rendered but not yet on disk: {@code name -> (where, what)}.
     *
     * <p>Two maps rather than one keyed by {@link Path} because a refusal names files in the sentence it
     * shows, and a project-relative name is what the user recognises where an absolute path is noise.
     */
    private record Emission(Map<String, String> sources, Map<String, Path> destinations) {}

    /**
     * Renders every file this save generates — all of it, in memory, before any of it is written.
     *
     * <p><b>Why the whole batch and not file by file.</b> A regenerated file holds no user code and has a
     * shape entirely of our making, so the bar is higher than for the user's own source: it must compile and
     * it must not lose anything the model says. There is therefore no half-correct outcome worth keeping —
     * three files written and the fourth refused would leave a project that does not build, whereas leaving
     * all four alone leaves one that does (against the jar they were written for). That is what "all or
     * nothing" buys, and it is why rendering happens here rather than inside each writer.
     *
     * @param includeStubs whether to render the per-activity stubs that do not exist yet. They are
     *                     {@code SEED} files — written once and the user's thereafter — but they are part of
     *                     the batch all the same.
     */
    private Emission render(ActivitiesConfig cfg, boolean includeStubs) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, Path> destinations = new LinkedHashMap<>();
        // Both written even when they would hold no fields at all. Activities used to be *deleted* in that case,
        // which is fine for a project that has never had an activity and wrong for one that has just deleted
        // its last: anything still saying `import com.<pkg>.Activities;` — a scaffold file, a hand-written
        // helper — stops compiling the moment the class evaporates. An empty class cannot break a build.
        put(sources, destinations, config.activitiesSourceFile(), generateActivitiesSource(cfg));
        put(sources, destinations, config.parametersSourceFile(), generateParametersSource(cfg));
        put(sources, destinations, config.activityRegistrySourceFile(), generateRegistrySource(cfg));
        put(sources, destinations, config.flowDriverSourceFile(), generateDriverSource(cfg));
        if (includeStubs) {
            for (ActivityDefinition a : cfg.activities()) {
                Path stub = config.activitiesPackageDir().resolve(a.name() + ".java");
                if (Files.exists(stub)) continue;           // never overwrites what the user has edited
                put(sources, destinations, stub, generateStubSource(a));
            }
        }
        return new Emission(sources, destinations);
    }

    /**
     * Adds one rendered file to the batch, keyed on its path relative to the project.
     *
     * <p>Relative to the project rather than by file name, because a name is not unique: nothing stops an
     * activity being called {@code FlowDriver}, and keying on the name would have its stub quietly displace
     * the driver in the batch. Relative rather than absolute because the key is also what a refusal names.
     */
    private void put(Map<String, String> sources, Map<String, Path> destinations, Path file, String source) {
        String key;
        try {
            key = config.projectPath().relativize(file).toString();
        } catch (IllegalArgumentException e) {
            key = file.toString();                          // different root: unusual, but never a collision
        }
        sources.put(key, source);
        destinations.put(key, file);
    }

    /** Writes a verified {@link Emission}, and only ever a verified one. */
    private static void write(Emission emission) throws IOException {
        for (Map.Entry<String, String> file : emission.sources().entrySet()) {
            Path to = emission.destinations().get(file.getKey());
            Files.createDirectories(to.getParent());
            Files.writeString(to, file.getValue());
        }
    }

    /**
     * Re-renders the generated files against the SDK the project pins <em>now</em> — what an SDK upgrade calls
     * once the pom has moved.
     *
     * <p>It is the other half of {@code SdkMigrationRunner} no longer refusing an upgrade that touches a
     * generated file: the user's source is rewritten by the migrator, and these files, which the migrator
     * must not rewrite, are simply produced again from the model against the new jar. No stubs — a stub is
     * the user's file and the migrator has already been over it.
     */
    public void regenerate() throws IOException {
        write(render(current(), false));
    }

    /**
     * The scaffold templates of the SDK this project pins, resolved once: the frame of a generated file
     * belongs to the SDK the bot compiles against, and asking again on every save would buy a jar resolve for
     * an answer that only changes when the pom does.
     *
     * <p>Memoised on the service, so the generators below can be called with nothing but a model — which is
     * what lets them be exercised against a project directory that does not exist yet.
     *
     * <p>The floor is checked here rather than at each caller because this is the one place all of them pass
     * through: every generated file is a filled template, so every generated file needs an SDK that has
     * templates. It is checked before the memo is set, so a project sitting below the floor refuses each time
     * it is asked rather than caching a store it was never allowed to use.
     */
    private synchronized TemplateStore templates() throws IOException {
        if (templates == null) {
            Path project = config == null ? null : config.projectPath();
            String version = project == null ? null : MavenService.readSdkVersion(project);
            TemplateStore.requireFloor(version);
            templates = TemplateStore.forVersionNewerThanStudio(project, version);
        }
        return templates;
    }

    /** One template of that SDK, required and filled. */
    private String render(String id, String className, Map<String, String> fills) throws IOException {
        TemplateStore store = templates();
        return store.render(store.require(id), config.packageName(), className, fills);
    }

    /**
     * Deletes the stub of every activity this save removed.
     *
     * <p>The counterpart of {@link #ensureStubs}, and not optional: a removed activity stops generating its
     * {@code Activities.<Name>} field, so its {@code <Name>.java} — which reads that field in
     * {@code isEnabled()} — no longer compiles. Leaving the file behind turns "I removed an activity" into a
     * broken build in a file the user never opened.
     *
     * <p>Keyed on the <em>difference</em> between the two configs, never on "every file in {@code activities/}
     * that isn't an activity": a helper class the user parked in that package is not this method's business,
     * and a sweep would eat it on the next unrelated save.
     */
    private void deleteRemovedStubs(ActivitiesConfig previous, ActivitiesConfig cfg) throws IOException {
        Set<String> kept = cfg.activities().stream().map(ActivityDefinition::name).collect(Collectors.toSet());
        for (ActivityDefinition gone : previous.activities()) {
            if (kept.contains(gone.name())) continue;
            Files.deleteIfExists(config.activitiesPackageDir().resolve(gone.name() + ".java"));
        }
    }

    /**
     * The generated {@code Activities} class: one {@code public static final boolean} per activity the project
     * defines, read from {@code activities.json} at startup.
     *
     * <p><b>Both tokens carry everything this project says.</b> {@code FIELDS} is the declarations,
     * {@code INITS} the reads. The frame around them — the class, the javadoc explaining why these are blank
     * finals assigned in a static block, the {@code Wire} import — is the SDK's template, and so is the
     * loader: {@code Wire.one} / {@code Wire.many} over a {@code ConfigStore} that reads the JSON. What used
     * to sit between those two halves was a Jackson loader and up to thirteen parser bodies <em>written as
     * Java strings</em>, emitted per project for whichever types it happened to use. They are compiled SDK
     * methods now, and the editor calls the same ones — which is what ended the {@code 1h30m} grammar
     * existing twice with a comment asking the next reader to diff them by eye.
     *
     * <p>This file used to hold the values as well; see {@link #generateParametersSource}.
     */
    public String generateActivitiesSource(ActivitiesConfig cfg) throws IOException {
        return render("ACTIVITIES", null, fieldsAndInits(cfg.activityFlags(), Map.of()));
    }

    /**
     * The generated {@code Parameters} class: one {@code public static final} field per project variable, of
     * the variable's own type.
     *
     * <p>Its own file since 2026-08-25. The two halves were one class holding one flat namespace, in which an
     * activity's on/off tick and the delay it waits for were spelled the same way and neither name said which
     * was which — while they are governed differently at every level above the field: a flag is written by the
     * Activity Flow and read by a stub, a value is the user's and is what the Runner offers. Splitting them is
     * also what lets a variable be renamed, retyped or deleted without the enable flags being in the blast
     * radius.
     *
     * <p>{@code IMPORTS} is filled with nothing, always. {@link VariableWire#javaType} names every type in
     * full, so the file needs no import for a variable's type — which is the cheapest way to guarantee it
     * never needs one that was forgotten. The hole exists because the template's own defaults use short
     * names, and because a future Studio may have something to put there.
     */
    public String generateParametersSource(ActivitiesConfig cfg) throws IOException {
        return render("PARAMETERS", null, fieldsAndInits(cfg.variables(), Map.of("IMPORTS", "")));
    }

    /**
     * The two holes both holder classes are made of: a declaration per field, and the {@code Wire} read that
     * assigns it.
     *
     * <p>One routine for both, because the fields are the same shape wherever they are declared. What differs
     * between the two files is only which variables are passed in.
     *
     * @param extra holes this template has and the other does not, merged in ({@code IMPORTS})
     */
    private static Map<String, String> fieldsAndInits(List<ActivityVariable> variables,
                                                      Map<String, String> extra) {
        StringBuilder fields = new StringBuilder();
        StringBuilder inits = new StringBuilder();
        for (ActivityVariable v : variables) {
            if (!v.description().isBlank()) {
                fields.append("    /** ").append(v.description().replace("*/", "*\\/")).append(" */\n");
            }
            fields.append("    public static final ").append(VariableWire.javaType(v.type()))
                    .append(' ').append(v.name()).append(";\n");
            inits.append("        ").append(v.name()).append(" = ")
                    .append(VariableWire.loadExpression(v.type(), v.name())).append(";\n");
        }
        Map<String, String> fills = new LinkedHashMap<>(extra);
        fills.put("FIELDS", fields.toString().strip());
        fills.put("INITS", inits.toString().strip());
        return fills;
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
     * <p>Orphans (placed but unreachable) are left out: they don't run. They still get a stub and their
     * {@code Activities.<field>} flags, so the project keeps compiling.
     */
    public String generateRegistrySource(ActivitiesConfig cfg) throws IOException {
        List<ActivityDefinition> reachable = cfg.orderedActivities();
        StringBuilder singletons = new StringBuilder();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < reachable.size(); i++) {
            String name = reachable.get(i).name();
            singletons.append("    public static final ").append(name).append(' ').append(constantName(name))
                    .append(" = new ").append(name).append("();\n");
            all.append("            ").append(constantName(name)).append(i < reachable.size() - 1 ? ",\n" : "");
        }
        return render("ACTIVITY_REGISTRY", null, Map.of(
                "ACTIVITY_IMPORT", activitiesImportFor(cfg),
                "SINGLETONS", singletons.toString().strip(),
                "ALL", all.toString().strip()));
    }

    /**
     * The {@code import com.<pkg>.activities.*;} line for a generated file, or nothing when that package has
     * no source in it.
     *
     * <p>An import-on-demand of a package with nothing in it does not compile, so a project with no activities
     * at all gets no import line rather than a broken one.
     */
    private String activitiesImportFor(ActivitiesConfig cfg) {
        return cfg.activities().isEmpty()
                ? "" : "import com." + config.packageName() + ".activities.*;";
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
     * The generated {@code FlowDriver} — the drawn flow as a {@link com.botmaker.sdk.api.flow.FlowGraph
     * table}, and nothing else.
     *
     * <p><b>The walk is not generated any more.</b> Studio used to write the state machine itself: a
     * {@code switch} over node names, each case checking {@code active()}, setting the popup guard, calling
     * {@code GoHome}, then switching again over the activity's outcome — around a loop with its own step
     * budget, {@code Watchdog.checkpoint()} and an after-not-before delay. Every line of that was identical
     * in every project; the only thing that differed was the table. So the table stayed here and the walk
     * moved into the SDK, where it compiles once, has a type, and is tested against branches, joins, loops,
     * unwired outcomes and a disabled fall-through — none of which was reachable by a test while it was text.
     *
     * <p>What this method emits is one {@code FlowGraph.node(…)} per reachable activity, each carrying its
     * name, its registry singleton, its {@code PopupCheck}, its {@code Recovery}, where a disabled activity
     * falls through to, and one {@code FlowGraph.route(…)} per outcome that has a wire. The typing that made
     * the old {@code switch} safe is kept rather than traded away: {@code node} is generic in the activity's
     * own outcome enum, so a route built from another activity's constant does not compile.
     *
     * <p>Ending a run is unchanged and now stated in one place: an outcome with no route <em>is</em> the
     * stop, cycles are legal because that is how a bot repeats, and the step budget is what stops one that
     * loops with no way out.
     */
    public String generateDriverSource(ActivitiesConfig cfg) throws IOException {
        List<ActivityDefinition> reachable = cfg.orderedActivities();
        ActivityFlow flow = cfg.flow();
        String start = flow.resolvedStart(reachable.stream().map(ActivityDefinition::name).toList());

        StringBuilder table = new StringBuilder(start.isEmpty() ? "null" : '"' + start + '"');
        for (ActivityDefinition a : reachable) {
            table.append(",\n").append(driverNode(a, flow));
        }
        return render("FLOW_DRIVER", null, Map.of(
                "ACTIVITY_IMPORT", activitiesImportFor(cfg),
                "FLOW", table.toString(),
                "MAX_STEPS", Integer.toString(flow.maxSteps()),
                "STEP_DELAY_MS", Integer.toString(flow.stepDelayMs())));
    }

    /** The indent a node sits at inside {@code FlowGraph.of(…)}, and its routes one level further in. */
    private static final String NODE_INDENT = " ".repeat(12);
    private static final String ROUTE_INDENT = " ".repeat(20);

    /**
     * One activity's row of the table: which activity the node runs, how to run it, and where each outcome
     * it can report leads.
     */
    private String driverNode(ActivityDefinition a, ActivityFlow flow) {
        String constant = "ActivityRegistry." + constantName(a.name());
        // A disabled activity isn't skipped out of the flow — the flow still passes through it, it just
        // doesn't do anything, so it follows the wire it would have taken with nothing to report.
        FlowEdge fallthrough = edgeFor(flow, a.name(), FlowEdge.NEXT_OUTCOME);
        StringBuilder out = new StringBuilder(NODE_INDENT)
                .append("FlowGraph.node(\"").append(a.name()).append("\", ").append(constant)
                .append(", PopupCheck.").append(a.popupCheck() ? "ON" : "OFF")
                .append(", Recovery.").append(a.goHome() ? "GO_HOME" : "NONE")
                .append(", ").append(fallthrough == null ? "null" : target(fallthrough));
        for (String outcome : a.allOutcomes()) {
            FlowEdge wire = edgeFor(flow, a.name(), outcome);
            if (wire == null) continue;   // nothing drawn for it: an unrouted outcome ends the run
            // The outcome constant is spelled through the activity's own enum, which is what makes the
            // route checked: FlowGraph.node is generic in that enum, so a constant of another activity's
            // does not compile — the same guarantee the generated switch had.
            out.append(",\n").append(ROUTE_INDENT).append("FlowGraph.route(").append(a.name())
                    .append(".Outcome.").append(outcome).append(", ").append(target(wire)).append(')');
        }
        return out.append(')').toString();
    }

    /** The wire drawn for one {@code (activity, outcome)} pair, or null when that outcome goes nowhere. */
    private static FlowEdge edgeFor(ActivityFlow flow, String from, String outcome) {
        for (FlowEdge e : flow.edges()) {
            if (e.from().equals(from) && e.outcomeOrNext().equals(outcome)) return e;
        }
        return null;
    }

    /** The quoted next-node name a wire leads to. */
    private static String target(FlowEdge wire) {
        return '"' + wire.to() + '"';
    }

    /**
     * The initial editable stub for one activity's {@code Activity} subclass.
     *
     * <p>The SDK's {@code ACTIVITY_STUB} template under the name the user chose: the class it declares is
     * called {@code ActivityStub} over there and is renamed here, which is why the same template serves every
     * activity. Two tokens carry what is this activity's own — the constants of its {@code Outcome} enum, and
     * the {@code Activities} flag its {@code isEnabled()} reads. The {@code run()} body, the javadoc and the
     * absence of a constructor (the SDK's no-arg one names the activity after its class) are the template's.
     *
     * <p>A SEED file: written once, and the user's from that moment — with the one exception of
     * {@code Outcome}, which the flow editor keeps in step with what the canvas can route on.
     */
    // Public because recovery needs it too: an activity's isEnabled() is generated against that activity's own
    // flag, so this is the only thing that can say what the stub *should* look like when repairing a mangled one.
    public String generateStubSource(ActivityDefinition a) throws IOException {
        return render("ACTIVITY_STUB", a.name(), Map.of(
                "OUTCOMES", String.join(", ", a.allOutcomes()),
                "ENABLED", "Activities." + a.name()));
    }
}
