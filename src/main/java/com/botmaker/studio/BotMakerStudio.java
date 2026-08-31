package com.botmaker.studio;

import com.botmaker.session.remote.DisplayAgent;
import com.botmaker.shared.capture.linux.X11ErrorTrap;
import com.botmaker.session.impl.NestedSession;
import com.botmaker.studio.project.BotProject;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.project.migration.ProjectSchema;
import com.botmaker.studio.project.migration.ProjectSchemaTooNew;
import com.botmaker.studio.ui.app.ForceX11Notice;
import com.botmaker.studio.ui.app.ProjectSelectionScreen;
import com.botmaker.studio.ui.app.ProjectWindow;
import com.botmaker.studio.ui.app.StudioWindow;
import com.botmaker.studio.ui.app.UIManager;
import com.botmaker.studio.ui.app.runner.RunnerWindow;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;


public class BotMakerStudio extends Application {

    /** Root directory where all user projects live. */
    public static final Path PROJECTS_ROOT =
            Path.of(System.getProperty("user.home"), "BotMakerProjects").toAbsolutePath();

    /** Narrowest the shell may be dragged — see {@code configureWindow}. */
    private static final double MIN_WINDOW_WIDTH = 900;
    /** Shortest the shell may be dragged: menu bar, toolbar, a usable canvas and the status line. */
    private static final double MIN_WINDOW_HEIGHT = 560;

    /** The currently open project (null when on project selection screen). */
    private BotProject currentProject;
    /** The window built for {@link #currentProject}, held only so it can be disposed when that project ends. */
    private ProjectWindow currentWindow;

    /**
     * Which window the open project gets, when the user has said. {@code null} means "derive it" — an
     * installed bot opens in the Runner, your own opens in the editor.
     *
     * <p>Session-only and deliberately not on disk: previewing what a user sees, or taking a read-only look at
     * an installed bot's code, must not change what the project <em>is</em>. Only "Improve this bot" does that,
     * and it does it through {@link com.botmaker.studio.project.ProjectMode}'s marker. Cleared whenever a
     * different project is opened, so the choice never outlives the bot it was made about.
     */
    private Boolean showAsUser;
    /** The project {@link #showAsUser} was chosen for. */
    private String openProjectName;

    /** The primary window, kept for owning dialogs. */
    private Stage primaryStage;

