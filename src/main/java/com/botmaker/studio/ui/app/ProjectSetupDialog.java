package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.sdk.authoring.CaptureTargetModel;
import com.botmaker.sdk.internal.plugin.launch.QuickLaunch;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.function.Consumer;

/**
 * The "Project Setup" hub — a single checklist that walks the user through everything a fresh project needs
 * to actually run: <b>what to launch</b> ({@link LaunchTargetDialog}), <b>what to capture</b>
 * (the SDK plugin's 🎯 Capture Targets), and the <b>reference resolution</b> ({@link ProjectSettingsDialog}),
 * plus an optional nudge to capture image templates. Each row shows a ✓/✗ status read live from the project
 * and a button that opens the existing config dialog for that step; the list re-ticks itself as settings change
 * (via {@link CoreApplicationEvents.SettingsChangedEvent}) and whenever the window regains focus after a child
 * dialog closes, so the user watches the checklist complete without leaving it.
 *
 * <p>Opened from the toolbar's Project Setup button, Project ▸ Project Setup…, and auto-opened once when a
 * project is first created ({@code BotMakerStudio.finishOpen}). Image templates are optional — pixel/OCR and
 * coordinate bots need none — so that row is informational and never counts toward completion.
 */
public final class ProjectSetupDialog {

    private final Stage owner;
    private final ProjectConfig config;
    private final ProjectSettingsService settings;
    private final ProjectAnalyzer analyzer;
    private final EventBus eventBus;
    /** Opens the live overlay template capture (owned by {@link UIManager}, which knows the capture service). */
    /**
     * Where a launch target picked in <em>this</em> dialog is announced — {@code ToolbarManager::setLaunchTarget},
     * the only path that re-labels the toolbar button and resolves its cover art. Without it the checklist row
     * ticked green while the button behind the dialog still read "Launch Target" with no artwork until the
     * project was reopened.
     */
    private final Consumer<String> onLaunchTargetChanged;

    private Stage stage;
    private VBox rows;
    private Label summary;
    /**
     * Where the quick-launch button reports. Deliberately <em>not</em> {@link #summary}: a launch flips the
     * window's focus to the game, and focus coming back re-runs {@link #refresh()}, which rewrites the summary —
     * so a failure message parked there would be wiped by the very act of looking at the dialog again.
     */
    private Label launchStatus;

    public ProjectSetupDialog(Stage owner, ProjectConfig config, ProjectSettingsService settings,
                              ProjectAnalyzer analyzer, EventBus eventBus,
                              Consumer<String> onLaunchTargetChanged) {
        this.owner = owner;
        this.config = config;
        this.settings = settings;
        this.analyzer = analyzer;
        this.eventBus = eventBus;
        this.onLaunchTargetChanged = onLaunchTargetChanged;
    }

    public void show() {
        StudioWindow window = StudioWindow.modal("project-setup", "Project Setup", owner)
                .size(520, 440).minSize(440, 360);
        stage = window.stage();

        Label heading = new Label("Set your project up to run");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label intro = new Label("A new bot needs a few things wired up before it can run. Work down the list — "
                + "each row opens the editor for that step, and ticks green once it's done.");
        intro.setWrapText(true);
        intro.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        summary = new Label();
        summary.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        launchStatus = new Label();
        launchStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        rows = new VBox(10);

        Button recheck = new Button("Re-check");
        recheck.setOnAction(e -> refresh());
        Button done = new Button("Done");
        done.setDefaultButton(true);
        done.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, summary, spacer, recheck, done);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(14, heading, intro, new Separator(), rows, new Separator(), launchStatus, bar);
        root.setPadding(new Insets(18));

