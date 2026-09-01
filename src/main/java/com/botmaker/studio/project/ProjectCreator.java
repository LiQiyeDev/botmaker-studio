package com.botmaker.studio.project;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.launch.SupportedTargets;
import com.botmaker.studio.project.migration.SchemaFile;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.botmaker.studio.config.Constants.PROJECTS_ROOT;

/**
 * Creates a new user project, and since 2026-09-01 <b>writes every byte of it</b>.
 *
 * <p>It used to hand the job to {@code Authoring.createProject}, which owned five directories, an empty
 * {@code activities.json}, {@code botmaker-project.properties}, a placeholder PNG and the all-or-none
 * commit — and took Studio's own files as {@code callerFiles} so they landed in the same pass. Every one of
 * those five has since become something Studio either already owned or should not write at all:
 *
 * <ul>
 *   <li>the directories are a {@code mkdir} list and were never knowledge;
 *   <li><b>{@code activities.json} is written by Studio on every edit anyway</b> —
 *       {@link ActivitiesConfig#write}, with Studio's own mapper and its own
 *       {@code SchemaFile.ACTIVITIES} stamp. Writing an empty one at creation was never a fact the SDK held
 *       and the editor did not;
 *   <li>{@code botmaker-project.properties} carried only the capture resolution, which stopped being the
 *       editor's on 2026-09-01 — a fresh project has neither key, and the capturing plugin seeds them;
 *   <li>the placeholder picture belongs to whoever offers the type it stands for, so the plugin's own
 *       picture surfaces call {@code TemplateLibrary.ensurePlaceholder} when they first look at the folder.
 *       Creation writing it meant a project created without that plugin still got its file;
 *   <li>the pom and every {@code .java} were already composed here.
 * </ul>
 *
 * <p>The pom won that argument first, on 2026-08-26 after one day in the SDK. It is not a file about the
 * SDK, it is the file that declares <em>which</em> SDK the project has — and the SDK is the editor's default
 * plugin, not the editor. A second plugin would be invisible to it, so a pom it wrote would silently omit
 * that plugin's dependency. The entry point is the same argument one step on: it is where those plugins get
 * <em>installed</em>. And the argument for every other file is plainer still — a project's structure belongs
 * to the user, so it is written once ({@link StarterSources}) and never read, rewritten or restored.
 *
 * <p><b>All of it or none of it survives whole</b>, and it is the one thing that had to be carried across
 * rather than dropped: every file is rendered into a map before the first directory exists, anything that
 * can refuse refuses while there is nothing to clean up, and whole-file ownership is enforced by a
 * collision check rather than a merge. A half-created project is worse than no project — the editor lists
 * it, opening it fails in a different place each time, and the user has to find and delete it by hand.
 */
public class ProjectCreator {

    public void createProject(String projectName) throws IOException {
        createProject(projectName, "");
    }

    public void createProject(String projectName, String sdkVersion) throws IOException {
        createProject(projectName, sdkVersion, ProjectTemplate.EMPTY);
    }

