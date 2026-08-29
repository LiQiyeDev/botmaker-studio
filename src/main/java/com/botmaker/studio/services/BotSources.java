package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One walk over the bot's own Java sources, for the three things that need it: finding template references
 * and rewriting them ({@link TemplateReferences}), finding review marks and stripping them
 * ({@link ReviewService}), and repointing every reference to a seed's type when the thing it stands for is
 * renamed ({@code project/seed/SeedSync}).
 *
 * <h2>Why the buffer wins over the file</h2>
 *
 * <p>The editor holds open files in memory ({@link ProjectState}) and writes them out on run, so a sweep that
 * read the disk would miss the user's last ten minutes of work, and a rewrite that touched only the disk would
 * be silently undone by the next save. Every file is therefore read from its open buffer where one exists, and
 * a rewrite is written to <em>both</em> copies. That is the whole reason {@code state} is a parameter rather
 * than something a caller could skip.
 *
 * <p>An open file outside the source root is visited too — a library source the user opened is still one of
 * ours if it is in {@code openFiles} — and the generated {@code Templates.java} is skipped everywhere: it
 * declares the constants rather than using them, and it is rewritten from the images folder anyway.
 */
public final class BotSources {

    private BotSources() {}

    /** What to do with one source file; return the rewritten text, or null to leave it alone. */
    @FunctionalInterface
    public interface Rewrite {
        String apply(Path file, String source);
    }

    /** Visits every {@code .java} file the bot owns exactly once, buffer first. */
    public static void forEach(ProjectConfig config, ProjectState state, Rewrite rewrite) {
        Map<Path, ProjectFile> open = new LinkedHashMap<>();
        if (state != null) {
            for (ProjectFile file : state.getAllFiles()) {
                if (file.getPath() != null) open.put(file.getPath().toAbsolutePath().normalize(), file);
            }
        }
        Set<Path> seen = new LinkedHashSet<>();
        Path generated = config.templatesSourceFile().toAbsolutePath().normalize();

        for (Path file : javaFiles(config)) {
            if (file.equals(generated) || !seen.add(file)) continue;
            ProjectFile buffer = open.get(file);
            String source = buffer != null ? buffer.getContent() : read(file);
            if (source == null) continue;
            String rewritten = rewrite.apply(file, source);
            if (rewritten == null) continue;
            if (buffer != null) buffer.setContent(rewritten);
            write(file, rewritten);
        }
        for (Map.Entry<Path, ProjectFile> entry : open.entrySet()) {
            if (entry.getKey().equals(generated) || !seen.add(entry.getKey())) continue;
            String rewritten = rewrite.apply(entry.getKey(), entry.getValue().getContent());
            if (rewritten == null) continue;
            entry.getValue().setContent(rewritten);
            if (Files.isRegularFile(entry.getKey())) write(entry.getKey(), rewritten);
        }
    }

    /** Every {@code .java} file under the project's source root, absolute and normalized. */
    private static List<Path> javaFiles(ProjectConfig config) {
        Path root = config.sourceRoot();
        if (root == null || !Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Couldn't list the project's sources: " + e.getMessage());
            return List.of();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return null;   // unreadable is not a use site; nothing to rewrite
        }
    }

    private static void write(Path file, String source) {
        try {
            Files.writeString(file, source);
        } catch (IOException e) {
            System.err.println("Couldn't update " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
