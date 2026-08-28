package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavenService#localPluginBuilds(Path)} over a repository tree built here.
 *
 * <p>The one thing worth holding: <b>what makes a jar a plugin is the service file</b>. Every case below is
 * a real state a developer's {@code ~/.m2} reaches — an ordinary library beside a plugin, a released version
 * beside a snapshot, a jar that was never written — and in each the answer is fewer rows rather than an
 * exception, because this scan runs while a dialog is opening.
 */
class LocalPluginBuildsTest {

    private static final String SERVICE = "META-INF/services/com.botmaker.plugin.api.StudioPlugin";

    @TempDir
    Path repository;

    @Test
    void a_snapshot_jar_declaring_a_plugin_is_found_with_its_full_coordinate() throws Exception {
        install("com/example/tools", "botmaker-discord", "0.0.0-SNAPSHOT", true);

        List<MavenService.LocalPluginBuild> found = MavenService.localPluginBuilds(repository);

        assertEquals(1, found.size());
        MavenService.LocalPluginBuild build = found.get(0);
        assertEquals("com.example.tools", build.groupId());
        assertEquals("botmaker-discord", build.artifactId());
        assertEquals("0.0.0-SNAPSHOT", build.version());
        assertEquals("com.example.tools:botmaker-discord", build.coordinate());
    }

    @Test
    void a_jar_without_the_service_file_is_not_a_plugin() throws Exception {
        install("com/example", "some-library", "0.0.0-SNAPSHOT", false);

        assertTrue(MavenService.localPluginBuilds(repository).isEmpty());
    }

    @Test
    void a_released_version_is_not_a_local_build() throws Exception {
        install("com/example", "botmaker-discord", "1.2.0", true);

        assertTrue(MavenService.localPluginBuilds(repository).isEmpty());
    }

    @Test
    void a_version_directory_with_no_jar_in_it_is_skipped() throws Exception {
        Files.createDirectories(repository.resolve("com/example/botmaker-discord/0.0.0-SNAPSHOT"));

        assertTrue(MavenService.localPluginBuilds(repository).isEmpty());
    }

    @Test
    void a_missing_repository_answers_empty_rather_than_throwing() {
        assertTrue(MavenService.localPluginBuilds(repository.resolve("nothing-here")).isEmpty());
    }

    /** One artifact in the repository layout: {@code <group path>/<artifact>/<version>/<artifact>-<version>.jar}. */
    private void install(String groupPath, String artifactId, String version, boolean plugin)
            throws Exception {
        Path dir = repository.resolve(groupPath).resolve(artifactId).resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(artifactId + "-" + version + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            if (plugin) {
                zip.putNextEntry(new ZipEntry(SERVICE));
                zip.write("com.example.ExamplePlugin\n".getBytes());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("com/example/Anything.class"));
            zip.closeEntry();
        }
    }
}
