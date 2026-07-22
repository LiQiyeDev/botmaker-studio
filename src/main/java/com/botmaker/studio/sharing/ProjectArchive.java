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
 */
public final class ProjectArchive {

    private ProjectArchive() {}

    /** Top-level directory / file names that are never published. */
    private static final Set<String> EXCLUDED_NAMES = Set.of(
            "target", ".git", ".idea", ".gradle", "build", "out",
            BotSource.FILE_NAME,                                    // provenance is regenerated on install
            com.botmaker.studio.project.ProjectMode.MARKER);       // local Reader/Editor opt-in, never shipped

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
                            files.put(rel, Files.readAllBytes(p));
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

    private static boolean isExcluded(Path root, Path file) {
        for (Path seg : root.relativize(file)) {
            if (EXCLUDED_NAMES.contains(seg.toString())) return true;
        }
        return false;
    }
}
