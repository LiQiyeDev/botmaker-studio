package com.botmaker.studio.ui.app.record;

import com.botmaker.studio.ui.app.overlay.OverlayToolbars;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The small, always-on-top floating toolbar for the macro recorder — the same non-covering, transparent
 * mini-toolbar pattern as {@code capture.OverlayTemplateCapture} (it sits just above the target window so the
 * app stays fully clickable while you perform the actions being recorded). Pure view: it owns no recording
 * state; {@link MacroRecorder} drives the label and button text through the setters.
 *
 * <p>Buttons: a primary Record/Pause/Resume toggle, {@code ■ Stop & Insert} (translate + insert the blocks),
 * and {@code ✕ Close} (cancel without inserting). Escape also cancels.
 */
public final class MacroRecorderToolbar {

    private final Stage stage;
    private final Label status = new Label("Ready to record");
    private final Button primary = new Button("● Record");
    private final Button stop = new Button("■ Stop & Insert");

    public MacroRecorderToolbar(Window owner, java.awt.Rectangle windowBounds,
                                Runnable onPrimary, Runnable onStop, Runnable onClose) {
        status.setTextFill(Color.web("#c9d4e6"));
        primary.setOnAction(e -> onPrimary.run());
        stop.setDisable(true);
        stop.setOnAction(e -> onStop.run());
        Button close = new Button("✕ Close");
        close.setOnAction(e -> onClose.run());

        HBox bar = new HBox(8, status, primary, stop, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));
        bar.setStyle("-fx-background-color: rgba(20,24,33,0.92); -fx-background-radius: 8;");

        // Shared: draggable, always-on-top, ownerless (so Studio can be minimized without hiding this).
        stage = OverlayToolbars.show(bar, windowBounds);
        stage.getScene().setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) onClose.run(); });
    }

    /** Updates the left-hand status text (e.g. {@code "Recording — 5 actions"}). */
    public void setStatus(String text) {
        status.setText(text);
    }

    /** Sets the primary button label (Record → Pause → Resume). */
    public void setPrimaryText(String text) {
        primary.setText(text);
    }

    /** Enables the Stop button once a recording has started. */
    public void enableStop(boolean enabled) {
        stop.setDisable(!enabled);
    }

    /** Raises the toolbar to the front (used when a second open is requested). */
    public void toFront() {
        stage.toFront();
    }

    public void close() {
        stage.close();
    }
}
