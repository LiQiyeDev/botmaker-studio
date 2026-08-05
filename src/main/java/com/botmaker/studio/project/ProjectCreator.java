package com.botmaker.studio.project;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.botmaker.studio.config.Constants.PROJECTS_ROOT;

/**
 * Scaffolds a new user project as a standard Maven project.
 * The {@code pom.xml} is generated programmatically via {@link MavenService#writePom}
 * (Maven Model API), not from a build-file string.
 */
public class ProjectCreator {

    public void createProject(String projectName) throws IOException {
        createProject(projectName, "");
    }

    public void createProject(String projectName, String sdkVersion) throws IOException {
        createProject(projectName, sdkVersion, new StudioProjectSettings.Resolution(1920, 1080));
    }

    public void createProject(String projectName, String sdkVersion,
                              StudioProjectSettings.Resolution referenceResolution) throws IOException {
        createProject(projectName, sdkVersion, referenceResolution, ProjectTemplate.EMPTY);
    }

    /**
     * Creates a new project, pinning the BotMaker SDK to {@code sdkVersion}
     * (blank → {@link MavenService#SDK_FALLBACK_VERSION}) and seeding its standard capture resolution
     * {@code referenceResolution} (null leaves it unseeded — auto-seeded from the window on first capture).
     * {@code template} chooses the starting source files.
     */
    public void createProject(String projectName, String sdkVersion,
                              StudioProjectSettings.Resolution referenceResolution,
                              ProjectTemplate template) throws IOException {
        validateProjectName(projectName);

        ProjectConfig cfg = ProjectConfig.forProject(projectName, PROJECTS_ROOT);
        Path projectPath = cfg.projectPath();

        if (Files.exists(projectPath.resolve("pom.xml"))) {
            throw new IllegalArgumentException("Project '" + projectName + "' already exists");
        }

        System.out.println("------------------------------------------------");
        System.out.println("Creating Project: " + projectName);
        System.out.println("Location: " + projectPath);
        System.out.println("------------------------------------------------");

        try {
            // 1. Standard Maven directory layout
            Files.createDirectories(projectPath.resolve("src/main/java"));
            Files.createDirectories(projectPath.resolve("src/main/resources"));
            Files.createDirectories(projectPath.resolve("src/test/java"));
            Files.createDirectories(projectPath.resolve("src/test/resources"));

            // 2. pom.xml via the Maven Model API
            System.out.println("1. Generating pom.xml...");
            MavenService.writePom(projectPath, cfg, sdkVersion);

            // 3. Package + source files (per template)
            System.out.println("2. Creating source files...");
            Path srcPath = projectPath.resolve("src/main/java/com/" + cfg.packageName());
            Files.createDirectories(srcPath);
            writeSources(srcPath, sourcesFor(template, cfg.className(), cfg.packageName()));

            // 4. Built-in default image template so freshly-dropped vision blocks reference a real file.
            createDefaultTemplate(cfg.imagesRoot());

            // 5. Seed settings.json (the chosen template + the standard capture resolution) and mirror the
            //    resolution into botmaker-project.properties, so the editor snaps captures to it and the
            //    generated bot's runtime scaling defaults to it.
            seedSettings(cfg, referenceResolution, template);

            // 5b. Seed the runtime tuning — delays, confidence, real input, and background isolation (a private
            //     :N display). A game bot starts with real input on; that is the whole difference between
            //     GAME_DEFAULTS and DEFAULTS, and it used to be the difference between the two generated
            //     BotSettings.java files. Written explicitly rather than left to the absent-key defaults so the
            //     dialogs show a concrete state and the SDK and Studio agree from the first run.
            BotSettings.write(cfg.resourcesRoot(),
                    template == ProjectTemplate.GAME_BOT ? BotSettings.GAME_DEFAULTS : BotSettings.DEFAULTS);

            // 6. Initialize local project history (linear VCS) with an initial commit.
            new ProjectVcs(projectPath).init();

            System.out.println("------------------------------------------------");
            System.out.println("SUCCESS: Project created at " + projectPath);
            System.out.println("------------------------------------------------");
        } catch (Exception e) {
            System.err.println("!!! ERROR during project creation !!!");
            e.printStackTrace();
            throw new IOException("Failed to create project: " + e.getMessage(), e);
        }
    }

