package com.botmaker.studio.ui.app.capture;

import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio ui MISSING 3 — {@code MagicWand.Session} releases every {@code Mat} on {@code close()}.</b> Gates
 * <b>SU14</b> ({@code stage.setOnHidden(e -> session.close())} in {@code ObjectCaptureSurface}).
 *
 * <p>{@link MagicWandTest} proves the extraction is correct; nothing proved the cleanup is. A session holds
 * six live {@code Mat}s plus two unbounded-looking history stacks, all of them native allocations outside the
 * Java heap — so a leak here is invisible to the GC, invisible in a heap dump, and shows up only as the
 * editor's RSS climbing while a user refines one template.
 *
 * <p>The audit's phrasing was "assert {@code nativeObj == 0} (or {@code empty()}) post-close". It is
 * {@code empty()}: OpenCV's {@code release()} frees the pixel buffer and leaves the wrapper's handle intact,
 * so {@code nativeObj} stays non-zero by design and asserting on it would fail against correct code.
 */
class MagicWandSessionLifecycleTest {

    /**
     * {@link MagicWandTest}'s scene — a textured rectangle on noise. Reused verbatim rather than shrunk:
     * a smaller frame makes GrabCut degenerate (see the last test), which would make these leak assertions
     * pass for the wrong reason.
     */
    private static BufferedImage scene() {
        BufferedImage img = new BufferedImage(200, 160, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(11);
        for (int y = 0; y < 160; y++) {
            for (int x = 0; x < 200; x++) img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        }
        for (int y = 50; y < 110; y++) {
            for (int x = 60; x < 130; x++) img.setRGB(x, y, ((x + y) % 8 < 4) ? 0x808080 : 0x888888);
        }
        return img;
    }

    /** A session boxed the way a user drags a rough rectangle around the object. */
    private static MagicWand.Session boxedSession() {
        MagicWand.Session session = new MagicWand.Session(scene());
        assertNotNull(session.initFromRect(48, 38, 94, 84, MagicWand.DEFAULT_ITERATIONS),
                "the fixture must produce a real selection");
        return session;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(MagicWand.Session session, String name) {
        try {
            Field f = MagicWand.Session.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(session);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("MagicWand.Session." + name + " moved; this test tracks its Mats", e);
        }
    }

    /** Every {@code Mat} the session owns directly, in declaration order. */
    private static List<Mat> ownedMats(MagicWand.Session session) {
        List<Mat> mats = new ArrayList<>();
        for (String name : List.of("image", "mask", "bgModel", "fgModel")) {
            Mat m = field(session, name);
            assertNotNull(m, name + " is null before close");
            mats.add(m);
        }
        return mats;
    }

    private static Deque<Mat> undo(MagicWand.Session session) {
        return field(session, "undoStack");
    }

    private static Deque<Mat> redo(MagicWand.Session session) {
        return field(session, "redoStack");
    }

    // --- close() ---

    @Test
    void closingASessionReleasesEveryMatItOwns() {
        MagicWand.Session session = boxedSession();
        List<Mat> owned = ownedMats(session);
        for (Mat m : owned) assertFalse(m.empty(), "a live session's Mats must hold pixels");

        session.close();

        for (Mat m : owned) {
            assertTrue(m.empty(), "a Mat survived close() still holding native pixels: " + m);
        }
    }

    @Test
    void closingASessionReleasesAndClearsBothHistoryStacks() {
        MagicWand.Session session = boxedSession();
        session.refine(1);
        session.undo();                       // one snapshot on each stack
        List<Mat> snapshots = new ArrayList<>();
        snapshots.addAll(undo(session));
        snapshots.addAll(redo(session));
        assertFalse(snapshots.isEmpty(), "the scenario must actually populate both stacks");

        session.close();

        assertTrue(undo(session).isEmpty(), "undo history not cleared");
        assertTrue(redo(session).isEmpty(), "redo history not cleared");
        for (Mat m : snapshots) assertTrue(m.empty(), "a history snapshot leaked its pixels");
    }

    /** The dialog's close handler can fire twice (window hidden, then disposed); the second must be harmless. */
    @Test
    void closingTwiceIsHarmless() {
        MagicWand.Session session = boxedSession();

        session.close();
        session.close();

        for (Mat m : ownedMats(session)) assertTrue(m.empty());
    }

    @Test
    void aSessionThatWasNeverInitialisedStillClosesCleanly() {
        MagicWand.Session session = new MagicWand.Session(scene());

        session.close();

        for (Mat m : ownedMats(session)) assertTrue(m.empty());
    }

    // --- The bounded history ---

    /**
     * The other half of the audit's entry: {@code pushHistory} evicts past {@code MAX_HISTORY}, and the
     * evicted snapshot must be released rather than dropped on the floor. Each snapshot is a full-frame
     * {@code CV_8UC1} clone, so an unreleased eviction is a steady native leak for as long as the user keeps
     * refining — the one interaction in this dialog they are expected to repeat.
     */
    @Test
    void evictingTheOldestHistorySnapshotReleasesIt() {
        int maxHistory = 24;
        MagicWand.Session session = boxedSession();

        for (int i = 0; i < maxHistory; i++) session.refine(1);
        assertEquals(maxHistory, undo(session).size(),
                "the stack must be at its bound before the eviction under test");

        Mat oldest = new ArrayDeque<>(undo(session)).peekLast();
        assertNotNull(oldest);
        assertFalse(oldest.empty());

        session.refine(1);                    // one more push → the oldest is evicted

        assertEquals(maxHistory, undo(session).size(), "the history is bounded, not merely trimmed on close");
        assertTrue(oldest.empty(), "the evicted snapshot kept its native pixels");
        session.close();
    }

    /** A new solve invalidates the redo branch — and those snapshots are native too. */
    @Test
    void startingANewBranchReleasesTheDiscardedRedoHistory() {
        MagicWand.Session session = boxedSession();
        session.refine(1);
        session.undo();
        List<Mat> discarded = new ArrayList<>(redo(session));
        assertFalse(discarded.isEmpty());

        session.refine(1);                    // a new solve from here abandons the redo branch

        assertTrue(redo(session).isEmpty());
        for (Mat m : discarded) assertTrue(m.empty(), "an abandoned redo snapshot leaked");
        session.close();
    }

    /** Undo and redo each consume the snapshot they popped; neither may leave it allocated. */
    @Test
    void undoAndRedoReleaseTheSnapshotTheyConsume() {
        MagicWand.Session session = boxedSession();
        session.refine(1);

        Mat consumedByUndo = undo(session).peek();
        assertNotNull(consumedByUndo);
        session.undo();
        assertTrue(consumedByUndo.empty(), "undo() left its popped snapshot allocated");

        Mat consumedByRedo = redo(session).peek();
        assertNotNull(consumedByRedo);
        session.redo();
        assertTrue(consumedByRedo.empty(), "redo() left its popped snapshot allocated");

        session.close();
    }

    /**
     * <b>B24 — a stroke that covers the whole selection throws out of {@code refine}.</b> GrabCut's
     * {@code initGMMs} asserts that the ROI holds <em>both</em> background and foreground samples; painting
     * definite foreground over the working box leaves it with only one, and the native assertion surfaces as
     * a {@code CvException}.
     *
     * <p>It does not crash the dialog — {@code ObjectCaptureSurface.solve} catches {@code RuntimeException},
     * which {@code CvException} is — but the handling is {@code printStackTrace()} and a {@code null} result,
     * so the user's stroke silently does nothing and the only trace is on stdout. The snapshot
     * {@code pushHistory()} took before the throw stays on the undo stack, so the failed solve also costs an
     * undo step that reverts to the same image. Characterised here, and it is one of the six uncommented
     * silent catches SU15 has to justify.
     */
    @Test
    void paintingOverTheWholeSelectionMakesRefineThrowRatherThanReportNoChange() {
        try (MagicWand.Session session = boxedSession()) {
            session.paint(100, 80, 500, true);

            assertThrows(RuntimeException.class, () -> session.refine(1),
                    "a degenerate mask must be reported somehow; today it is a native assertion");
            assertEquals(2, undo(session).size(),
                    "and the snapshot taken before the throw stays, costing an undo that changes nothing");
        }
    }

    @Test
    void undoAndRedoOnAnEmptyHistoryDoNothingRatherThanThrowing() {
        try (MagicWand.Session session = new MagicWand.Session(scene())) {
            assertFalse(session.canUndo());
            assertFalse(session.canRedo());
            org.junit.jupiter.api.Assertions.assertNull(session.undo());
            org.junit.jupiter.api.Assertions.assertNull(session.redo());
        }
    }
}
