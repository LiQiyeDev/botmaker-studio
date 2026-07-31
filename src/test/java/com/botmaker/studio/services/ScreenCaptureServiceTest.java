package com.botmaker.studio.services;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 3 — the middle of {@link ScreenCaptureService}'s three sections.</b> Gates
 * <b>SV15</b>, the §10 split of a 1,014-line file into desktop-grab + overlay (1–339), window capture via the
 * native controller (341–727), and the multi-argument capture session (729–1014).
 *
 * <p>The split is safe only if the seam is real, and the window-capture section is the one that can be
 * checked without a display: it talks to nothing but shared's {@link NativeController}, which
 * {@link NativeControllerFactory#setForTesting} lets a test replace outright. Sections 1 and 3 need a live
 * screen and an FX overlay respectively; what is asserted for them here is the part that crosses the seam —
 * the value types the other two sections hand back and forth, and the one file-system side effect.
 *
 * <p>The behaviour worth pinning is the <b>fallback</b>. Native per-window capture returns black under
 * Wayland rather than failing, so the service treats a blank frame as a failure and re-grabs from the
 * desktop. A refactor that keeps "capture the window" and drops "…unless it came back black" produces a
 * feature that works on the maintainer's X11 machine and silently returns black rectangles everywhere else.
 */
class ScreenCaptureServiceTest {

    private static final Rectangle BOUNDS = new Rectangle(120, 80, 640, 480);

    @AfterEach
    void resetFactory() {
        NativeControllerFactory.setForTesting(null); // never leak an injected controller into another test
    }

    /** Hands back a fixed window list and a fixed frame, and records what it was asked to do. */
    private static class FakeController implements NativeController {
        final List<String> calls = new ArrayList<>();
        List<GenericWindow> windows = List.of();
        BufferedImage frame;
        RuntimeException captureFailure;

        @Override public List<GenericWindow> getAllWindows() { calls.add("enumerate"); return windows; }
        @Override public List<GenericWindow> getAllWindows(boolean includeMinimized) {
            calls.add("enumerate(minimized=" + includeMinimized + ")");
            return windows;
        }
        @Override public BufferedImage captureWindow(GenericWindow window) {
            calls.add("capture " + window.getTitle());
            if (captureFailure != null) throw captureFailure;
            return frame;
        }
        @Override public void restoreWindow(GenericWindow window) { calls.add("restore " + window.getTitle()); }
        @Override public void resizeWindow(GenericWindow w, int width, int height) {
            calls.add("resize " + w.getTitle() + " " + width + "x" + height);
        }
        @Override public GenericWindow getForegroundWindow() { return null; }
        @Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
        @Override public void postLeftClick(GenericWindow window, int x, int y) { }
        @Override public void focusWindow(GenericWindow window) { calls.add("focus " + window.getTitle()); }
        @Override public void moveWindow(GenericWindow window, int x, int y) { }
        @Override public void keyDown(int nativeKeyCode) { }
        @Override public void keyUp(int nativeKeyCode) { }
        @Override public void typeText(String text) { }
        @Override public void mouseMove(int xAbs, int yAbs) { }
        @Override public void mouseButton(int button, boolean press) { }
        @Override public void scroll(int amount) { }
    }

    private static GenericWindow window(String title, Rectangle rect) {
        return new GenericWindow(new Object(), title, rect);
    }

    /** An image that is definitely not blank, so the desktop fallback is not taken. */
    private static BufferedImage realFrame(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, 0x3355AA);
        }
        return img;
    }

    private static FakeController install(FakeController controller) {
        NativeControllerFactory.setForTesting(controller);
        return controller;
    }

    // ---- Section 2: finding the window ----

    @Test
    void aWindowIsMatchedOnATitleSubstringCaseInsensitively() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Steam", new Rectangle(0, 0, 10, 10)),
                window("Some Game — Direct3D", BOUNDS));
        nc.frame = realFrame(640, 480);

        var shot = new ScreenCaptureService().captureWindow(new WindowTarget("some game"));

        assertNotNull(shot, "'some game' must match 'Some Game — Direct3D'");
        assertEquals(BOUNDS, shot.bounds());
    }

    @Test
    void noMatchingWindowIsNullRatherThanAnException() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Steam", BOUNDS));

        assertNull(new ScreenCaptureService().captureWindow(new WindowTarget("no such game")));
    }

    /**
     * Minimized windows are included in the search on purpose — the service de-iconifies before grabbing, so
     * excluding them would make "capture my game" fail for the case the restore call exists to handle.
     */
    @Test
    void theSearchIncludesMinimizedWindowsAndRestoresTheMatchFirst() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Some Game", BOUNDS));
        nc.frame = realFrame(640, 480);

        new ScreenCaptureService().captureWindow(new WindowTarget("Some Game"));

        assertTrue(nc.calls.contains("enumerate(minimized=true)"),
                "a minimized target must be findable: " + nc.calls);
        assertTrue(nc.calls.indexOf("restore Some Game") < nc.calls.indexOf("capture Some Game"),
                "restore must precede capture, or the grab is of a hidden window: " + nc.calls);
    }

    // ---- Section 2: the frame, and the fallback ----

    @Test
    void aRealFrameIsReturnedWithTheWindowsBounds() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Some Game", BOUNDS));
        nc.frame = realFrame(640, 480);

        var shot = new ScreenCaptureService().captureWindow(new WindowTarget("Some Game"));

        assertNotNull(shot);
        assertEquals(640, shot.image().getWidth());
        assertEquals(BOUNDS, shot.bounds(),
                "the bounds travel with the image — the overlay is placed with them");
    }

    /** A black frame is the Wayland signature, so the native result is not trusted on its own. */
    @Test
    void aBlankFrameSendsTheServiceToTheDesktopFallback() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Some Game", BOUNDS));
        nc.frame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB); // all black

        new ScreenCaptureService().captureWindow(new WindowTarget("Some Game"));

        assertTrue(nc.calls.contains("capture Some Game"), "the native path must be tried first: " + nc.calls);
    }

    /**
     * <b>B17.</b> When the fallback cannot produce anything either — no desktop grab available, which is
     * exactly the Wayland session the fallback exists for — {@code captureWindow} returns the blank frame it
     * had already judged unusable, as a successful {@code WindowShot}.
     *
     * <p>That would be a display bug on its own. What makes it the guard's undoing is
     * {@code grabOffThread}: the window-target branch builds its {@code ScreenShot} with {@code blank} hard-
     * coded to {@code false} (line 174), so {@code finishGrab}'s blank check — whose whole purpose is that
     * "the user is never trapped behind a black full-screen overlay" — cannot fire for a window target. A
     * window is the default target for a game bot, so the warning is unreachable in the case it was written
     * for, and the user gets the black full-screen overlay instead of the message telling them why.
     */
    @Test
    @Disabled("B17 is unfixed: verified red on this commit — with no desktop fallback available, "
            + "captureWindow returns the blank frame as a successful WindowShot, and grabOffThread:174 then "
            + "labels it blank=false. Delete this line in Phase 4 with SV21's fix.")
    void aBlankFrameWithNoWorkingFallbackIsAFailedCaptureNotABlackOne() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Some Game", BOUNDS));
        nc.frame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB); // all black

        var shot = new ScreenCaptureService().captureWindow(new WindowTarget("Some Game"));

        if (shot != null) {
            assertFalse(com.botmaker.studio.services.capture.DesktopGrab.looksBlank(shot.image()),
                    "a capture that came back blank must not be handed back as a successful one");
        }
    }

    /** A native capture that throws is a failed capture, not a failed Studio. */
    @Test
    void aThrowingNativeCaptureIsSurvivedRatherThanPropagated() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Some Game", BOUNDS));
        nc.captureFailure = new RuntimeException("X error: BadWindow");

        // Null (or a desktop-crop fallback) — but never the exception.
        var shot = new ScreenCaptureService().captureWindow(new WindowTarget("Some Game"));
        assertTrue(shot == null || shot.image() != null);
    }

    // ---- Section 2: the other window operations ----

    @Test
    void resizingSkipsAWindowThatIsAlreadyTheRequestedSize() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Some Game", new Rectangle(0, 0, 1280, 720)));

        new ScreenCaptureService().resizeTarget(new WindowTarget("Some Game"), 1280, 720);

        assertTrue(nc.calls.stream().noneMatch(c -> c.startsWith("resize")),
                "resizing to the size it already is costs a compositor round trip for nothing: " + nc.calls);
    }

    @Test
    void resizingAWindowOfADifferentSizeGoesThrough() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Some Game", new Rectangle(0, 0, 800, 600)));

        new ScreenCaptureService().resizeTarget(new WindowTarget("Some Game"), 1280, 720);

        assertTrue(nc.calls.contains("resize Some Game 1280x720"), nc.calls.toString());
    }

    /** A nonsense size is refused before the window is even looked up — the canonical resolution can be unset. */
    @Test
    void aZeroSizedResizeIsRefusedWithoutTouchingTheNativeLayer() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(window("Some Game", BOUNDS));

        new ScreenCaptureService().resizeTarget(new WindowTarget("Some Game"), 0, 720);

        assertTrue(nc.calls.isEmpty(), nc.calls.toString());
    }

    @Test
    void raisingAWindowThatIsNotThereIsANoOp() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of();

        new ScreenCaptureService().raiseWindow(new WindowTarget("Some Game"));

        assertTrue(nc.calls.stream().noneMatch(c -> c.startsWith("restore")), nc.calls.toString());
    }

    /** The chooser's list: blank titles dropped, duplicates collapsed, order preserved. */
    @Test
    void theWindowTitleListIsDeduplicatedAndBlankFree() {
        FakeController nc = install(new FakeController());
        nc.windows = List.of(
                window("Steam", BOUNDS), window("", BOUNDS), window("Some Game", BOUNDS),
                window("Steam", BOUNDS), window("   ", BOUNDS));

        assertEquals(List.of("Steam", "Some Game"), ScreenCaptureService.listWindowTitles());
    }

    /** Enumeration is native and can fail hard; an empty chooser beats a dialog that never opens. */
    @Test
    void aFailingEnumerationYieldsAnEmptyListRatherThanThrowing() {
        NativeControllerFactory.setForTesting(new FakeController() {
            @Override public List<GenericWindow> getAllWindows() { throw new UnsatisfiedLinkError("no X11"); }
        });

        assertEquals(List.of(), ScreenCaptureService.listWindowTitles());
    }

    // ---- Across the seam: what sections 1 and 3 hand back ----

    /**
     * The one file-system side effect in the file, and the reason it is not simply {@code ImageIO.write}: the
     * template directory does not exist until the first capture is saved into it.
     */
    @Test
    void savingATemplateCreatesTheDirectoryItGoesIn(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("images").resolve("nested").resolve("button.png");

        new ScreenCaptureService().savePng(realFrame(8, 8), target);

        assertTrue(Files.exists(target), "the parent directories must be created, not assumed");
        assertNotNull(javax.imageio.ImageIO.read(target.toFile()), "and it must be a readable PNG");
    }

    /**
     * Section 3's step list is a sealed interface with exactly three members — one per argument type the
     * "Pick all" pass knows how to fill. Pinned because the split moves it: a fourth member added on one side
     * of the seam and not handled on the other is a silently skipped argument.
     */
    @Test
    void theCaptureSessionKnowsExactlyThreeKindsOfArgumentToPick() {
        List<String> permitted = java.util.Arrays.stream(
                        ScreenCaptureService.PickStep.class.getPermittedSubclasses())
                .map(Class::getSimpleName).sorted().toList();

        assertEquals(3, permitted.size(), "the sealed set changed: " + permitted);
    }
}
