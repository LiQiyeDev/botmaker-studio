package com.botmaker.studio.ui.app;

import com.botmaker.shared.capture.linux.input.LinuxInputBackendId;
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
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;

/**
 * Editor for the bot's runtime tuning — the whole of the SDK's {@code BotSettings} facade plus the Linux input
 * backend and the private display — saved into the project's {@code botmaker-project.properties}.
 *
 * <p>Replaces the toolbar's old {@code 🖱 Game} toggle, which could say only "real input on/off". The rest of
 * these knobs were reachable only by hand-editing, which meant nobody changed them; the delays in particular
 * are what decide whether a bot feels sluggish or misses screens, so they belong where they can be seen.
 *
 * <p>Every control writes a project key rather than a line of generated Java (see {@link BotSettings} for why
 * that changed); the SDK reads them before the first click, so the values apply when the bot runs outside the
 * Studio too. Values are read back each time the dialog opens, so a hand edit isn't lost.
 */
public final class BotSettingsDialog {

    private final Window owner;
    private final ProjectConfig config;
    /** Notified after a successful save, so the caller can refresh anything that reflects these settings. */
    private final Runnable onSaved;

    private final CheckBox realInput = new CheckBox("Drive the real mouse and keyboard (turn on for games)");
    private final CheckBox randomizeClicks = new CheckBox("Click a random point inside the match, not its centre");
    private final Spinner<Integer> foundDelay = new Spinner<>(0, 60_000, 500, 50);
    private final Spinner<Integer> notFoundDelay = new Spinner<>(0, 60_000, 200, 50);
    private final Spinner<Double> confidence = new Spinner<>(0.0, 1.0, 0.8, 0.05);
    private final Spinner<Double> compareMargin = new Spinner<>(0.0, 1.0, 0.05, 0.01);
    private final Spinner<Integer> maxRetryAttempts = new Spinner<>(1, 1000, 20, 1);
    private final ComboBox<LinuxInputBackendId> linuxInput = new ComboBox<>();
    private final CheckBox isolatedSession = new CheckBox("Run in a private display (background)");
    private final ComboBox<BotSettings.SessionBackend> sessionBackend = new ComboBox<>();

    private final Label status = new Label();
    private Stage stage;

    public BotSettingsDialog(Window owner, ProjectConfig config, Runnable onSaved) {
        this.owner = owner;
        this.config = config;
        this.onSaved = onSaved;
    }

    public void show() {
        StudioWindow window = StudioWindow.modal("bot-settings", "Input & Clicks", owner)
                .size(620, 720).minSize(520, 420);
        stage = window.stage();

        seed(BotSettings.read(config.resourcesRoot()));

        Label heading = new Label("How this bot clicks and looks");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label intro = new Label("Saved into your project's settings and applied by the SDK before the first "
                + "click — so these apply when the bot runs outside the Studio too.");
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
                buildSessionPane(), new Separator(), buildVisionPane(), new Separator(), bar);
        root.setPadding(new Insets(18));

        window.show(root);
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

        linuxInput.getItems().setAll(LinuxInputBackendId.values());
        linuxInput.setConverter(new StringConverter<>() {
            @Override public String toString(LinuxInputBackendId v) { return v == null ? "" : v.label(); }
            @Override public LinuxInputBackendId fromString(String s) { return null; }
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

    /**
     * The private-display section. This is the setting that decides whether you can keep using the machine
     * while the bot runs, so it says so in those terms rather than in terms of nested X servers.
     *
     * <p>It has a second editing surface — the Launch Target dialog's "Run in background" toggle — writing the
     * same two keys, so the two always agree.
     */
    private VBox buildSessionPane() {
        Label title = new Label("Session");
        title.setStyle("-fx-font-weight: bold;");

        Label explain = new Label(
                "On (the default), the bot brings up a private display of its own and launches the game there. "
                        + "The window never appears on your desktop, the bot never steals your cursor or focus, "
                        + "and your own clicks can't land in its window — so you can keep using the machine "
                        + "while it runs. Turn it off to watch the bot work on your real desktop, sharing the "
                        + "one cursor with it. Linux only; on Windows the bot runs on the desktop either way.");
        explain.setWrapText(true);
        explain.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        sessionBackend.getItems().setAll(BotSettings.SessionBackend.values());
        sessionBackend.setConverter(new StringConverter<>() {
            @Override public String toString(BotSettings.SessionBackend v) { return v == null ? "" : v.label(); }
            @Override public BotSettings.SessionBackend fromString(String s) { return null; }
        });
        sessionBackend.setMaxWidth(Double.MAX_VALUE);
        // The backend only means anything for a private display.
        sessionBackend.disableProperty().bind(isolatedSession.selectedProperty().not());

        Label backendNote = new Label(
                "Which private display hosts it. Automatic is right almost always: a game gets gamescope, which "
                        + "puts a real GPU inside the private display, and a plain command gets the lighter "
                        + "Xephyr. Pin one only to reproduce a problem — Xephyr renders in software, which is "
                        + "what makes 3D games and store launchers crash.");
        backendNote.setWrapText(true);
        backendNote.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        GridPane grid = grid();
        grid.addRow(0, new Label("Display backend"), sessionBackend);
        GridPane.setHgrow(sessionBackend, Priority.ALWAYS);

        return new VBox(8, title, isolatedSession, explain, grid, backendNote);
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
        isolatedSession.setSelected(s.isolatedSession());
        sessionBackend.setValue(s.sessionBackend());
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
                linuxInput.getValue() == null ? LinuxInputBackendId.AUTO : linuxInput.getValue(),
                isolatedSession.isSelected(),
                sessionBackend.getValue() == null ? BotSettings.SessionBackend.AUTO : sessionBackend.getValue());
        try {
            // One write for the lot, session keys included — they are part of this record now rather than a
            // second form kept in step by SessionSetting.
            BotSettings.write(config.resourcesRoot(), settings);
            if (onSaved != null) onSaved.run();
            stage.close();
        } catch (IOException e) {
            status.setText("Couldn't save these settings: " + e.getMessage());
            status.setStyle("-fx-font-size: 11px; -fx-text-fill: #c0392b;");
        }
    }
}
