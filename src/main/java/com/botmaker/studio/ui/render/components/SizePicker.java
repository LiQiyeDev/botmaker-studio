package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ScreenCaptureService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;

/**
 * A control standing in for a {@code Size} argument. Shows the current {@code w × h} and opens a menu to
 * "Measure on screen…" (drag a rectangle over the thing being measured) or "Edit values…" (a manual popup).
 *
 * <p>Measuring is the rubber-band region selection with the origin thrown away, which is exactly how a person
 * measures something on screen: they do not know that a health bar is 240 wide, they know where its ends are.
 *
 * <p>{@code Size} was the one geometry type with no picker at all, so it rendered as a bare {@code new Size(…)}
 * instantiation block — two numbers to type from nothing, next to a {@code Point} and a {@code Rect} that could
 * both be taken off the screen.
 */
public final class SizePicker {

    private SizePicker() {}

    public static Node create(CodeEditorService context, ValueSlot arg) {
        MenuButton button = new MenuButton();
        button.getStyleClass().add("size-picker");
        button.setText(label(arg));

        button.setOnShowing(e -> {
            button.getItems().clear();
            MenuItem measure = new MenuItem("Measure on screen…");
            measure.setOnAction(a -> {
                Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
                ScreenCaptureService.forProject(context).selectRegion(owner, r -> Platform.runLater(() ->
                        context.getCodeEditor().setSize(arg.node(), r[2], r[3])));
            });
            MenuItem edit = new MenuItem("Edit values…");
            edit.setOnAction(a -> NumberFieldsDialog.show("Size", new String[]{"width", "height"},
                    currentValues(arg), button.getScene() == null ? null : button.getScene().getWindow(),
                    v -> context.getCodeEditor().setSize(arg.node(), v[0], v[1])));
            button.getItems().addAll(measure, new SeparatorMenuItem(), edit);
        });
        return button;
    }


    /** {@code w × h} for a {@code new Size(...)}; otherwise the raw expression (e.g. a variable name). */
    private static String label(ValueSlot arg) {
        int[] v = currentValues(arg);
        if (v != null) return v[0] + " × " + v[1];
        String raw = arg.source();
        return raw.isBlank() ? "Choose size…" : raw;
    }

    /** Reads {@code [w,h]} from {@code new Size(w,h)}, defaulting missing args to 0; null if not a Size ctor. */
    private static int[] currentValues(ValueSlot arg) {
        if (arg.node() instanceof ClassInstanceCreation cic) {
            int[] out = new int[2];
            for (int i = 0; i < 2 && i < cic.arguments().size(); i++) {
                out[i] = NumberFieldsDialog.parseInt(cic.arguments().get(i).toString());
            }
            return out;
        }
        return null;
    }
}
