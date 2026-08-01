package com.botmaker.studio.ui.app.capture;

import com.botmaker.shared.opencv.ColorMatcher;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ScreenCaptureService;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * The eyedropper: pick a colour off a frozen frame of the game rather than out of the OS colour palette.
 *
 * <p>The palette answers "which colour do I want", but a bot author does not want a colour — they have a pixel
 * on screen and need the value that matches it. Guessing that from a swatch grid is hopeless: game art is
 * shaded, compressed and anti-aliased, so the red of a health bar is never {@code Color.RED}.
 *
 * <p>Two things make a single pixel actually pickable:
 * <ul>
 *   <li><b>A loupe.</b> At 1:1 the cursor covers the pixel it is choosing. The loupe magnifies the
 *       neighbourhood {@value #LOUPE_ZOOM}× with a crosshair on the exact pixel, so what you commit is what
 *       you aimed at. {@link ZoomPan} magnifies the frame itself as well, for finding the spot at all.</li>
 *   <li><b>The ΔE spread of the surrounding {@value #NEIGHBOURHOOD}×{@value #NEIGHBOURHOOD}.</b> This is the
 *       honest suggested tolerance, and the number the ΔE slider has never had any way to justify: it says how
 *       much this patch actually varies in the real frame, which is the smallest tolerance that can hold it
 *       together.</li>
 * </ul>
 *
 * <p>The frame is frozen rather than live — unlike {@link CaptureSurface}, which is a transparent window onto
 * the moving desktop. Freezing is what allows zooming, and it means the pixel you inspect is the pixel you
 * get, with no chance of the game repainting between the look and the click.
 */
public final class ColorSampler {

    /** Magnification of the loupe, in screen pixels per frame pixel. */
    private static final int LOUPE_ZOOM = 8;
    /** Frame pixels across the loupe — odd, so there is an exact centre to put the crosshair on. */
    private static final int LOUPE_PIXELS = 17;
    /** The neighbourhood whose ΔE spread becomes the suggested tolerance — odd, for the same reason. */
    static final int NEIGHBOURHOOD = 5;

    /** A picked pixel: the colour, the frame it came from, and how much its neighbourhood varies in ΔE. */
    public record Sample(java.awt.Color color, GameFrame frame, double spread) {

        /** The smallest whole ΔE that still holds this patch together — what the tolerance slider should read. */
        public double suggestedTolerance() {
            return Math.ceil(spread);
        }
    }

    private ColorSampler() {}

    /** Grabs the project's capture target and opens the sampler over it. Does nothing if there is no frame. */
    public static void open(CodeEditorService context, Window owner, Consumer<Sample> onPicked) {
        GameFrame.grab(context, owner, frame -> openOn(frame, owner, onPicked));
    }

    /** Opens the sampler over an already-grabbed {@code frame} — for a caller that has one in hand. */
    public static void openOn(GameFrame frame, Window owner, Consumer<Sample> onPicked) {
        new Surface(frame, owner, onPicked).show();
    }

    /** One open sampler window. Instance state (the hovered pixel) is what keeps the readouts in step. */
    private static final class Surface {

        private final GameFrame frame;
        private final BufferedImage image;
        private final Consumer<Sample> onPicked;
        private final Stage stage = new Stage();

        private final Pane pane = new Pane();
        private final ImageView view;
        private final Canvas loupe = new Canvas(LOUPE_ZOOM * LOUPE_PIXELS, LOUPE_ZOOM * LOUPE_PIXELS);
        private final Rectangle swatch = new Rectangle(28, 28);
        private final Label readout = new Label("Move over the frame to inspect a pixel");
        private final Label spreadLabel = new Label();
        private final Label zoomLabel = new Label("100%");
        private final ZoomPan zoomPan;

        /** Frame pixels per unscaled content pixel — the frame is fitted to the surface before any zoom. */
        private final double pixelsPerContentUnit;

        private int hoverX = -1, hoverY = -1;

        Surface(GameFrame frame, Window owner, Consumer<Sample> onPicked) {
            this.frame = frame;
            this.image = frame.image();
            this.onPicked = onPicked;

            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            double fit = Math.min(1.0, Math.min(screen.getWidth() * 0.85 / image.getWidth(),
                    (screen.getHeight() * 0.85 - 90) / image.getHeight()));
            double displayW = Math.max(1, Math.floor(image.getWidth() * fit));
            double displayH = Math.max(1, Math.floor(image.getHeight() * fit));
            this.pixelsPerContentUnit = image.getWidth() / displayW;

            view = new ImageView(ScreenCaptureService.toFxImage(image));
            view.setFitWidth(displayW);
            view.setFitHeight(displayH);

            Group layers = new Group(view);
            loupe.setMouseTransparent(true);
            loupe.setVisible(false);
            pane.getChildren().addAll(layers, loupe);
            pane.setPrefSize(displayW, displayH);
            pane.setStyle("-fx-background-color: #12161d;");

            zoomPan = ZoomPan.attach(pane, layers);
            zoomPan.zoomProperty().addListener((obs, o, n) -> {
                zoomLabel.setText(Math.round(n.doubleValue() * 100) + "%");
                // Magnified pixels should look like pixels; smoothing turns the single-pixel edges you came
                // here to find into mush.
                view.setSmooth(n.doubleValue() <= 1);
            });

            installHandlers();

            BorderPane root = new BorderPane(pane);
            root.setBottom(buildBar());
            Scene scene = new Scene(root);
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) stage.close();
                if (e.isControlDown() && (e.getCode() == KeyCode.DIGIT0 || e.getCode() == KeyCode.NUMPAD0)) {
                    zoomPan.reset();
                }
            });

            stage.setTitle("Pick a colour — " + frame.label());
            stage.initModality(Modality.APPLICATION_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setScene(scene);
        }

        void show() {
            stage.show();
        }

        private Region buildBar() {
            swatch.setArcWidth(6);
            swatch.setArcHeight(6);
            swatch.setStroke(Color.web("#39404d"));
            swatch.setFill(Color.TRANSPARENT);

            readout.setTextFill(Color.web("#e8eefb"));
            readout.setStyle("-fx-font-family: monospace;");
            spreadLabel.setTextFill(Color.web("#9fb0cc"));
            zoomLabel.setTextFill(Color.web("#9fb0cc"));

            Button cancel = new Button("Cancel");
            cancel.setOnAction(e -> stage.close());

            Label hint = new Label("click to pick · Ctrl+scroll zoom · middle-drag pan · Ctrl+0 reset · Esc cancel");
            hint.setTextFill(Color.web("#6b7688"));
            hint.setStyle("-fx-font-size: 11px;");

            HBox left = new HBox(10, swatch, new javafx.scene.layout.VBox(2, readout, spreadLabel));
            left.setAlignment(Pos.CENTER_LEFT);
            Region spacer = new Region();
            HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

            HBox bar = new HBox(14, left, spacer, hint, zoomLabel, cancel);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(10, 14, 10, 14));
            bar.setStyle("-fx-background-color: #1a1f28;");
            return bar;
        }

        private void installHandlers() {
            pane.setOnMouseMoved(this::hover);
            pane.setOnMouseDragged(this::hover);
            pane.setOnMouseExited(e -> {
                loupe.setVisible(false);
                hoverX = hoverY = -1;
            });
            pane.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY || hoverX < 0) return;
                java.awt.Color picked = new java.awt.Color(image.getRGB(hoverX, hoverY), false);
                stage.close();
                onPicked.accept(new Sample(picked, frame, spreadAt(hoverX, hoverY)));
            });
        }

        /** Tracks the frame pixel under the cursor and republishes the loupe, swatch and readouts. */
        private void hover(MouseEvent e) {
            Point2D content = zoomPan.toContent(e);
            int px = (int) Math.floor(content.getX() * pixelsPerContentUnit);
            int py = (int) Math.floor(content.getY() * pixelsPerContentUnit);
            if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
                loupe.setVisible(false);
                hoverX = hoverY = -1;
                return;
            }
            hoverX = px;
            hoverY = py;

            java.awt.Color c = new java.awt.Color(image.getRGB(px, py), false);
            swatch.setFill(Color.rgb(c.getRed(), c.getGreen(), c.getBlue()));
            readout.setText(String.format("#%02X%02X%02X   rgb(%d, %d, %d)   at %d,%d",
                    c.getRed(), c.getGreen(), c.getBlue(), c.getRed(), c.getGreen(), c.getBlue(), px, py));
            double spread = spreadAt(px, py);
            spreadLabel.setText(String.format(
                    "this %dx%d patch varies by ΔE %.1f — a tolerance of about %.0f would hold it together",
                    NEIGHBOURHOOD, NEIGHBOURHOOD, spread, Math.ceil(spread)));

            drawLoupe(px, py);
            placeLoupe(e.getX(), e.getY());
            loupe.setVisible(true);
        }

        /**
         * The largest ΔE between the centre pixel and any of its {@value #NEIGHBOURHOOD}×{@value #NEIGHBOURHOOD}
         * neighbours — measured with {@link ColorMatcher#deltaE}, the same function the bot will run, rather
         * than a second approximation of Lab distance living in the editor.
         */
        private double spreadAt(int px, int py) {
            java.awt.Color centre = new java.awt.Color(image.getRGB(px, py), false);
            int r = NEIGHBOURHOOD / 2;
            double worst = 0;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = px + dx, y = py + dy;
                    if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) continue;
                    worst = Math.max(worst,
                            ColorMatcher.deltaE(centre, new java.awt.Color(image.getRGB(x, y), false)));
                }
            }
            return worst;
        }

        /** Draws the neighbourhood of ({@code px},{@code py}) magnified, with a crosshair on the exact pixel. */
        private void drawLoupe(int px, int py) {
            GraphicsContext g = loupe.getGraphicsContext2D();
            double side = loupe.getWidth();
            g.clearRect(0, 0, side, side);
            int r = LOUPE_PIXELS / 2;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = px + dx, y = py + dy;
                    boolean inside = x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight();
                    java.awt.Color c = inside ? new java.awt.Color(image.getRGB(x, y), false) : null;
                    g.setFill(c == null ? Color.web("#0d1015")
                            : Color.rgb(c.getRed(), c.getGreen(), c.getBlue()));
                    g.fillRect((dx + r) * LOUPE_ZOOM, (dy + r) * LOUPE_ZOOM, LOUPE_ZOOM, LOUPE_ZOOM);
                }
            }
            // Two-tone crosshair so it stays visible over both a light and a dark pixel.
            double c0 = r * LOUPE_ZOOM;
            g.setStroke(Color.web("#000000", 0.8));
            g.setLineWidth(3);
            g.strokeRect(c0 - 1, c0 - 1, LOUPE_ZOOM + 2, LOUPE_ZOOM + 2);
            g.setStroke(Color.web("#ffffff"));
            g.setLineWidth(1);
            g.strokeRect(c0 - 1, c0 - 1, LOUPE_ZOOM + 2, LOUPE_ZOOM + 2);
            g.setStroke(Color.web("#39404d"));
            g.strokeRect(0.5, 0.5, side - 1, side - 1);
        }

        /** Keeps the loupe beside the cursor and inside the surface, so it never covers what it magnifies. */
        private void placeLoupe(double cursorX, double cursorY) {
            double side = loupe.getWidth();
            double gap = 22;
            double x = cursorX + gap;
            double y = cursorY + gap;
            if (x + side > pane.getWidth()) x = cursorX - gap - side;
            if (y + side > pane.getHeight()) y = cursorY - gap - side;
            loupe.setLayoutX(Math.max(0, x));
            loupe.setLayoutY(Math.max(0, y));
        }
    }
}
