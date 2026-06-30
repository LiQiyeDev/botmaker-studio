package com.botmaker;

import com.botmaker.project.BotProject;
import com.botmaker.project.ProjectPreferences;
import com.botmaker.ui.app.ProjectSelectionScreen;
import com.botmaker.ui.app.UIManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
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
        try {
            // 1. Close previous project
            if (currentProject != null) {
                currentProject.close();
                currentProject = null;
            }

            // 2. Save preference
            ProjectPreferences.updateLastOpened(projectName);

            // 3. Open new project
            currentProject = BotProject.open(projectName, PROJECTS_ROOT, false);

            // 4. Setup UI
            UIManager uiManager = getUiManager(primaryStage);

            primaryStage.setScene(uiManager.createScene());
            primaryStage.setTitle("BotMaker Blocks - " + projectName);

            // 5. Load code
            currentProject.getCodeEditorService().loadInitialCode();

            // 6. Shutdown hook
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