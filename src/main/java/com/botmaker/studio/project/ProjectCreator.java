package com.botmaker.studio.project;

import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
            if (template == ProjectTemplate.GAME_BOT) {
                createGameBotFiles(srcPath, projectName, cfg.packageName());
            } else {
                createMainJavaFile(srcPath, projectName, cfg.packageName());
            }

            // 4. Built-in default image template so freshly-dropped vision blocks reference a real file.
            createDefaultTemplate(cfg.imagesRoot());

            // 5. Seed the standard capture resolution into settings.json + botmaker-project.properties so the
            //    editor snaps captures to it and the generated bot's runtime scaling defaults to it.
            seedResolution(cfg, referenceResolution);

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
     * Writes {@code settings.json} (the editor's standard/reference resolution) and mirrors it into
     * {@code botmaker-project.properties} ({@code capture.width}/{@code capture.height}) so the generated bot's
     * runtime resolution scaling ({@code ProjectDefaults}/{@code ResolutionScaler}) defaults to the same size.
     * No-op for a null resolution (left to auto-seed from the window on first capture).
     */
    static void seedResolution(ProjectConfig cfg, StudioProjectSettings.Resolution resolution) throws IOException {
        if (resolution == null) return;
        StudioProjectSettings.empty().withReferenceResolution(resolution).write(cfg.resourcesRoot());
        writeCaptureProperties(cfg.resourcesRoot(), resolution);
    }

    /** Writes/updates {@code capture.width}/{@code capture.height} in {@code botmaker-project.properties}. */
    public static void writeCaptureProperties(Path resourcesDir, StudioProjectSettings.Resolution resolution)
            throws IOException {
        Files.createDirectories(resourcesDir);
        Path file = resourcesDir.resolve("botmaker-project.properties");
        java.util.Properties props = new java.util.Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { props.load(in); }
        }
        props.setProperty("capture.width", Integer.toString(resolution.width()));
        props.setProperty("capture.height", Integer.toString(resolution.height()));
        try (var out = Files.newOutputStream(file)) {
            props.store(out, "BotMaker project defaults (standard capture resolution)");
        }
    }

    private void createMainJavaFile(Path srcPath, String projectName, String packageName) throws IOException {
        String content = String.format("""
            package com.%s;
            import com.botmaker.sdk.api.BotMaker;

            public class %s {
                public static void main(String[] args) {
                    BotMaker.print("Hello from %s!");
                }
            }
            """, packageName, projectName, projectName);

        Files.writeString(srcPath.resolve(projectName + ".java"), content);
    }

    /**
     * Writes the full "Game bot" scaffold: a supervised entry point, the {@code MacroLoop} that dispatches
     * over the (initially empty) activity registry, editable {@code GoHome}/{@code Startup} recovery hooks,
     * and an initial empty {@code ActivityRegistry}. Adding activities in Project → Manage Activities fills
     * the registry and generates a subclass stub per activity.
     */
    private void createGameBotFiles(Path srcPath, String projectName, String packageName) throws IOException {
        // Entry point: supervise the macro loop, recovering via goHome → startGame on crash/stuck.
        Files.writeString(srcPath.resolve(projectName + ".java"), String.format("""
            package com.%s;

            import com.botmaker.sdk.api.bot.Bot;

            public class %s {
                public static void main(String[] args) {
                    // Runs MacroLoop forever; on a crash or a stuck screen it runs GoHome then Startup and restarts.
                    Bot.supervise(MacroLoop::run, GoHome::run, Startup::run);
                }
            }
            """, packageName, projectName));

        // The macro loop: one pass runs every enabled activity, in registry order.
        Files.writeString(srcPath.resolve("MacroLoop.java"), String.format("""
            package com.%s;

            import com.botmaker.sdk.api.bot.Activity;
            import com.botmaker.sdk.api.bot.Watchdog;

            /**
             * One pass of the bot: run each enabled activity in turn. {@code Bot.supervise} re-runs this
             * continuously. {@code Watchdog.checkpoint()} throws if the bot has made no progress for a while,
             * so the supervisor can recover (GoHome + Startup).
             */
            public class MacroLoop {
                public static void run() {
                    for (Activity activity : ActivityRegistry.ALL) {
                        if (activity.isEnabled()) {
                            activity.execute();
                            Watchdog.checkpoint();
                        }
                    }
                }
            }
            """, packageName));

        // Safe-point navigation (last resort before restarting; also a clean start point for activities).
        Files.writeString(srcPath.resolve("GoHome.java"), String.format("""
            package com.%s;

            /**
             * Navigate back to a known-good "home" screen. Called by the supervisor before Startup during
             * recovery. Fill this in for your game, e.g.:
             * <pre>
             *   while (!ImageFinder.find(home)) {
             *       ImageClicker.click(back);
             *       Wait.seconds(1);
             *   }
             * </pre>
             */
            public class GoHome {
                public static void run() {
                    // TODO: navigate back to your game's home screen.
                }
            }
            """, packageName));

        // (Re)start the game from the home screen.
        Files.writeString(srcPath.resolve("Startup.java"), String.format("""
            package com.%s;

            /**
             * (Re)start the game from the home screen. Called by the supervisor after GoHome during recovery.
             * Fill this in for your game, e.g. {@code Game.launchIfNotRunning("steam", "123456");}.
             */
            public class Startup {
                public static void run() {
                    // TODO: launch or restart the game.
                }
            }
            """, packageName));

        // Initial (empty) activity registry so MacroLoop compiles before any activity is added.
        Files.writeString(srcPath.resolve("ActivityRegistry.java"), String.format("""
            package com.%s;

            import com.botmaker.sdk.api.bot.Activity;
            import java.util.List;

            /**
             * The activities this bot can run. GENERATED by BotMaker Studio — do not edit by hand; manage via
             * Project &rarr; Manage Activities. The macro loop iterates {@link #ALL} and runs each activity
             * whose enable flag is set.
             */
            public final class ActivityRegistry {

                public static final List<Activity> ALL = List.of(
                );

                private ActivityRegistry() {}
            }
            """, packageName));
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

    private void validateProjectName(String projectName) {
        if (projectName == null || projectName.trim().isEmpty() || !projectName.matches("^[A-Z][a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid project name: " + projectName);
        }
    }
}
