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
import com.botmaker.studio.project.settings.SettingsModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

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
 * <p><b>Two models, selected once.</b> Everything above describes a {@link SettingsModel#JSON} project — every
 * project created before 2026-08. A {@link SettingsModel#JAVA} project instead generates {@code Settings.java}
 * (values inlined as Java literals) and {@code Setting.java} (the annotation Studio reads them back from), and
 * reads no JSON at run time at all; {@code activities.json} survives holding only the canvas model. The
 * discriminator is {@link ActivitiesConfig#settingsModel()} and it is consulted at three edges — writing
 * ({@link #update}), naming the class a stub reads its flag from ({@link #generateStubSource}), and loading —
 * never inside a generator. {@link #generateSource} is the legacy generator, kept whole and untouched.
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
        // Read on the caller's thread, before the async body: moveArchivedStubs needs to know which side of
        // the archive line each activity was on, and state is not for background callers.
        ActivitiesConfig previous = current();
        return CompletableFuture.runAsync(() -> {
            try {
                newConfig.write(config.resourcesRoot());
                if (newConfig.settingsModel().isJava()) writeSettingsClasses(newConfig);
                else writeActivitiesClass(newConfig);
                writeRegistryClass(newConfig);
                writeDriverClass(newConfig);
                moveArchivedStubs(previous, newConfig);
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

    /**
     * Writes the generated {@code Activities.java}.
     *
     * <p>Written even when it would hold no fields at all. It used to be <em>deleted</em> in that case, which
     * is fine for a project that has never had an activity and wrong for one that has just archived its last:
     * anything still saying {@code import com.<pkg>.Activities;} — a scaffold file, a hand-written helper —
     * stops compiling the moment the class evaporates. An empty class costs nothing and cannot break a build.
     */
    private void writeActivitiesClass(ActivitiesConfig cfg) throws IOException {
        Path file = config.activitiesSourceFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, generateSource(cfg));
    }

    /**
     * Writes the generated {@code Settings.java} and its {@code Setting.java} annotation — the whole store for
     * a {@link SettingsModel#JAVA} project.
     *
     * <p>Both are written every time, the annotation included: its content never changes, so rewriting it
     * costs one file write and means a project that has lost it (a bad merge, a stray delete) is repaired by
     * the next save rather than left with a {@code Settings.java} that no longer compiles.
     */
    private void writeSettingsClasses(ActivitiesConfig cfg) throws IOException {
        Path settings = config.settingsSourceFile();
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, SettingsClassWriter.settingsSource(config.packageName(), cfg.allSettings()));
        Files.writeString(config.settingAnnotationSourceFile(),
                SettingsClassWriter.annotationSource(config.packageName()));
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

    /**
     * Moves each activity's stub to whichever side of the archive line its definition is now on: an archived
     * activity's {@code activities/<Name>.java} goes to {@link ProjectConfig#archivedActivitiesDir()}, and a
     * restored one comes back.
     *
     * <p>This is what makes archiving safe rather than merely quiet. An archived activity generates no
     * {@code Activities.<Name>} field any more, so its stub's {@code isEnabled()} would not compile — the very
     * breakage that once ruled removal out. Moving the file keeps the project green <em>and</em> keeps the
     * user's {@code run()} body, so restoring returns the activity exactly as it was written rather than as a
     * fresh stub. Runs before {@link #ensureStubs}, so a restore is a move back and not a blank rewrite.
     *
     * <p><b>The move always empties the side it came from.</b> This guarded on {@code Files.exists(to)} and
     * skipped, which sounds cautious and is the opposite: the source was then never removed, so the file
     * existed on <em>both</em> sides at once, and every later archive or restore hit the same guard and did
     * nothing — the state froze and no error was ever raised. The side a file is moving <em>from</em> is by
     * definition the one the user last edited on, so it wins and overwrites whatever stale copy is waiting at
     * the destination.
     */
    private void moveArchivedStubs(ActivitiesConfig previous, ActivitiesConfig cfg) throws IOException {
        Path live = config.activitiesPackageDir();
        Path attic = config.archivedActivitiesDir();
        for (ActivityDefinition a : cfg.activities()) {
            Path from = (a.archived() ? live : attic).resolve(a.name() + ".java");
            Path to = (a.archived() ? attic : live).resolve(a.name() + ".java");
            if (!Files.exists(from)) {
                // A restore whose archived source has gone missing: ensureStubs is about to write a blank one,
                // which is the right recovery but a silent loss of a run() body if it goes unsaid. Only for an
                // activity that really was archived — a brand-new one has no archived source by definition.
                if (!a.archived() && !Files.exists(to) && wasArchived(previous, a.name())) {
                    System.err.println("Activities: no archived source for '" + a.name() + "' at " + from
                            + "; restoring it as an empty stub.");
                }
                continue;
            }
            Files.createDirectories(to.getParent());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Whether {@code name} was on the archived side of {@code previous} — i.e. this save is a restore. */
    private static boolean wasArchived(ActivitiesConfig previous, String name) {
        return previous.archivedActivities().stream().anyMatch(a -> a.name().equals(name));
    }

    /**
     * Creates a subclass stub for each live activity if it does not already exist (never overwrites user
     * edits). Archived activities are skipped — {@link #moveArchivedStubs} has just taken their file away, and
     * writing it straight back is the one thing that would undo the archive.
     */
    private void ensureStubs(ActivitiesConfig cfg) throws IOException {
        if (cfg.liveActivities().isEmpty()) return;
        Path dir = config.activitiesPackageDir();
        Files.createDirectories(dir);
        for (ActivityDefinition a : cfg.liveActivities()) {
            Path stub = dir.resolve(a.name() + ".java");
            if (!Files.exists(stub)) {
                Files.writeString(stub, generateStubSource(a));
            }
        }
    }

    /**
     * The source files, other than {@code activity}'s own stub, that read a generated {@code Activities} field
     * archiving {@code activity} would take away — an empty list when archiving it is safe.
     *
     * <p>Archiving drops the activity's enable flag and every {@code <Name>_<param>} from
     * {@link ActivitiesConfig#allVariables()}, so those fields stop being generated. Its own stub is moved out
     * of the source tree and is fine; a <em>sibling</em> activity that read one of them is left referring to
     * something that no longer exists, and the first the user hears of it is a compile error in a file they
     * didn't touch. Better to say so before the archive than after.
     *
     * <p>Deliberately a textual scan, not a resolved one: the reference is always the literal
     * {@code Activities.<field>} (the class is generated, the field names are ours), and this has to answer for
     * files that may not currently parse. A commented-out mention is a false positive — a warning that costs a
     * moment beats an archive that costs a build.
     */
    public List<String> archiveBlockers(ActivityDefinition activity) {
        // In the java model the only field archiving takes away is the enable flag: settings are project-wide,
        // so one merely *tagged* with this activity outlives it and its use sites keep compiling.
        String holder = settingsHolder();
        List<String> fields = new ArrayList<>();
        fields.add(activity.enabledVariable().name());
        if (!current().settingsModel().isJava()) {
            for (ActivityVariable p : activity.params()) fields.add(activity.paramFieldName(p));
        }

        Path ownStub = config.activitiesPackageDir().resolve(activity.name() + ".java");
        List<String> blockers = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(config.sourceRoot())) {
            for (Path file : sources.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.equals(ownStub)) continue;
                String text = Files.readString(file);
                for (String field : fields) {
                    if (mentions(text, holder, field)) {
                        blockers.add(file.getFileName().toString());
                        break;
                    }
                }
            }
        } catch (IOException e) {
            // Can't read the tree: don't invent a blocker out of an I/O failure, let the archive proceed.
            System.err.println("Activities: could not scan for references to '" + activity.name()
                    + "': " + e.getMessage());
        }
        return blockers;
    }

    /**
     * The generated class a bot reads its values from — {@code Settings} for a
     * {@link SettingsModel#JAVA} project, {@code Activities} for a legacy one. The one place the two names
     * are chosen between, so nothing downstream branches on the model itself.
     */
    private String settingsHolder() {
        return current().settingsModel().isJava() ? SettingsClassWriter.SETTINGS_CLASS : "Activities";
    }

    /** True when {@code text} contains {@code <holder>.<field>} as a whole identifier, not as a prefix. */
    private static boolean mentions(String text, String holder, String field) {
        String needle = holder + "." + field;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            int after = at + needle.length();
            if (after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after))) return true;
        }
        return false;
    }

    /** Builds the source of the generated {@code Activities} class. */
    String generateSource(ActivitiesConfig cfg) {
        StringBuilder fields = new StringBuilder();
        StringBuilder inits = new StringBuilder();
        boolean needsTime = false;
        boolean needsDate = false;
        boolean needsChoices = false;
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
            needsChoices |= a.type() == ActivityType.MULTI_CHOICE;
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
                %s%s%s
                    private Activities() {}
                }
                """, config.packageName(), fields.toString().stripTrailing(),
                ActivitiesConfig.FILE_NAME, ActivitiesConfig.FILE_NAME, inits,
                needsTime ? TIME_HELPER : "", needsDate ? DATE_HELPER : "",
                needsChoices ? CHOICES_HELPER : "");
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
        String activitiesImport = activitiesImportFor(cfg);
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
     * The {@code import com.<pkg>.activities.*;} line for a generated file, or nothing when that package has
     * no source in it.
     *
     * <p>Keyed on {@link ActivitiesConfig#liveActivities()}, never on {@code activities()}: an archived
     * activity's file has been moved out of the source tree, so a project whose activities are <em>all</em>
     * archived has an empty {@code activities} package — and an import-on-demand of a package with nothing in
     * it does not compile. Counting archived definitions here is what made archiving the last activity break
     * the build.
     */
    private String activitiesImportFor(ActivitiesConfig cfg) {
        return cfg.liveActivities().isEmpty()
                ? "" : "import com." + config.packageName() + ".activities.*;\n";
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
     * <p>A run ends when {@code step} returns null, which happens two ways: the outcome the activity reported
     * has no wire leaving it, or the node isn't in the switch at all. There is no terminal node — an unwired
     * outcome <em>is</em> the stop. Cycles are legal, that being how a bot repeats, so the step budget is what
     * stops a flow that loops with no way out. It counts transitions <em>between</em> activities, which nothing
     * previously bounded; {@code Watchdog} covers being stuck inside one.
     */
    String generateDriverSource(ActivitiesConfig cfg) {
        List<ActivityDefinition> reachable = cfg.orderedActivities();
        ActivityFlow flow = cfg.flow();
        String start = flow.resolvedStart(reachable.stream().map(ActivityDefinition::name).toList());

        StringBuilder cases = new StringBuilder();
        for (ActivityDefinition a : reachable) {
            cases.append(driverCase(a, flow));
        }
        String activitiesImport = activitiesImportFor(cfg);
        return String.format("""
                package com.%s;

                import com.botmaker.sdk.api.Debug;
                import com.botmaker.sdk.api.bot.Bot;
                import com.botmaker.sdk.api.bot.PopupGuard;
                import com.botmaker.sdk.api.bot.Watchdog;
                import com.botmaker.sdk.api.interaction.Wait;
                %s
                /**
                 * Walks the Activity Flow drawn in BotMaker Studio. GENERATED — do not edit by hand; manage via
                 * Project &rarr; Activity Flow.
                 *
                 * <p>Runs the current activity, then picks the next one from the outcome it reported. The run
                 * ends when the reported outcome has no wire leaving it.
                 */
                public final class FlowDriver {

                    /**
                     * How many activities one run may hand off to before giving up. A flow is allowed to loop —
                     * that is how a bot repeats — so this is what separates &quot;farming all night&quot; from a
                     * cycle with no way out. Change it in Project &rarr; Activity Flow.
                     */
                    private static final int MAX_STEPS = %d;

                    /**
                     * How long to pause between two activities, in milliseconds. A flow may loop, so an
                     * activity that finishes in milliseconds can hand straight back to itself and never let go
                     * of the mouse — leaving no gap in which to stop the bot. This is that gap. 0 disables it.
                     * Change it in Project &rarr; Activity Flow.
                     */
                    private static final int STEP_DELAY_MS = %d;

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
                            // After the hand-off, not before it: this separates two activities rather than
                            // delaying the first, and a run that has just ended shouldn't sit here waiting.
                            if (node != null && STEP_DELAY_MS > 0) {
                                Wait.milliseconds(STEP_DELAY_MS);
                            }
                        }
                        Bot.stop();
                    }

                    /** The next node after {@code node}, or null to end the run. */
                    private static String step(String node) {
                %s    }

                    private FlowDriver() {}
                }
                """, config.packageName(), activitiesImport, flow.maxSteps(), flow.stepDelayMs(),
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
        FlowEdge fallthrough = edgeFor(flow, a.name(), FlowEdge.NEXT_OUTCOME);
        out.append("                if (!").append(constant).append(".active()) return ")
                .append(fallthrough == null ? "null" : target(fallthrough)).append(";\n");
        // Emitted for every activity, not just the ones opting out: PopupGuard.enabled is process-global, so
        // an activity that said nothing would inherit whatever the previous one left it set to.
        out.append("                PopupGuard.enabled(").append(a.popupCheck()).append(");\n");
        // After the active() check, not before: there is nothing to go home for if the activity won't run.
        if (a.goHome()) out.append("                GoHome.INSTANCE.execute();\n");
        out.append("                switch (").append(constant).append(".execute()) {\n");
        for (String outcome : a.allOutcomes()) {
            FlowEdge wire = edgeFor(flow, a.name(), outcome);
            if (wire == null) continue;   // nothing drawn for it: the default below ends the run
            out.append("                    case ").append(outcome).append(": return ")
                    .append(target(wire)).append(";\n");
        }
        out.append("                    default: return null;   // nothing wired — the run ends here\n");
        out.append("                }\n");
        return out.toString();
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
     * Builds the initial editable stub for one activity's {@code Activity} subclass.
     *
     * <p>No constructor: {@code Activity}'s no-arg constructor names the activity after its own class, so the
     * only thing the generated stub asks the user for is {@link #run()} — {@code isEnabled()} is wiring to the
     * generated {@code Activities} flag and the Studio marks it read-only ({@code MethodLock.FULL}).
     */
    // Public because recovery needs it too: an activity's isEnabled() is generated against that activity's own
    // flag, so this is the only thing that can say what the stub *should* look like when repairing a mangled one.
    public String generateStubSource(ActivityDefinition a) {
        String holder = settingsHolder();
        // Where the rest of this activity's configuration is to be found — the sentence differs because the
        // models differ: a java-model project has no per-activity params to point at, it has a tag.
        String elsewhere = current().settingsModel().isJava()
                ? "the bot's other settings are on {@code Settings}, the ones for this activity tagged "
                        + "&quot;%1$s&quot;".formatted(a.name())
                : "any config params are {@code Activities.%1$s_<param>}".formatted(a.name());
        return String.format("""
                package com.%1$s.activities;

                import com.%1$s.%4$s;
                import com.botmaker.sdk.api.bot.Activity;

                /**
                 * Activity: %2$s. Fill in {@link #run()} with how to do it — that method is the whole point of
                 * this file, and this file is yours to edit (BotMaker Studio creates it once and never
                 * overwrites it). {@link #isEnabled()} is wired to the enable flag {@code %4$s.%2$s} and
                 * is managed for you; %5$s.
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
                        return %4$s.%2$s;
                    }

                    @Override
                    public Outcome run() {
                        // TODO: how to do %2$s
                        return Outcome.NEXT;
                    }
                }
                """, config.packageName(), a.name(), String.join(", ", a.allOutcomes()), holder, elsewhere);
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

    /**
     * Generated helper: read a multiple-choice value as a list of strings.
     *
     * <p>Total, like the two above: a missing key, a {@code null}, or a node of the wrong shape (a bare string
     * where an array belongs — what an activities file hand-edited by its user tends to contain) all read as
     * "nothing selected". The bot starts either way.
     */
    private static final String CHOICES_HELPER = """

            private static java.util.List<String> parseChoices(JsonNode n) {
                    java.util.List<String> chosen = new java.util.ArrayList<>();
                    if (n != null && n.isArray()) {
                        for (JsonNode each : n) chosen.add(each.asText(""));
                    }
                    return java.util.List.copyOf(chosen);
                }
        """;
}
