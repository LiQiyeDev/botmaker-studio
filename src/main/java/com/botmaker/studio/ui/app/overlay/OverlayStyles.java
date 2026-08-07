package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;

/**
 * The look and the small talk of the overlay windows: the translucent panel style both overlays draw on, the
 * dim-on-dark label colours, and the alert helpers.
 *
 * <p>Every one of these was duplicated between {@link ProgramShapeOverlay} and
 * {@code capture.OverlayTemplateCapture} — the panel background as the same literal in two places, {@code warn}
 * as the same four lines. They are the parts that have to <em>stay</em> identical: the two overlays are
 * routinely on screen together (the HUD opens capture surfaces), so a panel colour that drifts in one reads as
 * a rendering bug rather than a style choice.
 */
public final class OverlayStyles {

    private OverlayStyles() {}

    /** Rounded, semi-opaque panel background shared by every overlay panel and mini-toolbar. */
    public static final String PANEL = "-fx-background-color: rgba(20,24,33,0.92); -fx-background-radius: 8;";

    /** Body text on a {@link #PANEL}. */
    public static final String LABEL = "-fx-text-fill: #c9d4e6;";

    /** Secondary text on a {@link #PANEL}: captions, hints and "nothing here yet" placeholders. */
    public static final String DIM_LABEL = "-fx-text-fill: #8b93a1;";

    /** A {@link #LABEL}-styled label. */
    public static Label label(String text) {
        Label l = new Label(text);
        l.setStyle(LABEL);
        return l;
    }

    /** A {@link #DIM_LABEL}-styled label. */
    public static Label dimLabel(String text) {
        Label l = new Label(text);
        l.setStyle(DIM_LABEL);
        return l;
    }

    /** A compact glyph button with a tooltip — the overlay's only button shape outside the palette. */
    public static Button iconButton(String glyph, String tip, Runnable action) {
        Button b = new Button(glyph);
        b.setTooltip(new Tooltip(tip));
        b.setMinWidth(30);
        b.setOnAction(e -> action.run());
        return b;
    }

    /**
     * Adds the app's current theme style class to {@code node}. An overlay stage builds its own
     * {@link javafx.scene.Scene}, so it does not inherit the main window's.
     *
     * <p>Kept as its own entry point rather than folded into {@link ThemedWindows#apply(javafx.scene.Scene)}:
     * an overlay's scene is deliberately transparent and loads {@code blocks.css} itself, so it wants the
     * class and nothing else.
     */
    public static void applyThemeClass(Parent node) {
        ThemedWindows.applyThemeClass(node);
    }

    public static void warn(Window owner, String message) {
        alert(owner, Alert.AlertType.WARNING, message);
    }

    public static void info(Window owner, String message) {
        alert(owner, Alert.AlertType.INFORMATION, message);
    }

    private static void alert(Window owner, Alert.AlertType type, String message) {
        Alert alert = ThemedWindows.alert(type, message);
        if (owner != null) alert.initOwner(owner);
        alert.showAndWait();
    }
}
