package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.ActivitiesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                ensureStubs(newConfig);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save activities: " + e.getMessage(), e);
            }
            state.setActivities(newConfig);
            eventBus.publish(new ActivitiesChangedEvent(newConfig));
        });
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
                 * manage via Project &rarr; Manage Activities. Values are loaded at startup from
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

    /** Builds the source of the generated {@code ActivityRegistry} class. */
    String generateRegistrySource(ActivitiesConfig cfg) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < cfg.activities().size(); i++) {
            String name = cfg.activities().get(i).name();
            entries.append("            new ").append(name).append("()");
            entries.append(i < cfg.activities().size() - 1 ? ",\n" : "\n");
        }
        String activitiesImport = cfg.activities().isEmpty()
                ? "" : "import com." + config.packageName() + ".activities.*;\n";
        return String.format("""
                package com.%s;

                import com.botmaker.sdk.api.bot.Activity;
                %simport java.util.List;

                /**
                 * The activities this bot can run. GENERATED by BotMaker Studio — do not edit by hand;
                 * manage via Project &rarr; Manage Activities. The macro loop iterates {@link #ALL} and runs
                 * each activity whose enable flag is set.
                 */
                public final class ActivityRegistry {

                    public static final List<Activity> ALL = List.of(
                %s    );

                    private ActivityRegistry() {}
                }
                """, config.packageName(), activitiesImport, entries.toString().stripTrailing());
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
                public class %2$s extends Activity {

                    @Override
                    public boolean isEnabled() {
                        return Activities.%2$s;
                    }

                    @Override
                    public void run() {
                        // TODO: how to do %2$s
                    }
                }
                """, config.packageName(), a.name());
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
