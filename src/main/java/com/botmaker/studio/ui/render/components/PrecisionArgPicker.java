package com.botmaker.studio.ui.render.components;

import com.botmaker.shared.opencv.ColorMatcher;
import com.botmaker.shared.opencv.RawColorMatch;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.app.capture.ColorSampler;
import com.botmaker.studio.ui.app.capture.GameFrame;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NumberLiteral;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Editor for a {@code Precision} argument — every knob that decides whether the SDK's {@code Pixel} facade
 * calls something a match, in one dialog because they are one SDK type.
 *
 * <p>Each of the three numbers fails silently on its own. ΔE has no obvious top and is not a percentage, and
 * being wrong about it fails as "the bot never sees the colour" rather than as an error. {@code minArea} is an
 * <em>area</em>, and the mistake it invites is reading it as a length — "at least 20 pixels" typed as
 * {@code 20} asks for a blob of about 4×5, not one 20 across. {@code minCount} is the pixels of the colour
 * present at all, clustered or not, which sounds like the same question as the area and is not.
 *
 * <p>So the editor answers each of them by showing rather than telling: the ΔE slider is laid out against the
 * SDK's own named anchors with a strip of swatches at increasing distance from the target colour, marking
 * which ones the current tolerance would let through; the area spinner draws the blob <b>to scale</b> over a
 * 1:1 grid (drawing it as a radius would teach exactly the wrong model); and <b>Sample from game</b> grabs a
 * frozen frame of the project's capture target and reports what the current settings actually do to it — how
 * many blobs match, how big the largest is, and how much of the colour is in the frame at all. That last one
 * is the real answer to "what should I put here", and it is why the sampler exists: without a frame these are
 * all abstractions.
 *
 * <p><b>Only the knobs the call can use are shown.</b> The SDK collapsed colour and quantity into one type,
 * which means {@code matchesAt} and {@code coverage} are handed an area and a count they cannot act on, and
 * {@code findInRange} a tolerance it has no target colour to measure from. Their javadoc says so; this editor
 * enforces it, reading {@code methodName} so a slot on {@code matchesAt} offers the tolerance alone. A knob
 * that cannot change the answer should not be presented as if it could.
 *
 * <p>Commits the shortest exact form: an anchor constant when the tolerance is one
 * ({@code Precision.TIGHT}), plus withers for whatever differs from the anchor's defaults
 * ({@code Precision.TIGHT.minArea(400)}), or {@code Precision.of(d, a, c)} when nothing is standard.
 */
public final class PrecisionArgPicker {

    private PrecisionArgPicker() {}

    private static final String FQN = "com.botmaker.sdk.api.vision.Precision";

    /** The quantity gates every {@code Precision} anchor carries — must match the SDK's constants. */
    private static final int DEFAULT_AREA = 4;
    private static final int DEFAULT_COUNT = 0;

    /** Past LOOSE the match is mostly noise, but leave headroom so the slider isn't a wall at the last anchor. */
    private static final double MAX_DELTA_E = 40.0;
    /** ΔE distances the preview strip samples — spanning the anchors so the cut-off is visible as it moves. */
    private static final double[] SAMPLE_DISTANCES = {0, 3, 5, 8, 12, 18, 25, 33};
    /** The blob preview canvas is square; a blob larger than this is drawn clipped rather than scaled down. */
    private static final int PREVIEW_SIDE = 180;
    /** Quiet time before a slider/spinner change triggers an OpenCV pass over the frame. */
    private static final Duration DEBOUNCE = Duration.millis(150);

    /** Kept in step with the SDK's {@code Precision} constants — the anchors the slider is laid out against. */
    private record Anchor(String constant, double deltaE, String meaning) {}

    private static final List<Anchor> ANCHORS = List.of(
            new Anchor("EXACT", 0.0, "only this exact colour"),
            new Anchor("TIGHT", 5.0, "this shade"),
            new Anchor("DEFAULT", 12.0, "this colour, shaded or anti-aliased"),
            new Anchor("LOOSE", 25.0, "the whole colour family"));

