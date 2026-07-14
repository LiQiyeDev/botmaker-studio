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
}
