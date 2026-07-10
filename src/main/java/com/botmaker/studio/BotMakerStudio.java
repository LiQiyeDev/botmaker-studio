package com.botmaker.studio;

import com.botmaker.shared.capture.linux.X11ErrorSilencer;
import com.botmaker.studio.project.BotProject;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.ui.app.ForceX11Notice;
import com.botmaker.studio.ui.app.ProjectSelectionScreen;
import com.botmaker.studio.ui.app.UIManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;


public class BotMakerStudio extends Application {

    /** Root directory where all user projects live. */
    public static final Path PROJECTS_ROOT =
            Path.of(System.getProperty("user.home"), "BotMakerProjects").toAbsolutePath();

    /** The currently open project (null when on project selection screen). */
    private BotProject currentProject;

    /** The primary window, kept for owning dialogs. */
    private Stage primaryStage;

    /** Guards the one-time-per-session Wayland → X11 notice (across project switches). */
    private boolean waylandNoticeChecked;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        applyAppIcons(primaryStage);
        configureWindow(primaryStage);
        String lastProject = ProjectPreferences.getLastOpened();
        if (lastProject != null && projectExists(lastProject)) {
            openProject(primaryStage, lastProject);
        } else {
            showProjectSelection(primaryStage);
        }
    }

    // =========================================================================
    // PROJECT SELECTION
    // =========================================================================

    private void showProjectSelection(Stage primaryStage) {
        ProjectSelectionScreen selectionScreen = new ProjectSelectionScreen(
                primaryStage,
                (projectName, clearCache) -> openProject(primaryStage, projectName)
        );
        primaryStage.setScene(selectionScreen.createScene());
        primaryStage.setTitle("BotMaker - Select Project");
        primaryStage.show();
        requestSceneLayout(primaryStage);
    }

    /**
     * Forces a layout pass once the real scene is shown, so the content fills the (explicitly sized) stage.
     * The stage is already given concrete bounds in {@link #configureWindow} before {@code show()}, so this
     * is just a belt-and-suspenders relayout — no {@code setMaximized} here (see {@code configureWindow} for
     * why the startup fill is done with explicit bounds rather than the WM's async maximize).
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

    private void openProject(Stage primaryStage, String projectName) {
        // 1. Close previous project
        if (currentProject != null) {
            currentProject.close();
            currentProject = null;
        }

        // 2. Save preference
        ProjectPreferences.updateLastOpened(projectName);

        // 3. Show the loading screen immediately so the window is never blank/frozen while the (slow,
        //    possibly download-heavy) open runs off the FX thread.
        Label statusLabel = new Label("Resolving dependencies…");
        ProgressBar progressBar = new ProgressBar();
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setPrefWidth(320);
        primaryStage.setScene(createLoadingScene(projectName, statusLabel, progressBar));
        primaryStage.setTitle("BotMaker - Opening " + projectName + "…");
        primaryStage.show();
        requestSceneLayout(primaryStage);

        // 4. Run BotProject.open() on a background thread; its progress feeds the status label AND a real
        //    percentage bar during the (download-heavy) dependency resolution. Non-download phases report a
        //    negative fraction, which JavaFX renders as an indeterminate bar.
        Task<BotProject> openTask = new Task<>() {
            @Override
            protected BotProject call() {
                return BotProject.open(projectName, PROJECTS_ROOT, false, (fraction, message) -> {
                    updateProgress(fraction, 1.0);
                    updateMessage(fraction >= 0
                            ? message + " — " + Math.round(fraction * 100) + "%"
                            : message);
                });
            }
        };
        statusLabel.textProperty().bind(openTask.messageProperty());
        progressBar.progressProperty().bind(openTask.progressProperty());

        openTask.setOnSucceeded(e -> {
            statusLabel.textProperty().unbind();
            progressBar.progressProperty().unbind();
            currentProject = openTask.getValue();
            finishOpen(primaryStage, projectName);
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

    /** Post-open UI wiring that must run on the FX thread once {@link BotProject#open} has completed. */
    private void finishOpen(Stage primaryStage, String projectName) {
        try {
            UIManager uiManager = getUiManager(primaryStage);

            primaryStage.setScene(uiManager.createScene());
            primaryStage.setTitle("BotMaker Blocks - " + projectName);

            currentProject.getCodeEditorService().loadInitialCode();

            primaryStage.setOnCloseRequest(e -> {
                e.consume();
                shutdown();
            });

            primaryStage.show();
            requestSceneLayout(primaryStage);

            // One-time-per-session: on Wayland, guide the user to switch to X11 (and offer package install).
            if (!waylandNoticeChecked) {
                waylandNoticeChecked = true;
                ForceX11Notice.maybeShow(primaryStage);
            }
        } catch (Exception e) {
            e.printStackTrace();
            showErrorDialog("Error opening project: " + e.getMessage());
            showProjectSelection(primaryStage);
        }
    }

    /** A minimal loading scene: title, a progress bar (bound to the open task), and a live status line. */
    private Scene createLoadingScene(String projectName, Label statusLabel, ProgressBar progressBar) {
        Label title = new Label("Opening " + projectName + "…");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        VBox box = new VBox(15, title, progressBar, statusLabel);
        box.setAlignment(Pos.CENTER);
        return new Scene(box, 620, 600);
    }

    private UIManager getUiManager(Stage primaryStage) {
        UIManager uiManager = new UIManager(
                currentProject.getDragAndDropManager(),
                currentProject.getEventBus(),
                currentProject.getCodeEditorService(),
                currentProject.getDiagnosticsManager(),
                primaryStage,
                currentProject.getConfig(),
                currentProject.getState(),
                currentProject.getProjectAnalyzer(),
                currentProject.getLibraryService(),
                currentProject.getActivityService(),
                currentProject.getCodeExecutionService()
        );
        uiManager.setOnSelectProject(v -> switchToProjectSelector(primaryStage));
        return uiManager;
    }

    private void switchToProjectSelector(Stage primaryStage) {
        if (currentProject != null) {
            currentProject.close();
            currentProject = null;
        }
        showProjectSelection(primaryStage);
    }

    private void shutdown() {
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
     * <p>The default (fresh install, or a previous session left "maximized") is to <em>fill the usable
     * screen</em> by setting explicit bounds to the primary screen's visual bounds — deliberately <em>not</em>
     * {@code stage.setMaximized(true)}. On GTK/X11 a maximize is applied asynchronously by the window manager
     * after {@code show()}, so the scene is laid out at the pre-maximize size (a black border between content
     * and frame until a manual resize), the window visibly jumps, and the first maximize toggle is frequently
     * dropped (the "have to click twice to expand" symptom). An explicit fill is deterministic and paints
     * correctly on the first frame. A user's explicitly-saved <em>restored</em> (non-maximized) size still wins.
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

        boolean fill = (saved == null || !saved.isUsable() || saved.isMaximized());
        if (fill) {
            stage.setX(vb.getMinX());
            stage.setY(vb.getMinY());
            stage.setWidth(vb.getWidth());
            stage.setHeight(vb.getHeight());
        } else {
            stage.setX(state.getX());
            stage.setY(state.getY());
            stage.setWidth(state.getWidth());
            stage.setHeight(state.getHeight());
        }

        // Track the actual (non-maximized) geometry so we persist a usable size, never the maximized bounds.
        // Any such change (user resize/move, or our startup fill) also clears the "maximized" flag, so a filled
        // window the user later resizes is restored at that size next launch instead of re-filling the screen.
        javafx.beans.value.ChangeListener<Number> geom = (obs, o, n) -> {
            if (!stage.isMaximized()) {
                state.setX(stage.getX());
                state.setY(stage.getY());
                state.setWidth(stage.getWidth());
                state.setHeight(stage.getHeight());
                state.setMaximized(false);
            }
        };
        stage.xProperty().addListener(geom);
        stage.yProperty().addListener(geom);
        stage.widthProperty().addListener(geom);
        stage.heightProperty().addListener(geom);

        // Flush to disk only on cheap, infrequent events (never per resize-pixel): maximize toggle, focus loss, close.
        stage.maximizedProperty().addListener((obs, was, isMax) -> {
            state.setMaximized(isMax);
            ProjectPreferences.saveWindowState(state);
        });
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) ProjectPreferences.saveWindowState(state);
        });
        stage.setOnHidden(e -> ProjectPreferences.saveWindowState(state));
    }

    private void applyAppIcons(Stage stage) {
        for (int size : new int[] {16, 32, 64, 128, 256, 512}) {
            InputStream in = getClass().getResourceAsStream("/icons/icon-" + size + ".png");
            if (in != null) stage.getIcons().add(new Image(in));
        }
    }

    private boolean projectExists(String projectName) {
        Path projectPath = PROJECTS_ROOT.resolve(projectName);
        return Files.exists(projectPath) && Files.exists(projectPath.resolve("pom.xml"));
    }

    private void showErrorDialog(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        if (primaryStage != null) alert.initOwner(primaryStage);
        alert.setTitle("Error");
        alert.setHeaderText("Failed to open project");
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        // Swallow benign Xlib protocol errors (BadMatch from window capture, etc.) at their source. Must run
        // BEFORE launch(): installing an Xlib error handler after JavaFX's GTK backend is up triggers GDK's
        // own "XSetErrorHandler() called with a GDK error trap pushed" warning. No-op off Linux.
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            X11ErrorSilencer.install();
        }
        launch(args);
    }
}