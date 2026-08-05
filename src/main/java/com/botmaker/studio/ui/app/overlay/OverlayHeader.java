package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.ui.app.ResolutionChoices;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The HUD's title bar: what this window is, <b>what size it is authoring against</b>, and the ✕ that closes
 * it. It is also the drag handle — the stage is borderless, so {@code OverlayToolbars.installDrag} is attached
 * to {@link #node()}.
 *
 * <p>The size readout is the part worth a class. Recorded coordinates are raw window-relative pixels
 * ({@code MacroTranslator} scales nothing) while the bot replays against the project's <em>reference</em>
 * resolution, so authoring over a window that is not at that size silently produces a bot that clicks the
 * wrong places — and until this line said so, nothing on screen did. It is not a rare state: a private
 * session's host window is deliberately never resized, and resizing an ordinary window target is
 * best-effort. A mismatch therefore reads {@code ▧ 1600×900 · ref 1920×1080 ⚠} in amber rather than the
 * window size alone in grey.
 */
final class OverlayHeader {

    private static final String OK_STYLE = "-fx-text-fill: #8fa3bf; -fx-font-size: 11px;";
    private static final String MISMATCH_STYLE = "-fx-text-fill: #e0a33a; -fx-font-size: 11px;";

    private final Label resolution = new Label();
    private final HBox node;

    OverlayHeader(Runnable onClose) {
        Label title = new Label("Overlay Editor");
        title.setStyle("-fx-text-fill: #c9d4e6; -fx-font-weight: bold;");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        Button close = new Button("✕");
        close.setTooltip(new Tooltip("Close overlay"));
        close.setOnAction(e -> onClose.run());

        node = new HBox(8, title, resolution, spring, close);
        node.setAlignment(Pos.CENTER_LEFT);
        node.setPadding(new Insets(6, 8, 6, 10));
        node.setStyle(OverlayStyles.PANEL);
    }

    /** The bar itself — also the stage's drag handle. */
    HBox node() {
        return node;
    }

    /** Re-states the size being authored against, flagged when it isn't the reference the bot replays at. */
    void showSize(java.awt.Rectangle windowBounds, StudioProjectSettings.Resolution reference) {
        resolution.setText(ResolutionChoices.readout(windowBounds, reference));
        resolution.setStyle(ResolutionChoices.mismatched(windowBounds, reference) ? MISMATCH_STYLE : OK_STYLE);
    }
}