    /**
     * Creates a new project, pinning the BotMaker SDK to {@code sdkVersion}
     * (blank → {@link MavenService#SDK_FALLBACK_VERSION}). {@code template} chooses the starting source
     * files.
     *
     * <p><b>No capture resolution is chosen here (2026-09-01).</b> The size templates are captured at is
     * the capturing plugin's, seeded by its own toolbar item the first time a picture is taken; a project
     * is created without one and {@code capture.width}/{@code capture.height} stay absent until then.
     */
    public void createProject(String projectName, String sdkVersion,
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

        // There is no "can this SDK generate?" question to ask any more. It used to refuse an unrecognised
        // pin here, before anything existed, because the SDK was about to generate the project's files
        // against that version. It generates nothing now — the pin is a coordinate in a pom, and a pom
        // naming a version nobody can resolve fails where the user can read why.

        try {
            // 1. Everything the project is made of, in one pass: the src/ layout, activities.json for a game
            //    bot, the pom and every .java. Rendered first, committed second, so a refusal lands before a
            //    single directory exists and a project that cannot be created never has to be deleted by
            //    hand.
            System.out.println("1. Creating the project...");
            Map<String, String> ourFiles = new LinkedHashMap<>(StarterSources.of(cfg));
            ourFiles.put("pom.xml", MavenService.pomXml(cfg, effectiveSdkVersion(sdkVersion)));
            writeProject(cfg, template, ourFiles);

            // 2. Seed settings.json (the chosen template). Studio's own file: no bot reads it, and it
            //    records what the editor chose rather than what the bot needs.
            System.out.println("2. Generating settings...");
            seedSettings(cfg, template);

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
     * Creates a project from a published template: download the release, rename it into the user's own
     * package and class, and record how it was made.
     *
     * <p><b>Nothing composed here reaches the result.</b> The pom is the template author's, versions and all,
     * and so are {@code activities.json}, the runtime settings and every {@code .java}. That is the point of
     * a template being a real published bot rather than a set of holes: what the user gets is a project that
     * demonstrably built for its author. Changing the SDK pin afterwards is <b>Project ▸ Manage Libraries</b>,
     * which is the same path any other version change takes.
     *
     * <p>The only thing written is the one Studio owns and the template cannot know: {@code settings.json},
     * whose {@code template} is {@link ProjectTemplate#FROM_TEMPLATE}. Whatever
     * {@code botmaker-project.properties} the template shipped is left exactly as its author wrote it.
     *
     * <p>All-or-none is kept the crude way rather than the {@code createProject} way: the unpack writes a
     * whole directory tree that {@code Authoring} never sees, so a failure anywhere in here deletes the
     * directory. A half-unpacked project the user has to remove by hand is exactly what the atomic pass on
     * the other path exists to prevent.
     *
     * @param projectName the user's name for it, which becomes the directory, the package and the class
     * @param unpack      downloads the release into the directory it is handed — {@code BotInstaller
     *                    ::unpackTemplate} bound to the chosen entry and tag, passed in so this class keeps
     *                    knowing nothing about GitHub
     */
    public void createFromTemplate(String projectName, TemplateUnpack unpack) throws IOException {
        validateProjectName(projectName);

        ProjectConfig cfg = ProjectConfig.forProject(projectName, PROJECTS_ROOT);
        Path projectPath = cfg.projectPath();
        if (Files.exists(projectPath)) {
            throw new IllegalArgumentException("Project '" + projectName + "' already exists");
        }

        System.out.println("------------------------------------------------");
        System.out.println("Creating Project: " + projectName + " (from a template)");
        System.out.println("Location: " + projectPath);
        System.out.println("------------------------------------------------");

        try {
            System.out.println("1. Downloading the template...");
            unpack.into(projectPath);

            System.out.println("2. Making it yours...");
            TemplateProject.read(projectPath).renameInto(projectPath, "com." + cfg.packageName());

            System.out.println("3. Generating settings...");
            seedSettings(cfg, ProjectTemplate.FROM_TEMPLATE);

            new ProjectVcs(projectPath).init();

            System.out.println("------------------------------------------------");
            System.out.println("SUCCESS: Project created at " + projectPath);
            System.out.println("------------------------------------------------");
        } catch (Exception e) {
            deleteRecursively(projectPath);
            System.err.println("!!! ERROR during project creation !!!");
            throw new IOException("Failed to create project from the template: " + e.getMessage(), e);
        }
    }

    /** Downloads a chosen template release into {@code dest}. */
    @FunctionalInterface
    public interface TemplateUnpack {
        void into(Path dest) throws IOException;
    }

    /** Removes a half-created project so a failure leaves nothing behind to delete by hand. */
    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best effort: the creation failure is what the user is told about, not this.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    /** What a blank pin means — the version a new pom is written with. */
    private static String effectiveSdkVersion(String sdkVersion) {
        return sdkVersion == null || sdkVersion.isBlank()
                ? MavenService.SDK_FALLBACK_VERSION : sdkVersion.trim();
    }

    /**
     * Writes {@code settings.json} — the originating {@code template}, which {@link FileRole} and
     * {@code ProjectRepair} read to tell scaffolding from user code. A project must record it or it is
     * indistinguishable from a legacy one.
     *
     * <p>The {@code capture.width}/{@code capture.height} mirror that used to go with it is gone
     * (2026-09-01), along with the resolution the editor used to pick. Those keys describe the size the
     * pictures were taken at, so the plugin that takes them writes them.
     */
    /**
     * The project's own files, rendered whole and then committed — the half that used to be
     * {@code Authoring.createProject}, absorbed on 2026-09-01.
     *
     * <p>Two rules travelled with it and neither is negotiable.
     *
     * <p><b>All of it or none of it.</b> Every file, ours and the caller's alike, is built in memory before
     * {@code src/main/java} exists. Anything that can refuse — a project already at this path, a game bot
     * whose model will not serialize — refuses while there is nothing to clean up.
     *
     * <p><b>Whole-file ownership, keyed by project-relative path.</b> A caller file colliding with one this
     * method writes is a hard error and never a merge. Two authors of one file is the mistake the scaffold
     * contract made and was deleted for, and the check outlives the arrangement that first needed it: it is
     * how a second plugin contributing files would be told it had claimed a path that is already taken.
     *
     * @param callerFiles project-relative path → content, committed in the same pass
     */
    static void writeProject(ProjectConfig cfg, ProjectTemplate template, Map<String, String> callerFiles)
            throws IOException {
        Path projectDir = cfg.projectPath();
        if (Files.exists(projectDir.resolve("pom.xml"))) {
            throw new IOException("There is already a project at " + projectDir + ".");
        }

        // ---- render ----------------------------------------------------------------------------------
        Map<String, String> files = new LinkedHashMap<>();
        if (template == ProjectTemplate.GAME_BOT) {
            // Empty, but stamped: an unstamped file reads as schema version 0 and the next open re-runs
            // every migration step against an already-current file.
            files.put("src/main/resources/" + ActivitiesConfig.FILE_NAME, ActivitiesConfig.empty().json());
        }
        for (Map.Entry<String, String> file : callerFiles.entrySet()) {
            if (files.containsKey(file.getKey())) {
                throw new IllegalArgumentException(
                        "Project creation already writes " + file.getKey() + "; a caller cannot also write it.");
            }
            files.put(file.getKey(), file.getValue());
        }

        // ---- commit ----------------------------------------------------------------------------------
        for (String dir : List.of("src/main/java", "src/main/resources", "src/test/java",
                "src/test/resources", "src/main/resources/images")) {
            Files.createDirectories(projectDir.resolve(dir));
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = projectDir.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
    }

    static void seedSettings(ProjectConfig cfg, ProjectTemplate template) throws IOException {
        StudioProjectSettings.empty()
                .withTemplate(template)
                .write(cfg.resourcesRoot());
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

    // readCaptureSize was here until 2026-09-01. Its javadoc named QuickLaunch as the caller that needed the
    // size a background session's nested display is created at — and QuickLaunch is the plugin's now, asking
    // shared's ProjectFile.captureSize directly. Nothing in the editor was left calling it.

    /**
     * One key's trimmed value from {@code botmaker-project.properties}, or {@code null} when the key, the file
     * or the directory is absent (or the read fails). The single load path behind every {@code read…} below —
     * they were four copies of this same eight lines, each free to disagree about what a missing file means.
     *
     * <p>Those eight lines are shared's now ({@link com.botmaker.shared.config.ProjectFile}), because the
     * editor is no longer the only thing that holds a project directory: a plugin serving one asks the same
     * questions of the same file, and shared already owns the keys and the classpath-side reader beside it.
     */
    private static String readKey(Path resourcesDir, String key) {
        return com.botmaker.shared.config.ProjectFile.value(resourcesDir, key);
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
        return com.botmaker.shared.config.ProjectFile.read(resourcesDir);
    }

    /**
     * The current {@code session.isolated} setting: {@code true} unless the key is explicitly
     * {@code false}/{@code 0}/{@code no}/{@code off} (matching {@link ProjectProperties#sessionIsolated()} and
     * the SDK's default-on isolation). The inverse of {@link #writeSessionIsolated} — used to seed the Launch
     * Target dialog's "Run in background" toggle and to gate the Studio Launch buttons' background path.
     */
    public static boolean readSessionIsolated(Path resourcesDir) {
        return com.botmaker.shared.config.ProjectFile.sessionIsolated(resourcesDir);
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
