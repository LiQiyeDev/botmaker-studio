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
