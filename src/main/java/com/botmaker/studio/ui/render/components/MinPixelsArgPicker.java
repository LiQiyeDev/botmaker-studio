package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NumberLiteral;

/**
 * Editor for a {@code MinPixels} argument — the location-precision knob of the SDK's {@code Pixel} facade.
 *
 * <p>{@code minPixels} is a minimum cluster <em>area</em>, and the mistake it invites is reading it as a
 * length: "at least 20 pixels" typed as {@code 20} asks for a blob of about 4×5, not one 20 across. So the
 * preview draws the area to scale — a filled blob of exactly that many pixels next to a 1:1 grid — and the
 * readout gives the equivalent square and circle. Drawing it as a radius would teach the wrong model, which
 * is the thing this editor exists to prevent.
 *
 * <p>Commits {@code MinPixels.DEFAULT} / {@code MinPixels.ANY} on the named values and
 * {@code MinPixels.of(n)} otherwise, importing the type so the simple name resolves.
 */
public final class MinPixelsArgPicker {

    private MinPixelsArgPicker() {}

    private static final String FQN = "com.botmaker.sdk.api.vision.MinPixels";
    private static final int DEFAULT_PIXELS = 4;
    private static final int ANY_PIXELS = 1;
    /** The preview canvas is square; a blob larger than this is drawn clipped rather than scaled down. */
    private static final int PREVIEW_SIDE = 180;

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button(label(currentPixels(arg)));
        button.getStyleClass().add("minpixels-picker");
        button.setOnAction(e -> openDialog(context, arg, button));
        return button;
    }

    private static void openDialog(CodeEditorService context, ExpressionBlock arg, Button button) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Smallest patch that counts");
        if (button.getScene() != null) dialog.initOwner(button.getScene().getWindow());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Spinner<Integer> spinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 1_000_000, currentPixels(arg), 4));
        spinner.setEditable(true);
        spinner.setPrefWidth(140);

        Label explain = new Label("How many touching pixels of the colour must be found before it counts as a "
                + "match. This is an area, not a width — raise it to ignore stray specks and anti-aliased edges.");
        explain.setWrapText(true);
        explain.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Canvas canvas = new Canvas(PREVIEW_SIDE, PREVIEW_SIDE);
        Label readout = new Label();
        readout.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Runnable refresh = () -> {
            int pixels = spinner.getValue() == null ? DEFAULT_PIXELS : spinner.getValue();
            drawBlob(canvas, pixels);
            readout.setText(readoutFor(pixels));
        };
        spinner.valueProperty().addListener((obs, o, n) -> refresh.run());
        refresh.run();

        VBox content = new VBox(10, explain, spinner, canvas, readout);
        content.setPadding(new Insets(14));
        content.setAlignment(Pos.CENTER_LEFT);
        dialog.getDialogPane().setContent(content);

        dialog.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            commitEditor(spinner);
            int chosen = Math.max(1, spinner.getValue());
            context.getCodeEditor().replaceWithRawExpression(exprNode(arg), literalFor(chosen), FQN);
            button.setText(label(chosen));
        });
    }

    /** The committed source text: the named constant when the value is one, else an explicit {@code of(n)}. */
    static String literalFor(int pixels) {
        if (pixels == DEFAULT_PIXELS) return "MinPixels.DEFAULT";
        if (pixels == ANY_PIXELS) return "MinPixels.ANY";
        return "MinPixels.of(" + pixels + ")";
    }

    /** "400 px² — about 20×20, or a circle 23 across" — the area said three ways so none of them mislead. */
    static String readoutFor(int pixels) {
        double side = Math.sqrt(pixels);
        double diameter = 2 * Math.sqrt(pixels / Math.PI);
        return String.format("%,d px² — about %.0f×%.0f, or a circle %.0f across",
                pixels, side, side, diameter);
    }

    /**
     * Draws the area at 1:1 over a pixel grid: a filled circle of exactly {@code pixels} area, with the
     * equivalent square outlined behind it. Both shapes are the same area, which is the point — the user sees
     * how little "40 pixels" actually is before typing it into a bot that then never matches.
     */
    private static void drawBlob(Canvas canvas, int pixels) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        g.clearRect(0, 0, w, w);
        g.setFill(Color.web("#fafafa"));
        g.fillRect(0, 0, w, w);

        g.setStroke(Color.web("#e4e4e4"));
        g.setLineWidth(1);
        for (int i = 10; i < w; i += 10) {
            g.strokeLine(i, 0, i, w);
            g.strokeLine(0, i, w, i);
        }

        double side = Math.sqrt(pixels);
        double diameter = 2 * Math.sqrt(pixels / Math.PI);
        double cx = w / 2;
        double cy = w / 2;

        g.setStroke(Color.web("#9aa0a6"));
        g.setLineDashes(4);
        g.strokeRect(cx - side / 2, cy - side / 2, side, side);
        g.setLineDashes(0);

        g.setFill(Color.web("#c0392b"));
        g.fillOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter);

        g.setStroke(Color.web("#b0b0b0"));
        g.strokeRect(0.5, 0.5, w - 1, w - 1);
        g.setFill(Color.web("#9aa0a6"));
        g.fillText("grid squares are 10×10 px", 8, w - 8);
    }

    /** Force a typed-but-not-committed spinner value into the model before we read it. */
    private static void commitEditor(Spinner<Integer> spinner) {
        String text = spinner.getEditor().getText();
        if (text != null && !text.isBlank()) {
            try {
                SpinnerValueFactory<Integer> factory = spinner.getValueFactory();
                factory.setValue(factory.getConverter().fromString(text.trim()));
            } catch (RuntimeException ignored) {
                // keep the last valid model value when the text can't be parsed
            }
        }
    }

    // --- labels / current value ---

    private static String label(int pixels) {
        if (pixels == DEFAULT_PIXELS) return "DEFAULT (4 px²)";
        if (pixels == ANY_PIXELS) return "ANY (1 px²)";
        return pixels + " px²";
    }

    private static int currentPixels(ExpressionBlock arg) {
        Expression e = exprNode(arg);
        if (e instanceof MethodInvocation mi && "of".equals(mi.getName().getIdentifier())
                && mi.arguments().size() == 1 && mi.arguments().getFirst() instanceof NumberLiteral n) {
            try {
                return Math.max(1, (int) Double.parseDouble(n.getToken()));
            } catch (NumberFormatException ignored) {
                return DEFAULT_PIXELS;
            }
        }
        if (e instanceof Name name) {
            String text = name.getFullyQualifiedName();
            if (text.endsWith("ANY")) return ANY_PIXELS;
        }
        return DEFAULT_PIXELS;
    }

    private static Expression exprNode(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
