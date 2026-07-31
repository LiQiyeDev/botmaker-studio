package com.botmaker.studio.services;

import com.botmaker.studio.services.capture.DesktopGrab;
import com.botmaker.studio.services.platform.SessionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 7 — {@code services/platform} and {@code services/capture}.</b> 95 lines at
 * <b>0.0%</b>, and pure predicates: nothing here needs a display, a process or a project.
 *
 * <p>They are worth pinning out of proportion to their size because they are <em>decision</em> functions. The
 * whole Wayland story turns on {@link SessionEnvironment#isWayland()} and {@link DesktopGrab#looksBlank}: get
 * the first wrong and Studio silently uses a capture path that returns black; get the second wrong and it
 * either overlays a black screenshot the user cannot escape, or throws away a perfectly good frame that
 * happened to start with a dark pixel.
 */
class PlatformAndCapturePredicatesTest {

    /** An image whose every pixel is {@code rgb}. */
    private static BufferedImage filled(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, rgb);
        }
        return img;
    }

    // ---- The blank-frame heuristic ----

    @Test
    void anAllBlackFrameLooksBlankAndAnythingElseDoesNot() {
        assertAll(
                () -> assertTrue(DesktopGrab.looksBlank(filled(200, 100, 0x000000)),
                        "an all-black grab is the Wayland failure this exists to catch"),
                () -> assertTrue(DesktopGrab.looksBlank(filled(1, 1, 0x000000)),
                        "a 1×1 image must not divide by zero in the sampling step"),
                () -> assertFalse(DesktopGrab.looksBlank(filled(200, 100, 0xFFFFFF))),
                () -> assertFalse(DesktopGrab.looksBlank(filled(200, 100, 0x010000)),
                        "one unit off black is a real frame — the test is pure black, not 'dark'"));
    }

    /**
     * The heuristic <b>samples</b> — a 20th of the shorter side — so a frame that is black everywhere except
     * a few unsampled pixels is still reported blank. That is deliberate (a real screenshot is never that
     * uniform) but it is the kind of thing an optimisation "fixes" into a full scan, so it is stated here.
     */
    @Test
    void theCheckSamplesRatherThanScanningEveryPixel() {
        BufferedImage almostBlack = filled(200, 200, 0x000000);
        almostBlack.setRGB(1, 1, 0xFFFFFF); // between sample points: step is 200/20 = 10

        assertTrue(DesktopGrab.looksBlank(almostBlack),
                "sampling, not scanning — a lone bright pixel off the grid does not rescue a black frame");

        BufferedImage onTheGrid = filled(200, 200, 0x000000);
        onTheGrid.setRGB(10, 10, 0xFFFFFF);
        assertFalse(DesktopGrab.looksBlank(onTheGrid), "a sampled bright pixel does");
    }

    // ---- Cropping the desktop grab ----

    @Test
    void noDesktopImageIsNotAnException() {
        assertNull(DesktopGrab.cropToBounds(null, new Rectangle(0, 0, 10, 10)),
                "the grab is allowed to fail, and the crop is allowed to say so");
    }

    @Test
    @DisabledIf(value = "isHeadless", disabledReason = "cropToBounds reads the real screen devices — see below")
    void croppingToBoundsClampsRatherThanThrowing() {
        BufferedImage desktop = filled(800, 600, 0x203040);

        assertAll(
                () -> {
                    // Wholly outside the image: still a usable 1×1 rather than a RasterFormatException.
                    BufferedImage out = DesktopGrab.cropToBounds(desktop, new Rectangle(5000, 5000, 100, 100));
                    assertNotNull(out);
                    assertTrue(out.getWidth() >= 1 && out.getHeight() >= 1);
                },
                () -> {
                    // Larger than the image: clamped to what exists.
                    BufferedImage out = DesktopGrab.cropToBounds(desktop, new Rectangle(0, 0, 9999, 9999));
                    assertTrue(out.getWidth() <= 800 && out.getHeight() <= 600);
                });
    }

    /**
     * Bounds are absolute screen coordinates and the image is the virtual desktop, so the crop is offset by
     * the virtual origin. Asserted relative to {@link DesktopGrab#virtualBounds()} rather than to a hard-coded
     * (0,0), because a second monitor left of the primary puts that origin at a negative x.
     */
    @Test
    @DisabledIf(value = "isHeadless", disabledReason = "needs real screen devices — see below")
    void croppingIsRelativeToTheVirtualScreenOrigin() {
        Rectangle virtual = DesktopGrab.virtualBounds();
        BufferedImage desktop = filled(800, 600, 0x000000);
        desktop.setRGB(50, 40, 0xFF0000);

        BufferedImage out = DesktopGrab.cropToBounds(desktop,
                new Rectangle(virtual.x + 50, virtual.y + 40, 20, 20));

        assertEquals(0xFF0000, out.getRGB(0, 0) & 0xFFFFFF,
                "the marked pixel must land at the crop's origin");
    }

    /**
     * The two tests above are conditional, and the condition is a finding rather than an inconvenience:
     * {@link DesktopGrab#virtualBounds()} and the {@link DesktopGrab#cropToBounds} that calls it throw
     * {@link java.awt.HeadlessException} on a headless JVM, while every other public method in the class
     * answers. {@code grabVirtualDesktop} is the shape they should have — it wraps the same call and returns
     * {@code null}.
     *
     * <p>Not a bug in the product: Studio is a desktop app and is never headless in the field, and the one
     * in-app caller of the private twin sits inside a {@code catch (Exception)}. It is recorded here because
     * it is precisely the kind of asymmetry the <b>SV15</b> split has to resolve rather than copy — and
     * because it is the reason two of these assertions are conditional, which would otherwise read as
     * flakiness.
     */
    @Test
    void theVirtualBoundsAnswerOnADisplayAndThrowWithoutOne() {
        if (isHeadless()) {
            assertThrows(java.awt.HeadlessException.class, DesktopGrab::virtualBounds,
                    "unguarded, unlike grabVirtualDesktop, which returns null for the same failure");
        } else {
            assertNotNull(assertDoesNotThrow(DesktopGrab::virtualBounds));
        }
    }

    static boolean isHeadless() {
        return java.awt.GraphicsEnvironment.isHeadless();
    }

    // ---- The session probe ----

    /**
     * {@code isWayland} reads two environment signals and the test cannot set either, so what is asserted is
     * the invariant that holds on any machine: it agrees with the raw env, and it is the single source both
     * {@link DesktopGrab} and the capture service consult (they must never disagree about which one this is).
     */
    @Test
    void theWaylandProbeAgreesWithTheEnvironmentAndWithItself() {
        boolean fromEnv = "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"))
                || System.getenv("WAYLAND_DISPLAY") != null;

        assertEquals(fromEnv, SessionEnvironment.isWayland());
        assertEquals(SessionEnvironment.isWayland(), DesktopGrab.isWayland(),
                "two answers to 'is this Wayland' is how a capture path gets chosen twice, differently");
    }

    @Test
    void theOsAndDesktopProbesAnswerWithoutNulls() {
        assertEquals(System.getProperty("os.name", "").toLowerCase().contains("linux"),
                SessionEnvironment.isLinux());

        String desktop = SessionEnvironment.currentDesktop();
        assertNotNull(desktop, "never null — callers use contains() on it directly");
        assertEquals(desktop.toLowerCase(), desktop, "lower-cased, so callers need not");
    }

    /**
     * The install command is env-dependent (which package manager is on PATH, which desktop is running), so
     * what is asserted is its <em>shape</em>: either "we could not tell" (null) or a runnable argv. A command
     * with a blank element in it fails at exec with an unreadable error, and this is the only place that can
     * catch it — the real invocation needs a polkit prompt.
     */
    @Test
    void theX11InstallCommandIsEitherAbsentOrRunnable() {
        List<String> cmd = assertDoesNotThrow(SessionEnvironment::x11InstallCommand);
        if (cmd == null) return; // no supported package manager here — a legitimate answer

        assertEquals("pkexec", cmd.get(0), "it must ask for privilege, not assume it");
        assertTrue(cmd.size() >= 4, "pkexec + manager + subcommand + at least one package: " + cmd);
        for (String arg : cmd) {
            assertNotNull(arg, cmd.toString());
            assertFalse(arg.isBlank(), "a blank argv element fails at exec with no useful message: " + cmd);
        }
    }
}
