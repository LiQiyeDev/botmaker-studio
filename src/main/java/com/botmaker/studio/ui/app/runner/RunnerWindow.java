package com.botmaker.studio.ui.app.runner;

import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectMode;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTargetNames;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.ui.app.LaunchTargetDialog;
import com.botmaker.studio.ui.app.ManageCaptureTargetsDialog;
import com.botmaker.studio.ui.app.ProjectWindow;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The window someone who wants to <em>use</em> a bot gets: what it launches, where it looks, which of its
 * activities to run, the settings its author chose to expose, and a Run button with the log underneath.
 *
 * <p>It is a whole separate window rather than the editor with parts hidden, and that is the entire design.
 * Hiding is a rule someone has to remember to apply to every control they add; not building the editor at all
 * is a rule that enforces itself. Nothing here constructs a {@code ToolbarManager}, a file explorer or a block
 * canvas, so no amount of later work can leak one into a user's view — and the bot's source is never even
 * parsed on this path, which is why the window opens instantly on a project the editor takes seconds over.
 *
 * <p>Two ways in, and the difference is only what the header offers:
 * <ul>
 *   <li>{@link Origin#INSTALLED} — somebody else's bot, opened for use. The header offers a look at the code
 *       (still read-only) and "Improve this bot", which is the one action here that changes what the project
 *       <em>is</em>.</li>
 *   <li>{@link Origin#PREVIEW} — its author checking what they have exposed. The header offers the way back.
 *       This mode writes no marker and leaves no trace: see {@link ProjectMode}.</li>
 * </ul>
 */
public final class RunnerWindow implements ProjectWindow {

    /** How this window was reached — which decides what the header offers, and nothing else. */
    public enum Origin { INSTALLED, PREVIEW }

    /** Widest the content column grows, so the settings don't stretch across a maximised screen. */
    private static final double CONTENT_MAX_WIDTH = 900;

    /**
     * How wide one setting card is. Fixed, because it is what makes the gallery reflow on its own: the tile
     * pane fits as many of them across as the window allows and wraps the rest, so narrow is one column and
     * wide is three without a single width listener.
     */
    private static final double TILE_WIDTH = 300;

    private final Stage stage;
    private final Origin origin;
    private final ProjectConfig config;
    private final EventBus eventBus;
    private final ActivityService activityService;
    private final ProjectSettingsService settings;

    /** Leaves the Runner for the editor scene, without changing what the project is. Supplied by the shell. */
    private final Runnable onShowEditor;

    /** Dropped from {@link BlockTheme}'s <b>static</b> listener list on dispose, like the editor's. */
    private final Consumer<BlockTheme.ThemeType> themeListener;
    /** Closed on dispose: the project's bus outlives this window whenever the audience is toggled. */
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();

    private Parent root;
    private TextArea log;
    private Label status;
    private Button runButton;
    private Button stopButton;
    private Label launchTargetLabel;
    private Label captureTargetLabel;

    /** One checkbox per live activity, keyed by name — the "which of these do I want" list. */
    private final Map<String, CheckBox> enableBoxes = new LinkedHashMap<>();
    /** Every shared variable's widget. A variable's name is project-wide, so there is no scope to record. */
    private final List<ParamValueWidgets.ValueEditor> valueEditors = new ArrayList<>();

    /** The body's scroller and one card per category — what the category chips jump between. */
    private ScrollPane bodyScroll;
    private final Map<String, Node> categoryCards = new LinkedHashMap<>();

    public RunnerWindow(StudioContext ctx, Stage stage, Origin origin, Runnable onShowEditor) {
        this.stage = stage;
        this.origin = origin;
        this.config = ctx.config();
        this.eventBus = ctx.eventBus();
        this.activityService = ctx.activityService();
        this.settings = ctx.projectSettingsService();
        this.onShowEditor = onShowEditor;

        BlockTheme.initialize();
        this.themeListener = theme -> { if (root != null) ThemedWindows.applyThemeClass(root); };
        BlockTheme.addThemeChangeListener(themeListener);
    }

    @Override
    public Scene createScene() {
        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("runner-root");
        shell.setTop(header());
        shell.setCenter(body());
        shell.setBottom(runBar());

        this.root = shell;
        ThemedWindows.applyThemeClass(shell);
        // Same reason as the editor's: JavaFX reads the scene root's minimum as the window's, so a long
        // activity name must not become a width the user cannot drag below.
        shell.setMinWidth(0);
        shell.setMinHeight(0);

        subscribe();

        // Unsized on purpose — see UIManager.createScene(): this scene lands on the shell's stage, which
        // already has the geometry the user chose, and a sized one would fight it (or, maximized, lose to it
        // and leave the window bordered in black).
        Scene scene = new Scene(shell);
        ThemedWindows.addStylesheet(scene);
        return scene;
    }

    @Override
    public void dispose() {
        BlockTheme.removeThemeChangeListener(themeListener);
        for (EventBus.Subscription s : subscriptions) {
            try {
                s.close();
            } catch (Exception ignored) {
                // A subscription that is already gone is exactly the state we wanted.
            }
        }
        subscriptions.clear();
    }

    // =========================================================================
    // HEADER
    // =========================================================================

    private Node header() {
        Label name = new Label(config.projectName());
        name.getStyleClass().add("runner-title");
        Label subtitle = new Label(origin == Origin.PREVIEW
                ? "This is what someone running your bot sees."
                : "Set it up, choose what it should do, and press Run.");
        subtitle.getStyleClass().add("runner-subtitle");

        VBox titles = new VBox(2, name, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, titles, spacer);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("runner-header");

        if (origin == Origin.PREVIEW) {
            Button back = new Button("← Back to editing");
            back.setOnAction(e -> onShowEditor.run());
            bar.getChildren().add(back);
        } else {
            Button code = new Button("View the code");
            code.setTooltip(new javafx.scene.control.Tooltip(
                    "Read how this bot works. Nothing is editable until you make it yours."));
            code.setOnAction(e -> onShowEditor.run());
            Button improve = new Button("Improve this bot");
            improve.getStyleClass().add("primary-button");
            improve.setOnAction(e -> switchToEditorMode());
            bar.getChildren().addAll(code, improve);
        }
        return bar;
    }

    /**
     * "Improve this bot" — the one action here that changes what the project is. It drops the local opt-in
     * marker, commits the as-installed state so there is a restore point, and reloads: the project then opens
     * as the editor, because {@link ProjectMode} now says it is yours.
     */
    private void switchToEditorMode() {
        try {
            ProjectMode.switchToEditor(config.projectPath());
        } catch (IOException ex) {
            ThemedWindows.alert(Alert.AlertType.ERROR, "Couldn't switch to Editor mode: " + ex.getMessage(),
                    ButtonType.OK).showAndWait();
            return;
        }
        // Daemon: a git commit that hangs must not keep the JVM alive after the user closes the window.
        Thread commit = new Thread(() -> {
            try {
                new ProjectVcs(config.projectPath()).commit("Start editing (switched from Reader mode)");
            } catch (Exception ignored) {
                // A missing/again-committed repo is fine; the reload below is what matters.
            }
            Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.ProjectReloadRequestedEvent()));
        }, "reader-to-editor");
        commit.setDaemon(true);
        commit.start();
    }

    // =========================================================================
    // BODY — targets, activities, settings
    // =========================================================================

    private Node body() {
        VBox column = new VBox(18);
        column.setPadding(new Insets(18));
        column.setMaxWidth(CONTENT_MAX_WIDTH);
        column.getChildren().addAll(targetsSection(), activitiesSection(), settingsSection());

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("transparent-scroll");
        bodyScroll = scroll;
        return scroll;
    }

    /**
     * Scrolls the body so {@code target} is at the top of the viewport.
     *
     * <p>Measured in the content's own coordinates rather than the section's, because a category card is
     * nested three deep and its position within its parent says nothing about where it is in the column.
     */
    private void scrollTo(Node target) {
        if (bodyScroll == null) return;
        Node content = bodyScroll.getContent();
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewHeight = bodyScroll.getViewportBounds().getHeight();
        if (contentHeight <= viewHeight) return;
        double y = content.sceneToLocal(target.localToScene(0, 0)).getY();
        bodyScroll.setVvalue(Math.max(0, Math.min(1, y / (contentHeight - viewHeight))));
    }

    /** What the bot opens, and where it looks — in that order, because you cannot capture a window that is closed. */
    private Node targetsSection() {
        launchTargetLabel = new Label();
        captureTargetLabel = new Label();
        refreshTargets();

        Button changeLaunch = new Button("Change…");
        changeLaunch.setOnAction(e ->
                new LaunchTargetDialog(stage, config, spec -> refreshTargets()).show());

        Button changeCapture = new Button("Change…");
        changeCapture.setOnAction(e -> new ManageCaptureTargetsDialog(stage, settings, config.resourcesRoot())
                .show(this::refreshTargets));

        VBox rows = new VBox(8,
                targetRow("🚀", "Game or app to launch", launchTargetLabel, changeLaunch),
                targetRow("🎯", "Window the bot watches", captureTargetLabel, changeCapture));
        return section("Where it runs", "Pick the game first — its window can only be chosen as a target "
                + "once it is actually open.", rows);
    }

    private static Node targetRow(String glyph, String what, Label value, Button change) {
        Label title = new Label(glyph + "  " + what);
        value.getStyleClass().add("dialog-hint-text");
        VBox text = new VBox(1, title, value);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, text, spacer, change);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("runner-row");
        return row;
    }

    /** Re-reads both targets from the project. Called on open and after either dialog closes. */
    private void refreshTargets() {
        String spec = ProjectCreator.readLaunchTarget(config.resourcesRoot());
        launchTargetLabel.setText(spec == null || spec.isBlank()
                ? "Nothing chosen yet" : LaunchSpec.shortLabel(spec, null));
        CaptureTarget target = settings.defaultTarget();
        captureTargetLabel.setText(target == null
                ? "Nothing chosen yet" : CaptureTargetNames.shortLabel(target));
    }

    /** The activity checkboxes: the bot's own list of things it can do, each one on or off. */
    private Node activitiesSection() {
        List<ActivityDefinition> live = activityService.current().activities();
        VBox rows = new VBox(6);
        if (live.isEmpty()) {
            rows.getChildren().add(hint("This bot has no activities — Run just starts it."));
        }
        for (ActivityDefinition a : live) {
            CheckBox box = new CheckBox(a.name());
            box.setSelected(a.enabled());
            enableBoxes.put(a.name(), box);
            VBox cell = new VBox(1, box);
            if (!a.description().isBlank()) {
                Label note = hint(a.description());
                note.setPadding(new Insets(0, 0, 0, 22));   // under the box's label, not under its tick
                cell.getChildren().add(note);
            }
            rows.getChildren().add(cell);
        }
        return section("What it does", "Untick anything you don't want it doing this run.", rows);
    }

    /**
     * The variables the bot's author chose to share, under their tag headings. The grouping is the whole
     * reason a variable carries a tag — the bot's user reads "Mining" and "General", not one flat list in
     * which two activities' delays are told apart only by a prefix in their name.
     *
     * <p>Everything else stays where it belongs — with the author — which is why an empty section says so
     * rather than quietly rendering nothing: "no settings" is a fact about this bot, not a sign the window
     * failed to load.
     */
    private Node settingsSection() {
        Map<String, List<ActivityVariable>> byTag = activityService.current().sharedVariables();
        VBox groups = new VBox(18);
        if (byTag.isEmpty()) {
            groups.getChildren().add(hint("This bot has no settings for you to change."));
        }
        byTag.forEach((tag, group) -> groups.getChildren().add(categoryCard(tag, group)));

        Node index = categoryIndex(byTag);
        return section("Settings", null, index == null ? groups : new VBox(12, index, groups));
    }

    /**
     * A wrapping row of one chip per category, each jumping to its card.
     *
     * <p>A bot with a dozen categories is a page of scrolling, and the thing being looked for is nearly always
     * known by name before the scrolling starts. The chips are built from the same map the cards are, so the
     * index cannot offer a category that isn't below it — and it is left out entirely below two categories,
     * where a jump list is longer than the thing it indexes.
     */
    private Node categoryIndex(Map<String, List<ActivityVariable>> byTag) {
        if (byTag.size() < 3) return null;
        FlowPane chips = new FlowPane(6, 6);
        byTag.forEach((tag, group) -> {
            Button chip = new Button(tag + " (" + group.size() + ")");
            chip.getStyleClass().add("runner-category-chip");
            chip.setOnAction(e -> {
                Node card = categoryCards.get(tag);
                if (card != null) scrollTo(card);
            });
            chips.getChildren().add(chip);
        });
        return chips;
    }

    /**
     * One tag as a titled block of setting cards.
     *
     * <p>The headings used to be a bare {@link Label} over a flat column, and at twenty settings the
     * categories stopped reading as categories — everything was the same weight in one long list, and the
     * window's width went unused no matter how wide it was pulled. A titled card with a rule under it and a
     * reflowing grid inside says where a group starts and ends without anybody having to count rows.
     */
    private Node categoryCard(String tag, List<ActivityVariable> group) {
        Label heading = new Label(tag);
        heading.getStyleClass().add("dialog-subheading");
        Label count = hint(group.size() == 1 ? "1 setting" : group.size() + " settings");

        HBox title = new HBox(8, heading, count);
        title.setAlignment(Pos.BASELINE_LEFT);

        TilePane tiles = new TilePane(12, 12);
        tiles.setPrefColumns(1);
        // One column when the window is narrow, several when it is wide: the tile is a fixed width and the
        // pane wraps, so the reflow is the layout's own doing and needs no width listener.
        tiles.setPrefTileWidth(TILE_WIDTH);
        tiles.setTileAlignment(Pos.TOP_LEFT);
        for (ActivityVariable v : group) tiles.getChildren().add(paramCard(v));

        VBox card = new VBox(8, title, new Separator(), tiles);
        card.getStyleClass().add("runner-category");
        categoryCards.put(tag, card);
        return card;
    }

    /**
     * One setting as a card: what it is called, <b>what kind of value it is</b>, its editor, and the author's
     * note underneath.
     *
     * <p>The type badge is there because a value with no unit or shape stated is a guess — a bare {@code 30}
     * beside "Delay" could be seconds or milliseconds, and "Region" could be a name or four numbers. The
     * badge says which, in the same words the author picked the type with.
     */
    private Node paramCard(ActivityVariable v) {
        Label name = new Label(v.displayLabel());
        name.getStyleClass().add("runner-setting-name");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label badge = new Label(v.type().label());
        badge.getStyleClass().add("runner-type-badge");
        badge.setWrapText(true);
        // The badge keeps the width it asks for and the name wraps into what is left. The other way round —
        // the name growing and the badge shrinking — is what put an ellipsis through "One of Image template"
        // and, on a long name, through the name as well: a card is 300px wide and a Label in a full row
        // truncates rather than wrapping unless it is told which of the two gives way.
        badge.setMinWidth(Region.USE_PREF_SIZE);

        HBox header = new HBox(6, name, badge);
        header.setAlignment(Pos.TOP_LEFT);

        Node widget = ParamValueWidgets.build(v, config, valueEditors);
        if (widget instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);

        VBox card = new VBox(6, header, widget);
        if (!v.description().isBlank()) card.getChildren().add(hint(v.description()));
        card.getStyleClass().add("runner-setting-card");
        card.setPrefWidth(TILE_WIDTH);
        card.setMinHeight(Region.USE_PREF_SIZE);   // a wrapped name makes the card taller, never shorter
        return card;
    }

    private static Node section(String title, String hintText, Node content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-heading");
        VBox box = new VBox(8, heading);
        if (hintText != null) box.getChildren().add(hint(hintText));
        box.getChildren().add(content);
        box.getStyleClass().add("runner-section");
        return box;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint-text");
        label.setWrapText(true);
        return label;
    }

    // =========================================================================
    // RUN BAR
    // =========================================================================

    private Node runBar() {
        runButton = new Button("▶ Run");
        runButton.getStyleClass().add("primary-button");
        runButton.setOnAction(e -> run());

        stopButton = new Button("⏹ Stop");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent()));

        status = new Label("Ready");
        status.getStyleClass().add("dialog-status");

        HBox buttons = new HBox(8, runButton, stopButton, status);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(8, 12, 8, 12));

        log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(8);
        log.getStyleClass().add("console-area");

        VBox bar = new VBox(buttons, log);
        bar.getStyleClass().add("runner-runbar");
        return bar;
    }

    /**
     * Saves what the window is showing, then runs.
     *
     * <p>The save is not optional and not a separate button: a tick box that does nothing until you find a
     * Save you didn't know about is the bug this ordering removes. The run waits for the write because the
     * bot reads {@code activities.json} off the classpath at startup — starting first would run the previous
     * answers.
     */
    private void run() {
        runButton.setDisable(true);
        status.setText("Saving your choices…");
        activityService.update(edited()).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                runButton.setDisable(false);
                status.setText("Couldn't save your choices: " + rootMessage(error));
                return;
            }
            status.setText("Starting…");
            // Re-enabled before the publish, not after the run: a run that is refused (a compile error, an
            // already-running bot) reports on the status line and never sends ProgramStarted/Stopped, so a
            // button left disabled here would stay disabled for the rest of the session.
            runButton.setDisable(false);
            eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent());
        }));
    }

    /**
     * The project's activities with this window's answers folded in: each activity's enable flag from its
     * checkbox, and each public parameter's value from its widget. Everything else — the parameters the author
     * kept to themselves, the flow, the presets — is carried through untouched, because a user's window must
     * not be able to delete what it cannot see.
     */
    private ActivitiesConfig edited() {
        ActivitiesConfig base = activityService.current();

        Map<String, List<String>> typed = new LinkedHashMap<>();
        for (ParamValueWidgets.ValueEditor editor : valueEditors) typed.put(editor.name(), editor.read().get());

        List<ActivityDefinition> activities = new ArrayList<>();
        for (ActivityDefinition a : base.activities()) {
            CheckBox box = enableBoxes.get(a.name());
            activities.add(box == null ? a : a.withEnabled(box.isSelected()));
        }

        // A variable nobody was offered is carried through as it was: a user's window must not be able to
        // change what it cannot see.
        List<ActivityVariable> variables = new ArrayList<>();
        for (ActivityVariable v : base.variables()) {
            List<String> value = typed.get(v.name());
            variables.add(value == null ? v : v.withValue(value));
        }

        return base.withActivities(activities).withVariables(variables);
    }

    /** Scope-qualified, because two activities may each have a parameter of the same name. */
    private static String key(String activity, String param) {
        return (activity == null ? "" : activity) + '#' + param;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    // =========================================================================
    // EVENTS
    // =========================================================================

    private void subscribe() {
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.OutputAppendedEvent.class,
                e -> log.appendText(e.text()), true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.OutputClearedEvent.class,
                e -> log.clear(), true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.StatusMessageEvent.class,
                e -> status.setText(e.message()), true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class,
                e -> running(true), true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class,
                e -> running(false), true));
    }

    private void running(boolean isRunning) {
        runButton.setDisable(isRunning);
        stopButton.setDisable(!isRunning);
    }
}
