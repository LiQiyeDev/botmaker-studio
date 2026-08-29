package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the file-role rules — the single source of "may the user change this?".
 *
 * <p>There is one rule left and it is worth stating why. Until 2026-08-29 this enum had a third constant,
 * {@code GENERATED}, for the files BotMaker wrote into a user's own source tree and then kept rewriting: the
 * game-bot entry point, {@code Activities}, {@code ActivityRegistry}, {@code FlowDriver}, {@code Templates}.
 * They were locked because an edit that appears to work and is discarded at the next save reads to the user
 * as data loss. Nothing generates a project's Java now, so there is nothing to protect an edit from — and a
 * file that is nobody's to rewrite is simply the user's.
 *
 * <p>What is left is bundled library source, which is genuinely not the project's. The other read-only case —
 * a bot installed from the gallery and opened for reading — is a property of the checkout rather than of a
 * file, and lives in {@link ProjectMode} and {@link LockResolver}.
 */
class FileRoleTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static Path inMainPackage(String fileName) {
        return CONFIG.mainSourceFile().getParent().resolve(fileName);
    }

    @Test
    void librarySourceIsLibrary() {
        Path lib = Paths.get("/tmp/projects/MyBot/src/main/java/com/botmaker/library/Helper.java");
        assertEquals(FileRole.LIBRARY, FileRole.of(lib));
    }

    /** Every file BotMaker used to write and keep is an ordinary user file now. */
    @Test
    void nothingInAProjectIsGeneratedAnyMore() {
        for (Path file : List.of(CONFIG.mainSourceFile(),
                inMainPackage("Activities.java"),
                inMainPackage("ActivityRegistry.java"),
                inMainPackage("FlowDriver.java"),
                inMainPackage("Templates.java"),
                inMainPackage("GoHome.java"),
                inMainPackage("Popups.java"))) {
            assertEquals(FileRole.EDITABLE, FileRole.of(file), file.toString());
        }
    }

    @Test
    void unknownFilesBelongToTheUser() {
        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("MyHelper.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(null));
    }

    @Test
    void libraryIsFullyInert() {
        FileRole library = FileRole.LIBRARY;
        assertTrue(library.isReadOnly());
        assertTrue(library.suppressesInteraction(), "library blocks must not offer interaction");
        assertEquals("Library - Read Only", library.badge());
    }

    @Test
    void editableIsUnrestricted() {
        FileRole editable = FileRole.EDITABLE;
        assertFalse(editable.isReadOnly());
        assertFalse(editable.suppressesInteraction());
        assertNull(editable.badge());
    }
}
