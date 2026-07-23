package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.BotSettings;
import com.botmaker.studio.project.ProjectConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Editor for the bot's runtime tuning — the whole of the SDK's {@code ClickConfig} plus the Linux input
 * backend — written straight into the project's generated {@link BotSettings} file.
 *
 * <p>Replaces the toolbar's old {@code 🖱 Game} toggle, which could say only "real input on/off". The rest of
 * these knobs were reachable only by hand-editing, which meant nobody changed them; the delays in particular
 * are what decide whether a bot feels sluggish or misses screens, so they belong where they can be seen.
 *
 * <p>Every control writes a statement in {@code BotSettings.java} rather than a settings key: the values have
 * to apply when the bot is run outside the Studio, and the generated file is the only form that travels with
 * the code. Values are read back out of that file each time the dialog opens, so a hand edit isn't lost.
 */
public final class ClickConfigDialog {

    private final Window owner;
    private final ProjectConfig config;
    /** Receives the regenerated {@code BotSettings.java} source, so the editor's in-memory copy stays true. */
    private final Consumer<String> onWritten;

    private final CheckBox realInput = new CheckBox("Drive the real mouse and keyboard (turn on for games)");
    private final CheckBox randomizeClicks = new CheckBox("Click a random point inside the match, not its centre");
    private final Spinner<Integer> foundDelay = new Spinner<>(0, 60_000, 500, 50);
    private final Spinner<Integer> notFoundDelay = new Spinner<>(0, 60_000, 200, 50);
    private final Spinner<Double> confidence = new Spinner<>(0.0, 1.0, 0.8, 0.05);
    private final Spinner<Double> compareMargin = new Spinner<>(0.0, 1.0, 0.05, 0.01);
    private final Spinner<Integer> maxRetryAttempts = new Spinner<>(1, 1000, 20, 1);
    private final ComboBox<BotSettings.LinuxInput> linuxInput = new ComboBox<>();

    private final Label status = new Label();
    private Stage stage;

    public ClickConfigDialog(Window owner, ProjectConfig config, Consumer<String> onWritten) {
        this.owner = owner;
        this.config = config;
        this.onWritten = onWritten;
    }

    public void show() {
        stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Input & Clicks");

        seed(BotSettings.read(BotSettings.fileFor(config.mainSourceFile())));

        Label heading = new Label("How this bot clicks and looks");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label intro = new Label("Saved into your project's BotSettings.java and applied at the top of main — "
                + "so these apply when the bot runs outside the Studio too.");
        intro.setWrapText(true);
        intro.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        status.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());
        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> save());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, status, spacer, cancel, save);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(14, heading, intro, new Separator(), buildInputPane(), new Separator(),
                buildVisionPane(), new Separator(), bar);
        root.setPadding(new Insets(18));

        stage.setScene(new Scene(root, 620, 560));
        stage.show();
    }

    /**
     * The real-input section. The explanation is the one the old toolbar toggle carried as a tooltip, kept
     * because it is the only place that says <em>why</em> a game needs this — the events BotMaker sends by
     * default are rejected by design, and no OS reports the drop, which is why it can't be auto-detected.
     */
    private VBox buildInputPane() {
        Label title = new Label("Input");
        title.setStyle("-fx-font-weight: bold;");

        Label explain = new Label(
                "Games ignore the quiet background clicks BotMaker sends by default, so this drives the real "
                        + "mouse and keyboard instead — the pointer moves to each click and returns, and the "
                        + "game window is raised. Leave it off for an ordinary application you'd rather the bot "
                        + "never took the cursor away from.");
        explain.setWrapText(true);
        explain.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        linuxInput.getItems().setAll(BotSettings.LinuxInput.values());
        linuxInput.setConverter(new StringConverter<>() {
            @Override public String toString(BotSettings.LinuxInput v) { return v == null ? "" : v.label(); }
            @Override public BotSettings.LinuxInput fromString(String s) { return null; }
        });
        linuxInput.setMaxWidth(Double.MAX_VALUE);

        Label backendNote = new Label(
                "Which Linux backend delivers that input. Automatic tries uinput first (a virtual device the "
                        + "kernel reports as real, so games accept it), then xdotool, then XTest — pin one here "
                        + "if this machine only works with a particular one. Ignored on Windows.");
        backendNote.setWrapText(true);
        backendNote.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        GridPane grid = grid();
        grid.addRow(0, new Label("Linux backend"), linuxInput);
        GridPane.setHgrow(linuxInput, Priority.ALWAYS);

        return new VBox(8, title, realInput, explain, grid, backendNote);
    }

    /** Delays, confidence and retries — what the bot does around each match attempt. */
    private VBox buildVisionPane() {
        Label title = new Label("Clicks & matching");
        title.setStyle("-fx-font-weight: bold;");

        for (Spinner<?> s : new Spinner<?>[] {foundDelay, notFoundDelay, confidence, compareMargin,
                maxRetryAttempts}) {
            s.setEditable(true);
            s.setPrefWidth(120);
        }

        GridPane grid = grid();
        grid.addRow(0, new Label("Pause after a match (ms)"), foundDelay,
                hint("Let the game's animation finish before the next look."));
        grid.addRow(1, new Label("Pause after a miss (ms)"), notFoundDelay,
                hint("How fast the bot retries when it doesn't see what it wants."));
        grid.addRow(2, new Label("Match confidence"), confidence,
                hint("0–1. Lower finds more, and finds wrong things more."));
        grid.addRow(3, new Label("Compare margin"), compareMargin,
                hint("How far the right template must beat a look-alike to win."));
        grid.addRow(4, new Label("Stuck after N no-progress checks"), maxRetryAttempts,
                hint("When the watchdog decides the bot is stuck and restarts it."));

        return new VBox(8, title, grid, randomizeClicks);
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        return l;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        return grid;
    }

    private void seed(BotSettings s) {
        realInput.setSelected(s.realInput());
        randomizeClicks.setSelected(s.randomizeClicks());
        setValue(foundDelay, s.foundDelay());
        setValue(notFoundDelay, s.notFoundDelay());
        setValue(confidence, s.confidence());
        setValue(compareMargin, s.compareMargin());
        setValue(maxRetryAttempts, s.maxRetryAttempts());
        linuxInput.setValue(s.linuxInput());
    }

    private static <T> void setValue(Spinner<T> spinner, T value) {
        spinner.getValueFactory().setValue(value);
    }

    private void save() {
        // An editable spinner keeps the last committed value until focus leaves it, so a number typed and then
        // saved straight away would be dropped. Commit the text first.
        for (Spinner<?> s : new Spinner<?>[] {foundDelay, notFoundDelay, confidence, compareMargin,
                maxRetryAttempts}) {
            s.commitValue();
        }
        BotSettings settings = new BotSettings(
                realInput.isSelected(), foundDelay.getValue(), notFoundDelay.getValue(), confidence.getValue(),
                randomizeClicks.isSelected(), compareMargin.getValue(), maxRetryAttempts.getValue(),
                linuxInput.getValue() == null ? BotSettings.LinuxInput.AUTO : linuxInput.getValue());
        try {
            BotSettings.write(config.mainSourceFile(), config.packageName(), settings);
            if (onWritten != null) onWritten.accept(BotSettings.source(config.packageName(), settings));
            stage.close();
        } catch (IOException e) {
            status.setText("Couldn't write BotSettings.java: " + e.getMessage());
            status.setStyle("-fx-font-size: 11px; -fx-text-fill: #c0392b;");
        }
    }
}
