package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the file-role rules — the single source of "may the user change this?".
 *
 * <p>Two things worth stating outright, because both have been got wrong here before:
 * <ul>
 *   <li>{@link FileRole#GENERATED} is as inert as {@link FileRole#LIBRARY}. It used to stay interactive while
 *       silently discarding the edits at save time, which reads to the user as data loss, not as a lock.</li>
 *   <li>The entry point's role depends on the <b>template</b>: scaffolding in a game bot, the user's only file
 *       in an empty project.</li>
 * </ul>
 */
class FileRoleTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static Path inMainPackage(String fileName) {
        return CONFIG.mainSourceFile().getParent().resolve(fileName);
    }

    @Test
    void theGameBotEntryPointIsGenerated() {
        assertEquals(FileRole.GENERATED,
                FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, CONFIG.mainSourceFile()));
    }

    @Test
    void theEmptyProjectEntryPointIsTheUsers() {
        // An empty project's main() is the only file it has; locking it would leave nothing to edit.
        assertEquals(FileRole.EDITABLE,
                FileRole.of(CONFIG, ProjectTemplate.EMPTY, CONFIG.mainSourceFile()));
    }

    @Test
    void anUnknownTemplateDoesNotLockTheEntryPoint() {
        // Legacy project with no template recorded: err towards leaving the user in control.
        assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, null, CONFIG.mainSourceFile()));
    }

    @Test
    void generatedScaffoldingIsRecognised() {
        for (ProjectTemplate t : ProjectTemplate.values()) {
            assertEquals(FileRole.GENERATED, FileRole.of(CONFIG, t, CONFIG.activityRegistrySourceFile()));
            assertEquals(FileRole.GENERATED, FileRole.of(CONFIG, t, CONFIG.activitiesSourceFile()));
            assertEquals(FileRole.GENERATED, FileRole.of(CONFIG, t, inMainPackage("GameLoop.java")));
        }
    }

    @Test
    void userEditableTemplateFilesStayEditable() {
        // GoHome/Startup are scaffolded but explicitly the user's to fill in. Their run() signature is
        // protected by MethodLock, not by locking the whole file.
        assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("GoHome.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("Startup.java")));
    }

    @Test
    void activityStubsAreEditable() {
        Path stub = CONFIG.activitiesPackageDir().resolve("Mining.java");
        assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, stub));
    }

    @Test
    void librarySourceIsLibrary() {
        Path lib = Paths.get("/tmp/projects/MyBot/src/main/java/com/botmaker/library/Helper.java");
        assertEquals(FileRole.LIBRARY, FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, lib));
    }

    @Test
    void unknownFilesBelongToTheUser() {
        assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("MyHelper.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(null, ProjectTemplate.GAME_BOT, inMainPackage("Whatever.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, null));
    }

    @Test
    void generatedIsFullyInert() {
        FileRole generated = FileRole.GENERATED;
        assertTrue(generated.isReadOnly());
        assertTrue(generated.blocksPersistence(), "generated edits must not reach disk");
        assertTrue(generated.suppressesInteraction(),
                "an edit that can't be saved must not be offered in the first place");
    }

    @Test
    void libraryIsFullyInert() {
        FileRole library = FileRole.LIBRARY;
        assertTrue(library.isReadOnly());
        assertTrue(library.blocksPersistence());
        assertTrue(library.suppressesInteraction(), "library blocks must not offer interaction");
    }

    @Test
    void editableIsUnrestricted() {
        FileRole editable = FileRole.EDITABLE;
        assertFalse(editable.isReadOnly());
        assertFalse(editable.blocksPersistence());
        assertFalse(editable.suppressesInteraction());
        assertNull(editable.badge());
    }

    @Test
    void badgesDistinguishGeneratedFromLibrary() {
        assertEquals("Generated - Read Only", FileRole.GENERATED.badge());
        assertEquals("Library - Read Only", FileRole.LIBRARY.badge());
    }
}
