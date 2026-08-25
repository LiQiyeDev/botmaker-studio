package com.botmaker.studio.project;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.launch.SupportedTargets;
import com.botmaker.studio.project.migration.SchemaFile;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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

        // 0. Render the scaffold before a single directory exists, because the answer may be a refusal and a
        //    half-created project the user has to delete by hand is a worse outcome.
        //
        //    Since 2026-08-25 the game-bot scaffold *is* that refusal, and temporarily. Its five files were
        //    the SDK's own templates with this project's name dropped into them; the templates left the SDK
        //    and its emitters do not exist yet (inversion phase 2), so there is nothing to render. The empty
        //    template is unaffected — it was never a template at all, only a text block written here — so
        //    "New Project" still works for it, which is the whole reason to refuse per template rather than
        //    outright.
        Map<String, String> sources;
        try {
            sources = sourcesFor(template, cfg.className(), cfg.packageName());
        } catch (IllegalStateException refused) {
            // Checked on the way out, because creation is a user action with a dialog to show it in.
            throw new IOException(refused.getMessage(), refused);
        }

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
            writeSources(srcPath, sources);

            // 4. Built-in default image template so freshly-dropped vision blocks reference a real file,
            //    and the Templates class that names it — generated here rather than on first capture so a
            //    brand-new project's `new ImageTemplate(Templates.DEFAULT_TEMPLATE)` compiles at once.
            createDefaultTemplate(cfg.imagesRoot());
            ImageTemplateLibrary.regenerateTemplatesClass(cfg);

            System.out.println("3. Generating settings...");
            seedActivitiesFile(cfg, template);

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

    /** What a blank pin means — the version {@link MavenService#writePom} would write. */
    private static String effectiveSdkVersion(String sdkVersion) {
        return sdkVersion == null || sdkVersion.isBlank()
                ? MavenService.SDK_FALLBACK_VERSION : sdkVersion.trim();
    }

    /**
     * Declares where a new project keeps the values its bot reads, and writes the files that hold them.
     *
     * <p>A game bot keeps them in Java: {@code activities.json} records {@link SettingsModel#JAVA}, and both
     * generated files are written straight away — empty, but present, so {@code Settings.<field>} and the
     * {@code @Setting} annotation compile before the first setting exists.
     *
     * <p>The model is recorded here and never inferred afterwards. Every load, save and dialog branches on it,
     * and a project that didn't declare it at birth could only be guessed at — so this is the one moment it
     * can be set, which is why it is a named step and not a line inside the creation sequence.
     *
     * <p>Any other template writes nothing at all: an empty project has no activities and no settings, and an
     * {@code activities.json} it never asked for is a file it would carry forever.
     */
    static void seedActivitiesFile(ProjectConfig cfg, ProjectTemplate template) throws IOException {
        if (template != ProjectTemplate.GAME_BOT) return;
        ActivitiesConfig.empty().write(cfg.resourcesRoot());
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
        writeProjectKeys(resourcesDir, new java.util.LinkedHashMap<>(java.util.Map.of(
                ProjectProperties.KEY_CAPTURE_WIDTH, Integer.toString(resolution.width()),
                ProjectProperties.KEY_CAPTURE_HEIGHT, Integer.toString(resolution.height()))));
    }

    /**
     * Writes/updates the {@code launch.target} key in {@code botmaker-project.properties} — the spec the SDK's
     * {@code Bot.start} start-up step ({@code Target.startIfNotRunning()}) launches at runtime. Accepts a spec
     * in the SDK's {@code LaunchTarget} form ({@code steam:<id>} / {@code epic:<name>} / {@code exe:<path>} /
     * {@code emu-app:<pkg>@<instance>}); a null/blank {@code spec} removes the key (no configured target).
     * Preserves the other properties (capture resolution/source) already in the file.
     */
    public static void writeLaunchTarget(Path resourcesDir, String spec) throws IOException {
        writeProjectKey(resourcesDir, ProjectProperties.KEY_LAUNCH_TARGET,
                spec == null || spec.isBlank() ? null : spec.trim());
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
        writeProjectKey(resourcesDir, ProjectProperties.KEY_CAPTURE_SOURCE,
                spec == null || spec.isBlank() ? null : spec.trim());
    }

    /**
     * Writes/updates the {@code debug} key in {@code botmaker-project.properties} — the initial state of the
     * generated bot's global debug-output switch (the SDK's {@code api.Debug}, which all {@code [Bot]}/
     * {@code [Game]}/{@code [Target]}/{@code [Activity]} and vision traces consult). {@code true}/{@code false};
     * a {@code null} removes the key (bot falls back to its default, on). Preserves the other properties.
     */
    public static void writeDebug(Path resourcesDir, Boolean enabled) throws IOException {
        writeProjectKey(resourcesDir, ProjectProperties.KEY_DEBUG,
                enabled == null ? null : Boolean.toString(enabled));
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
     * Writes/updates the {@code launch.supported} key — the launch kinds the <em>author</em> declares the bot
     * works on. Unlike {@link #writeLaunchTarget}, this is not about this machine: it is the fact that travels
     * with a published bot, so whoever installs it knows whether their platform is one the bot was built for.
     * An undeclared ({@link SupportedTargets#any()}) set removes the key rather than writing an empty value —
     * absent is what "the author never said" reads as everywhere else in this file.
     */
    public static void writeSupportedTargets(Path resourcesDir, SupportedTargets supported) throws IOException {
        writeProjectKey(resourcesDir, SupportedTargets.KEY, supported == null ? null : supported.spec());
    }

    /** The declared {@code launch.supported} set, or {@link SupportedTargets#any()} when the key is absent. */
    public static SupportedTargets readSupportedTargets(Path resourcesDir) {
        return SupportedTargets.parse(readKey(resourcesDir, SupportedTargets.KEY));
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
     *
     * <p><b>Every write of this file goes through here</b>, which is what lets it be the one place that
     * records {@link SchemaFile#PROPERTIES}'s version. Four callers used to keep their own copy of the
     * load-modify-store, and a stamp in one copy is a stamp three writes can silently drop.
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
        SchemaFile.PROPERTIES.stamp(props);
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
     * The starting sources for {@code template} as {@code fileName -> source}.
     *
     * <p>The single source of truth for {@link ProjectRepair} and for recovery, which regenerate one missing
     * scaffold file long after creation.
     *
     * <p><b>{@link ProjectTemplate#GAME_BOT} refuses, and temporarily (2026-08-25).</b> Its five files were
     * the SDK's own scaffold templates with this project's name and model dropped into their tokens; the
     * templates left the SDK and its emitters do not exist yet (inversion phase 2), so nothing here can say
     * what those files should contain. Refusing is the only honest answer — writing a guess would produce a
     * bot that does not compile, and returning the empty scaffold instead would silently create a different
     * project than the one asked for. {@link ProjectTemplate#EMPTY} never used a template and is unaffected.
     *
     * <p>Unchecked, because every caller of this three-argument form treats a failure here as a broken
     * Studio rather than as something a user did, and adding a checked signature to all of them for one
     * interim release would be undone again in phase 2.
     */
    public static Map<String, String> sourcesFor(ProjectTemplate template, String className, String packageName) {
        if (template == ProjectTemplate.GAME_BOT) {
            throw new IllegalStateException("The game-bot files (the entry point, FlowDriver, GoHome, Popups "
                    + "and ActivityRegistry) are generated by the SDK, and this build of BotMaker does not "
                    + "have that generator yet. Create an empty project for now.");
        }
        return emptySources(className, packageName);
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
            import com.botmaker.sdk.api.util.BotMaker;

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
     * <p><b>It refuses, and temporarily.</b> Every file it produced was one of the SDK's own scaffold
     * templates with this project's package, class name and (for the two generated files) an empty model
     * dropped into its tokens. The templates are gone from the SDK and its own emitters are not written yet
     * (inversion phase 2), so there is nothing to fill and nothing to render. See {@link #sourcesFor}.
     *
     * <p>Kept as a method rather than deleted because {@link ProjectRepair} and recovery name it as the
     * single source of truth for an individual missing scaffold file, and that is still true — it is the
     * source of truth that currently has no answer.
     */
    public static Map<String, String> gameBotSources(String className, String packageName) {
        return sourcesFor(ProjectTemplate.GAME_BOT, className, packageName);
    }

    /**
     * The file names of the game-bot scaffold — which survive {@link #sourcesFor}'s refusal, because what was
     * lost with the templates is what those files <em>contain</em>, never what they are called.
     *
     * <p>That distinction is the whole reason this exists (2026-08-25): {@link ProjectRepair} asks two
     * separate questions of the scaffold, and only one of them needs the text. <em>Is this file gone?</em> is
     * answerable from the names alone, so a game bot whose {@code GoHome.java} was deleted outside the Studio
     * is still <b>told</b> so — it is only the restoring that has to wait for inversion phase 2. Answering
     * "nothing is missing" would be the worse failure of the two: a project that does not compile, reported
     * as healthy.
     */
    public static List<String> gameBotFileNames(String className) {
        return List.of(className + ".java", "FlowDriver.java", "GoHome.java", "Popups.java",
                "ActivityRegistry.java");
    }

    /**
     * Writes a small placeholder PNG at {@code <imagesRoot>/default_template.png}. It is intentionally a
     * generated checker pattern (not a bundled asset) so there's nothing to ship; the Resource Manager marks
     * it undeletable and new vision blocks default to it, guaranteeing a fresh project compiles.
     */
    private void createDefaultTemplate(Path imagesRoot) throws IOException {
        Files.createDirectories(imagesRoot);
        createDefaultTemplateAt(imagesRoot.resolve(ImageTemplateLibrary.DEFAULT_TEMPLATE_FILE));
    }

    /**
     * Writes the placeholder at exactly {@code target}, if it is not already there. Split out from
     * {@link #createDefaultTemplate} so {@link ProjectRepair} can restore a deleted one: recovery knows the
     * path it found missing, and inventing a second copy of the "which file is the placeholder" rule is how
     * the two would drift.
     */
    static void createDefaultTemplateAt(Path target) throws IOException {
        if (Files.exists(target)) return;
        Files.createDirectories(target.getParent());
        // The pattern itself lives in the library, so "is this still the placeholder?" (asked by export) has
        // one answer rather than a second copy of the checker to drift from.
        javax.imageio.ImageIO.write(ImageTemplateLibrary.defaultTemplateImage(), "png", target.toFile());
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
