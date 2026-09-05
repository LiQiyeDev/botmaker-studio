package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.UserLibrary;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pom shapes, and the classification that has to keep recognising both.
 *
 * <p>Since 2026-09-04 a project Studio creates is <b>blank</b> — a test framework and nothing else — while
 * every project made before that date, and every one unpacked from a gallery template, carries the SDK plus
 * the eight entries that serve its plugin half. Both shapes are live on one machine at once, which is what
 * makes the classification cases below the load-bearing ones here.
 */
class MavenServiceSdkTest {

    @TempDir
    Path projectsRoot;

    // ---- the two shapes -------------------------------------------------------------------------------

    @Test
    void aBlankPomNamesNoPluginAtAll() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();

        MavenService.writeBlankPom(projectDir, cfg);

        assertEquals(List.of("org.junit.jupiter:junit-jupiter"), groupArtifacts(projectDir));
        assertTrue(MavenService.readSdkVersion(projectDir).isEmpty(),
                "a pom naming no SDK must answer empty, not the fallback");
    }

    /**
     * A blank project still declares the repositories, which is what lets it <em>become</em> a bot project
     * later without anybody hand-editing XML: Manage Plugins adds the SDK as an ordinary dependency and it
     * resolves from JitPack.
     */
    @Test
    void aBlankPomKeepsTheRepositoriesSoAPluginCanBeAddedLater() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        MavenService.writeBlankPom(cfg.projectPath(), cfg);

        List<String> repos = readModel(cfg.projectPath()).getRepositories().stream()
                .map(org.apache.maven.model.Repository::getId).toList();
        assertTrue(repos.contains("jitpack"), repos.toString());
        assertTrue(repos.contains("central"), repos.toString());
    }

    @Test
    void writePomPinsSdkVersionAndReadsItBack() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();

        MavenService.writePom(projectDir, cfg, "1.0.5");

        assertEquals("1.0.5", MavenService.readSdkVersion(projectDir).orElseThrow());
    }

    @Test
    void blankSdkVersionFallsBackToConstant() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();

        MavenService.writePom(projectDir, cfg, "");

        assertEquals(MavenService.SDK_FALLBACK_VERSION,
                MavenService.readSdkVersion(projectDir).orElseThrow());
    }

    // ---- the classification, which is where the data loss would be ------------------------------------

    /**
     * <b>The hazard this whole file exists for.</b> {@code DEFAULT_GROUP_ARTIFACTS} classifies the pom of
     * <em>any</em> project, not only of one created today. Narrowed to the blank list, every entry a bot
     * project carries — the SDK, the toolkit, JavaFX, Javalin, ZXing, JNA, Jackson — would read as a user
     * library: offered for deletion in Manage Libraries, and then genuinely dropped by
     * {@code writeUserLibraries}, which keeps what it recognises and discards the rest.
     */
    @Test
    void aBotPomsBuiltInsAreNeverUserLibraries() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.6");

        assertEquals(List.of(), MavenService.readUserLibraries(projectDir),
                "a freshly written bot pom has no user libraries at all");
    }

    /** The same fact from the write side: editing libraries on a bot project keeps all nine built-ins. */
    @Test
    void editingLibrariesOnABotProjectPreservesEveryBuiltIn() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.6");
        List<String> before = groupArtifacts(projectDir);

        MavenService.writeUserLibraries(projectDir, List.of(new UserLibrary("com.example", "widget", "2.3.4")));

        List<String> after = groupArtifacts(projectDir);
        for (String built : before) {
            assertTrue(after.contains(built), built + " was dropped from the pom");
        }
        assertTrue(after.contains("com.example:widget"));
    }

    /** And a blank project does not acquire an SDK by having its libraries edited. */
    @Test
    void editingLibrariesOnABlankProjectDoesNotAddAnSdk() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);

        MavenService.writeUserLibraries(projectDir, List.of(new UserLibrary("com.example", "widget", "2.3.4")));

        assertTrue(MavenService.readSdkVersion(projectDir).isEmpty());
        assertFalse(groupArtifacts(projectDir).contains(
                MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID));
    }

    // ---- user libraries -------------------------------------------------------------------------------

    @Test
    void writeUserLibrariesUpdatesSdkAndKeepsUserLibs() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.6");

        UserLibrary userLib = new UserLibrary("com.example", "widget", "2.3.4");
        MavenService.writeUserLibraries(projectDir, List.of(userLib), "1.0.4");

        // SDK re-versioned...
        assertEquals("1.0.4", MavenService.readSdkVersion(projectDir).orElseThrow());

        // ...user libs preserved, and the SDK is NOT treated as a user library.
        List<UserLibrary> userLibs = MavenService.readUserLibraries(projectDir);
        assertEquals(List.of(userLib), userLibs);
        assertFalse(userLibs.stream().anyMatch(l -> l.artifactId().equals(MavenService.SDK_ARTIFACT_ID)));
    }

    @Test
    void writeUserLibrariesTwoArgPreservesSdkVersion() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.3");

        MavenService.writeUserLibraries(projectDir, List.of(new UserLibrary("g", "a", "1")));

        assertEquals("1.0.3", MavenService.readSdkVersion(projectDir).orElseThrow());
        assertTrue(MavenService.readUserLibraries(projectDir).stream()
                .anyMatch(l -> l.groupArtifact().equals("g:a")));
    }

    // ---- installing a plugin --------------------------------------------------------------------------

    /**
     * The defect this holds shut, and it was silent in the worst way: a blank project that installed the SDK
     * through <b>Manage Plugins</b> got the SDK jar and none of the five {@code provided} entries its plugin
     * half needs, so {@code SdkPlugin} could not resolve {@code AbstractStudioPlugin},
     * {@code ServiceLoader} failed, {@code PluginLoader} caught it — correctly — and the user got an empty
     * palette and one line on stderr. Nothing failed to compile at any point.
     */
    @Test
    void installingTheSdkDeclaresWhatItsPluginHalfNeeds() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);

        MavenService.installPlugin(projectDir, new UserLibrary(
                MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "1.1.6"));

        List<String> after = groupArtifacts(projectDir);
        assertTrue(after.contains(MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID));
        for (String companion : List.of("com.github.LiQiyeDev:botmaker-plugin-toolkit",
                "org.openjfx:javafx-controls", "org.openjfx:javafx-graphics",
                "io.javalin:javalin", "com.google.zxing:core")) {
            assertTrue(after.contains(companion), companion + " was not declared");
        }
        // provided, never compile: the bot itself must link none of them when it runs.
        for (Dependency d : readModel(projectDir).getDependencies()) {
            if (d.getArtifactId().equals("javafx-controls") || d.getArtifactId().equals("javalin")) {
                assertEquals("provided", d.getScope(), d.getArtifactId() + " must be provided");
            }
        }
        assertEquals("1.1.6", MavenService.readSdkVersion(projectDir).orElseThrow());
    }

    /**
     * Pressing <i>Install</i> on a row that already reads installed must not write the coordinate twice.
     *
     * <p>It did, until 2026-09-05, and by two independent routes: the dialog could not tell the SDK was
     * installed (it asked {@code readUserLibraries}, which classes the SDK as built in), and the writer it
     * used kept the pom's built-ins <em>and</em> appended the list it was handed.
     */
    @Test
    void installingTwiceLeavesExactlyOneDependency() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);
        UserLibrary sdk = new UserLibrary(
                MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "1.1.6");

        MavenService.installPlugin(projectDir, sdk);
        MavenService.installPlugin(projectDir, new UserLibrary(
                MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "1.1.7"));

        List<String> sdkRows = groupArtifacts(projectDir).stream()
                .filter(ga -> ga.equals(MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID))
                .toList();
        assertEquals(1, sdkRows.size(), "the SDK was declared more than once");
        assertEquals("1.1.7", MavenService.readSdkVersion(projectDir).orElseThrow(),
                "re-installing must move the version, not add a second row");
        // And the companions are a floor, not a re-write: still one of each.
        assertEquals(1, groupArtifacts(projectDir).stream()
                .filter(ga -> ga.equals("org.openjfx:javafx-controls")).count());
    }

    /** Removing the SDK takes back what installing it declared — the pom must not keep orphans. */
    @Test
    void removingTheSdkTakesItsCompanionsWithIt() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);
        MavenService.installPlugin(projectDir, new UserLibrary(
                MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "1.1.6"));

        MavenService.removePlugin(projectDir, MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID);

        assertEquals(List.of("org.junit.jupiter:junit-jupiter"), groupArtifacts(projectDir));
        assertTrue(MavenService.readSdkVersion(projectDir).isEmpty());
    }

    /**
     * And nobody else gets companions. A plugin generated by {@code botmaker-plugin-archetype} declares its
     * toolkit at {@code compile} scope, so it is transitive and needs nothing from this pom — which is what
     * makes the SDK's arm an exception with a reason rather than a privilege.
     */
    @Test
    void installingAnyOtherPluginDeclaresOnlyThatPlugin() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);

        MavenService.installPlugin(projectDir, new UserLibrary("com.example", "shiny-plugin", "0.1.0"));

        assertEquals(List.of("org.junit.jupiter:junit-jupiter", "com.example:shiny-plugin"),
                groupArtifacts(projectDir));
    }

    /** Every dependency, defaults included — the question Manage Plugins asks. */
    @Test
    void readDeclaredLibrariesSeesTheBuiltInsThatReadUserLibrariesHides() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.1.6");

        List<String> declared = MavenService.readDeclaredLibraries(projectDir).stream()
                .map(UserLibrary::groupArtifact).toList();

        assertTrue(declared.contains(MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID));
        assertFalse(MavenService.readUserLibraries(projectDir).stream()
                        .anyMatch(l -> l.artifactId().equals(MavenService.SDK_ARTIFACT_ID)),
                "the narrow reader must keep its meaning");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static List<String> groupArtifacts(Path projectDir) throws Exception {
        return readModel(projectDir).getDependencies().stream()
                .map(d -> d.getGroupId() + ":" + d.getArtifactId())
                .toList();
    }

    private static Model readModel(Path projectDir) throws Exception {
        try (InputStream in = Files.newInputStream(projectDir.resolve("pom.xml"))) {
            return new MavenXpp3Reader().read(in);
        }
    }
}
