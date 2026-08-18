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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Orchestrates the project's <em>activities</em> — the game tasks a bot performs — and the project-wide
 * {@link ActivityVariable variables} they read. Persistence + generation:
 * <ul>
 *   <li>{@code src/main/resources/activities.json} — the whole model, values included, read at runtime</li>
 *   <li>generated {@code Activities.java} — {@code public static final} typed fields (each live activity's
 *       enable flag, then every variable) loaded from that JSON at startup</li>
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
        // Read on the caller's thread, before the async body: moveArchivedStubs needs to know which side of
        // the archive line each activity was on, and state is not for background callers.
        ActivitiesConfig previous = current();
        return CompletableFuture.runAsync(() -> {
            try {
                newConfig.write(config.resourcesRoot());
                writeActivitiesClass(newConfig);
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
        // The only field archiving takes away is the enable flag. Variables are project-wide, so one merely
        // *tagged* with this activity outlives it and its use sites keep compiling.
        String holder = "Activities";
        List<String> fields = new ArrayList<>();
        fields.add(activity.enabledVariable().name());

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

    /** True when {@code text} contains {@code <holder>.<field>} as a whole identifier, not as a prefix. */
    private static boolean mentions(String text, String holder, String field) {
        String needle = holder + "." + field;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            int after = at + needle.length();
            if (after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after))) return true;
        }
        return false;
    }

    /**
     * Builds the source of the generated {@code Activities} class: one {@code public static final} field per
     * {@link ActivitiesConfig#allVariables() referenceable value}, read from {@code activities.json} at
     * startup.
     *
     * <p><b>Blank final, assigned in the static block — never an inline initializer.</b>
     * {@code public static final boolean MINING = true;} would make the field a JLS §4.12.4 <em>constant
     * variable</em>, which javac folds into every use site: {@code while (Activities.MINING) { … }} with a
     * constant-{@code false} flag becomes an <b>{@code unreachable statement} compile error</b>, so a bot
     * would stop compiling because its user unticked a box. Loading at runtime is what keeps that from
     * happening, and it is the reason this class reads a file at all rather than being generated with the
     * values baked in.
     *
     * <p>Only the helpers a project actually uses are emitted, de-duplicated on their text — {@code Key} and
     * {@code Direction} both want {@code constant(…)}, and a class cannot declare it twice.
     */
    String generateSource(ActivitiesConfig cfg) {
        StringBuilder fields = new StringBuilder();
        StringBuilder inits = new StringBuilder();
        // Insertion-ordered so the generated file is stable: a set that reordered on rehash would produce a
        // different file from the same project, and every save would show up as a diff.
        LinkedHashSet<String> helpers = new LinkedHashSet<>();
        for (ActivityVariable v : cfg.allVariables()) {
            if (!v.description().isBlank()) {
                fields.append("    /** ").append(v.description().replace("*/", "*\\/")).append(" */\n");
            }
            fields.append("    public static final ").append(VariableWire.javaType(v.type()))
                    .append(' ').append(v.name()).append(";\n");
            inits.append("        ").append(v.name()).append(" = ")
                    .append(VariableWire.loadExpression(v.type(), v.name())).append(";\n");
            VariableWire.Helper helper = VariableWire.helper(v.type().type());
            helpers.addAll(helper.shared());
            helpers.add(helper.source());
        }
        return String.format("""
                package com.%s;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;

                import java.io.InputStream;
                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;

                /**
                 * Every configured value this bot reads. GENERATED by BotMaker Studio — do not edit by hand;
                 * manage via Project &rarr; Parameters. Values are loaded at startup from
                 * {@code /activities.json} on the classpath. A missing file, a missing key, and a value that
                 * will not parse all fall back to the type's default — a bot never fails to start because of
                 * its own configuration file.
                 */
                public final class Activities {

                    private static final Map<String, List<String>> VALUES = load();

                %s
                    static {
                %s    }

                    private static Map<String, List<String>> load() {
                        Map<String, List<String>> values = new HashMap<>();
                        try (InputStream in = Activities.class.getResourceAsStream("/%s")) {
                            if (in != null) {
                                JsonNode root = new ObjectMapper().readTree(in);
                                for (JsonNode a : root.path("activities")) {
                                    values.put(a.path("name").asText(),
                                            List.of(a.path("enabled").asText("false")));
                                }
                                for (JsonNode v : root.path("variables")) {
                                    List<String> items = new ArrayList<>();
                                    for (JsonNode item : v.path("value")) items.add(item.asText(""));
                                    values.put(v.path("name").asText(), items);
                                }
                            }
                        } catch (Exception e) {
                            // Degrade gracefully: keep the defaults rather than crash the bot at startup.
                            System.err.println("Activities: could not load /%s (" + e.getMessage()
                                    + "); using defaults.");
                        }
                        return values;
                    }

                    private static String one(String name) {
                        List<String> stored = VALUES.getOrDefault(name, List.of());
                        return stored.isEmpty() ? "" : stored.get(0);
                    }

                    private static <T> List<T> many(String name, java.util.function.Function<String, T> of) {
                        List<T> out = new ArrayList<>();
                        for (String item : VALUES.getOrDefault(name, List.of())) out.add(of.apply(item));
                        return List.copyOf(out);
                    }

                %s
                    private Activities() {}
                }
                """, config.packageName(), fields.toString().stripTrailing(), inits,
                ActivitiesConfig.FILE_NAME, ActivitiesConfig.FILE_NAME,
                String.join("\n", helpers).stripTrailing());
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
        String holder = "Activities";
        // Where the rest of this activity's configuration is to be found. Not "its params": it has none —
        // a variable is the project's and merely tagged with this activity, which is what the sentence says.
        String elsewhere = ("the bot's variables are on {@code Activities}, the ones for this activity "
                + "tagged &quot;%1$s&quot;").formatted(a.name());
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



}
