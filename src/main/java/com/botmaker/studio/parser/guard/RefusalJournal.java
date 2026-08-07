package com.botmaker.studio.parser.guard;

import com.botmaker.studio.config.BotMakerDirs;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The append-only record of every edit the guard refused: one JSON line per refusal in {@code refusals.jsonl},
 * with the source that would have been published — and the source it would have replaced — dumped beside it.
 *
 * <p><b>Why a file and not a print.</b> The refusal itself is handled; what isn't is the rewrite that caused
 * it, and that is diagnosed after the fact, usually on someone else's machine. A {@code System.err} line lives
 * until Studio closes and doesn't exist at all in a packaged build, and the source dump this replaces carried
 * no metadata — there was no way to tell which dump belonged to which problem, rewrite or block. JSONL because
 * appending a line is the whole write: no read-modify-write, so a crash costs at most the line in flight, and
 * the file stays greppable and {@code jq}-able as it grows.
 *
 * <p><b>Nothing here may throw.</b> Every entry point is wrapped: a diagnostic that fails must cost the
 * diagnostic and not the edit, which is already refused by the time we are called.
 */
public final class RefusalJournal {

    /**
     * One JSON object per line — indentation would break the format. Unknown properties are ignored on read so
     * a newer Studio's entries stay readable by an older one; {@code schema} says which shape a line is.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String JOURNAL = "refusals.jsonl";
    /** Rotate rather than grow without bound; a few MB is many thousands of refusals. */
    private static final long MAX_JOURNAL_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ROTATIONS = 2;
    /** Source dumps kept, newest first. Each refusal writes two files (the new source and the previous one). */
    private static final int MAX_REFUSALS_KEPT = 200;

    private final Path directory;

    private RefusalJournal(Path directory) {
        this.directory = directory;
    }

    /** The journal every {@code CodeEditor} writes to: {@code <cacheDir>/refused-edits}. */
    public static RefusalJournal inCacheDir() {
        return new RefusalJournal(BotMakerDirs.getCacheDir().resolve("refused-edits"));
    }

    /** A journal over an explicit directory — for tests, and for anything that must not touch the cache dir. */
    public static RefusalJournal in(Path directory) {
        return new RefusalJournal(directory);
    }

    /** Where the entries go. Does not create it — see {@link #openableDirectory()}. */
    public Path directory() {
        return directory;
    }

    /**
     * The journal directory, created if it doesn't exist, so a "show me the diagnostics" affordance never
     * opens nothing. Falls back to the uncreated path if it can't be made.
     */
    public Path openableDirectory() {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            return directory;
        }
    }

    /**
     * Writes {@code edit} and both sources, and returns the path of the dumped new source — or a parenthesised
     * reason it couldn't be written, which is what the console line then says. Best-effort throughout.
     */
    public String record(RefusedEdit edit, String newSource, String previousSource) {
        try {
            Files.createDirectories(directory);
            String stem = "refused-" + System.currentTimeMillis();
            String newFile = stem + ".java";
            String previousFile = stem + "-prev.java";
            Files.writeString(directory.resolve(newFile), newSource == null ? "" : newSource);
            if (previousSource != null) Files.writeString(directory.resolve(previousFile), previousSource);

            append(edit.withSources(newFile, previousSource != null ? previousFile : null));
            prune();
            return directory.resolve(newFile).toString();
        } catch (Exception e) {
            return "(could not be written: " + e + ")";
        }
    }

    /** Every entry in the current journal file, oldest first — the read side, for tests and tooling. */
    public List<RefusedEdit> entries() throws IOException {
        Path file = directory.resolve(JOURNAL);
        if (!Files.exists(file)) return List.of();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).map(RefusalJournal::parse).toList();
        }
    }

    private static RefusedEdit parse(String line) {
        try {
            return MAPPER.readValue(line, RefusedEdit.class);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable journal line: " + line, e);
        }
    }

    private void append(RefusedEdit edit) throws IOException {
        rotateIfLarge();
        Files.writeString(directory.resolve(JOURNAL), MAPPER.writeValueAsString(edit) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void rotateIfLarge() throws IOException {
        Path file = directory.resolve(JOURNAL);
        if (!Files.exists(file) || Files.size(file) < MAX_JOURNAL_BYTES) return;
        Files.move(file, directory.resolve("refusals-" + System.currentTimeMillis() + ".jsonl"));
        deleteOldest(list("refusals-*.jsonl"), MAX_ROTATIONS);
    }

    /**
     * Drops the oldest source dumps. The journal keeps the metadata of every refusal it hasn't rotated away;
     * only the bulky part — two full copies of the user's file per refusal — is capped.
     */
    private void prune() throws IOException {
        deleteOldest(list("refused-*.java"), MAX_REFUSALS_KEPT * 2);
    }

    private List<Path> list(String glob) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            PathMatcher matcher = directory.getFileSystem().getPathMatcher("glob:" + glob);
            return files.filter(p -> matcher.matches(p.getFileName())).toList();
        }
    }

    private static void deleteOldest(List<Path> files, int keep) {
        if (files.size() <= keep) return;
        files.stream()
                .sorted(Comparator.comparing(RefusalJournal::modifiedAt).reversed())
                .skip(keep)
                .forEach(RefusalJournal::deleteQuietly);
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Pruning is housekeeping; a file we can't delete is not worth failing a refusal over.
        }
    }
}
