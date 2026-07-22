package com.botmaker.studio.ui.app;

import com.botmaker.studio.game.EpicLibraryScanner;
import com.botmaker.studio.game.GameLibraryProvider;
import com.botmaker.studio.game.HeroicLibraryScanner;
import com.botmaker.studio.game.SteamLibraryScanner;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.launch.LaunchTargetNames;
import com.botmaker.studio.ui.render.components.EmulatorPickerDialog;
import com.botmaker.studio.ui.render.components.GameLibraryPickerDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The project-level "Launch Target" editor: configures <em>what the bot launches</em> at startup —
 * a Steam game, an Epic game, a plain executable, or an app inside an Android emulator — and bakes it into
 * {@code botmaker-project.properties} ({@code launch.target}) via {@link ProjectCreator#writeLaunchTarget}, so
 * the generated {@code Startup.run()} ({@code Target.start()}) launches it. Each kind is chosen through the same
 * reusable picker dialog the in-block {@code LaunchTarget} picker uses ({@link GameLibraryPickerDialog} / OS file
 * chooser / {@link EmulatorPickerDialog}).
 *
 * <p>Opened from the toolbar's Launch Target button — the project-target sibling of the Capture Targets button
 * ({@link ManageCaptureTargetsDialog}). Picking an emulator app additionally points the project's capture source
 * at that emulator ({@code capture.source = emulator:<instance>}), mirroring the in-block emulator picker.
 */
public final class LaunchTargetDialog {

    private final Window owner;
    private final Path resourcesDir;
    /** Notified with the new spec (or {@code null} when cleared) after a successful write, so the toolbar can refresh. */
    private final Consumer<String> onChanged;

    private Stage stage;
    private Label currentLabel;
    private Label statusLabel;
    private String currentSpec;

    public LaunchTargetDialog(Window owner, Path resourcesDir, Consumer<String> onChanged) {
        this.owner = owner;
        this.resourcesDir = resourcesDir;
        this.onChanged = onChanged;
    }

    public void show() {
        stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Launch Target");

        currentSpec = ProjectCreator.readLaunchTarget(resourcesDir);

        Label heading = new Label("What should the bot launch?");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label hint = new Label("Baked into the project (launch.target) so the generated Startup launches it when the "
                + "bot runs. Choose a Steam or Epic game, an executable, or an app inside an emulator.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        currentLabel = new Label();
        currentLabel.setStyle("-fx-font-size: 12px;");
        refreshCurrentLabel();

        Button steam = new Button("🎮 Steam game…");
        steam.setMaxWidth(Double.MAX_VALUE);
        steam.setOnAction(e -> pickGame(new SteamLibraryScanner(), "steam"));
        Button epic = new Button("🎮 Epic game…");
        epic.setMaxWidth(Double.MAX_VALUE);
        epic.setOnAction(e -> pickGame(new EpicLibraryScanner(), "epic"));
        Button heroic = new Button("🎮 Heroic game (Epic/GOG on Linux)…");
        heroic.setMaxWidth(Double.MAX_VALUE);
        heroic.setOnAction(e -> pickGame(new HeroicLibraryScanner(), "heroic"));
        Button exe = new Button("📁 Executable…");
        exe.setMaxWidth(Double.MAX_VALUE);
        exe.setOnAction(e -> pickExecutable());
        Button cli = new Button("⌨️ CLI command…");
        cli.setMaxWidth(Double.MAX_VALUE);
        cli.setOnAction(e -> pickCliCommand());
        Button emu = new Button("📱 Emulator app…");
        emu.setMaxWidth(Double.MAX_VALUE);
        emu.setOnAction(e -> pickEmulatorApp());

        VBox choices = new VBox(6, steam, epic, heroic, exe, cli, emu);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px;");
        // The status text is the flexible element: it grows to fill the row and ellipsizes when long, so it
        // never squeezes the buttons below their label width (which is what truncated them to "…").
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        Button clear = new Button("Clear target");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> apply(null, null));
        Button close = new Button("Close");
        close.setMinWidth(Region.USE_PREF_SIZE);
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());
        HBox bar = new HBox(8, statusLabel, clear, close);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, heading, hint, currentLabel, choices, bar);
        root.setPadding(new Insets(16));
        stage.setScene(new Scene(root, 440, 420));
        stage.show();
    }

    private void pickGame(GameLibraryProvider provider, String kind) {
        GameLibraryPickerDialog.show(stage, provider).ifPresent(game -> {
            if (game.id() == null || game.id().isBlank()) return;
            apply(kind + ":" + game.id(), null);
        });
    }

    private void pickExecutable() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a program to launch");
        File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) apply("exe:" + chosen.getAbsolutePath(), null);
    }

    /** Prompts for an arbitrary command line — the escape hatch for launchers we don't model (e.g. Heroic/legendary). */
    private void pickCliCommand() {
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(cliCommandOf(currentSpec));
        dialog.initOwner(stage);
        dialog.setTitle("CLI command");
        dialog.setHeaderText("Command the bot runs to launch the game");
        dialog.setContentText("Command:");
        dialog.getEditor().setPrefColumnCount(40);
        dialog.showAndWait().ifPresent(cmd -> {
            String trimmed = cmd.trim();
            if (!trimmed.isEmpty()) apply("cli:" + trimmed, null);
        });
    }

    /** The command line inside a {@code cli:} spec (so re-editing pre-fills it), else empty. */
    private static String cliCommandOf(String spec) {
        return spec != null && spec.startsWith("cli:") ? spec.substring("cli:".length()) : "";
    }

    private void pickEmulatorApp() {
        EmulatorPickerDialog.show(stage).ifPresent(sel -> {
            if (!sel.hasApp()) {
                error("Pick an app inside the emulator (a launch target needs the app package).");
                return;
            }
            String instance = sel.instance().name();
            // An emulator launch target also points the project's capture source at that emulator (mirrors the
            // in-block emulator picker), so no-source vision/click calls target it.
            apply("emu-app:" + sel.appPackage() + "@" + instance, "emulator:" + instance);
        });
    }

    /**
     * Writes {@code spec} to {@code launch.target} (a null/blank spec clears it) and, when {@code captureSource}
     * is given, also updates {@code capture.source}; refreshes the label and notifies the toolbar.
     */
    private void apply(String spec, String captureSource) {
        try {
            ProjectCreator.writeLaunchTarget(resourcesDir, spec);
            if (captureSource != null) ProjectCreator.writeCaptureSource(resourcesDir, captureSource);
            currentSpec = (spec == null || spec.isBlank()) ? null : spec.trim();
            refreshCurrentLabel();
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #2e7d32;");
            statusLabel.setText(currentSpec == null ? "Launch target cleared." : "Launch target saved.");
            if (onChanged != null) onChanged.accept(currentSpec);
        } catch (IOException ex) {
            error("Couldn't save: " + ex.getMessage());
        }
    }

    private void refreshCurrentLabel() {
        currentLabel.setText("Current: " + LaunchTargetNames.describe(currentSpec));
    }

    private void error(String message) {
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #b00020;");
        statusLabel.setText(message);
    }
}
