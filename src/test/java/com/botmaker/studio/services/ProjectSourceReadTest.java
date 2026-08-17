package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disk half of project open, now that it runs off the FX thread.
 *
 * <p>It used to be inlined in {@code loadInitialCode}, walking the tree and reading every file on the FX
 * thread with the main window already shown — the white-window freeze. Moving it to the worker only helps if
 * it stays what it is here: pure IO, no {@code ProjectState}, no parse, no event. These tests pin the
 * contract the worker thread depends on.
 */
class ProjectSourceReadTest {

    @Test
    void everyJavaFileUnderTheRootIsRead(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        Path nested = Files.createDirectories(root.resolve("activities"));
        Files.writeString(nested.resolve("Mining.java"), "class Mining {}");
        Files.writeString(root.resolve("notes.txt"), "not source");

        List<ProjectFile> read = CodeEditorService.readSourcesUnder(root);

        assertEquals(List.of("Main", "Mining"),
                read.stream().map(ProjectFile::getClassName).sorted().toList());
        assertTrue(read.stream().allMatch(f -> f.getContent().startsWith("class ")),
                "content must be read eagerly — the analyzer scans it without opening the file");
    }

    /**
     * Parsing is what stayed lazy. A file is turned into an AST only when it becomes the active file, so a read
     * carries source text and nothing more — a project with dozens of activities pays one parse at open, not N.
     */
    @Test
    void nothingIsParsedWhileReading(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Broken.java"), "this is not { valid java(");

        List<ProjectFile> read = CodeEditorService.readSourcesUnder(root);

        assertEquals(1, read.size());
        assertEquals(null, read.get(0).getAst(), "a read must not parse — not even a file that wouldn't compile");
    }

    @Test
    void aMissingOrNonDirectoryRootReadsAsEmpty(@TempDir Path root) {
        assertTrue(CodeEditorService.readSourcesUnder(null).isEmpty());
        assertTrue(CodeEditorService.readSourcesUnder(root.resolve("no-such-dir")).isEmpty());
    }
}