    /** Guards the one-time-per-session Wayland → X11 notice (across project switches). */
    private boolean waylandNoticeChecked;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        // Before any window is shown: from here on every window gets the stylesheet and the theme class as it
        // appears, including the popups (context menus, tooltips, dropdowns) that no call site can reach.
        ThemedWindows.install();
        // Collect what a previous run (or a killed bot JVM) left behind, before anything asks the process table
        // whether a launcher is open: a leftover session reads as one, and a launch is then refused on its account.
        // Off the FX thread — it shells out to systemctl.
        //
        // It also warms the `systemd-run --user --scope` probe every SessionReaper needs, which is cached per JVM
        // but costs a spawn and a waitFor the first time. Paying it here, while the user is still opening a
        // project, keeps it off the first session launch — which is why this thread starts before the UI is built.
        Thread sweep = new Thread(NestedSession::reapOrphanSessions, "session-orphan-sweep");
        sweep.setDaemon(true);
        sweep.start();
        applyAppIcons(primaryStage);
        configureWindow(primaryStage);
        String requested = requestedProject();
        String toOpen = requested != null ? requested : ProjectPreferences.getLastOpened();
        if (toOpen != null && projectExists(toOpen)) {
            openProject(primaryStage, toOpen, false);
        } else {
            if (requested != null) {
                // Named but absent: say so rather than silently showing the picker, because the caller was
                // a program (`botmaker run --project`) and a picker is not an answer a program can read.
                System.err.println("No project '" + requested + "' under " + PROJECTS_ROOT
                        + " — showing the project list instead.");
            }
            showProjectSelection(primaryStage);
        }
    }

    // =========================================================================
    // PROJECT SELECTION
    // =========================================================================

    private void showProjectSelection(Stage primaryStage) {
        ProjectSelectionScreen selectionScreen = new ProjectSelectionScreen(
                primaryStage,
                (projectName, clearCache, freshlyCreated) ->
                        openProject(primaryStage, projectName, freshlyCreated)
        );
        setScenePreservingGeometry(primaryStage, selectionScreen.createScene());
        primaryStage.setTitle("BotMaker - Select Project");
        primaryStage.show();
        requestSceneLayout(primaryStage);
    }

    /**
     * Forces a layout pass once the real scene is shown, so the content fills the stage. The stage is given
     * its geometry in {@link #configureWindow} before {@code show()} and keeps it across every scene swap
     * ({@link #setScenePreservingGeometry}), so this is a belt-and-suspenders relayout rather than a resize.
     */
    private void requestSceneLayout(Stage stage) {
        Platform.runLater(() -> {
            if (stage.getScene() != null && stage.getScene().getRoot() != null) {
                stage.getScene().getRoot().requestLayout();
            }
        });
    }

    // =========================================================================
    // PROJECT LIFECYCLE
    // =========================================================================

    private void openProject(Stage primaryStage, String projectName, boolean freshlyCreated) {
        // 1. Close previous project — its window first. This is also the reload path (a VCS rollback publishes
        //    ProjectReloadRequestedEvent), so it runs far more often than "the user picked another project";
        //    without releasing the shell the Remote Pilot port and its nested display would survive every one.
        disposeUi();
        if (currentProject != null) {
            currentProject.close();
            currentProject = null;
        }

        // A window choice belongs to the project it was made about. Reloading the same one (the audience
        // toggle, a VCS rollback) keeps it; moving to another drops it.
        if (!projectName.equals(openProjectName)) showAsUser = null;
        openProjectName = projectName;

        // 2. Save preference
        ProjectPreferences.updateLastOpened(projectName);

        // 3. Show the loading screen immediately so the window is never blank/frozen while the (slow,
        //    possibly download-heavy) open runs off the FX thread.
        Label statusLabel = new Label("Resolving dependencies…");
        ProgressBar progressBar = new ProgressBar();
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setPrefWidth(320);
        setScenePreservingGeometry(primaryStage, createLoadingScene(projectName, statusLabel, progressBar));
        primaryStage.setTitle("BotMaker - Opening " + projectName + "…");
        primaryStage.show();
        requestSceneLayout(primaryStage);

        // 4. Run BotProject.open() on a background thread; its progress feeds the status label AND a real
        //    percentage bar during the (download-heavy) dependency resolution. Non-download phases report a
        //    negative fraction, which JavaFX renders as an indeterminate bar.
        Task<OpenedProject> openTask = new Task<>() {
            @Override
            protected OpenedProject call() {
                BotProject project = BotProject.open(projectName, PROJECTS_ROOT, false, (fraction, message) -> {
                    updateProgress(fraction, 1.0);
                    updateMessage(fraction >= 0
                            ? message + " — " + Math.round(fraction * 100) + "%"
                            : message);
                });
                // Reading the sources belongs here rather than in finishOpen: it is a disk walk plus one
                // readString per file, and on FX it ran with the main window already shown — a project with a
                // few dozen activities froze it for as long as the disk took.
                updateProgress(-1, 1.0);
                updateMessage("Reading project files…");
                return new OpenedProject(project, project.getCodeEditorService().readProjectSources());
            }
        };
        statusLabel.textProperty().bind(openTask.messageProperty());
        progressBar.progressProperty().bind(openTask.progressProperty());

        openTask.setOnSucceeded(e -> {
            statusLabel.textProperty().unbind();
            progressBar.progressProperty().unbind();
            OpenedProject opened = openTask.getValue();
            currentProject = opened.project();
            finishOpen(primaryStage, projectName, freshlyCreated, opened.sources());
        });

        openTask.setOnFailed(e -> {
            statusLabel.textProperty().unbind();
            progressBar.progressProperty().unbind();
            Throwable ex = openTask.getException();
            if (ex != null) ex.printStackTrace();
            showErrorDialog("Error opening project: " + (ex == null ? "unknown error" : ex.getMessage()));
            showProjectSelection(primaryStage);
        });

        Thread t = new Thread(openTask, "project-open");
        t.setDaemon(true);
        t.start();
    }

    /** What the background open produced: the project, and the sources read for it off the FX thread. */
    private record OpenedProject(BotProject project, java.util.List<ProjectFile> sources) {}

    /**
     * Post-open UI wiring that must run on the FX thread once {@link BotProject#open} has completed.
     *
     * <p>The order here is the point. Parsing the entry point with bindings and building its blocks is the one
     * genuinely slow step left, and it used to run <em>between</em> setting the scene and painting it — the
     * window was up, sized and completely white for the whole of it, which is indistinguishable from a hang.
     * So the shell is shown first with the canvas in its loading state, and the parse is handed to
     * {@link #afterFirstPaint}. Everything after the parse (the reload subscription, the Wayland notice, the
     * new-project setup dialog) goes with it, since all of it either assumes a rendered program or opens a
     * dialog over one.
     */
    private void finishOpen(Stage primaryStage, String projectName, boolean freshlyCreated,
                            java.util.List<ProjectFile> sources) {
        try {
            // A project saved by a newer Studio is refused here and not in ProjectOpenMigrations, because it
            // has to be refused for *both* audiences: a file shape this build cannot read is no more readable
            // to the Runner than to the editor, and the Runner never reaches the migrations at all. Before the
            // close hook and the reload subscription, so a refusal leaves nothing wired to a project we are
            // about to abandon.
            ProjectSchema.check(currentProject.getConfig());

            primaryStage.setOnCloseRequest(e -> {
                e.consume();
                shutdown();
            });

            // A VCS rollback rewrites the working tree on disk, and "Improve this bot" changes which window
            // this project gets; both ask for a reload. Subscribed before either window is built, because
            // both of them can raise it.
            currentProject.getEventBus().subscribe(
                    com.botmaker.studio.events.CoreApplicationEvents.ProjectReloadRequestedEvent.class,
                    e -> openProject(primaryStage, projectName, false), true);

            // The audience decides the whole window, not a set of hidden controls — so the branch is here,
            // before anything editor-shaped is constructed. The Runner needs no parsed source at all, which
            // is why it returns before the block-building work below.
            if (openAsUser()) {
                openRunner(primaryStage, projectName);
                return;
            }

            UIManager uiManager = getUiManager(primaryStage, projectName);

            setScenePreservingGeometry(primaryStage, uiManager.createScene());
            primaryStage.setTitle("BotMaker Blocks - " + projectName);
            uiManager.showEditorLoading();

            primaryStage.show();
            requestSceneLayout(primaryStage);

            // The project this deferred work belongs to. A reload (or a fast switch to another project) can
            // land between the frame and the tick, and the sources read above are this project's, not its
            // successor's.
            BotProject opened = currentProject;
            afterFirstPaint(() -> {
                if (currentProject != opened) return;
                try {
                    currentProject.getCodeEditorService().openInitialFile(sources);

                    // One-time-per-session: on Wayland, guide the user to switch to X11 (and offer install).
                    if (!waylandNoticeChecked) {
                        waylandNoticeChecked = true;
                        ForceX11Notice.maybeShow(primaryStage);
                    }

                    // A brand-new project used to auto-open the Project Setup checklist here. That checklist
                    // is the SDK plugin's 📋 Project Setup toolbar item since 2026-08-31, and the shell has
                    // no handle on another plugin's item — the same precedent the Capture Templates and
                    // Capture Targets steps set. The button is on the bar the moment the project opens.
                } catch (Exception e) {
                    e.printStackTrace();
                    showErrorDialog("Error opening project: " + e.getMessage());
                    showProjectSelection(primaryStage);
                }
            });
        } catch (ProjectSchemaTooNew ex) {
            // Not a failure, so it is not dressed as one: the message is a whole explanation with the way out
            // in it, and prefixing "Error opening project:" would bury that.
            showErrorDialog(ex.getMessage());
            showProjectSelection(primaryStage);
        } catch (Exception e) {
            e.printStackTrace();
            showErrorDialog("Error opening project: " + e.getMessage());
            showProjectSelection(primaryStage);
        }
    }

    /**
     * Runs {@code work} once the window has actually been drawn.
     *
     * <p>Not {@code Platform.runLater}: the runLater queue is drained at the <em>start</em> of a pulse, before
     * that pulse lays out and paints, so a long task posted there still delays the first frame — the blank
     * window would just be blank for a pulse longer. An {@link AnimationTimer} ticks once per pulse, so
     * skipping one tick puts the work after a completed frame.
     */
    private static void afterFirstPaint(Runnable work) {
        new AnimationTimer() {
            private int pulses;

            @Override
            public void handle(long now) {
                if (pulses++ < 2) return;
                stop();
                work.run();
            }
        }.start();
    }

    /** A minimal loading scene: title, a progress bar (bound to the open task), and a live status line. */
    private Scene createLoadingScene(String projectName, Label statusLabel, ProgressBar progressBar) {
        Label title = new Label("Opening " + projectName + "…");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        // Not inline: `gray` is a literal, and a literal survives the theme switch that everything around it obeys.
        statusLabel.getStyleClass().add("dialog-hint");

        VBox box = new VBox(15, title, progressBar, statusLabel);
        box.setAlignment(Pos.CENTER);
        // Deliberately unsized: this scene lands on a stage that already has the user's geometry, and a sized
        // scene would impose 620×600 on it for the duration of the open.
        return ThemedWindows.scene(box);
    }

    /** True when this project should open in the Runner: the user said so, or it is somebody else's bot. */
    private boolean openAsUser() {
        return showAsUser != null ? showAsUser : currentProject.context().state().isReaderMode();
    }

    /**
     * Builds the Runner — the window for using a bot rather than writing one. Its way out is a reload rather
     * than a scene swap: the project's services and its event bus are rebuilt with it, so nothing from the
     * window being left behind can still be listening.
     */
    private void openRunner(Stage primaryStage, String projectName) {
        RunnerWindow.Origin origin = Boolean.TRUE.equals(showAsUser)
                ? RunnerWindow.Origin.PREVIEW
                : RunnerWindow.Origin.INSTALLED;
        RunnerWindow runner = new RunnerWindow(currentProject.context(), primaryStage, origin, () -> {
            showAsUser = Boolean.FALSE;
            openProject(primaryStage, projectName, false);
        });
        this.currentWindow = runner;

        setScenePreservingGeometry(primaryStage, runner.createScene());
        primaryStage.setTitle("BotMaker - " + projectName);
        primaryStage.show();
        requestSceneLayout(primaryStage);
    }

    private UIManager getUiManager(Stage primaryStage, String projectName) {
        UIManager uiManager = new UIManager(currentProject.context(), primaryStage);
        uiManager.setOnSelectProject(v -> switchToProjectSelector(primaryStage));
        uiManager.setOnPreviewAsUser(() -> {
            showAsUser = Boolean.TRUE;
            openProject(primaryStage, projectName, false);
        });
        this.currentWindow = uiManager;
        return uiManager;
    }

    /**
     * Releases the current window's OS resources (the Remote Pilot port, its nested display, the theme
     * listeners) before the project behind it goes away. FX-thread only, and idempotent — every path that ends
     * a project calls it.
     */
    private void disposeUi() {
        if (currentWindow != null) {
            currentWindow.dispose();
            currentWindow = null;
        }
    }

    private void switchToProjectSelector(Stage primaryStage) {
        disposeUi();
        if (currentProject != null) {
            currentProject.close();
            currentProject = null;
        }
        showProjectSelection(primaryStage);
    }

    private void shutdown() {
        disposeUi(); // on the FX thread, before the background close below
        new Thread(() -> {
            try {
                if (currentProject != null) currentProject.close();
            } catch (Exception ex) {
                System.err.println("Error during shutdown: " + ex.getMessage());
            } finally {
                Platform.runLater(() -> {
                    Platform.exit();
                    System.exit(0);
                });
            }
        }).start();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Load the window/taskbar icon from the bundled PNG rasters (generated from {@code icons/icon.svg};
     * JavaFX's {@link Image} can't read SVG). Multiple sizes let the OS pick the sharpest per context.
     * Missing rasters are skipped so a fresh checkout without generated PNGs still launches.
     */
    /**
     * Sizes the window at startup and keeps its geometry synced with {@link ProjectPreferences}.
     *
     * <p>A session that ended maximized reopens maximized, through {@code setMaximized(true)}; a session that
     * ended at a size the user chose reopens at that size. A fresh install gets a large inset window. The
     * saved restored geometry is kept alongside the maximized flag, so un-maximizing lands on the size the
     * user last picked rather than on whatever the WM decides.
     *
     * <p><b>Only a real user resize is recorded.</b> This used to track every {@code x/y/width/height} change
     * and clear the maximized flag on each, then flush on focus loss — so opening a dialog (which takes focus)
     * wrote back whatever geometry the WM had transiently reported, and the main window drifted a little every
     * time a dialog opened or closed. Now the write is debounced, and both the write and the tracking are
     * skipped unless the stage is focused and not maximized: while a dialog is up, the shell's own geometry is
     * not the user talking.
     */
    private void configureWindow(Stage stage) {
        javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
        ProjectPreferences.WindowState saved = ProjectPreferences.loadWindowState();

        // The restored (non-maximized) geometry we persist and fall back to; a large inset window by default.
        ProjectPreferences.WindowState state = (saved != null && saved.isUsable())
                ? saved
                : new ProjectPreferences.WindowState(
                        vb.getMinX() + vb.getWidth() * 0.05, vb.getMinY() + vb.getHeight() * 0.05,
                        vb.getWidth() * 0.9, vb.getHeight() * 0.9, true);

        // The floor goes on the Stage, not on the scene root: root.setMinWidth(0) (UIManager.createScene) is
        // what stops a growing toolbar pushing the window outwards and must stay 0, so the root's computed
        // minimum — which is what JavaFX would otherwise derive the window's from — says nothing. Below this
        // the editor is not merely untidy: the canvas has no room for a block's own width and the toolbar is
        // entirely in its overflow menu. Clamped to the screen, since a minimum larger than the display is a
        // window that cannot be positioned at all.
        stage.setMinWidth(Math.min(MIN_WINDOW_WIDTH, vb.getWidth()));
        stage.setMinHeight(Math.min(MIN_WINDOW_HEIGHT, vb.getHeight()));

        stage.setX(state.getX());
        stage.setY(state.getY());
        stage.setWidth(state.getWidth());
        stage.setHeight(state.getHeight());
        if (state.isMaximized()) stage.setMaximized(true);

        // One pending write at a time, a second after the last change: a drag-resize is hundreds of property
        // events, and the file is rewritten wholesale.
        javafx.animation.PauseTransition flush = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1));
        flush.setOnFinished(e -> ProjectPreferences.saveWindowState(state));

        javafx.beans.value.ChangeListener<Number> geom = (obs, o, n) -> {
            if (StudioWindow.fillsScreen(stage) || !stage.isFocused() || !stage.isShowing()) return;
            state.setX(stage.getX());
            state.setY(stage.getY());
            state.setWidth(stage.getWidth());
            state.setHeight(stage.getHeight());
            state.setMaximized(false);
            flush.playFromStart();
        };
        stage.xProperty().addListener(geom);
        stage.yProperty().addListener(geom);
        stage.widthProperty().addListener(geom);
        stage.heightProperty().addListener(geom);

        // The maximize toggle is a user act whatever the focus state, and cheap enough to write straight out.
        stage.maximizedProperty().addListener((obs, was, isMax) -> {
            // Going fullscreen drops the maximized flag on some window managers. That is the WM answering, not
            // the user un-maximizing, and persisting it would reopen a session that ended full-screen as a
            // small window.
            if (stage.isFullScreen()) return;
            state.setMaximized(isMax);
            ProjectPreferences.saveWindowState(state);
        });
        stage.setOnHidden(e -> {
            flush.stop();
            ProjectPreferences.saveWindowState(state);
        });
    }

    /**
     * Puts {@code scene} on the shell's stage without letting it resize the window.
     *
     * <p>The shell swaps its whole scene several times in a normal session — project selector, loading
     * screen, editor, Runner — and a {@link Scene} built with a size resizes the stage it is set on. That is
     * the other half of "the window changes size when I open something": the geometry is restored around the
     * swap so the window the user sized stays the size they made it. All four of those scenes are now built
     * unsized, so this is the belt to that pair of braces rather than the only thing holding the window still.
     *
     * <p><b>Maximized is handled, not skipped.</b> It used to return early on a maximized stage — nothing to
     * restore, since the window manager owns the geometry — which was true of the *window* and false of the
     * *scene*: a sized scene kept its own size inside the maximized frame and the rest showed as a black
     * border. The re-assert below is what closes that, and it costs nothing when the scene already fills.
     *
     * <p>And it is a re-assert, not a test. The first fix asked for the maximize again only
     * {@code if (!stage.isMaximized())} — but the flag survives a scene swap, so that condition was false on
     * exactly the path that breaks (editor → user view → editor, maximized throughout) and true only where
     * nothing was wrong. A property that already holds the value fires no invalidation and re-runs no
     * maximize, so the flag is dropped and set again in the same pulse to force one.
     *
     * <p>Fullscreen is the same window as far as this is concerned — see {@code StudioWindow.fillsScreen} —
     * except that the re-assert has to be on the flag the user set: a fullscreen editor put back as a
     * maximized one has still lost the state it was in.
     */
    private void setScenePreservingGeometry(Stage stage, Scene scene) {
        boolean wasFullScreen = stage.isFullScreen();
        boolean wasFilling = StudioWindow.fillsScreen(stage);
        double x = stage.getX();
        double y = stage.getY();
        double w = stage.getWidth();
        double h = stage.getHeight();
        stage.setScene(scene);
        if (wasFilling) {
            // setScene on a filled stage leaves the flag on while the scene sits at its own size; taking the
            // flag off and putting it back re-runs the fill, which is what makes the root fill the frame.
            // Fullscreen gets the same treatment on its own flag — asserting maximize on it would leave the
            // user in a window they never asked for.
            if (wasFullScreen) {
                stage.setFullScreen(false);
                stage.setFullScreen(true);
            } else {
                stage.setMaximized(false);
                stage.setMaximized(true);
            }
            fillStage(stage, scene);
            requestSceneLayout(stage);
            return;
        }
        if (Double.isNaN(w) || Double.isNaN(h) || w <= 0 || h <= 0) return;
        if (stage.getWidth() != w) stage.setWidth(w);
        if (stage.getHeight() != h) stage.setHeight(h);
        if (stage.getX() != x) stage.setX(x);
        if (stage.getY() != y) stage.setY(y);
    }

    /**
     * The belt to the maximize re-assert: one pulse later, a root still laid out smaller than the scene it is
     * in is resized to fill it.
     *
     * <p>This is the black border itself, measured rather than assumed — the gap the user sees is the stage's
     * background showing where the root stopped. The root's min sizes are 0 ({@code UIManager.createScene}),
     * so nothing here can push the window outwards; the only thing it can do is take up slack that a scene
     * swap left behind.
     */
    private void fillStage(Stage stage, Scene scene) {
        Platform.runLater(() -> {
            if (stage.getScene() != scene || !(scene.getRoot() instanceof Region root)) return;
            if (root.getWidth() < scene.getWidth() - 1 || root.getHeight() < scene.getHeight() - 1) {
                root.resize(scene.getWidth(), scene.getHeight());
                root.requestLayout();
            }
        });
    }

    private void applyAppIcons(Stage stage) {
        for (int size : new int[] {16, 32, 64, 128, 256, 512}) {
            InputStream in = getClass().getResourceAsStream("/icons/icon-" + size + ".png");
            if (in != null) stage.getIcons().add(new Image(in));
        }
    }

    /**
     * The project named on the command line as {@code --project=<name>}, or {@code null}.
     *
     * <p>Added 2026-08-28 for {@code botmaker run}, whose whole job is to build a plugin and put the user in
     * front of a project that uses it. Without it the CLI would have to write Studio's own preferences file
     * from outside to choose what opens, which is a program reaching into another program's state.
     *
     * <p>A <em>name</em>, not a path, because that is the unit Studio deals in: projects live under
     * {@link #PROJECTS_ROOT} and are opened, remembered and listed by name everywhere else. A path here
     * would be a second way to identify the same thing.
     *
     * <p>It does not become the remembered project by itself — {@link #openProject} does that, exactly as it
     * would for a click. Opening one project by request and then having Studio reopen it next time is what a
     * user expects; suppressing that would be a special case with no reason behind it.
     */
    private String requestedProject() {
        Parameters parameters = getParameters();
        if (parameters == null) {
            return null;
        }
        String named = parameters.getNamed().get("project");
        return named == null || named.isBlank() ? null : named;
    }

    private boolean projectExists(String projectName) {
        Path projectPath = PROJECTS_ROOT.resolve(projectName);
        return Files.exists(projectPath) && Files.exists(projectPath.resolve("pom.xml"));
    }

    private void showErrorDialog(String message) {
        Alert alert = ThemedWindows.alert(Alert.AlertType.ERROR);
        if (primaryStage != null) alert.initOwner(primaryStage);
        alert.setTitle("Error");
        alert.setHeaderText("Failed to open project");
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        // Before anything else — certainly before JavaFX: this same program is what a session re-execs to get a
        // process that can hold a :N connection safely (see DisplayAgent). Booting a whole IDE to answer window
        // queries over a pipe would be absurd, and the agent's stdout is a binary protocol a UI would corrupt.
        if (DisplayAgent.isAgentInvocation(args)) {
            DisplayAgent.run(args);
            return;
        }
        // Swallow benign Xlib protocol errors (BadMatch from window capture, etc.) at their source. Must run
        // BEFORE launch(): installing an Xlib error handler after JavaFX's GTK backend is up triggers GDK's
        // own "XSetErrorHandler() called with a GDK error trap pushed" warning. No-op off Linux.
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            X11ErrorTrap.install();
        }
        launch(args);
    }
}
