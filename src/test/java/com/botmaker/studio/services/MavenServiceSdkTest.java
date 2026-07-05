package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.UserLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenServiceSdkTest {

    @TempDir
    Path projectsRoot;

    @Test
    void writePomPinsSdkVersionAndReadsItBack() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();

        MavenService.writePom(projectDir, cfg, "1.0.5");

        assertEquals("1.0.5", MavenService.readSdkVersion(projectDir));
    }

    @Test
    void blankSdkVersionFallsBackToConstant() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();

        MavenService.writePom(projectDir, cfg, "");

        assertEquals(MavenService.SDK_FALLBACK_VERSION, MavenService.readSdkVersion(projectDir));
    }

    @Test
    void writeUserLibrariesUpdatesSdkAndKeepsUserLibs() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.6");

        UserLibrary userLib = new UserLibrary("com.example", "widget", "2.3.4");
        MavenService.writeUserLibraries(projectDir, List.of(userLib), "1.0.4");

        // SDK re-versioned...
        assertEquals("1.0.4", MavenService.readSdkVersion(projectDir));

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

        assertEquals("1.0.3", MavenService.readSdkVersion(projectDir));
        assertTrue(MavenService.readUserLibraries(projectDir).stream()
                .anyMatch(l -> l.groupArtifact().equals("g:a")));
    }
}
