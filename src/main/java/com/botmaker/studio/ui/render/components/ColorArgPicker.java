package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.app.capture.ColorSampler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NumberLiteral;

import java.util.List;

/**
 * Editor for a {@code java.awt.Color} argument (e.g. the target of {@code Pixel.matchesAt} or the
 * {@code low}/{@code high} of {@code Pixel.findInRange}), as two ways of naming a colour:
 *
 * <ul>
 *   <li>a JavaFX {@link ColorPicker} swatch onto the OS colour palette, for a colour the user already has in
 *       mind, instead of hand-writing {@code new Color(r, g, b)};</li>
 *   <li>an <b>eyedropper</b> onto a frozen frame of the game ({@link ColorSampler}), for the far more common
 *       case where they do not have one in mind at all — they have a pixel on screen and need the value that
 *       matches it. Game art is shaded, compressed and anti-aliased, so the red of a health bar is never
 *       {@code Color.RED}, and no amount of staring at a palette will produce it.</li>
 * </ul>
 *
 * <p>Both paths commit the same fully-qualified {@code new java.awt.Color(r, g, b)} through
 * {@link com.botmaker.studio.parser.CodeEditor#replaceWithRawExpression} (fully qualified so no import is
 * needed), so nothing downstream can tell which one was used.
 *
 * <p>Selected by {@link com.botmaker.studio.ui.render.components.pickers.PickerRegistry} for any {@code Color}
 * parameter. When the current value is already a {@code new Color(r, g, b)} literal its RGB seeds the swatch;
 * anything else (a named constant, a variable) leaves the swatch at its default and is overwritten on the first pick.
 */
public final class ColorArgPicker {

    private ColorArgPicker() {}

    public static Node create(CodeEditorService context, ValueSlot arg) {
        ColorPicker picker = new ColorPicker();
        picker.getStyleClass().add("color-arg-picker");
        Color initial = currentColor(arg);
        if (initial != null) picker.setValue(initial);

        picker.setOnAction(e -> {
            Color c = picker.getValue();
            commit(context, arg, (int) Math.round(c.getRed() * 255),
                    (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
        });

        Button eyedropper = new Button("⌖");
        eyedropper.getStyleClass().add("color-eyedropper");
        eyedropper.setTooltip(new Tooltip("Pick a colour off a frame of the game"));
        eyedropper.setOnAction(e -> ColorSampler.open(context,
                eyedropper.getScene() == null ? null : eyedropper.getScene().getWindow(),
                sample -> {
                    java.awt.Color c = sample.color();
                    picker.setValue(Color.rgb(c.getRed(), c.getGreen(), c.getBlue()));
                    commit(context, arg, c.getRed(), c.getGreen(), c.getBlue());
                }));

        HBox box = new HBox(4, picker, eyedropper);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** The one place either path writes the slot — fully qualified, so the user's file needs no import. */
    private static void commit(CodeEditorService context, ValueSlot arg, int r, int g, int b) {
        context.getCodeEditor().replaceWithRawExpression(arg.node(),
                "new java.awt.Color(" + r + ", " + g + ", " + b + ")");
    }

    /** The RGB of a {@code new Color(r, g, b)} / {@code new java.awt.Color(r, g, b)} literal, else null. */
    private static Color currentColor(ValueSlot arg) {
        if (!(arg.node() instanceof ClassInstanceCreation cic)) return null;
        List<?> args = cic.arguments();
        if (args.size() < 3
                || !(args.get(0) instanceof NumberLiteral rl)
                || !(args.get(1) instanceof NumberLiteral gl)
                || !(args.get(2) instanceof NumberLiteral bl)) {
            return null;
        }
        try {
            return Color.rgb(clamp(rl.getToken()), clamp(gl.getToken()), clamp(bl.getToken()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int clamp(String token) {
        return Math.max(0, Math.min(255, Integer.parseInt(token.trim())));
    }
}