        // Live refresh: capture/resolution edits publish SettingsChangedEvent (from a background write), so
        // re-tick on the FX thread — guarded so a stale subscription from a previously-closed dialog no-ops.
        eventBus.subscribe(CoreApplicationEvents.SettingsChangedEvent.class, e -> {
            if (stage.isShowing()) refresh();
        }, true);
        // Launch target and captured templates don't publish an event; catch them when focus returns after the
        // child dialog / capture overlay closes.
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused && stage.isShowing()) refresh();
        });

        refresh();
        window.show(root);
    }

    /** Re-reads every step's status from the project and rebuilds the checklist rows. */
    private void refresh() {
        StudioProjectSettings s = settings.current();

        boolean launchDone = ProjectCreator.readLaunchTarget(config.resourcesRoot()) != null;
        boolean captureDone = captureConfigured(s);
        boolean resolutionDone = s.referenceResolution() != null;
        int templateCount = ImageTemplateLibrary.list(config).size();

        int required = 3;
        int doneCount = (launchDone ? 1 : 0) + (captureDone ? 1 : 0) + (resolutionDone ? 1 : 0);
        summary.setText(doneCount + " of " + required + " required steps done"
                + (doneCount == required ? " — you're ready to run." : ""));

        rows.getChildren().setAll(
                row(launchDone, false, "Launch target",
                        launchDone ? describeLaunch() : "Not set — pick what the bot should open.",
                        "Set…", () -> new LaunchTargetDialog(owner, config, spec -> {
                            onLaunchTargetChanged.accept(spec);
                            refresh();
                        }).show(),
                        quickLaunchButton()),
                // Buttonless for the same reason as the pictures row below: the targets manager is the SDK
                // plugin's 🎯 Capture Targets toolbar item since 2026-08-31, and this dialog has no handle on
                // another plugin's item. The row still reports the state, which is what a checklist is for.
                row(captureDone, false, "Capture target",
                        describeCapture(s) + " Choose it with 🎯 Capture Targets on the toolbar.",
                        null, null),
                row(resolutionDone, false, "Reference resolution",
                        resolutionDone
                                ? s.referenceResolution().width() + "×" + s.referenceResolution().height()
                                : "Not set — the size templates are captured at (often set on first capture).",
                        "Set…", () -> new ProjectSettingsDialog(owner, settings, analyzer).show()),
                // No button on this row since 2026-08-31: capturing is the SDK plugin's ✂ Capture Templates
                // toolbar item, and this dialog has no handle on another plugin's item. The row still counts
                // the pictures, which is the half of it a checklist is for; it says where to go instead.
                row(templateCount > 0, true, "Image templates (optional)",
                        templateCount == 0
                                ? "None yet — only needed for image-matching bots (skip for pixel/OCR/coords). "
                                        + "Capture them with ✂ Capture Templates on the toolbar."
                                : templateCount + (templateCount == 1 ? " template saved." : " templates saved."),
                        null, null));
    }

    /**
     * The launch-target row's second button: start the configured target <em>without</em> running the bot, so the
     * user can confirm the row's ✓ actually means what they wanted — and so the game's window exists before they
     * walk down to the capture-target row, which is the next step and needs something to point at.
     *
     * <p>Rebuilt on every {@link #refresh()} rather than kept and re-bound, because refresh() rebuilds every row
     * anyway; {@link QuickLaunch#button} re-reads the target each time, so the button can't go stale.
     */
    private Button quickLaunchButton() {
        return QuickLaunch.button(config.resourcesRoot(), (ok, message) -> {
            launchStatus.setText(message);
            launchStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (ok ? "gray" : "#c0392b") + ";");
        });
    }

    /**
     * A capture target counts as "chosen" once it's anything other than the whole-desktop seed a fresh project
     * starts with — so the row nudges the user to point at their game window / emulator, while still letting an
     * explicit multi-target or non-desktop default satisfy it.
     */
    private static boolean captureConfigured(StudioProjectSettings s) {
        CaptureTargetModel def = s.defaultTarget();
        if (def == null) return false;
        return !def.isDesktop() || s.captureTargets().size() > 1;
    }

    private String describeLaunch() {
        String spec = ProjectCreator.readLaunchTarget(config.resourcesRoot());
        return spec == null ? "Not set" : LaunchSpec.describe(spec);
    }

    private static String describeCapture(StudioProjectSettings s) {
        CaptureTargetModel def = s.defaultTarget();
        if (def == null) return "No default set.";
        String label = def.shortLabel();
        return captureConfigured(s) ? label : label + " (default — pick your game window or emulator).";
    }

    /**
     * One checklist row: a ✓/✗ status glyph, a bold title with a wrapped detail line beneath, and a right-aligned
     * button that opens the step's editor. {@code optional} steps show a neutral glyph and never a red ✗.
     */
    private HBox row(boolean done, boolean optional, String title, String detail, String btnText, Runnable action) {
        return row(done, optional, title, detail, btnText, action, null);
    }

    /**
     * {@link #row(boolean, boolean, String, String, String, Runnable)} with an {@code extra} control placed
     * <em>before</em> the step's own button, so the editor button stays in the same column down the whole list.
     * Only the launch-target row uses it (for quick launch); the parameter exists so that row is the same builder
     * as the others rather than a second, drifting copy.
     */
    private HBox row(boolean done, boolean optional, String title, String detail, String btnText, Runnable action,
                     Node extra) {
        Label glyph = new Label(done ? "✓" : (optional ? "○" : "✗"));
        glyph.setMinWidth(18);
        glyph.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: "
                + (done ? "#27ae60" : (optional ? "#95a5a6" : "#e67e22")) + ";");

        Label name = new Label(title);
        name.setStyle("-fx-font-weight: bold;");
        Label sub = new Label(detail);
        sub.setWrapText(true);
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        VBox text = new VBox(2, name, sub);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, glyph, text);
        if (extra != null) row.getChildren().add(extra);
        // A null label is a row with nothing to press — a step whose destination this dialog cannot open,
        // which is what a step owned by a plugin's toolbar item is. Its detail text says where to go.
        if (btnText != null) {
            Button btn = new Button(btnText);
            btn.setMinWidth(84);
            btn.setOnAction(e -> { if (action != null) action.run(); });
            row.getChildren().add(btn);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
