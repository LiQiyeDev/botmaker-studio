package com.botmaker;

import com.botmaker.project.BotProject;
import com.botmaker.project.ProjectPreferences;
import com.botmaker.ui.app.ProjectSelectionScreen;
import com.botmaker.ui.app.UIManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;


public class BotMakerStudio extends Application {

    /** Root directory where all user projects live. */
    public static final Path PROJECTS_ROOT =
            Path.of(System.getProperty("user.home"), "BotMakerProjects").toAbsolutePath();

    /** The currently open project (null when on project selection screen). */
    private BotProject currentProject;

    @Override
    public void start(Stage primaryStage) {
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
        primaryStage.setScene(createLoadingScene(projectName, statusLabel));
        primaryStage.setTitle("BotMaker - Opening " + projectName + "…");
        primaryStage.show();

        // 4. Run BotProject.open() on a background thread; its progress messages feed the status label.
        Task<BotProject> openTask = new Task<>() {
            @Override
            protected BotProject call() {
                return BotProject.open(projectName, PROJECTS_ROOT, false, this::updateMessage);
            }
        };
        statusLabel.textProperty().bind(openTask.messageProperty());

        openTask.setOnSucceeded(e -> {
            statusLabel.textProperty().unbind();
            currentProject = openTask.getValue();
            finishOpen(primaryStage, projectName);
        });

        openTask.setOnFailed(e -> {
            statusLabel.textProperty().unbind();
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
        } catch (Exception e) {
            e.printStackTrace();
            showErrorDialog("Error opening project: " + e.getMessage());
            showProjectSelection(primaryStage);
        }
    }

    /** A minimal loading scene: title, an indeterminate progress bar, and a live status line. */
    private Scene createLoadingScene(String projectName, Label statusLabel) {
        Label title = new Label("Opening " + projectName + "…");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        ProgressBar progressBar = new ProgressBar();
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setPrefWidth(320);

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
                currentProject.getActivityService()
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

    private boolean projectExists(String projectName) {
        Path projectPath = PROJECTS_ROOT.resolve(projectName);
        return Files.exists(projectPath) && Files.exists(projectPath.resolve("pom.xml"));
    }

    private void showErrorDialog(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("Failed to open project");
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}