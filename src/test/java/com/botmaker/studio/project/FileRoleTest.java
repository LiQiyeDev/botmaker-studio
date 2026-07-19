package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the file-role rules — the single source of "may the user change this?".
 *
 * <p>Three things worth stating outright, because all three have been got wrong here before:
 * <ul>
 *   <li>{@link FileRole#GENERATED} is inert <b>by default</b>. It used to stay interactive while silently
 *       discarding the edits at save time, which reads to the user as data loss, not as a lock. But "inert by
 *       default" is not "inert": a {@link MethodLock#SIGNATURE} method inside it keeps an editable body — see
 *       {@code LockResolver}. This verdict is one of two inputs, not the answer.</li>
 *   <li><b>Every</b> role here depends on the template, not just the entry point's. These file names are only
 *       scaffolding because the game-bot template wrote them; in an empty project they are the user's.</li>
 *   <li>Unknown files, and unknown templates, belong to the user. Err towards leaving them in control.</li>
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
        assertEquals(FileRole.GENERATED,
                FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, CONFIG.activityRegistrySourceFile()));
        assertEquals(FileRole.GENERATED,
                FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, CONFIG.activitiesSourceFile()));
        assertEquals(FileRole.GENERATED,
                FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("GameLoop.java")));
        // Startup.java is generated wiring too now — its run() is just Target.start() over the configured target.
        assertEquals(FileRole.GENERATED,
                FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("Startup.java")));
    }

    @Test
    void onlyTheGameBotTemplateGeneratesAnything() {
        // These names are scaffolding only because the game-bot template wrote them. In an empty project the
        // same names are just files the user made, and locking them left the user's own GameLoop unwritable.
        for (ProjectTemplate t : ProjectTemplate.values()) {
            if (t == ProjectTemplate.GAME_BOT) continue;
            assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, t, CONFIG.activityRegistrySourceFile()));
            assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, t, CONFIG.activitiesSourceFile()));
            assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, t, inMainPackage("GameLoop.java")));
            assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, t, inMainPackage("Startup.java")));
        }
        assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, null, inMainPackage("GameLoop.java")));
    }

    @Test
    void goHomeStaysEditable() {
        // GoHome is scaffolded but explicitly the user's to fill in: its run() signature is protected by
        // MethodLock, not by locking the whole file. (Startup used to be here too, but it is generated now.)
        assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("GoHome.java")));
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
    void generatedIsInertByDefault() {
        FileRole generated = FileRole.GENERATED;
        assertTrue(generated.isReadOnly());
        assertTrue(generated.suppressesInteraction(),
                "an edit that can't be saved must not be offered in the first place");
    }

    @Test
    void libraryIsFullyInert() {
        FileRole library = FileRole.LIBRARY;
        assertTrue(library.isReadOnly());
        assertTrue(library.suppressesInteraction(), "library blocks must not offer interaction");
    }

    @Test
    void editableIsUnrestricted() {
        FileRole editable = FileRole.EDITABLE;
        assertFalse(editable.isReadOnly());
        assertFalse(editable.suppressesInteraction());
        assertNull(editable.badge());
    }

    @Test
    void badgesDistinguishGeneratedFromLibrary() {
        // GENERATED doesn't claim "read-only": GameLoop.java is generated but its run() body is the user's, so
        // a blanket claim on the status line would contradict the editable body right there on screen.
        assertEquals("Generated by BotMaker", FileRole.GENERATED.badge());
        assertEquals("Library - Read Only", FileRole.LIBRARY.badge());
    }
}
