package com.botmaker.studio.ui.app.record;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
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

        Scene scene = new Scene(bar, Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) onClose.run(); });

        stage = new Stage(StageStyle.TRANSPARENT);
        if (owner != null) stage.initOwner(owner);
        stage.setAlwaysOnTop(true);
        stage.setScene(scene);
        stage.show();
        stage.sizeToScene();
        // Sit just above the window's top edge, or tuck inside the top when there's no room above.
        double barHeight = stage.getHeight();
        stage.setX(windowBounds.x);
        stage.setY(windowBounds.y - barHeight - 4 >= 0 ? windowBounds.y - barHeight - 4 : windowBounds.y + 4);
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

    public void close() {
        stage.close();
    }
}
