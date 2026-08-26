package com.botmaker.studio.ui.render.components;

import com.botmaker.sdk.authoring.WireText;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import java.util.List;

/**
 * A length of time as one field per unit — hours, minutes, seconds, milliseconds — with the total spelled out
 * beside it.
 *
 * <p>The control it replaces was one amount plus a unit dropdown, and it could only ever say a multiple of a
 * single unit: "4 hours and 30 minutes" had to be entered as 270 minutes, and anything that didn't divide
 * evenly came back as a raw millisecond count. Four boxes cost three more widgets and remove the arithmetic.
 *
 * <p>It lives here, in the shared widget package, because both duration editors want it: the parameters /
 * user-view editor (`ValueEditors`, which stores the result as {@link WireText#spellDuration} text) and the block
 * editor's wait picker (`DurationPicker`, which commits it as a {@code Duration.ofX(…)} call). They had two
 * separate controls with two different capabilities, which is how the block editor came to be the one place
 * in Studio where a wait could not be four and a half minutes.
 */
public final class DurationFields extends HBox {

    private final TextField hours = unitField("h");
    private final TextField minutes = unitField("m");
    private final TextField seconds = unitField("s");
    private final TextField millis = unitField("ms");
    private final Label preview = new Label();

    public DurationFields(long totalMillis) {
        super(6);
        setAlignment(Pos.CENTER_LEFT);
        long total = Math.max(0L, totalMillis);
        hours.setText(Long.toString(total / 3_600_000L));
        minutes.setText(Long.toString(total / 60_000L % 60));
        seconds.setText(Long.toString(total / 1000L % 60));
        millis.setText(Long.toString(total % 1000L));

        preview.getStyleClass().add("dialog-hint-text");
        for (TextField field : List.of(hours, minutes, seconds, millis)) {
            field.textProperty().addListener((obs, was, now) -> refresh());
            getChildren().addAll(field, unitLabel(field.getPromptText()));
        }
        getChildren().add(preview);
        refresh();
    }

    /** What the four fields add up to. Anything unreadable in a field counts as zero, never as a throw. */
    public long totalMillis() {
        return whole(hours) * 3_600_000L + whole(minutes) * 60_000L + whole(seconds) * 1000L + whole(millis);
    }

    private void refresh() {
        preview.setText("= " + WireText.spellDuration(totalMillis()));
    }

    private static long whole(TextField field) {
        try {
            return Math.max(0L, Long.parseLong(field.getText() == null ? "" : field.getText().trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static TextField unitField(String unit) {
        TextField field = new TextField("0");
        field.setPromptText(unit);
        field.setPrefColumnCount(unit.length() > 1 ? 4 : 3);
        return field;
    }

    private static Label unitLabel(String unit) {
        Label label = new Label(unit);
        label.getStyleClass().add("dialog-hint-text");
        return label;
    }
}