    /** The three knobs of the SDK type, as the editor holds them. */
    record Settings(double deltaE, int minArea, int minCount) {}

    /**
     * Which halves of {@code Precision} the enclosing call can actually act on — the SDK's per-method javadoc
     * turned into something the editor can switch on.
     */
    record Knobs(boolean tolerance, boolean quantity) {

        /** A one-line note for the dialog when the call reads only part of the type. */
        String note(String methodName) {
            if (tolerance && quantity) return null;
            if (tolerance) {
                return methodName + " tests a single point, so only the colour tolerance applies — a minimum "
                        + "blob or pixel count describes a search over an area and there is none here.";
            }
            return methodName + " takes a colour band rather than a target colour, so there is nothing for a "
                    + "ΔE tolerance to measure from — only the two quantity gates apply.";
        }
    }

    /**
     * {@code matchesAt} reads one pixel and {@code coverage} never clusters, so both use the tolerance alone;
     * {@code findInRange} is given a colour band instead of a target, so it uses only the quantity gates.
     * Everything else — {@code find}, {@code findAll}, {@code waitFor}, {@code waitForGone} — uses all three,
     * and so does an unknown method: showing every knob is the safe default when we cannot tell.
     */
    static Knobs knobsFor(String methodName) {
        if ("matchesAt".equals(methodName) || "coverage".equals(methodName)) return new Knobs(true, false);
        if ("findInRange".equals(methodName)) return new Knobs(false, true);
        return new Knobs(true, true);
    }

    public static Node create(CodeEditorService context, ExpressionBlock arg, String methodName) {
        Button button = new Button(label(currentValue(arg)));
        button.getStyleClass().add("precision-picker");
        button.setOnAction(e -> openDialog(context, arg, button, methodName));
        return button;
    }

