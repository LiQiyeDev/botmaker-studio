package com.botmaker.studio.ui.render.components;

import com.botmaker.shared.opencv.ColorMatcher;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NumberLiteral;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Editor for a {@code Tolerance} argument — the colour-precision knob of the SDK's {@code Pixel} facade.
 *
 * <p>The problem it solves is that ΔE means nothing to a user typing into an empty slot: the scale has no
 * obvious top, the number is not a percentage, and being wrong about it fails as "the bot never sees the
 * colour" rather than as an error. So the editor is a slider laid out against the SDK's own named anchors
 * (EXACT / TIGHT / DEFAULT / LOOSE) with the anchor name shown as the reading, plus — when the sibling
 * {@code Color} argument of the same call is a literal — a strip of swatches at increasing ΔE from it,
 * marking which ones the current tolerance would accept. That strip is the actual answer to "what should I
 * put here": you pick the tolerance by looking at which colours it lets through.
 *
 * <p>The swatch distances are measured with {@link ColorMatcher#deltaE} — the same function the bot will run
 * — rather than a second approximation of Lab distance living in the editor.
 *
 * <p>Commits {@code Tolerance.TIGHT} when the value lands on an anchor and {@code Tolerance.of(x)} otherwise,
 * importing the type so the simple name resolves.
 */
public final class ToleranceArgPicker {

    private ToleranceArgPicker() {}

    /** Kept in step with the SDK's {@code Tolerance} constants — the anchors the slider is laid out against. */
    private record Anchor(String constant, double deltaE, String meaning) {}

    private static final List<Anchor> ANCHORS = List.of(
            new Anchor("EXACT", 0.0, "only this exact colour"),
            new Anchor("TIGHT", 5.0, "this shade"),
            new Anchor("DEFAULT", 12.0, "this colour, shaded or anti-aliased"),
            new Anchor("LOOSE", 25.0, "the whole colour family"));

    /** Past LOOSE the match is mostly noise, but leave headroom so the slider isn't a wall at the last anchor. */
    private static final double MAX_DELTA_E = 40.0;

    private static final String FQN = "com.botmaker.sdk.api.vision.Tolerance";

    /** ΔE distances the preview strip samples — spanning the anchors so the cut-off is visible as it moves. */
    private static final double[] SAMPLE_DISTANCES = {0, 3, 5, 8, 12, 18, 25, 33};

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button(label(currentDeltaE(arg)));
        button.getStyleClass().add("tolerance-picker");
        button.setOnAction(e -> openDialog(context, arg, button));
        return button;
    }

    private static void openDialog(CodeEditorService context, ExpressionBlock arg, Button button) {
        double current = currentDeltaE(arg);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Colour tolerance");
        if (button.getScene() != null) dialog.initOwner(button.getScene().getWindow());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Slider slider = new Slider(0, MAX_DELTA_E, clamp(current));
        slider.setPrefWidth(420);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setMajorTickUnit(5);
        slider.setMinorTickCount(4);
        // Snap to whole ΔE: the metric isn't precise enough for fractions to mean anything, and it makes
        // landing exactly on an anchor (and so committing the readable constant) easy rather than fiddly.
        slider.setSnapToTicks(false);

        Label reading = new Label();
        reading.setStyle("-fx-font-weight: bold;");
        Label meaning = new Label();
        meaning.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Color target = siblingColor(arg);
        HBox swatches = new HBox(6);
        swatches.setAlignment(Pos.CENTER_LEFT);
        Label swatchNote = new Label(target == null
                ? "Pick the colour argument first to preview which shades this tolerance accepts."
                : "Shades at increasing distance from the colour — solid ones match at this tolerance:");
        swatchNote.setWrapText(true);
        swatchNote.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Runnable refresh = () -> {
            double v = round(slider.getValue());
            reading.setText(label(v));
            meaning.setText(meaningOf(v));
            if (target != null) renderSwatches(swatches, target, v);
        };
        slider.valueProperty().addListener((obs, o, n) -> refresh.run());
        refresh.run();

        VBox content = new VBox(10, reading, slider, meaning, swatchNote, swatches);
        content.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(content);

        dialog.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            double chosen = round(slider.getValue());
            context.getCodeEditor().replaceWithRawExpression(exprNode(arg), literalFor(chosen), FQN);
            button.setText(label(chosen));
        });
    }

    /** The committed source text: the named constant when the value is one, else an explicit {@code of(x)}. */
    static String literalFor(double deltaE) {
        for (Anchor a : ANCHORS) {
            if (a.deltaE() == deltaE) return "Tolerance." + a.constant();
        }
        return "Tolerance.of(" + trim(deltaE) + ")";
    }

    // --- preview ---

    private static void renderSwatches(HBox box, Color target, double tolerance) {
        box.getChildren().clear();
        for (double d : SAMPLE_DISTANCES) {
            Color shade = shifted(target, d);
            Rectangle r = new Rectangle(34, 34, shade);
            r.setArcWidth(6);
            r.setArcHeight(6);
            boolean matches = d <= tolerance;
            r.setStroke(matches ? Color.web("#2d7d46") : Color.web("#b0b0b0"));
            r.setStrokeWidth(matches ? 3 : 1);
            r.setOpacity(matches ? 1.0 : 0.45);
            Label caption = new Label(trim(d));
            caption.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (matches ? "#2d7d46" : "gray") + ";");
            VBox cell = new VBox(3, r, caption);
            cell.setAlignment(Pos.CENTER);
            box.getChildren().add(cell);
        }
    }

    /**
     * A colour approximately {@code deltaE} away from {@code base}, found by walking towards a lighter/darker
     * variant until {@link ColorMatcher#deltaE} says we have gone far enough. Searching against the real
     * metric rather than computing an offset in Lab keeps the preview honest for any hue — including the ones
     * where a fixed RGB step is a much bigger perceptual jump than it looks.
     */
    private static Color shifted(Color base, double deltaE) {
        if (deltaE <= 0) return base;
        java.awt.Color awt = toAwt(base);
        // Move away from mid-grey so the walk has somewhere to go for both dark and light targets.
        int dir = (awt.getRed() + awt.getGreen() + awt.getBlue()) / 3 > 127 ? -1 : 1;
        java.awt.Color best = awt;
        for (int step = 1; step <= 255; step++) {
            java.awt.Color candidate = new java.awt.Color(
                    clampChannel(awt.getRed() + dir * step),
                    clampChannel(awt.getGreen() + dir * (step / 2)),
                    clampChannel(awt.getBlue() + dir * step));
            best = candidate;
            if (ColorMatcher.deltaE(awt, candidate) >= deltaE) break;
        }
        return Color.rgb(best.getRed(), best.getGreen(), best.getBlue());
    }

    private static int clampChannel(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static java.awt.Color toAwt(Color c) {
        return new java.awt.Color((int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    /**
     * The {@code new java.awt.Color(r, g, b)} argument of the same call, if there is one — the colour this
     * tolerance is measured from. Returns null for a named constant or a variable, where there is nothing to
     * preview against.
     */
    private static Color siblingColor(ExpressionBlock arg) {
        if (!(exprNode(arg).getParent() instanceof MethodInvocation call)) return null;
        for (Object o : call.arguments()) {
            if (o instanceof org.eclipse.jdt.core.dom.ClassInstanceCreation cic
                    && cic.getType().toString().endsWith("Color")) {
                List<?> args = cic.arguments();
                if (args.size() >= 3 && args.get(0) instanceof NumberLiteral r
                        && args.get(1) instanceof NumberLiteral g && args.get(2) instanceof NumberLiteral b) {
                    try {
                        return Color.rgb(channel(r), channel(g), channel(b));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static int channel(NumberLiteral n) {
        return clampChannel(Integer.parseInt(n.getToken().trim()));
    }

    // --- labels ---

    private static String label(double deltaE) {
        for (Anchor a : ANCHORS) {
            if (a.deltaE() == deltaE) return a.constant() + " (ΔE " + trim(deltaE) + ")";
        }
        return "ΔE " + trim(deltaE);
    }

    private static String meaningOf(double deltaE) {
        Anchor nearest = ANCHORS.getFirst();
        for (Anchor a : ANCHORS) {
            if (deltaE >= a.deltaE()) nearest = a;
        }
        String prefix = nearest.deltaE() == deltaE ? "" : "about ";
        return prefix + nearest.meaning();
    }

    // --- current value ---

    /** The ΔE the slot currently holds — a named constant, an {@code of(x)} call, or the SDK default. */
    private static double currentDeltaE(ExpressionBlock arg) {
        Expression e = exprNode(arg);
        if (e instanceof MethodInvocation mi && "of".equals(mi.getName().getIdentifier())
                && mi.arguments().size() == 1 && mi.arguments().getFirst() instanceof NumberLiteral n) {
            try {
                return clamp(Double.parseDouble(n.getToken()));
            } catch (NumberFormatException ignored) {
                return 12.0;
            }
        }
        if (e instanceof Name name) {
            String text = name.getFullyQualifiedName();
            for (Anchor a : ANCHORS) {
                if (text.endsWith("." + a.constant()) || text.equals(a.constant())) return a.deltaE();
            }
        }
        return 12.0;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(MAX_DELTA_E, v));
    }

    private static double round(double v) {
        return Math.round(v);
    }

    private static String trim(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static Expression exprNode(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
