package com.botmaker.studio.ui.app;

import com.botmaker.studio.config.AppVersion;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
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
    private Runnable onManagePlugins;
    private Runnable onUpgradeSdk;
    private Runnable onModernise;
    private Runnable onManageImports;
    private Runnable onActivityFlow;
    private Runnable onParameters;
    /** Kept so the entry can be renamed once the project's settings model is known. */
    private Runnable onRecoverProjectFiles;
    private Runnable onReviewChanges;
    private Runnable onManageResources;
    private Runnable onProjectSettings;
    private Runnable onProjectSetup;
    private Runnable onGettingStarted;
    /** Help ▸ Picker Gallery — present only in a dev build. See {@link #createHelpMenu()}. */
    private Runnable onPickerGallery;
    private Runnable onBrowseGallery;
    private Runnable onPublishGallery;
    private Runnable onShowHistory;
    private EventBus eventBus;
    private MenuItem undoItem;
    private MenuItem redoItem;
    private MenuItem projectRepoItem;
    private Runnable onEnableRemotePilot;
    private Runnable onPreviewAsUser;
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

        // Route through the stage's close-request handler rather than exiting here. That handler is the one
        // place that closes the open project first — which is what kills a running bot — and this item used to
        // bypass it entirely, so quitting with Ctrl+Q left the bot running where closing the window did not.
        exitItem.setOnAction(e -> primaryStage.fireEvent(
                new javafx.stage.WindowEvent(primaryStage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));

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

        MenuItem projectSetupItem = new MenuItem("Project Setup...");
        projectSetupItem.setOnAction(e -> {
            if (onProjectSetup != null) onProjectSetup.run();
        });

        MenuItem manageLibrariesItem = new MenuItem("Manage Libraries...");
        manageLibrariesItem.setOnAction(e -> {
            if (onManageLibraries != null) onManageLibraries.run();
        });

        // Beside Manage Libraries because a plugin IS a library — the registry browser is only a way to find
        // the coordinate, which is the one thing META-INF/services cannot tell anybody.
        MenuItem managePluginsItem = new MenuItem("Manage Plugins...");
        managePluginsItem.setOnAction(e -> {
            if (onManagePlugins != null) onManagePlugins.run();
        });

        // Beside Manage Libraries, not inside it: the SDK version is the one library whose change can stop
        // the bot compiling, and that deserves a report rather than a cell edit.
        MenuItem upgradeSdkItem = new MenuItem("Upgrade SDK...");
        upgradeSdkItem.setOnAction(e -> {
            if (onUpgradeSdk != null) onUpgradeSdk.run();
        });

        // The half of Upgrade SDK that needs no upgrade: this bot's own SDK already says which of the members
        // it calls are on the way out and what replaces them, and acting on that is not a version change.
        MenuItem moderniseItem = new MenuItem("Modernise...");
        moderniseItem.setOnAction(e -> {
            if (onModernise != null) onModernise.run();
        });

        MenuItem manageImportsItem = new MenuItem("Manage Imports...");
        manageImportsItem.setOnAction(e -> {
            if (onManageImports != null) onManageImports.run();
        });

        // One entry for the whole activity story — define, configure, order and switch activities on.
        MenuItem activityFlowItem = new MenuItem("Activity Flow...");
        activityFlowItem.setOnAction(e -> {
            if (onActivityFlow != null) onActivityFlow.run();
        });

        // Where a parameter is defined, retyped and exposed. Separate from the flow editor on purpose: that
        // one is about where the bot goes next, this one about what it is configured with.
        MenuItem parametersItem = new MenuItem("Parameters...");
        parametersItem.setOnAction(e -> {
            if (onParameters != null) onParameters.run();
        });

        MenuItem manageResourcesItem = new MenuItem("Resource Manager...");
        manageResourcesItem.setOnAction(e -> {
            if (onManageResources != null) onManageResources.run();
        });

        MenuItem projectSettingsItem = new MenuItem("Project Settings...");
        projectSettingsItem.setOnAction(e -> {
            if (onProjectSettings != null) onProjectSettings.run();
        });

        MenuItem recoverFilesItem = new MenuItem("Recover Project Files...");
        recoverFilesItem.setOnAction(e -> {
            if (onRecoverProjectFiles != null) onRecoverProjectFiles.run();
        });

        // Beside Project History on purpose: the two answer the same question from opposite ends — what did
        // BotMaker change, and what of it still needs looking at.
        MenuItem reviewItem = new MenuItem("Review Changes");
        reviewItem.setOnAction(e -> {
            if (onReviewChanges != null) onReviewChanges.run();
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
                projectSetupItem, new SeparatorMenuItem(),
                manageLibrariesItem, managePluginsItem, upgradeSdkItem, moderniseItem, manageImportsItem,
                new SeparatorMenuItem(),
                activityFlowItem, parametersItem, manageResourcesItem,
                new SeparatorMenuItem(),
                projectSettingsItem, new SeparatorMenuItem(),
                recoverFilesItem, reviewItem, historyItem, new SeparatorMenuItem(),
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

        MenuItem remotePilotItem = new MenuItem("Enable Remote Pilot…");
        remotePilotItem.setOnAction(e -> { if (onEnableRemotePilot != null) onEnableRemotePilot.run(); });

        // Wired here rather than through a callback like its neighbours: the dialog holds no project state —
        // it writes shared's saved-device list, which every picker and every generated bot reads for itself.
        MenuItem connectPhoneItem = new MenuItem("Connect a phone…");
        connectPhoneItem.setOnAction(e -> ConnectPhoneDialog.show(primaryStage));

        // Theme submenu
        Menu themeMenu = new Menu("Theme");
        ToggleGroup themeGroup = new ToggleGroup();

        RadioMenuItem defaultThemeItem = new RadioMenuItem("Default");
        defaultThemeItem.setToggleGroup(themeGroup);
        defaultThemeItem.setSelected(true); // Default theme is selected initially
        defaultThemeItem.setOnAction(e -> BlockTheme.setTheme(BlockTheme.ThemeType.DEFAULT));

        RadioMenuItem darkThemeItem = new RadioMenuItem("Dark");
        darkThemeItem.setToggleGroup(themeGroup);
        darkThemeItem.setOnAction(e -> BlockTheme.setTheme(BlockTheme.ThemeType.DARK));

        RadioMenuItem blackThemeItem = new RadioMenuItem("Black");
        blackThemeItem.setToggleGroup(themeGroup);
        blackThemeItem.setOnAction(e -> BlockTheme.setTheme(BlockTheme.ThemeType.BLACK));

        RadioMenuItem highContrastItem = new RadioMenuItem("High Contrast");
        highContrastItem.setToggleGroup(themeGroup);
        highContrastItem.setOnAction(e -> BlockTheme.setTheme(BlockTheme.ThemeType.HIGH_CONTRAST));

        // Set the selected item based on current theme
        BlockTheme.ThemeType currentTheme = BlockTheme.getCurrentThemeType();
        switch (currentTheme) {
            case DARK -> darkThemeItem.setSelected(true);
            case BLACK -> blackThemeItem.setSelected(true);
            case HIGH_CONTRAST -> highContrastItem.setSelected(true);
            default -> defaultThemeItem.setSelected(true);
        }

        themeMenu.getItems().addAll(defaultThemeItem, darkThemeItem, blackThemeItem, highContrastItem);

        // The other half of the audience switch. It swaps the whole window for the Runner rather than hiding
        // controls, so this is the only honest way to see what you have actually exposed — and it is
        // session-only: nothing about the project changes, and the Runner's header brings you back.
        MenuItem previewAsUserItem = new MenuItem("Preview as user");
        previewAsUserItem.setOnAction(e -> { if (onPreviewAsUser != null) onPreviewAsUser.run(); });

        viewMenu.getItems().addAll(
                zoomInItem,
                zoomOutItem,
                resetZoomItem,
                new SeparatorMenuItem(),
                previewAsUserItem,
                new SeparatorMenuItem(),
                connectPhoneItem,
                remotePilotItem,
                new SeparatorMenuItem(),
                themeMenu
        );

        return viewMenu;
    }

    /** Sets the action for View ▸ Preview as user (opens the project in the Runner window for this session). */
    public void setOnPreviewAsUser(Runnable callback) {
        this.onPreviewAsUser = callback;
    }

    /** Sets the action for View ▸ Enable Remote Pilot (starts the remote BotPilot server over Tailscale). */
    public void setOnEnableRemotePilot(Runnable callback) {
        this.onEnableRemotePilot = callback;
    }


    public void setEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.subscribe(CoreApplicationEvents.HistoryStateChangedEvent.class, this::updateMenuState, true);
    }

    /**
     * Enables ↶/↷ and names what each would take back — "Undo the change to loadTargets, in 4 files".
     *
     * <p>The count is the part worth showing: a signature change rewrites the files that call it, and an undo
     * that quietly puts four files back is alarming unless the menu said so first.
     */
    private void updateMenuState(CoreApplicationEvents.HistoryStateChangedEvent event) {
        if (undoItem != null) {
            undoItem.setDisable(!event.canUndo());
            undoItem.setText(labelled("Undo", event.canUndo() ? event.undoLabel() : ""));
        }
        if (redoItem != null) {
            redoItem.setDisable(!event.canRedo());
            redoItem.setText(labelled("Redo", event.canRedo() ? event.redoLabel() : ""));
        }
    }

    private static String labelled(String verb, String what) {
        return what == null || what.isBlank() ? verb : verb + " " + what;
    }

    /**
     * Creates the Help menu
     */
    private Menu createHelpMenu() {
        Menu helpMenu = new Menu("Help");

        MenuItem gettingStartedItem = new MenuItem("Getting Started");
        gettingStartedItem.setOnAction(e -> {
            if (onGettingStarted != null) onGettingStarted.run();
        });

        MenuItem studioRepoItem = new MenuItem("BotMaker Studio on GitHub");
        studioRepoItem.setOnAction(e -> BrowserLauncher.open(STUDIO_REPO_URL));

        MenuItem sdkRepoItem = new MenuItem("BotMaker SDK on GitHub");
        sdkRepoItem.setOnAction(e -> BrowserLauncher.open(SDK_REPO_URL));

        MenuItem checkUpdatesItem = new MenuItem("Check for Updates…");
        checkUpdatesItem.setOnAction(e -> checkForUpdates(false));

        MenuItem reportIssueItem = new MenuItem("Report Issue…");
        reportIssueItem.setOnAction(e -> new ReportIssueDialog(primaryStage).show());

        // Where the edit guard records every refused edit — the directory a user is asked to attach to a
        // report, because the refusal itself is diagnosed from those entries and not from the screenshot.
        MenuItem diagnosticsItem = new MenuItem("Open Diagnostics Folder");
        diagnosticsItem.setOnAction(e ->
                BrowserLauncher.open(RefusalJournal.inCacheDir().openableDirectory().toUri().toString()));

        MenuItem aboutItem = new MenuItem("About BotMaker");
        aboutItem.setOnAction(e -> showAboutDialog());

        helpMenu.getItems().addAll(gettingStartedItem, new SeparatorMenuItem(),
                studioRepoItem, sdkRepoItem, new SeparatorMenuItem(),
                reportIssueItem, diagnosticsItem, checkUpdatesItem, aboutItem);

        // Every value editor on one screen, with what each reads back. A developer's instrument, not a
        // feature: gated on the same switch that decides whether ~/.m2 snapshots are offered, so a packaged
        // build never grows a menu entry for it.
        if (AppVersion.isDevBuild()) {
            MenuItem pickerGalleryItem = new MenuItem("Picker Gallery (dev)…");
            pickerGalleryItem.setOnAction(e -> { if (onPickerGallery != null) onPickerGallery.run(); });
            helpMenu.getItems().addAll(new SeparatorMenuItem(), pickerGalleryItem);
        }

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
        dialog.setScene(ThemedWindows.scene(box));
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

    /** Sets the callback for when "Manage Plugins..." is clicked — the registry browser. */
    public void setOnManagePlugins(Runnable callback) {
        this.onManagePlugins = callback;
    }

    /**
     * Sets the callback for when "Upgrade SDK..." is clicked
     */
    public void setOnUpgradeSdk(Runnable callback) {
        this.onUpgradeSdk = callback;
    }

    /** Sets the callback for when "Modernise..." is clicked — the same report with no version change. */
    public void setOnModernise(Runnable callback) {
        this.onModernise = callback;
    }

    /** Sets the callback for when "Review Changes" is clicked — raises the Review tab. */
    public void setOnReviewChanges(Runnable callback) {
        this.onReviewChanges = callback;
    }

    /**
     * Sets the callback for when "Manage Imports..." is clicked
     */
    public void setOnManageImports(Runnable callback) {
        this.onManageImports = callback;
    }

    /** Sets the callback for when "Recover Project Files..." is clicked. */
    public void setOnRecoverProjectFiles(Runnable callback) {
        this.onRecoverProjectFiles = callback;
    }

    /** Sets the callback for when "Activity Flow..." is clicked. */
    public void setOnActivityFlow(Runnable callback) {
        this.onActivityFlow = callback;
    }

    /** Sets the callback for when "Parameters..." is clicked. */
    public void setOnParameters(Runnable callback) {
        this.onParameters = callback;
    }

    /**
     * Sets the callback for Help ▸ Picker Gallery. Harmless in a packaged build, where the item was never
     * created and nothing can call this back.
     */
    public void setOnPickerGallery(Runnable callback) {
        this.onPickerGallery = callback;
    }

    /** Sets the callback for when "Resource Manager..." is clicked. */
    public void setOnManageResources(Runnable callback) {
        this.onManageResources = callback;
    }

    /** Sets the callback for when "Project Settings..." is clicked. */
    public void setOnProjectSettings(Runnable callback) {
        this.onProjectSettings = callback;
    }

    /** Sets the callback for when Project ▸ "Project Setup..." is clicked. */
    public void setOnProjectSetup(Runnable callback) {
        this.onProjectSetup = callback;
    }

    /** Sets the callback for when Help ▸ "Getting Started" is clicked. */
    public void setOnGettingStarted(Runnable callback) {
        this.onGettingStarted = callback;
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