    private static void openDialog(CodeEditorService context, ExpressionBlock arg, Button button,
                                   String methodName) {
        Settings current = currentValue(arg);
        Knobs knobs = knobsFor(methodName);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("How exact should the match be?");
        if (button.getScene() != null) dialog.initOwner(button.getScene().getWindow());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Slider slider = new Slider(0, MAX_DELTA_E, clamp(current.deltaE()));
        Spinner<Integer> areaSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 1_000_000, current.minArea(), 4));
        Spinner<Integer> countSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, 10_000_000, current.minCount(), 50));

        Preview preview = new Preview(context, arg, button);
        VBox content = new VBox(12);
        content.setPadding(new Insets(14));

        String note = knobs.note(methodName);
        if (note != null) content.getChildren().add(hint(note));

        if (knobs.tolerance()) content.getChildren().add(tolerancePane(slider, preview));
        if (knobs.tolerance() && knobs.quantity()) content.getChildren().add(new Separator());
        if (knobs.quantity()) content.getChildren().add(quantityPane(areaSpinner, countSpinner));

        content.getChildren().addAll(new Separator(), preview.pane());

        Runnable read = () -> preview.update(read(slider, areaSpinner, countSpinner, current, knobs));
        slider.valueProperty().addListener((o, a, b) -> read.run());
        areaSpinner.valueProperty().addListener((o, a, b) -> read.run());
        countSpinner.valueProperty().addListener((o, a, b) -> read.run());
        read.run();

        dialog.getDialogPane().setContent(content);
        dialog.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            commitEditor(areaSpinner);
            commitEditor(countSpinner);
            Settings chosen = read(slider, areaSpinner, countSpinner, current, knobs);
            context.getCodeEditor().replaceWithRawExpression(exprNode(arg),
                    literalFor(chosen.deltaE(), chosen.minArea(), chosen.minCount()), FQN);
            button.setText(label(chosen));
        });
    }

    /**
     * The dialog's current values — but a knob this call cannot use keeps whatever the source already said,
     * because the editor never showed it. Silently rewriting a field the user was not offered would make
     * opening a {@code matchesAt} editor and pressing OK quietly reset an area someone had set deliberately.
     */
    private static Settings read(Slider slider, Spinner<Integer> area, Spinner<Integer> count,
                                 Settings current, Knobs knobs) {
        double deltaE = knobs.tolerance() ? round(slider.getValue()) : current.deltaE();
        int a = knobs.quantity() ? Math.max(1, valueOf(area, current.minArea())) : current.minArea();
        int c = knobs.quantity() ? Math.max(0, valueOf(count, current.minCount())) : current.minCount();
        return new Settings(deltaE, a, c);
    }

    // ------------------------------------------------------------------
    // panes
    // ------------------------------------------------------------------

    private static Node tolerancePane(Slider slider, Preview preview) {
        slider.setPrefWidth(420);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setMajorTickUnit(5);
        slider.setMinorTickCount(4);

        Label reading = new Label();
        reading.setStyle("-fx-font-weight: bold;");
        Label meaning = new Label();
        meaning.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        HBox swatches = new HBox(6);
        swatches.setAlignment(Pos.CENTER_LEFT);
        Label swatchNote = hint("Shades at increasing distance from the colour — solid ones match at this "
                + "tolerance. Pick the tolerance by looking at what it lets through.");

        Runnable refresh = () -> {
            double v = round(slider.getValue());
            reading.setText("Colour tolerance: " + label(v));
            meaning.setText(meaningOf(v));
            java.awt.Color target = preview.targetColor();
            swatches.setVisible(target != null);
            swatchNote.setVisible(target != null);
            if (target != null) renderSwatches(swatches, target, v);
        };
        slider.valueProperty().addListener((o, a, b) -> refresh.run());
        preview.onTargetChanged(refresh);
        refresh.run();

        return new VBox(8, reading, slider, meaning, swatchNote, swatches);
    }

    private static Node quantityPane(Spinner<Integer> area, Spinner<Integer> count) {
        area.setEditable(true);
        area.setPrefWidth(140);
        count.setEditable(true);
        count.setPrefWidth(140);

        Canvas canvas = new Canvas(PREVIEW_SIDE, PREVIEW_SIDE);
        Label areaReadout = new Label();
        areaReadout.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        Label countReadout = new Label();
        countReadout.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Runnable refresh = () -> {
            int px = valueOf(area, DEFAULT_AREA);
            drawBlob(canvas, px);
            areaReadout.setText(readoutFor(px));
            int c = valueOf(count, DEFAULT_COUNT);
            countReadout.setText(c == 0
                    ? "No total required — however much of the colour there is."
                    : String.format("At least %,d matching pixels anywhere in the frame, clustered or not.", c));
        };
        area.valueProperty().addListener((o, a, b) -> refresh.run());
        count.valueProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();

        VBox areaBox = new VBox(6,
                new Label("Smallest patch that counts"),
                hint("Touching pixels of the colour needed before it counts as one match. This is an area, "
                        + "not a width — raise it to ignore stray specks and anti-aliased edges."),
                area, canvas, areaReadout);

        VBox countBox = new VBox(6,
                new Label("Enough of the colour overall"),
                hint("Matching pixels the frame must contain in total, however they clump. A health bar drawn "
                        + "as twenty separate segments passes this and fails the patch test — that is the "
                        + "difference between the two, and why they are set together."),
                count, countReadout);

        return new VBox(14, areaBox, new Separator(), countBox);
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(440);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        return l;
    }

    // ------------------------------------------------------------------
    // the frame preview
    // ------------------------------------------------------------------

    /**
     * The "what does this actually do to my game" half of the dialog: a frozen frame, and a readout of what
     * the current settings would find in it. Every recompute is debounced and run off the FX thread —
     * {@code findClusters} over a 4K frame is real OpenCV work, and doing it on a slider tick is the
     * difference between a dialog that responds and one that stutters while you drag.
     */
    private static final class Preview {

        private final CodeEditorService context;
        private final javafx.scene.Node owner;
        private final VBox pane = new VBox(8);
        private final Label status = new Label();
        private final Label result = new Label();
        private final PauseTransition debounce = new PauseTransition(DEBOUNCE);
        private final AtomicLong generation = new AtomicLong();

        private GameFrame frame;
        private java.awt.Color target;
        private Settings pending;
        private Runnable onTargetChanged = () -> {};

        Preview(CodeEditorService context, ExpressionBlock arg, javafx.scene.Node owner) {
            this.context = context;
            this.owner = owner;
            this.target = siblingColor(arg);

            status.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            status.setWrapText(true);
            result.setStyle("-fx-font-weight: bold;");

            Button sample = new Button("Sample from game…");
            sample.setOnAction(e -> ColorSampler.open(context, window(), s -> {
                frame = s.frame();
                target = s.color();
                onTargetChanged.run();
                status.setText("Sampled from " + frame.label() + " — previewing against that colour.");
                schedule();
            }));

            Button grab = new Button("Use current frame");
            grab.setOnAction(e -> GameFrame.grab(context, window(), f -> {
                frame = f;
                status.setText("Frame from " + f.label() + ".");
                schedule();
            }));

            pane.getChildren().addAll(new HBox(8, sample, grab), status, result);
            status.setText(target == null
                    ? "No colour set yet. Sample one from the game to see what these settings would match."
                    : "Grab a frame to see how many blobs of this colour these settings would find.");
            debounce.setOnFinished(e -> run());
        }

        VBox pane() { return pane; }

        java.awt.Color targetColor() { return target; }

        void onTargetChanged(Runnable r) { this.onTargetChanged = r; }

        void update(Settings s) {
            pending = s;
            schedule();
        }

        private void schedule() {
            if (frame == null || target == null || pending == null) return;
            debounce.playFromStart();
        }

        private javafx.stage.Window window() {
            return owner.getScene() == null ? null : owner.getScene().getWindow();
        }

        /** Runs the same search the bot would, off the FX thread; a stale result is dropped by generation. */
        private void run() {
            BufferedImage image = frame.image();
            java.awt.Color c = target;
            Settings s = pending;
            long gen = generation.incrementAndGet();
            Thread t = new Thread(() -> {
                String text;
                try {
                    List<RawColorMatch> hits =
                            ColorMatcher.findClusters(image, c, s.deltaE(), s.minArea(), s.minCount());
                    int present = ColorMatcher.matchCount(image, c, s.deltaE());
                    text = describe(hits, present, s);
                } catch (RuntimeException | LinkageError ex) {
                    text = "Could not search this frame: " + ex.getMessage();
                }
                String done = text;
                Platform.runLater(() -> {
                    if (generation.get() == gen) result.setText(done);
                });
            }, "precision-preview");
            t.setDaemon(true);
            t.start();
        }

        /**
         * Says what happened <em>and</em>, on a miss, which of the two gates rejected it. "Nothing matched" is
         * the answer that sends a user round in circles; "the colour is there, but the biggest patch is 60 px²"
         * tells them which number to move.
         */
        private static String describe(List<RawColorMatch> hits, int present, Settings s) {
            if (!hits.isEmpty()) {
                int largest = hits.getFirst().pixelCount();
                return String.format("%d blob%s match, largest %,d px² — %,d px of this colour in the frame.",
                        hits.size(), hits.size() == 1 ? "" : "s", largest, present);
            }
            if (present == 0) {
                return "Nothing in this frame is within ΔE " + trim(s.deltaE()) + " of the colour.";
            }
            if (s.minCount() > present) {
                return String.format("No match: %,d px of the colour are present, below the %,d you asked for.",
                        present, s.minCount());
            }
            return String.format("No match: %,d px of the colour are present, but no single patch reaches "
                    + "%,d px². Lower the patch size, or ask for a total instead.", present, s.minArea());
        }
    }

    // ------------------------------------------------------------------
    // committed source text
    // ------------------------------------------------------------------

    /**
     * The shortest form that is exactly these three values: an anchor when the tolerance is one, withers for
     * whatever differs from the anchor's own quantity gates, and the three-argument factory when the tolerance
     * is off-anchor and both gates are non-standard (which is shorter, and reads no worse, than chaining).
     */
    static String literalFor(double deltaE, int minArea, int minCount) {
        String anchor = anchorFor(deltaE);
        boolean standardGates = minArea == DEFAULT_AREA && minCount == DEFAULT_COUNT;
        if (anchor != null) {
            StringBuilder sb = new StringBuilder("Precision.").append(anchor);
            if (minArea != DEFAULT_AREA) sb.append(".minArea(").append(minArea).append(')');
            if (minCount != DEFAULT_COUNT) sb.append(".minCount(").append(minCount).append(')');
            return sb.toString();
        }
        if (standardGates) return "Precision.of(" + trim(deltaE) + ")";
        return "Precision.of(" + trim(deltaE) + ", " + minArea + ", " + minCount + ")";
    }

    private static String anchorFor(double deltaE) {
        for (Anchor a : ANCHORS) {
            if (a.deltaE() == deltaE) return a.constant();
        }
        return null;
    }

    /** "400 px² — about 20×20, or a circle 23 across" — the area said three ways so none of them mislead. */
    static String readoutFor(int pixels) {
        double side = Math.sqrt(pixels);
        double diameter = 2 * Math.sqrt(pixels / Math.PI);
        return String.format("%,d px² — about %.0f×%.0f, or a circle %.0f across", pixels, side, side, diameter);
    }

    // ------------------------------------------------------------------
    // reading the current value
    // ------------------------------------------------------------------

    /**
     * The three values the slot currently holds. Reads an anchor, an {@code of(…)} factory, and any wither
     * chain built on either — {@code Precision.TIGHT.minArea(400).minCount(2000)} reopens showing exactly what
     * it says, which is the property that makes the editor safe to open on hand-written code.
     */
    static Settings currentValue(ExpressionBlock arg) {
        return settingsOf(exprNode(arg));
    }

    /** The three values {@code e} spells, defaulting to the SDK's own {@code DEFAULT} for anything unreadable. */
    static Settings settingsOf(Expression e) {
        if (e instanceof MethodInvocation mi) {
            String name = mi.getName().getIdentifier();
            List<?> args = mi.arguments();
            if ("of".equals(name)) {
                if (args.size() == 1) return new Settings(numberOr(args.get(0), 12.0), DEFAULT_AREA, DEFAULT_COUNT);
                if (args.size() == 3) {
                    return new Settings(numberOr(args.get(0), 12.0),
                            (int) numberOr(args.get(1), DEFAULT_AREA),
                            (int) numberOr(args.get(2), DEFAULT_COUNT));
                }
                return defaults();
            }
            if (args.size() == 1) {
                Settings base = settingsOf(mi.getExpression());
                double v = numberOr(args.get(0), Double.NaN);
                if (Double.isNaN(v)) return base;
                return switch (name) {
                    case "tolerance" -> new Settings(v, base.minArea(), base.minCount());
                    case "minArea" -> new Settings(base.deltaE(), Math.max(1, (int) v), base.minCount());
                    case "minCount" -> new Settings(base.deltaE(), base.minArea(), Math.max(0, (int) v));
                    default -> base;
                };
            }
            return defaults();
        }
        if (e instanceof Name name) {
            String text = name.getFullyQualifiedName();
            for (Anchor a : ANCHORS) {
                if (text.equals(a.constant()) || text.endsWith("." + a.constant())) {
                    return new Settings(a.deltaE(), DEFAULT_AREA, DEFAULT_COUNT);
                }
            }
        }
        return defaults();
    }

    private static Settings defaults() {
        return new Settings(12.0, DEFAULT_AREA, DEFAULT_COUNT);
    }

    private static double numberOr(Object node, double fallback) {
        if (!(node instanceof NumberLiteral n)) return fallback;
        try {
            return Double.parseDouble(n.getToken().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * The {@code new java.awt.Color(r, g, b)} argument of the same call, if there is one — the colour these
     * thresholds are measured from. Null for a named constant or a variable, where there is nothing to preview
     * against until the user samples one.
     */
    private static java.awt.Color siblingColor(ExpressionBlock arg) {
        if (!(exprNode(arg).getParent() instanceof MethodInvocation call)) return null;
        for (Object o : call.arguments()) {
            if (o instanceof ClassInstanceCreation cic && cic.getType().toString().endsWith("Color")) {
                List<?> args = cic.arguments();
                if (args.size() >= 3 && args.get(0) instanceof NumberLiteral r
                        && args.get(1) instanceof NumberLiteral g && args.get(2) instanceof NumberLiteral b) {
                    try {
                        return new java.awt.Color(channel(r), channel(g), channel(b));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static int channel(NumberLiteral n) {
        return Math.max(0, Math.min(255, Integer.parseInt(n.getToken().trim())));
    }

    // ------------------------------------------------------------------
    // drawing / labels
    // ------------------------------------------------------------------

    private static void renderSwatches(HBox box, java.awt.Color target, double tolerance) {
        box.getChildren().clear();
        for (double d : SAMPLE_DISTANCES) {
            java.awt.Color shade = shifted(target, d);
            Rectangle r = new Rectangle(34, 34,
                    Color.rgb(shade.getRed(), shade.getGreen(), shade.getBlue()));
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
     * variant until {@link ColorMatcher#deltaE} says we have gone far enough. Searching against the real metric
     * rather than computing an offset in Lab keeps the preview honest for any hue — including the ones where a
     * fixed RGB step is a much bigger perceptual jump than it looks.
     */
    private static java.awt.Color shifted(java.awt.Color base, double deltaE) {
        if (deltaE <= 0) return base;
        // Move away from mid-grey so the walk has somewhere to go for both dark and light targets.
        int dir = (base.getRed() + base.getGreen() + base.getBlue()) / 3 > 127 ? -1 : 1;
        java.awt.Color best = base;
        for (int step = 1; step <= 255; step++) {
            java.awt.Color candidate = new java.awt.Color(
                    clampChannel(base.getRed() + dir * step),
                    clampChannel(base.getGreen() + dir * (step / 2)),
                    clampChannel(base.getBlue() + dir * step));
            best = candidate;
            if (ColorMatcher.deltaE(base, candidate) >= deltaE) break;
        }
        return best;
    }

    private static int clampChannel(int v) {
        return Math.max(0, Math.min(255, v));
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

    /** The button face: the whole setting, so a glance at the block says what it will and will not match. */
    static String label(Settings s) {
        StringBuilder sb = new StringBuilder(label(s.deltaE()));
        if (s.minArea() != DEFAULT_AREA) sb.append(" · ").append(s.minArea()).append(" px²");
        if (s.minCount() != DEFAULT_COUNT) sb.append(" · ").append(s.minCount()).append(" px total");
        return sb.toString();
    }

    private static String label(double deltaE) {
        String anchor = anchorFor(deltaE);
        return anchor != null ? anchor + " (ΔE " + trim(deltaE) + ")" : "ΔE " + trim(deltaE);
    }

    private static String meaningOf(double deltaE) {
        Anchor nearest = ANCHORS.getFirst();
        for (Anchor a : ANCHORS) {
            if (deltaE >= a.deltaE()) nearest = a;
        }
        return (nearest.deltaE() == deltaE ? "" : "about ") + nearest.meaning();
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

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

    private static int valueOf(Spinner<Integer> spinner, int fallback) {
        Integer v = spinner.getValue();
        return v == null ? fallback : v;
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
