package com.botmaker.studio.ui.app;

import com.botmaker.shared.config.CaptureSourceKind;
import com.botmaker.studio.game.EpicLibraryScanner;
import com.botmaker.studio.game.FaugusLibraryScanner;
import com.botmaker.studio.game.GameLibraryProvider;
import com.botmaker.studio.game.HeroicLibraryScanner;
import com.botmaker.studio.game.SteamLibraryScanner;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectMode;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.project.SessionSetting;
import com.botmaker.studio.project.launch.QuickLaunch;
import com.botmaker.studio.project.launch.SupportedTargets;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.studio.ui.render.components.EmulatorPickerDialog;
import com.botmaker.studio.ui.render.components.GameLibraryPickerDialog;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
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
 * the SDK's {@code Bot.start} launch step ({@code Target.startIfNotRunning()}) launches it. Each kind is chosen through the same
 * reusable picker dialog the in-block {@code LaunchTarget} picker uses ({@link GameLibraryPickerDialog} / OS file
 * chooser / {@link EmulatorPickerDialog}).
 *
 * <p>Opened from the toolbar's Launch Target button — the project-target sibling of the Capture Targets button
 * ({@link ManageCaptureTargetsDialog}).
 *
 * <p><b>Two things here belong to two different people.</b> A published bot declares the launch kinds it
 * <em>works on</em> ({@link SupportedTargets}); which of them this machine actually runs, and what this machine
 * captures, are the running user's. So: an installed bot offers only the kinds its author declared (an editor
 * sees them all — narrowing your own project would be a cage of your own making), and the capture source an
 * emulator target implies is now an <em>offer</em> under {@link #pointCapture} rather than a silent side effect
 * of picking a launch target. It defaults to on because a bot pointed at an emulator it isn't looking at is
 * broken in a way that shows up only as vision that never matches.
 */
public final class LaunchTargetDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final Path resourcesDir;
    /** Notified with the new spec (or {@code null} when cleared) after a successful write, so the toolbar can refresh. */
    private final Consumer<String> onChanged;

    private Stage stage;
    private Label currentLabel;
    private Label statusLabel;
    /** "▶ Launch now" — rebound after every save, so it never launches the target the user just replaced. */
    private Button launchNow;
    /** "Run in background" — greyed for a target it can't apply to, so it is re-evaluated on every save. */
    private CheckBox background;
    /** The whole "Recently used" section — hidden (and unmanaged) while there is nothing to recap. */
    private VBox recentBox;
    /** The buttons inside {@link #recentBox}, one per remembered spec; rebuilt on every save. */
    private VBox recentList;
    private String currentSpec;
    /** "Also point capture at it" — the user's say over the capture source a target would imply. */
    private CheckBox pointCapture;
    /** What the bot's author declared it runs on; {@link SupportedTargets#any()} for a project that never said. */
    private SupportedTargets supported = SupportedTargets.any();
    /** True while reading someone else's bot: the author's declaration is then a limit, not a note. */
    private boolean reader;

    /**
     * @param config the open project — needed in full (not just its resources dir) because the "Run in
     *               background" toggle also rewrites the generated {@code Session} statement; see
     *               {@link com.botmaker.studio.project.SessionSetting} for why both forms must move together.
     */
    public LaunchTargetDialog(Window owner, ProjectConfig config, Consumer<String> onChanged) {
        this.owner = owner;
        this.config = config;
        this.resourcesDir = config.resourcesRoot();
        this.onChanged = onChanged;
    }

    public void show() {
        show(null);
    }

    /**
     * As {@link #show()}, additionally running {@code onClosed} on the FX thread once the window is gone.
     * Mirrors {@link ManageCaptureTargetsDialog#show(Runnable)} and exists for the same reason: the stage is
     * modal but {@code show()} does not block, so a caller that sent the user here to fix something — the
     * overlay editor, which has no window to draw over until a target exists — has no other way to know when
     * to look again.
     */
    public void show(Runnable onClosed) {
        stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        if (onClosed != null) stage.setOnHidden(e -> onClosed.run());
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Launch Target");

        currentSpec = ProjectCreator.readLaunchTarget(resourcesDir);
        supported = ProjectCreator.readSupportedTargets(resourcesDir);
        reader = ProjectMode.isReader(config.projectPath());

        Label heading = new Label("What should the bot launch?");
        heading.getStyleClass().add("dialog-heading");
        Label hint = new Label("Baked into the project (launch.target) so the bot launches it when it "
                + "bot runs. Choose a game from a launcher's library (Steam, Epic, Heroic, Faugus), an "
                + "executable or command, or an app inside an emulator.");
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");

        currentLabel = new Label();
        currentLabel.getStyleClass().add("dialog-subheading");
        refreshCurrentLabel();

        VBox choices = new VBox(6,
                choice(LaunchKind.STEAM, "🎮 Steam game…", () -> pickGame(new SteamLibraryScanner(), "steam")),
                choice(LaunchKind.EPIC, "🎮 Epic game…", () -> pickGame(new EpicLibraryScanner(), "epic")),
                choice(LaunchKind.HEROIC, "🎮 Heroic game (Epic/GOG on Linux)…",
                        () -> pickGame(new HeroicLibraryScanner(), "heroic")),
                choice(LaunchKind.FAUGUS, "🎮 Faugus game (Proton/Wine on Linux)…",
                        () -> pickGame(new FaugusLibraryScanner(), "faugus")),
                choice(LaunchKind.EXE, "📁 Executable…", this::pickExecutable),
                choice(LaunchKind.CLI, "⌨️ CLI command…", this::pickCliCommand),
                choice(LaunchKind.EMULATOR_APP, "📱 Emulator app…", this::pickEmulatorApp));

        Label supportedLabel = new Label("This bot's author declared it runs on: " + supported.describe() + ".");
        supportedLabel.setWrapText(true);
        supportedLabel.getStyleClass().add("dialog-hint");
        supportedLabel.setVisible(supported.declared());
        supportedLabel.setManaged(supported.declared());

        Label recentHeading = new Label("Recently used");
        recentHeading.getStyleClass().add("dialog-subheading");
        recentList = new VBox(4);
        recentBox = new VBox(4, recentHeading, recentList);
        refreshRecent();

        background = new CheckBox("Run in background (private display)");
        background.setSelected(ProjectCreator.readSessionIsolated(resourcesDir));
        background.setOnAction(e -> applyBackground(background.isSelected()));
        refreshBackgroundAvailability();
        Label backgroundHint = new Label("On: the game runs in a private nested display the bot alone drives "
                + "(gamescope for Steam/Epic/Heroic/Faugus/exe games, Xephyr for a CLI command) so your real "
                + "cursor stays free. Off: it launches on your real desktop (:0).");
        backgroundHint.setWrapText(true);
        backgroundHint.getStyleClass().add("dialog-hint");
        VBox backgroundBox = new VBox(2, background, backgroundHint);

        pointCapture = new CheckBox("Also point capture at this target");
        pointCapture.setSelected(true);
        Label captureHint = new Label("On: choosing an emulator app also makes that emulator what the bot "
                + "looks at (capture.source). Off: your capture target is left exactly as you set it in "
                + "Capture Targets — which is where it lives either way.");
        captureHint.setWrapText(true);
        captureHint.getStyleClass().add("dialog-hint");
        VBox captureBox = new VBox(2, pointCapture, captureHint);

        statusLabel = new Label();
        statusLabel.getStyleClass().add("dialog-status");
        // The status text is the flexible element: it grows to fill the row and ellipsizes when long, so it
        // never squeezes the buttons below their label width (which is what truncated them to "…").
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        launchNow = QuickLaunch.button(resourcesDir, this::report);
        launchNow.setMinWidth(Region.USE_PREF_SIZE);

        Button clear = new Button("Clear target");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> apply(null));
        Button close = new Button("Close");
        close.setMinWidth(Region.USE_PREF_SIZE);
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());
        HBox bar = new HBox(8, statusLabel, launchNow, clear, close);
        bar.setAlignment(Pos.CENTER_LEFT);

        // The recap grows the content by up to ten rows, so the choices scroll and the action bar stays pinned
        // — otherwise a full MRU pushes "Close" off the bottom of a fixed-size dialog.
        VBox content = new VBox(12, heading, hint, currentLabel, supportedLabel, choices, recentBox,
                captureBox, backgroundBox);
        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("transparent-scroll");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        VBox root = new VBox(12, scroller, bar);
        root.setPadding(new Insets(16));
        stage.setScene(ThemedWindows.scene(root, 440, 560));
        stage.show();
    }

    /**
     * Persists the "Run in background" toggle and reports the new state. It goes through
     * {@link SessionSetting} rather than the isolation key alone because the backend key travels with it: the
     * pair is one setting, and the backend the Input &amp; Clicks dialog chose is carried through untouched.
     */
    private void applyBackground(boolean isolated) {
        try {
            SessionSetting.write(config,
                    new SessionSetting(isolated, SessionSetting.read(resourcesDir).backend()));
            report(true, isolated
                    ? "Background mode on — launches into a private display, your real cursor stays free."
                    : "Background mode off — launches on your real desktop (:0).");
        } catch (IOException ex) {
            error("Couldn't save: " + ex.getMessage());
        }
    }

    /**
     * Greys the "Run in background" toggle for a target it cannot apply to — today an {@code emu-app:}, which
     * runs inside the emulator over ADB and never on a display of ours. The persisted key is left untouched, so
     * the user's setting is exactly as they left it once the target is a game again.
     *
     * <p>An enabled toggle that changes nothing is worse than a disabled one: the launch already ignores it
     * (see {@code QuickLaunch.usesBackgroundSession}), and this is the only place that says so before the fact.
     */
    private void refreshBackgroundAvailability() {
        LaunchSpec spec = (currentSpec == null || currentSpec.isBlank()) ? null : LaunchSpec.parse(currentSpec);
        boolean offDesktop = spec != null && spec.runsOffDesktop();
        background.setDisable(offDesktop);
        background.setTooltip(offDesktop
                ? new Tooltip("Doesn't apply to an emulator app: it runs inside the emulator and is driven over "
                        + "ADB, so it is already off your desktop.")
                : null);
    }

    /**
     * One kind's button, disabled when the bot's author didn't declare that kind and this is their bot, not
     * ours. The tooltip says who decided, because a silently dead button reads as a bug in Studio.
     */
    private Button choice(LaunchKind kind, String label, Runnable onPick) {
        Button button = new Button(label);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> onPick.run());
        if (reader && !supported.supports(kind)) {
            button.setDisable(true);
            button.setTooltip(new Tooltip("This bot's author didn't declare support for a "
                    + kind.displayName().toLowerCase() + ". They declared: " + supported.describe() + "."));
        }
        return button;
    }

    private void pickGame(GameLibraryProvider provider, String kind) {
        GameLibraryPickerDialog.show(stage, provider).ifPresent(game -> {
            if (game.id() == null || game.id().isBlank()) return;
            apply(kind + ":" + game.id());
        });
    }

    private void pickExecutable() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a program to launch");
        File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) apply("exe:" + chosen.getAbsolutePath());
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
            if (!trimmed.isEmpty()) apply("cli:" + trimmed);
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
            apply("emu-app:" + sel.appPackage() + "@" + sel.instance().name());
        });
    }

    /**
     * The capture source {@code spec} implies, or {@code null} for a target that implies none. Only an
     * emulator app does: the bot has to look at the emulator it just started, and nothing else on the machine
     * can be derived from a launch target — a game's window is picked in Capture Targets, by eye.
     */
    private static String impliedCaptureSource(String spec) {
        LaunchSpec parsed = LaunchSpec.parse(spec);
        String instance = parsed == null ? null : parsed.emulatorInstance();
        return (instance == null || instance.isBlank()) ? null : CaptureSourceKind.EMULATOR.spec(instance);
    }

    /**
     * Writes {@code spec} to {@code launch.target} (a null/blank spec clears it), refreshes the label and
     * notifies the toolbar — and, when the target implies a capture source and {@link #pointCapture} is
     * ticked, points capture there too, saying so on the status line.
     *
     * <p>Every path lands here, including "Recently used", which is why the implication is derived from the
     * spec rather than passed in: it used to be an argument only {@code pickEmulatorApp} filled, so
     * re-selecting the same emulator app from the MRU silently dropped the capture source it came with.
     */
    private void apply(String spec) {
        try {
            ProjectCreator.writeLaunchTarget(resourcesDir, spec);
            String captureSource = pointCapture.isSelected() ? impliedCaptureSource(spec) : null;
            if (captureSource != null) ProjectCreator.writeCaptureSource(resourcesDir, captureSource);
            currentSpec = (spec == null || spec.isBlank()) ? null : spec.trim();
            // Every pick funnels through here, so the MRU can't miss a kind the way per-call-site recording
            // would. A cleared target records nothing (addRecentLaunchTarget ignores a null spec).
            ProjectPreferences.recordLaunchTarget(currentSpec);
            refreshCurrentLabel();
            refreshRecent();
            report(true, currentSpec == null ? "Launch target cleared."
                    : captureSource == null ? "Launch target saved."
                    : "Launch target saved — capture now points at " + captureSource + ".");
            QuickLaunch.bind(launchNow, resourcesDir, this::report);
            refreshBackgroundAvailability();
            if (onChanged != null) onChanged.accept(currentSpec);
        } catch (IOException ex) {
            error("Couldn't save: " + ex.getMessage());
        }
    }

    /**
     * Rebuilds the "Recently used" list: one button per remembered spec, newest first, minus the one already
     * selected — recapping the current target is noise, and clicking it would be a no-op. The whole section
     * hides itself when nothing is left to show, so a first-run dialog looks exactly as it did before.
     */
    private void refreshRecent() {
        if (recentBox == null) return;
        recentList.getChildren().clear();
        for (String spec : ProjectPreferences.recentLaunchTargets()) {
            if (spec == null || spec.isBlank() || spec.equals(currentSpec)) continue;
            // The MRU is global across projects, so it will offer kinds this bot's author never claimed.
            if (reader && !supported.supportsSpec(spec)) continue;
            Button entry = new Button(LaunchSpec.describe(spec));
            entry.setMaxWidth(Double.MAX_VALUE);
            entry.setAlignment(Pos.CENTER_LEFT);
            entry.getStyleClass().add("dialog-compact");
            entry.setTooltip(new Tooltip(spec));
            entry.setOnAction(e -> apply(spec));
            recentList.getChildren().add(entry);
        }
        boolean any = !recentList.getChildren().isEmpty();
        recentBox.setVisible(any);
        recentBox.setManaged(any);
    }

    /** Shows an outcome on the status line — green when it worked, the usual red when it didn't. */
    private void report(boolean ok, String message) {
        if (ok) {
            statusLabel.getStyleClass().removeAll("dialog-status--error", "dialog-status--ok");
            statusLabel.getStyleClass().add("dialog-status--ok");
            statusLabel.setText(message);
        } else {
            error(message);
        }
    }

    private void refreshCurrentLabel() {
        currentLabel.setText("Current: " + LaunchSpec.describe(currentSpec));
    }

    private void error(String message) {
        statusLabel.getStyleClass().removeAll("dialog-status--ok", "dialog-status--error");
        statusLabel.getStyleClass().add("dialog-status--error");
        statusLabel.setText(message);
    }
}
