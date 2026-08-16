package com.botmaker.studio.sharing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Collects the publishable files of a project (a standard Maven layout) as a {@code relativePath → bytes}
 * map, ready to push to GitHub. Build output, VCS metadata and local-only files are excluded so the
 * repository stays a clean, importable project.
 *
 * <p>One file is not shipped verbatim: {@code botmaker-project.properties} loses the keys that describe the
 * <em>publisher's own machine</em> — see {@link #MACHINE_LOCAL_KEYS}.
 */
public final class ProjectArchive {

    private ProjectArchive() {}

    /** Top-level directory / file names that are never published. */
    private static final Set<String> EXCLUDED_NAMES = Set.of(
            "target", ".git", ".idea", ".gradle", "build", "out",
            BotSource.FILE_NAME,                                    // provenance is regenerated on install
            com.botmaker.studio.project.ProjectMode.MARKER);       // local Reader/Editor opt-in, never shipped

    /**
     * The project keys stripped on publish, because they are true of the machine that published rather than of
     * the bot. {@code launch.target} is the publisher's own Steam appId or emulator instance — a Steam id is
     * useless to someone who owns the game on Epic — and {@code capture.source} names their monitor or their
     * emulator instance by name. What the bot <em>does</em> declare is
     * {@link com.botmaker.studio.project.launch.SupportedTargets} ({@code launch.supported}), which ships; the
     * installer picks their own launch target from it, and their own capture source alongside.
     *
     * <p>Everything else in the file — the authored capture resolution, the debug switch, the session and bot
     * tuning — is a property of the bot and travels.
     */
    static final Set<String> MACHINE_LOCAL_KEYS = Set.of(
            com.botmaker.shared.config.ProjectProperties.KEY_LAUNCH_TARGET,
            com.botmaker.shared.config.ProjectProperties.KEY_CAPTURE_SOURCE);

    /**
     * Walks {@code projectDir} and returns every publishable file keyed by its POSIX-style relative path.
     *
     * @throws IOException if the directory cannot be read
     */
    public static Map<String, byte[]> collect(Path projectDir) throws IOException {
        Path root = projectDir.toAbsolutePath().normalize();
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(root, p))
                    .forEach(p -> {
                        try {
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            files.put(rel, publishable(p, Files.readAllBytes(p)));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read " + p + ": " + e.getMessage(), e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
        return files;
    }

    /**
     * The bytes to publish for {@code file}: itself, unless it is the project properties, in which case a copy
     * without {@link #MACHINE_LOCAL_KEYS}.
     *
     * <p>The rewrite goes through {@link java.util.Properties}, so comments and key order in the published copy
     * are Java's rather than the original's. That is acceptable here and nowhere else in the file's life: this
     * is a snapshot being pushed to a repo, not the working copy, and the working copy is untouched. An
     * unparseable file is published verbatim — a publish is not the place to discover a hand-edit is broken.
     */
    private static byte[] publishable(Path file, byte[] bytes) {
        if (!com.botmaker.shared.config.ProjectProperties.FILE_NAME.equals(file.getFileName().toString())) {
            return bytes;
        }
        java.util.Properties props = new java.util.Properties();
        try (var in = new java.io.ByteArrayInputStream(bytes)) {
            props.load(in);
        } catch (IOException e) {
            return bytes;
        }
        if (MACHINE_LOCAL_KEYS.stream().noneMatch(props::containsKey)) return bytes;
        MACHINE_LOCAL_KEYS.forEach(props::remove);
        try (var out = new java.io.ByteArrayOutputStream()) {
            props.store(out, "BotMaker project defaults (published — machine-local launch/capture removed)");
            return out.toByteArray();
        } catch (IOException e) {
            return bytes;
        }
    }

    private static boolean isExcluded(Path root, Path file) {
        for (Path seg : root.relativize(file)) {
            if (EXCLUDED_NAMES.contains(seg.toString())) return true;
        }
        return false;
    }
}
