package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.util.BrowserLauncher;
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
    private Runnable onShowHistory;
    private EventBus eventBus;
    private MenuItem undoItem;
    private MenuItem redoItem;
    private MenuItem projectRepoItem;
    private Runnable onOpenDebugDashboard;
    private Runnable onEnableRemotePilot;
    private String projectRepoUrl;
    /** The open project's directory, so the About dialog can report the SDK version it pins. May be null. */
    private java.nio.file.Path projectPath;

    /** GitHub repo of the Studio itself (opened from Help → BotMaker Studio on GitHub). */
    private static final String STUDIO_REPO_URL = "https://github.com/LiQiyeDev/BotMaker-Studio";
    /** GitHub repo of the BotMaker SDK (opened from Help → BotMaker SDK on GitHub). */
    private static final String SDK_REPO_URL = "https://github.com/LiQiyeDev/BotMaker-sdk";
    public MenuBarManager(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.menuBar = new MenuBar();
        createMenus();
    }

    /** Sets the open project's directory so the About dialog can report the project's SDK version. */
    public void setProjectPath(java.nio.file.Path projectPath) {
        this.projectPath = projectPath;
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

        MenuItem historyItem = new MenuItem("Project History...");
        historyItem.setOnAction(e -> {
            if (onShowHistory != null) onShowHistory.run();
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
                manageActivitiesItem, setActivityValuesItem, manageResourcesItem,
                new SeparatorMenuItem(),
                historyItem, new SeparatorMenuItem(),
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

        MenuItem debugDashboardItem = new MenuItem("Open Debug Dashboard");
        debugDashboardItem.setOnAction(e -> { if (onOpenDebugDashboard != null) onOpenDebugDashboard.run(); });

        MenuItem remotePilotItem = new MenuItem("Enable Remote Pilot…");
        remotePilotItem.setOnAction(e -> { if (onEnableRemotePilot != null) onEnableRemotePilot.run(); });

        viewMenu.getItems().addAll(
                zoomInItem,
                zoomOutItem,
                resetZoomItem,
                new SeparatorMenuItem(),
                debugDashboardItem,
                remotePilotItem
        );

        return viewMenu;
    }

    /** Sets the action for View ▸ Open Debug Dashboard (starts the local telemetry dashboard server). */
    public void setOnOpenDebugDashboard(Runnable callback) {
        this.onOpenDebugDashboard = callback;
    }

    /** Sets the action for View ▸ Enable Remote Pilot (starts the remote BotPilot server over Tailscale). */
    public void setOnEnableRemotePilot(Runnable callback) {
        this.onEnableRemotePilot = callback;
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

        MenuItem checkUpdatesItem = new MenuItem("Check for Updates…");
        checkUpdatesItem.setOnAction(e -> checkForUpdates(false));

        MenuItem aboutItem = new MenuItem("About BotMaker");
        aboutItem.setOnAction(e -> showAboutDialog());

        helpMenu.getItems().addAll(studioRepoItem, sdkRepoItem, new SeparatorMenuItem(),
                checkUpdatesItem, aboutItem);

        return helpMenu;
    }

    /**
     * Checks GitHub Releases for a newer version and, if the user agrees, downloads and launches the matching
     * installer. When {@code silentIfNone} is true a "you're up to date" result shows no dialog (used for an
     * optional check on startup); a manual check always reports its outcome.
     */
    public void checkForUpdates(boolean silentIfNone) {
        com.botmaker.studio.services.UpdateService service = new com.botmaker.studio.services.UpdateService();
        service.checkForUpdate().thenAccept(opt -> javafx.application.Platform.runLater(() -> {
            if (opt.isEmpty()) {
                if (!silentIfNone) {
                    showInfo("You're up to date",
                            "BotMaker Studio " + com.botmaker.studio.config.AppVersion.get() + " is the latest version.");
                }
                return;
            }
            com.botmaker.studio.services.UpdateService.AvailableUpdate update = opt.get();
            if (!confirm("Update available",
                    "Version " + update.tag() + " is available (you have "
                            + com.botmaker.studio.config.AppVersion.get() + ").\n\nDownload and install it now?")) {
                return;
            }
            downloadAndInstall(service, update);
        }));
    }

    /**
     * Downloads {@code update}'s installer behind a modal progress dialog, then hands it to the OS installer on
     * a background thread (the launch does AWT {@code Desktop} work that must never run on the FX thread, which
     * previously froze the window to a white screen). The user restarts the app manually once the installer runs.
     */
    private void downloadAndInstall(com.botmaker.studio.services.UpdateService service,
                                    com.botmaker.studio.services.UpdateService.AvailableUpdate update) {
        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
        bar.setPrefWidth(320);
        javafx.scene.control.Label status = new javafx.scene.control.Label("Downloading " + update.tag() + "…");
        status.setStyle("-fx-text-fill: gray;");
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(
                12, new javafx.scene.control.Label("Updating BotMaker Studio"), bar, status);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setPadding(new javafx.geometry.Insets(24));

        javafx.stage.Stage dialog = new javafx.stage.Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.setTitle("Updating…");
        dialog.setScene(new javafx.scene.Scene(box));
        dialog.setOnCloseRequest(javafx.event.Event::consume); // no manual close mid-download
        dialog.show();

        // Progress arrives on the HTTP client's thread; marshal to FX. A negative fraction means the server sent
        // no Content-Length, so show an indeterminate bar instead.
        java.util.function.DoubleConsumer onProgress = fraction -> javafx.application.Platform.runLater(() -> {
            if (fraction < 0) {
                bar.setProgress(javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS);
            } else {
                bar.setProgress(fraction);
                status.setText("Downloading " + update.tag() + "… " + (int) Math.round(fraction * 100) + "%");
            }
        });

        service.downloadInstaller(update, onProgress)
                .thenAccept(path -> javafx.application.Platform.runLater(() -> {
                    status.setText("Starting installer…");
                    bar.setProgress(javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS);
                    // Launch off the FX thread; report the outcome back on it.
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            service.launchInstaller(path);
                        } catch (Exception ex) {
                            throw new java.util.concurrent.CompletionException(ex);
                        }
                    }).whenComplete((v, ex) -> javafx.application.Platform.runLater(() -> {
                        dialog.close();
                        if (ex == null) {
                            showInfo("Installer started",
                                    "The installer for " + update.tag() + " has been launched.\n\n"
                                            + "Please quit BotMaker Studio and reopen it to finish updating.");
                        } else {
                            showError("Update failed", rootMessage(ex));
                        }
                    }));
                }))
                .exceptionally(ex -> {
                    javafx.application.Platform.runLater(() -> {
                        dialog.close();
                        showError("Download failed", rootMessage(ex));
                    });
                    return null;
                });
    }

    /** Unwraps {@code CompletionException} so the alert shows the real cause, not the wrapper. */
    private static String rootMessage(Throwable t) {
        Throwable cause = (t instanceof java.util.concurrent.CompletionException && t.getCause() != null)
                ? t.getCause() : t;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private boolean confirm(String header, String content) {
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle(header);
        alert.setHeaderText(header);
        alert.setContentText(content);
        return alert.showAndWait().filter(b -> b == javafx.scene.control.ButtonType.OK).isPresent();
    }

    private void showInfo(String header, String content) {
        showAlert(javafx.scene.control.Alert.AlertType.INFORMATION, header, content);
    }

    private void showError(String header, String content) {
        showAlert(javafx.scene.control.Alert.AlertType.ERROR, header, content);
    }

    private void showAlert(javafx.scene.control.Alert.AlertType type, String header, String content) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(type);
        alert.initOwner(primaryStage);
        alert.setTitle(header);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
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
                "A visual block-based programming environment for Java.\n" +
                        "Build Java applications using drag-and-drop blocks!\n\n" +
                        "Builds in use (local — not the GitHub update check):\n" +
                        "  Studio: " + com.botmaker.studio.config.VersionInfo.studio() + "\n" +
                        "  shared: " + com.botmaker.studio.config.VersionInfo.shared() + "\n" +
                        "  SDK (project): " + com.botmaker.studio.config.VersionInfo.sdkForProject(projectPath)
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

    /**
     * Sets the callback for when "Project History..." is clicked
     */
    public void setOnShowHistory(Runnable callback) {
        this.onShowHistory = callback;
    }
}