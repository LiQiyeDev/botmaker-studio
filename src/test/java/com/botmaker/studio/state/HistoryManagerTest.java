package com.botmaker.studio.state;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's history, over the files a change wrote. The whole point of this class is the multi-file step:
 * a signature migration rewrites the declaration in one file and the calls in others, and ↶ has to put back
 * all of them or the project no longer builds.
 */
public class HistoryManagerTest {

    private static final Path BOT = Path.of("/p/Bot.java");
    private static final Path GO_HOME = Path.of("/p/GoHome.java");

    /** The files as they stand, with the history restoring straight into them. */
    private final Map<Path, String> disk = new LinkedHashMap<>();
    private final HistoryManager history = new HistoryManager(disk::putAll);

    private static Map<Path, String> files(Object... pathsAndTexts) {
        Map<Path, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndTexts.length; i += 2) {
            map.put((Path) pathsAndTexts[i], (String) pathsAndTexts[i + 1]);
        }
        return map;
    }

    @Test
    void undoingAChangeThatSpannedFilesPutsBackEveryOneOfThem() {
        disk.putAll(files(BOT, "goHome()", GO_HOME, "goHome()"));
        history.record("the change to goHome, in 2 files",
                files(BOT, "goHome()", GO_HOME, "goHome()"),
                files(BOT, "walkHome()", GO_HOME, "walkHome()"));
        disk.putAll(files(BOT, "walkHome()", GO_HOME, "walkHome()"));

        history.undo();
        assertEquals("goHome()", disk.get(BOT));
        assertEquals("goHome()", disk.get(GO_HOME), "the file that only calls it is part of the same step");

        history.redo();
        assertEquals("walkHome()", disk.get(BOT));
        assertEquals("walkHome()", disk.get(GO_HOME));
    }

    @Test
    void anOrdinaryEditIsOneFileAndOneStep() {
        history.record("the last change", files(BOT, "a"), files(BOT, "b"));
        assertTrue(history.canUndo());
        assertEquals("the last change", history.undoLabel());

        history.undo();
        assertEquals("a", disk.get(BOT));
        assertFalse(history.canUndo());
        assertFalse(disk.containsKey(GO_HOME), "a one-file edit never mentions a file it did not write");
    }

    @Test
    void anEditThatChangedNothingIsNotAStep() {
        history.record("the last change", files(BOT, "a"), files(BOT, "a"));
        assertFalse(history.canUndo());
    }

    @Test
    void aWriteWithNoFilesBehindItIsIgnored() {
        // The first CodeUpdatedEvent of a session has no previous text and no active file yet.
        history.record("the last change", Map.of(), Map.of());
        assertFalse(history.canUndo());
    }
}