    /**
     * Writes {@code settings.json} — the originating {@code template} (which {@link FileRole} and
     * {@code ProjectRepair} read to tell scaffolding from user code) plus the editor's standard/reference
     * resolution — and mirrors the resolution into {@code botmaker-project.properties}
     * ({@code capture.width}/{@code capture.height}) so the generated bot's runtime resolution scaling
     * ({@code ProjectDefaults}/{@code ResolutionScaler}) defaults to the same size.
     *
     * <p>Settings are written even for a null resolution (left to auto-seed from the window on first capture) —
     * the template must be recorded regardless, or the project is indistinguishable from a legacy one.
     */
    static void seedSettings(ProjectConfig cfg, StudioProjectSettings.Resolution resolution,
                             ProjectTemplate template) throws IOException {
        StudioProjectSettings.empty()
                .withTemplate(template)
                .withReferenceResolution(resolution)
                .write(cfg.resourcesRoot());
        if (resolution != null) {
            writeCaptureProperties(cfg.resourcesRoot(), resolution);
        }
    }

    /** Writes/updates {@code capture.width}/{@code capture.height} in {@code botmaker-project.properties}. */
    public static void writeCaptureProperties(Path resourcesDir, StudioProjectSettings.Resolution resolution)
            throws IOException {
        Files.createDirectories(resourcesDir);
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        java.util.Properties props = new java.util.Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { props.load(in); }
        }
        props.setProperty(ProjectProperties.KEY_CAPTURE_WIDTH, Integer.toString(resolution.width()));
        props.setProperty(ProjectProperties.KEY_CAPTURE_HEIGHT, Integer.toString(resolution.height()));
        try (var out = Files.newOutputStream(file)) {
            props.store(out, "BotMaker project defaults (standard capture resolution)");
        }
    }

    /**
     * Writes/updates the {@code launch.target} key in {@code botmaker-project.properties} — the spec the SDK's
     * {@code Bot.start} start-up step ({@code Target.startIfNotRunning()}) launches at runtime. Accepts a spec
     * in the SDK's {@code LaunchTarget} form ({@code steam:<id>} / {@code epic:<name>} / {@code exe:<path>} /
     * {@code emu-app:<pkg>@<instance>}); a null/blank {@code spec} removes the key (no configured target).
     * Preserves the other properties (capture resolution/source) already in the file.
     */
    public static void writeLaunchTarget(Path resourcesDir, String spec) throws IOException {
        Files.createDirectories(resourcesDir);
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        java.util.Properties props = new java.util.Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { props.load(in); }
        }
        if (spec == null || spec.isBlank()) {
            props.remove(ProjectProperties.KEY_LAUNCH_TARGET);
        } else {
            props.setProperty(ProjectProperties.KEY_LAUNCH_TARGET, spec.trim());
        }
        try (var out = Files.newOutputStream(file)) {
            props.store(out, "BotMaker project defaults");
        }
    }

    /**
     * The project's standard capture resolution from {@code botmaker-project.properties}, or {@code null} when
     * either key is absent or unparseable. The inverse of {@link #writeCaptureProperties}.
     *
     * <p>It reads the properties file rather than {@code settings.json} so a caller holding nothing but a
     * resources dir — {@link com.botmaker.studio.project.launch.QuickLaunch} — can ask, exactly as it already
     * does for {@code launch.target} and {@code session.isolated}. This is the size a background session's
     * nested display is created at, and therefore the screen resolution the game inside it sees.
     */
    public static StudioProjectSettings.Resolution readCaptureSize(Path resourcesDir) {
        try {
            int w = Integer.parseInt(String.valueOf(readKey(resourcesDir, ProjectProperties.KEY_CAPTURE_WIDTH)));
            int h = Integer.parseInt(String.valueOf(readKey(resourcesDir, ProjectProperties.KEY_CAPTURE_HEIGHT)));
            return (w > 0 && h > 0) ? new StudioProjectSettings.Resolution(w, h) : null;
        } catch (NumberFormatException e) {
            return null; // a hand-edited or newer-format value must not stop a launch
        }
    }

    /**
     * One key's trimmed value from {@code botmaker-project.properties}, or {@code null} when the key, the file
     * or the directory is absent (or the read fails). The single load path behind every {@code read…} below —
     * they were four copies of this same eight lines, each free to disagree about what a missing file means.
     */
    private static String readKey(Path resourcesDir, String key) {
        if (resourcesDir == null) return null;
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        if (!Files.exists(file)) return null;
        java.util.Properties props = new java.util.Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        String value = props.getProperty(key);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * The current {@code launch.target} spec from {@code botmaker-project.properties}, or {@code null} when the
     * key (or the file) is absent. The inverse of {@link #writeLaunchTarget} — used to seed the Launch Target
     * editor with what's already configured.
     */
    public static String readLaunchTarget(Path resourcesDir) {
        return readKey(resourcesDir, ProjectProperties.KEY_LAUNCH_TARGET);
    }

    /**
     * The current {@code capture.source} spec, or {@code null} when unset. The inverse of
     * {@link #writeCaptureSource}, in the SDK's grammar ({@code desktop}, {@code monitor:<i>},
     * {@code window:<t>}, {@code emulator:<instance>}).
     *
     * <p>Read by the remote pilot, which routes its preview and its Interact gestures at whatever this names —
     * so that when the bot is looking at an emulator, so is the phone. Recognise the emulator form with
     * {@link ProjectProperties#emulatorInstanceOf}, never by re-spelling the prefix.
     */
    public static String readCaptureSource(Path resourcesDir) {
        return readKey(resourcesDir, ProjectProperties.KEY_CAPTURE_SOURCE);
    }

    /**
     * Writes/updates the {@code capture.source} key in {@code botmaker-project.properties} — the default
     * {@code CaptureSource} the generated bot's no-argument vision/click/OCR calls target (read by the SDK's
     * {@code ProjectDefaults}/{@code Source}). Accepts a spec in the SDK's form, e.g. {@code emulator:<instance>}
     * for an Android emulator instance; a null/blank {@code spec} removes the key. Preserves the other
     * properties (capture resolution / launch target) already in the file.
     */
    public static void writeCaptureSource(Path resourcesDir, String spec) throws IOException {
        Files.createDirectories(resourcesDir);
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        java.util.Properties props = new java.util.Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { props.load(in); }
        }
        if (spec == null || spec.isBlank()) {
            props.remove(ProjectProperties.KEY_CAPTURE_SOURCE);
        } else {
            props.setProperty(ProjectProperties.KEY_CAPTURE_SOURCE, spec.trim());
        }
        try (var out = Files.newOutputStream(file)) {
            props.store(out, "BotMaker project defaults");
        }
    }

    /**
     * Writes/updates the {@code debug} key in {@code botmaker-project.properties} — the initial state of the
     * generated bot's global debug-output switch (the SDK's {@code api.Debug}, which all {@code [Bot]}/
     * {@code [Game]}/{@code [Target]}/{@code [Activity]} and vision traces consult). {@code true}/{@code false};
     * a {@code null} removes the key (bot falls back to its default, on). Preserves the other properties.
     */
    public static void writeDebug(Path resourcesDir, Boolean enabled) throws IOException {
        Files.createDirectories(resourcesDir);
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        java.util.Properties props = new java.util.Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { props.load(in); }
        }
        if (enabled == null) {
            props.remove(ProjectProperties.KEY_DEBUG);
        } else {
            props.setProperty(ProjectProperties.KEY_DEBUG, Boolean.toString(enabled));
        }
        try (var out = Files.newOutputStream(file)) {
            props.store(out, "BotMaker project defaults");
        }
    }

    /**
     * The current {@code debug} setting from {@code botmaker-project.properties}: {@code true} unless the key is
     * explicitly {@code false}/{@code 0}/{@code no}/{@code off}. The inverse of {@link #writeDebug} — mirrors the
     * SDK's default-on semantics ({@code api.Debug}) so the Studio toggle shows the state the bot will run with.
     */
    public static boolean readDebug(Path resourcesDir) {
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        if (!Files.exists(file)) return true;
        java.util.Properties props = new java.util.Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return true;
        }
        String spec = props.getProperty(ProjectProperties.KEY_DEBUG);
        if (spec == null || spec.isBlank()) return true;
        return switch (spec.trim().toLowerCase()) {
            case "false", "0", "no", "off" -> false;
            default -> true;
        };
    }

    /**
     * Writes the {@code session.isolated} key in {@code botmaker-project.properties} — whether the bot (and the
     * Studio Launch buttons) run the game in a private nested display ({@code :N}) instead of the real
     * {@code :0} desktop. Always writes a definite {@code true}/{@code false} (the toggle is a deliberate
     * choice, not a tri-state like {@code launch.target}). The SDK's {@code SessionBootstrap} reads the same key.
     */
    public static void writeSessionIsolated(Path resourcesDir, boolean isolated) throws IOException {
        writeProjectKey(resourcesDir, ProjectProperties.KEY_SESSION_ISOLATED, Boolean.toString(isolated));
    }

    /**
     * Writes the {@code session.backend} key — which nested display hosts an isolated run
     * ({@code gamescope}/{@code xephyr}), or removes the key for the SDK's kind-driven choice. Studio's own
     * Launch buttons read this file (Studio doesn't depend on the SDK), which is why the dialog persists here as
     * well as into the generated source.
     *
     * <p>A blank/{@code auto} backend <b>removes</b> the key rather than writing the string {@code "auto"}:
     * absent is what the SDK reads as "choose by kind", and it is the state a project that never pinned a
     * backend is in — writing a value for it would make "never chose" and "chose automatic" different bytes.
     */
    public static void writeSessionBackend(Path resourcesDir, String backendId) throws IOException {
        boolean auto = backendId == null || backendId.isBlank() || "auto".equalsIgnoreCase(backendId.trim());
        writeProjectKey(resourcesDir, ProjectProperties.KEY_SESSION_BACKEND, auto ? null : backendId.trim());
    }

    /** The current {@code session.backend} value, or {@code null} when unset (the kind-driven default). */
    public static String readSessionBackend(Path resourcesDir) {
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        if (!Files.exists(file)) return null;
        java.util.Properties props = new java.util.Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        String spec = props.getProperty(ProjectProperties.KEY_SESSION_BACKEND);
        return spec == null || spec.isBlank() ? null : spec.trim();
    }

    /**
     * Sets (or, for a {@code null} value, removes) one key in {@code botmaker-project.properties}, preserving
     * every other key. The load-modify-store dance was copied per key; one copy is enough.
     */
    private static void writeProjectKey(Path resourcesDir, String key, String value) throws IOException {
        writeProjectKeys(resourcesDir, java.util.Collections.singletonMap(key, value));
    }

    /**
     * Sets (or, for a {@code null} value, removes) several keys at once, preserving every other key. One
     * load-modify-store for the lot — {@link BotSettings#write} sets nine of them, and writing them one at a
     * time would reparse and rewrite the file nine times.
     */
    static void writeProjectKeys(Path resourcesDir, java.util.Map<String, String> values) throws IOException {
        Files.createDirectories(resourcesDir);
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        java.util.Properties props = readProjectProperties(resourcesDir);
        for (java.util.Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null) {
                props.remove(e.getKey());
            } else {
                props.setProperty(e.getKey(), e.getValue());
            }
        }
        try (var out = Files.newOutputStream(file)) {
            props.store(out, "BotMaker project defaults");
        }
    }

    /**
     * The project's {@code botmaker-project.properties}, or an empty set when it is absent or unreadable —
     * every caller here treats a missing key as its own default, so an unreadable file is the same as an empty
     * one rather than an error.
     */
    static java.util.Properties readProjectProperties(Path resourcesDir) {
        java.util.Properties props = new java.util.Properties();
        if (resourcesDir == null) {
            return props;
        }
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
                // unreadable — same as absent
            }
        }
        return props;
    }

    /**
     * The current {@code session.isolated} setting: {@code true} unless the key is explicitly
     * {@code false}/{@code 0}/{@code no}/{@code off} (matching {@link ProjectProperties#sessionIsolated()} and
     * the SDK's default-on isolation). The inverse of {@link #writeSessionIsolated} — used to seed the Launch
     * Target dialog's "Run in background" toggle and to gate the Studio Launch buttons' background path.
     */
    public static boolean readSessionIsolated(Path resourcesDir) {
        String spec = readKey(resourcesDir, ProjectProperties.KEY_SESSION_ISOLATED);
        if (spec == null) return true;
        return switch (spec.toLowerCase()) {
            case "false", "0", "no", "off" -> false;
            default -> true;
        };
    }

    /**
     * The starting sources for {@code template} as {@code fileName -> source}. The single source of truth for
     * both creation and {@link ProjectRepair} — a template's files are defined exactly once, here.
     */
    public static Map<String, String> sourcesFor(ProjectTemplate template, String className, String packageName) {
        return template == ProjectTemplate.GAME_BOT
                ? gameBotSources(className, packageName)
                : emptySources(className, packageName);
    }

    /** Writes each {@code fileName -> source} of a template into {@code srcPath}. */
    private static void writeSources(Path srcPath, Map<String, String> sources) throws IOException {
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Files.writeString(srcPath.resolve(e.getKey()), e.getValue());
        }
    }

    /**
     * The {@link ProjectTemplate#EMPTY} scaffold: a bare {@code main} that prints a greeting.
     *
     * <p>It used to carry a generated {@code BotSettings.java} too, whose {@code apply()} was the first
     * statement of {@code main}. The tuning now lives in {@code botmaker-project.properties} and the SDK reads
     * it on first use, so there is nothing to generate and nothing to call — see {@link BotSettings}.
     */
    public static Map<String, String> emptySources(String className, String packageName) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(className + ".java", String.format("""
            package com.%s;
            import com.botmaker.sdk.api.BotMaker;

            public class %s {
                public static void main(String[] args) {
                    BotMaker.print("Hello from %s!");
                }
            }
            """, packageName, className, className));
        return sources;
    }

    /**
     * The full "Game bot" scaffold as {@code fileName -> source}: a supervised entry point, the generated
     * {@code FlowDriver} that walks the drawn Activity Flow, an editable {@code GoHome} recovery hook, and an
     * initial empty {@code ActivityRegistry}.
     *
     * <p>Two files the scaffold used to write are gone, because neither held anything about <em>this</em>
     * project: {@code GameLoop.java} was a one-line {@code FlowDriver.run()} hop, and {@code Startup.java} was a
     * two-branch switch over {@link com.botmaker.sdk.api.launch.Target} — the launch target itself lives in
     * {@code botmaker-project.properties}, not in that file. The SDK's 2-arg {@code Bot.start} now supplies the
     * launch step, and the entry point binds {@code FlowDriver::run} directly.
     *
     * <p>Exposed as data rather than written inline so {@link ProjectRepair} can regenerate an individual
     * missing file from the same source of truth — the templates must not be duplicated. Reached via
     * {@link #sourcesFor}.
     */
    public static Map<String, String> gameBotSources(String className, String packageName) {
        Map<String, String> sources = new LinkedHashMap<>();

        // Entry point: supervise the game loop, recovering via goHome → startGame on crash/stuck.
        sources.put(className + ".java", String.format("""
            package com.%s;

            import com.botmaker.sdk.api.bot.Bot;
            import com.botmaker.sdk.api.bot.PopupGuard;

            public class %s {
                public static void main(String[] args) {
                    // Click delays, match confidence, and whether to drive the real mouse and keyboard (which
                    // is what a game needs — it ignores the quiet background clicks BotMaker sends by default)
                    // are project settings, applied by the SDK before the first click. Edit them in the
                    // Studio's Input & Clicks dialog.

                    // Runs Popups.run() before every vision step, so a daily reward or a mail popup is
                    // dismissed instead of hiding whatever the next find was looking for. Popups.java is
                    // yours: it decides which templates mean "a popup is up", and how to close each one.
                    PopupGuard.install(Popups.INSTANCE::execute);

                    // Walks the Activity Flow forever; on a crash or a stuck screen it runs GoHome and
                    // restarts the game you picked in the Studio.
                    Bot.start(FlowDriver::run, GoHome.INSTANCE::execute);
                }
            }
            """, packageName, className));

        // Initial (empty) flow driver so the entry point compiles before any activity is added.
        sources.put("FlowDriver.java", String.format("""
            package com.%s;

            import com.botmaker.sdk.api.Debug;
            import com.botmaker.sdk.api.bot.Bot;
            import com.botmaker.sdk.api.bot.Watchdog;

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
                private static final int MAX_STEPS = 1000;

                public static void run() {
                    String node = null;
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
                    return null;
                }

                private FlowDriver() {}
            }
            """, packageName));

        // Safe-point navigation (last resort before restarting; also a clean start point for activities).
        // A real Activity like any other — so it self-registers by name and gets the before()/after()/onStuck()
        // hooks — but a standalone one: it isn't on the flow canvas, it is called directly by the supervisor
        // (recovery) and by the driver (the per-activity "go home first" tick), both via INSTANCE.execute().
        sources.put("GoHome.java", String.format("""
            package com.%s;

            import com.botmaker.sdk.api.bot.Activity;

            /**
             * Navigate back to a known-good "home" screen. Called by the supervisor before it relaunches the
             * game during recovery, and before any activity whose "go home first" tick is on. Fill in {@link #run()} for
             * your game, e.g.:
             * <pre>
             *   while (!ImageFinder.find(home)) {
             *       ImageClicker.click(back);
             *       Wait.seconds(1);
             *   }
             * </pre>
             */
            public class GoHome extends Activity<GoHome.Outcome> {

                /** The one instance; referenced by the entry point and FlowDriver. Constructing it registers "GoHome". */
                public static final GoHome INSTANCE = new GoHome();

                /** GoHome reports nothing to route on — it is called directly, not wired into the flow. */
                public enum Outcome { NEXT }

                @Override
                public boolean isEnabled() {
                    return true;   // recovery hook — always available
                }

                @Override
                public Outcome run() {
                    // TODO: navigate back to your game's home screen.
                    return Outcome.NEXT;
                }
            }
            """, packageName));

        // The popup guard's body. Like GoHome: a real Activity (so it self-registers and gets the hooks) that
        // isn't on the canvas — the SDK calls it through INSTANCE::execute, here before every vision step.
        // Ships empty on purpose: a scaffold cannot guess this game's popups, and an empty check costs one
        // no-op call rather than dismissing things the user never asked it to.
        sources.put("Popups.java", String.format("""
            package com.%s;

            import com.botmaker.sdk.api.bot.Activity;

            /**
             * Dismiss whatever the game has interrupted us with. BotMaker runs this before every vision step
             * (see the {@code PopupGuard.install} line in the entry point), so no activity has to open with
             * its own defensive dismissal code.
             *
             * <p>Fill in {@link #run()} for your game. The shape that works is "which combination is on
             * screen", not "click anything that looks like a cross": the same close button often belongs to
             * the screen the bot is actually working on, and a popup's body usually isn't clickable at all.
             * <pre>
             *   ImageTemplateGroup POPUPS = ImageTemplateGroup.of(mail, claimAll, tapToClose);
             *
             *   ImageFinder.whileFindAny(POPUPS, found -&gt; {
             *       if (found.has(mail) &amp;&amp; found.has(claimAll)) ImageClicker.click(found.get(claimAll));
             *       else if (found.has(tapToClose))              ImageClicker.click(found.get(tapToClose));
             *   });
             * </pre>
             * The loop keeps going while any popup is still up, so a reward stacked behind a mail is cleared
             * too — and the finds inside it are not themselves guarded, so this cannot recurse.
             *
             * <p>Each activity has a "check for popups" tick in Project &rarr; Activity Flow; turn it off for
             * one that works through a popup-shaped screen itself.
             */
            public class Popups extends Activity<Popups.Outcome> {

                /** The one instance; the entry point installs it as the popup guard. */
                public static final Popups INSTANCE = new Popups();

                /** Popups reports nothing to route on — it is called by the guard, not wired into the flow. */
                public enum Outcome { NEXT }

                @Override
                public boolean isEnabled() {
                    return true;   // guard hook — always available
                }

                @Override
                public Outcome run() {
                    // TODO: dismiss your game's popups. Until you do, this is a no-op and nothing changes.
                    return Outcome.NEXT;
                }
            }
            """, packageName));

        // Initial (empty) activity registry so the flow driver compiles before any activity is added.
        sources.put("ActivityRegistry.java", String.format("""
            package com.%s;

            import com.botmaker.sdk.api.bot.Activity;
            import java.util.List;

            /**
             * The activities this bot can run. GENERATED by BotMaker Studio — do not edit by hand; manage via
             * Project &rarr; Activity Flow. Each is built once here, which is also what registers it by name
             * for {@code Activity.disable("Name")}. {@link FlowDriver} routes between them using the outcome
             * each one reports; {@link #ALL} is the flat view for anything that just needs every activity.
             */
            public final class ActivityRegistry {

                public static final List<Activity<?>> ALL = List.of(
                );

                private ActivityRegistry() {}
            }
            """, packageName));

        return sources;
    }

    /**
     * Writes a small placeholder PNG at {@code <imagesRoot>/default_template.png}. It is intentionally a
     * generated checker pattern (not a bundled asset) so there's nothing to ship; the Resource Manager marks
     * it undeletable and new vision blocks default to it, guaranteeing a fresh project compiles.
     */
    private void createDefaultTemplate(Path imagesRoot) throws IOException {
        Files.createDirectories(imagesRoot);
        Path target = imagesRoot.resolve(ImageTemplateLibrary.DEFAULT_TEMPLATE_FILE);
        if (Files.exists(target)) return;
        int size = 32;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean on = ((x / 8) + (y / 8)) % 2 == 0;
                img.setRGB(x, y, on ? 0xFF1ABC9C : 0xFFECF0F1);
            }
        }
        javax.imageio.ImageIO.write(img, "png", target.toFile());
    }

    public boolean projectExists(String projectName) {
        Path projectPath = PROJECTS_ROOT.resolve(projectName);
        return Files.exists(projectPath.resolve("pom.xml"));
    }

    /**
     * The project name must be a single word of letters and digits, starting with a letter.
     *
     * <p>The first letter no longer has to be uppercase: the name is the user's, and the Java class name is
     * derived from it ({@link ProjectConfig#toClassName}) rather than being the same string. It still has to
     * start with a letter, because it also becomes the package name — {@code com.7bot} is not a package.
     */
    private void validateProjectName(String projectName) {
        if (projectName == null || projectName.trim().isEmpty() || !projectName.matches("^[A-Za-z][a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid project name: " + projectName);
        }
    }
}
