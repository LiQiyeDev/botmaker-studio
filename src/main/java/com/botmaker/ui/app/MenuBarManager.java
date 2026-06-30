package com.botmaker.ui.app;

import com.botmaker.events.CoreApplicationEvents;
import com.botmaker.events.EventBus;
import com.botmaker.util.BrowserLauncher;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

import java.util.function.Consumer;

/**
 * Manages the application menu bar
 */
public class MenuBarManager {

    private final MenuBar menuBar;
    private final Stage primaryStage;
    private Consumer<Void> onSelectProject;
    private Runnable onManageLibraries;
    private Runnable onManageImports;
    private Runnable onManageActivities;
    private Runnable onSetActivityValues;
    private Runnable onManageResources;
    private Runnable onBrowseGallery;
    private Runnable onPublishGallery;
    private EventBus eventBus;
    private MenuItem undoItem;
    private MenuItem redoItem;
    private MenuItem projectRepoItem;
    private String projectRepoUrl;

    /** GitHub repo of the Studio itself (opened from Help → BotMaker Studio on GitHub). */
    private static final String STUDIO_REPO_URL = "https://github.com/LiQiyeDev/BotMaker-Studio";
    /** GitHub repo of the BotMaker SDK (opened from Help → BotMaker SDK on GitHub). */
    private static final String SDK_REPO_URL = "https://github.com/LiQiyeDev/BotMaker-sdk";
    public MenuBarManager(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.menuBar = new MenuBar();
        createMenus();
    }

    /**
     * Creates all menus
     */
    private void createMenus() {
        // File menu
        Menu fileMenu = createFileMenu();

        // Edit menu (placeholder for future)
        Menu editMenu = createEditMenu();

        // View menu (placeholder for future)
        Menu viewMenu = createViewMenu();

        // Project menu
        Menu projectMenu = createProjectMenu();

        // Help menu
        Menu helpMenu = createHelpMenu();

        menuBar.getMenus().addAll(fileMenu, editMenu, viewMenu, projectMenu, helpMenu);
    }

    /**
     * Creates the File menu
     */
    private Menu createFileMenu() {
        Menu fileMenu = new Menu("File");

        // Select Project
        MenuItem selectProjectItem = new MenuItem("Select Project...");
        selectProjectItem.setAccelerator(new KeyCodeCombination(
                KeyCode.O,
                KeyCombination.CONTROL_DOWN,
                KeyCombination.SHIFT_DOWN
        ));
        selectProjectItem.setOnAction(e -> {
            if (onSelectProject != null) {
                onSelectProject.accept(null);
            }
        });

        // Separator
        SeparatorMenuItem separator1 = new SeparatorMenuItem();

        // Exit
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setAccelerator(new KeyCodeCombination(
                KeyCode.Q,
                KeyCombination.CONTROL_DOWN
        ));

        // UPDATED: Force system exit
        exitItem.setOnAction(e -> {
            javafx.application.Platform.exit(); // Close JavaFX
            System.exit(0); // Kill JVM (stops LSP, Debugger, etc.)
        });

        fileMenu.getItems().addAll(
                selectProjectItem,
                separator1,
                exitItem
        );

        return fileMenu;
    }

    /**
     * Creates the Edit menu
     */
    private Menu createEditMenu() {
        Menu editMenu = new Menu("Edit");

        undoItem = new MenuItem("Undo");
        undoItem.setAccelerator(new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN));
        undoItem.setDisable(true);
        undoItem.setOnAction(e -> {
            if (eventBus != null) eventBus.publish(new CoreApplicationEvents.UndoRequestedEvent());
        });

        redoItem = new MenuItem("Redo");
        redoItem.setAccelerator(new KeyCodeCombination(KeyCode.Y, KeyCombination.CONTROL_DOWN));
        redoItem.setDisable(true);
        redoItem.setOnAction(e -> {
            if (eventBus != null) eventBus.publish(new CoreApplicationEvents.RedoRequestedEvent());
        });

        SeparatorMenuItem separator = new SeparatorMenuItem();

