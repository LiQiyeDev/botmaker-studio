package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.StudioProjectSettings.Resolution;

import java.util.List;

/**
 * The catalog of standard capture resolutions offered in the resolution dropdowns (Project Settings and the
 * new-project flow), plus small helpers to flip a {@link Resolution} between landscape and portrait. Keeping
 * this in one place means both call sites share the same list and default (Full-HD landscape).
 *
 * <p>Values are stored <em>landscape</em> (width ≥ height); a portrait toggle just swaps the two components,
 * so the same catalog covers both orientations without duplicate entries.
 */
public final class ResolutionChoices {

    private ResolutionChoices() {}

    /** Full-HD landscape (1920×1080) — the default for new projects. */
    public static final Resolution DEFAULT = new Resolution(1920, 1080);

    /** Common landscape resolutions, largest label groups first; portrait is derived by {@link #oriented}. */
    public static final List<Resolution> LANDSCAPE = List.of(
            new Resolution(1280, 720),
            new Resolution(1366, 768),
            new Resolution(1600, 900),
            new Resolution(1920, 1080),
            new Resolution(2560, 1440),
            new Resolution(3840, 2160));

    /** {@code true} when {@code r} is (or is square-and-treated-as) landscape, i.e. width ≥ height. */
    public static boolean isLandscape(Resolution r) {
        return r != null && r.width() >= r.height();
    }

    /** {@code r} normalised to landscape (width ≥ height) so it matches a catalog entry regardless of input. */
    public static Resolution toLandscape(Resolution r) {
        if (r == null) return null;
        return isLandscape(r) ? r : new Resolution(r.height(), r.width());
    }

    /** {@code r} forced to the requested orientation (swapping width/height when needed). */
    public static Resolution oriented(Resolution r, boolean landscape) {
        if (r == null) return null;
        return isLandscape(r) == landscape ? r : new Resolution(r.height(), r.width());
    }

    /** Human label, e.g. {@code "1920 × 1080"}. */
    public static String label(Resolution r) {
        return r == null ? "" : r.width() + " × " + r.height();
    }

    /**
     * A compact "current resolution" readout for HUDs: {@code "▧ 1600×900  ·  🖵 1920×1080"} (target window
     * size · primary-screen size). Pass {@code null} bounds for a screen/desktop target (shows just the screen).
     */
    public static String readout(java.awt.Rectangle windowBounds) {
        javafx.geometry.Rectangle2D sb = javafx.stage.Screen.getPrimary().getBounds();
        String screen = "🖵 " + (int) sb.getWidth() + "×" + (int) sb.getHeight();
        if (windowBounds == null) return screen;
        return "▧ " + windowBounds.width + "×" + windowBounds.height + "  ·  " + screen;
    }

    /**
     * The same readout, but naming the project's {@code reference} resolution instead of the screen when the
     * target window isn't at it: {@code "▧ 1600×900  ·  ref 1920×1080 ⚠"}.
     *
     * <p>The mismatch is worth a line of its own because nothing else reveals it. Recorded coordinates are raw
     * window-relative pixels — {@code MacroTranslator} scales nothing — while the bot replays against the
     * reference resolution, so authoring over a window at the wrong size silently produces a bot that clicks
     * the wrong places. It is not a rare state either: a private session's host window is deliberately never
     * resized, and the resize of an ordinary window target is best-effort.
     */
    public static String readout(java.awt.Rectangle windowBounds, Resolution reference) {
        if (!mismatched(windowBounds, reference)) return readout(windowBounds);
        return "▧ " + windowBounds.width + "×" + windowBounds.height
                + "  ·  ref " + reference.width() + "×" + reference.height() + " ⚠";
    }

    /** Whether the target window's size differs from the reference resolution the bot will replay against. */
    public static boolean mismatched(java.awt.Rectangle windowBounds, Resolution reference) {
        return windowBounds != null && reference != null
                && (windowBounds.width != reference.width() || windowBounds.height != reference.height());
    }
}