        MenuItem cutItem = new MenuItem("Cut");
        cutItem.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.CONTROL_DOWN));
        cutItem.setDisable(true); // Not implemented yet

        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN));
        copyItem.setDisable(true); // Not implemented yet

        MenuItem pasteItem = new MenuItem("Paste");
        pasteItem.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN));
        pasteItem.setDisable(true); // Not implemented yet

        editMenu.getItems().addAll(
                undoItem,
                redoItem,
                separator,
                cutItem,
                copyItem,
                pasteItem
        );

        return editMenu;
    }

    /**
     * Creates the Project menu
     */
    private Menu createProjectMenu() {
        Menu projectMenu = new Menu("Project");

        MenuItem manageLibrariesItem = new MenuItem("Manage Libraries...");
        manageLibrariesItem.setOnAction(e -> {
            if (onManageLibraries != null) onManageLibraries.run();
        });

        MenuItem manageImportsItem = new MenuItem("Manage Imports...");
        manageImportsItem.setOnAction(e -> {
            if (onManageImports != null) onManageImports.run();
        });

        MenuItem manageActivitiesItem = new MenuItem("Manage Activities...");
        manageActivitiesItem.setOnAction(e -> {
            if (onManageActivities != null) onManageActivities.run();
        });

        MenuItem setActivityValuesItem = new MenuItem("Set Activity Values...");
        setActivityValuesItem.setOnAction(e -> {
            if (onSetActivityValues != null) onSetActivityValues.run();
        });

        MenuItem manageResourcesItem = new MenuItem("Resource Manager...");
        manageResourcesItem.setOnAction(e -> {
            if (onManageResources != null) onManageResources.run();
        });

        MenuItem browseGalleryItem = new MenuItem("Browse Gallery...");
        browseGalleryItem.setOnAction(e -> {
            if (onBrowseGallery != null) onBrowseGallery.run();
        });

        MenuItem publishGalleryItem = new MenuItem("Publish to Gallery...");
        publishGalleryItem.setOnAction(e -> {
            if (onPublishGallery != null) onPublishGallery.run();
        });

        // Opens the project's own GitHub repo; disabled until the project has been published.
        projectRepoItem = new MenuItem("Project Repository on GitHub");
        projectRepoItem.setDisable(true);
        projectRepoItem.setOnAction(e -> {
            if (projectRepoUrl != null) BrowserLauncher.open(projectRepoUrl);
        });

        projectMenu.getItems().addAll(
                manageLibrariesItem, manageImportsItem, new SeparatorMenuItem(),
                manageActivitiesItem, setActivityValuesItem, manageResourcesItem, new SeparatorMenuItem(),
                browseGalleryItem, publishGalleryItem, new SeparatorMenuItem(),
                projectRepoItem);
        return projectMenu;
    }

    /**
     * Creates the View menu
     */
    private Menu createViewMenu() {
        Menu viewMenu = new Menu("View");

        // Placeholder items for future implementation
        MenuItem zoomInItem = new MenuItem("Zoom In");
        zoomInItem.setAccelerator(new KeyCodeCombination(
                KeyCode.PLUS,
                KeyCombination.CONTROL_DOWN
        ));
        zoomInItem.setDisable(true); // Not implemented yet

        MenuItem zoomOutItem = new MenuItem("Zoom Out");
        zoomOutItem.setAccelerator(new KeyCodeCombination(
                KeyCode.MINUS,
                KeyCombination.CONTROL_DOWN
        ));
        zoomOutItem.setDisable(true); // Not implemented yet

        MenuItem resetZoomItem = new MenuItem("Reset Zoom");
        resetZoomItem.setAccelerator(new KeyCodeCombination(
                KeyCode.DIGIT0,
                KeyCombination.CONTROL_DOWN
        ));
        resetZoomItem.setDisable(true); // Not implemented yet

        viewMenu.getItems().addAll(
                zoomInItem,
                zoomOutItem,
                resetZoomItem
        );

        return viewMenu;
    }


    public void setEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.subscribe(CoreApplicationEvents.HistoryStateChangedEvent.class, this::updateMenuState, true);
    }

    private void updateMenuState(CoreApplicationEvents.HistoryStateChangedEvent event) {
        if (undoItem != null) undoItem.setDisable(!event.canUndo());
        if (redoItem != null) redoItem.setDisable(!event.canRedo());
    }

    /**
     * Creates the Help menu
     */
    private Menu createHelpMenu() {
        Menu helpMenu = new Menu("Help");

        MenuItem studioRepoItem = new MenuItem("BotMaker Studio on GitHub");
        studioRepoItem.setOnAction(e -> BrowserLauncher.open(STUDIO_REPO_URL));

        MenuItem sdkRepoItem = new MenuItem("BotMaker SDK on GitHub");
        sdkRepoItem.setOnAction(e -> BrowserLauncher.open(SDK_REPO_URL));

        MenuItem aboutItem = new MenuItem("About BotMaker");
        aboutItem.setOnAction(e -> showAboutDialog());

        helpMenu.getItems().addAll(studioRepoItem, sdkRepoItem, new SeparatorMenuItem(), aboutItem);

        return helpMenu;
    }

    /**
     * Shows the about dialog
     */
    private void showAboutDialog() {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION
        );
        alert.initOwner(primaryStage);
        alert.setTitle("About BotMaker");
        alert.setHeaderText("BotMaker Blocks");
        alert.setContentText(
                "Version: 1.0.0\n\n" +
                        "A visual block-based programming environment for Java.\n\n" +
                        "Build Java applications using drag-and-drop blocks!"
        );
        alert.showAndWait();
    }

    /**
     * Gets the menu bar
     */
    public MenuBar getMenuBar() {
        return menuBar;
    }

    /**
     * Sets the callback for when "Select Project" is clicked
     */
    public void setOnSelectProject(Consumer<Void> callback) {
        this.onSelectProject = callback;
    }

    /**
     * Sets the callback for when "Manage Libraries..." is clicked
     */
    public void setOnManageLibraries(Runnable callback) {
        this.onManageLibraries = callback;
    }

    /**
     * Sets the callback for when "Manage Imports..." is clicked
     */
    public void setOnManageImports(Runnable callback) {
        this.onManageImports = callback;
    }

    /** Sets the callback for when "Manage Activities..." is clicked. */
    public void setOnManageActivities(Runnable callback) {
        this.onManageActivities = callback;
    }

    /** Sets the callback for when "Set Activity Values..." is clicked. */
    public void setOnSetActivityValues(Runnable callback) {
        this.onSetActivityValues = callback;
    }

    /** Sets the callback for when "Resource Manager..." is clicked. */
    public void setOnManageResources(Runnable callback) {
        this.onManageResources = callback;
    }

    /**
     * Sets the project's GitHub repo URL (or {@code null} if the project hasn't been published yet).
     * Enables/disables the Project → Project Repository item accordingly.
     */
    public void setProjectRepoUrl(String url) {
        this.projectRepoUrl = url;
        if (projectRepoItem != null) projectRepoItem.setDisable(url == null);
    }

    /**
     * Sets the callback for when "Browse Gallery..." is clicked
     */
    public void setOnBrowseGallery(Runnable callback) {
        this.onBrowseGallery = callback;
    }

    /**
     * Sets the callback for when "Publish to Gallery..." is clicked
     */
    public void setOnPublishGallery(Runnable callback) {
        this.onPublishGallery = callback;
    }
}