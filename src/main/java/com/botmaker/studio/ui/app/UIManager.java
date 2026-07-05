package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.dnd.BlockEvent;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GitHubAuth;
import com.botmaker.studio.sharing.GitHubClient;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.validation.DiagnosticsManager;
import com.botmaker.studio.validation.ErrorTranslator;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class UIManager {

    private final EventBus eventBus;
    private final CodeEditorService codeEditorService;
    private final DiagnosticsManager diagnosticsManager;
    private final Stage primaryStage;
    private final ProjectConfig config;
    private final ScreenCaptureService screenCaptureService = new ScreenCaptureService();

    private final ToolbarManager toolbarManager;
    private final EventLogManager eventLogManager;
    private final MenuBarManager menuBarManager;
    private final FileExplorerManager fileExplorerManager;

    private VBox blocksContainer;
    private Label statusLabel;
    private TextArea outputArea;
    private ListView<Diagnostic> errorListView;
    private TabPane bottomTabPane;
    private Consumer<Void> onSelectProject;

    // --- NEW: Filter State ---
    private List<Diagnostic> allDiagnostics = new ArrayList<>();
    private ToggleButton errorFilterBtn;
    private ToggleButton warningFilterBtn;
    private ToggleButton infoFilterBtn;

    public UIManager(BlockDragAndDropManager dragAndDropManager,
                     EventBus eventBus,
                     CodeEditorService codeEditorService,
                     DiagnosticsManager diagnosticsManager,
                     Stage primaryStage,
                     ProjectConfig config,
                     ProjectState state, ProjectAnalyzer projectAnalyzer,
                     LibraryService libraryService,
                     ActivityService activityService) {
        this.eventBus = eventBus;
        this.codeEditorService = codeEditorService;
        this.diagnosticsManager = diagnosticsManager;
        this.primaryStage = primaryStage;
        this.config = config;

        this.toolbarManager = new ToolbarManager(eventBus);
        this.eventLogManager = new EventLogManager(eventBus);
        this.menuBarManager = new MenuBarManager(primaryStage);
        this.menuBarManager.setEventBus(eventBus);
        MavenCentralSearch mavenCentralSearch = new MavenCentralSearch();
        JitPackSearch jitPackSearch = new JitPackSearch();
        this.menuBarManager.setOnManageLibraries(() ->
                new ManageLibrariesDialog(primaryStage, libraryService, mavenCentralSearch, jitPackSearch).show());
        this.menuBarManager.setOnManageImports(() ->
                new ManageImportsDialog(primaryStage, codeEditorService).show());
        this.menuBarManager.setOnManageActivities(() ->
                new ManageActivitiesDialog(primaryStage, activityService).show());
        this.menuBarManager.setOnSetActivityValues(() ->
                new SetActivityValuesDialog(primaryStage, activityService).show());
        this.menuBarManager.setOnManageResources(this::openResourceManager);
        GitHubClient gitHubClient = new GitHubClient();
        GitHubGallery gallery = new GitHubGallery(gitHubClient);
        BotInstaller botInstaller = new BotInstaller(gitHubClient, gallery);
        this.menuBarManager.setOnBrowseGallery(() ->
                new GalleryDialog(primaryStage, gallery, botInstaller).show());
        GitHubAuth gitHubAuth = new GitHubAuth();
        BotPublisher botPublisher = new BotPublisher(gitHubClient, gitHubAuth);
        this.menuBarManager.setOnPublishGallery(() ->
                new PublishDialog(primaryStage, gitHubAuth, gitHubClient, gallery, botPublisher,
                        config.projectName(), config.projectPath()).show());
        this.menuBarManager.setProjectRepoUrl(BotSource.read(config.projectPath())
                .map(s -> "https://github.com/" + s.slug()).orElse(null));
        this.fileExplorerManager = new FileExplorerManager(config, codeEditorService, state);

        setupEventHandlers();
    }

    /** Opens the Resource Manager dialog. Reused by the Project menu and the block image-picker shortcut. */
    private void openResourceManager() {
        new ResourceManagerDialog(primaryStage, config, eventBus, screenCaptureService).show();
    }

    private void setupEventHandlers() {
        eventBus.subscribe(CoreApplicationEvents.OpenResourceManagerEvent.class,
                e -> openResourceManager(), true);
        eventBus.subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, this::handleBlocksUpdate, true);
        eventBus.subscribe(CoreApplicationEvents.OutputAppendedEvent.class, event -> {
            if (outputArea.getText().length() > 10_000) {
                String current = outputArea.getText();
                outputArea.setText("[...Trimmed...]\n" + current.substring(current.length() - 5000) + event.text());
                outputArea.positionCaret(outputArea.getLength());
            } else {
                outputArea.appendText(event.text());
            }
        }, true);
        eventBus.subscribe(CoreApplicationEvents.OutputClearedEvent.class, event -> outputArea.clear(), true);
        eventBus.subscribe(CoreApplicationEvents.StatusMessageEvent.class, event -> statusLabel.setText(event.message()), true);
        eventBus.subscribe(CoreApplicationEvents.DiagnosticsUpdatedEvent.class, event -> {
            diagnosticsManager.processDiagnostics(event.diagnostics());
            updateErrors(diagnosticsManager.getDiagnostics());
            statusLabel.setText(diagnosticsManager.getErrorSummary());
        }, true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> selectBottomTab(0), true);
        eventBus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class, e -> selectBottomTab(0), true);
        eventBus.subscribe(CoreApplicationEvents.InputRequestedEvent.class, this::promptForInput, true);
    }

    /** Shows a modal prompt when the running bot blocks on stdin, then sends the entered line to the program. */
    private void promptForInput(CoreApplicationEvents.InputRequestedEvent event) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(primaryStage);
        dialog.setTitle("Bot needs input");
        dialog.setHeaderText("The bot is waiting for input");
        dialog.setContentText(switch (event.type()) {
            case "int" -> "Enter a whole number:";
            case "double" -> "Enter a decimal number:";
            case "boolean" -> "Enter true or false:";
            case "line" -> "Enter some text:";
            default -> "Enter input:";
        });
        dialog.showAndWait().ifPresent(value ->
                eventBus.publish(new CoreApplicationEvents.SendInputEvent(value)));
    }

    private void handleBlocksUpdate(CoreApplicationEvents.UIBlocksUpdatedEvent event) {
        blocksContainer.getChildren().clear();
        if (event.rootBlock() != null) {
            Node rootNode = event.rootBlock().getUINode(codeEditorService);
            rootNode.addEventHandler(BlockEvent.BreakpointToggleEvent.TOGGLE_BREAKPOINT, e ->
                    eventBus.publish(new CoreApplicationEvents.BreakpointToggledEvent(e.getBlock(), e.isEnabled())));
            blocksContainer.getChildren().add(rootNode);
        }
    }

    public Scene createScene() {
        menuBarManager.setOnSelectProject(v -> { if (onSelectProject != null) onSelectProject.accept(null); });

        // --- 1. Top Bar Construction (edit controls left, execution controls right) ---
        HBox editControls = toolbarManager.createEditGroup();
        HBox leftContainer = new HBox(editControls);
        leftContainer.setAlignment(Pos.CENTER_LEFT);

        HBox executionControls = toolbarManager.createExecutionGroup();
        HBox rightContainer = new HBox(executionControls);
        rightContainer.setAlignment(Pos.CENTER_RIGHT);

        BorderPane topBar = new BorderPane();
        topBar.setPadding(new Insets(6));
        topBar.setLeft(leftContainer);
        topBar.setRight(rightContainer);
        topBar.getStyleClass().add("main-toolbar");
        topBar.setMinHeight(50);
        topBar.setPrefHeight(50);
        topBar.setMaxHeight(50);
        topBar.setStyle("-fx-border-color: #dcdcdc; -fx-border-width: 0 0 1 0; -fx-background-color: #f4f4f4;");

        // --- 2. Left Panel: File Explorer ---
        VBox fileExplorer = fileExplorerManager.createView();
        fileExplorer.setMinWidth(150);
        fileExplorer.setMaxWidth(400);

        // --- 3. Center: Code Canvas ---
        blocksContainer = new VBox(10);
        blocksContainer.getStyleClass().add("blocks-canvas");
        blocksContainer.setPadding(new Insets(20));

        // Accept block drags over the whole canvas so the OS "forbidden" cursor doesn't flash over gaps/padding.
        // Real drop zones (separators / block hitboxes) sit on top and consume the event; this only fires over
        // bare canvas, where a release is simply a no-op (no onDragDropped here).
        blocksContainer.setOnDragOver(e -> {
            var db = e.getDragboard();
            if (db.hasContent(BlockDragAndDropManager.ADDABLE_BLOCK_FORMAT)
                    || db.hasContent(BlockDragAndDropManager.EXISTING_BLOCK_FORMAT)) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY, javafx.scene.input.TransferMode.MOVE);
            }
        });

        ScrollPane canvasScroll = new ScrollPane(blocksContainer);
        canvasScroll.setFitToWidth(true);
        canvasScroll.setFitToHeight(true);
        canvasScroll.getStyleClass().add("code-scroll-pane");

        // --- 4. Bottom Panel: Terminal/Errors ---
        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.getStyleClass().add("console-area");
        addContextMenu(outputArea);

        // -- Construct Error Panel with Filters --
        VBox errorPanel = createErrorPanel();

        bottomTabPane = new TabPane();
        Tab terminalTab = new Tab("Terminal", outputArea); terminalTab.setClosable(false);
        Tab errorsTab = new Tab("Errors", errorPanel); errorsTab.setClosable(false);
        Tab eventsTab = new Tab("Event Log", eventLogManager.getView()); eventsTab.setClosable(false);
        bottomTabPane.getTabs().addAll(terminalTab, errorsTab, eventsTab);

        // --- 5. Layout Assembly ---
        SplitPane verticalSplit = new SplitPane();
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.getItems().addAll(canvasScroll, bottomTabPane);
        verticalSplit.setDividerPositions(0.82);

        SplitPane mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.getItems().addAll(fileExplorer, verticalSplit);
        mainSplit.setDividerPositions(0.25);

        statusLabel = new Label("Ready");
        statusLabel.setId("status-label");
        statusLabel.setPadding(new Insets(2, 5, 2, 5));

        VBox root = new VBox(menuBarManager.getMenuBar(), topBar, mainSplit, statusLabel);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);
        root.getStyleClass().add("light-theme");

        primaryStage.setOnHidden(e -> eventLogManager.shutdown());

        Scene scene = new Scene(root, 1000, 700);

        // Block "state" styling (highlight / error / breakpoint / read-only) via pseudo-classes.
        var blocksCss = UIManager.class.getResource("/css/blocks.css");
        if (blocksCss != null) {
            scene.getStylesheets().add(blocksCss.toExternalForm());
        }

        // Global Key Handlers
        KeyCombination copyCombo = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        KeyCombination pasteCombo = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof javafx.scene.control.TextInputControl) {
                return;
            }
            if (copyCombo.match(event)) {
                eventBus.publish(new CoreApplicationEvents.CopyRequestedEvent());
                event.consume();
            } else if (pasteCombo.match(event)) {
                eventBus.publish(new CoreApplicationEvents.PasteRequestedEvent());
                event.consume();
            }
        });

        return scene;
    }

    private VBox createErrorPanel() {
        errorListView = new ListView<>();
        configureErrorList(errorListView);
        addContextMenu(errorListView);
        VBox.setVgrow(errorListView, Priority.ALWAYS);

        // --- Filter Buttons ---
        errorFilterBtn = new ToggleButton("Errors");
        errorFilterBtn.setSelected(true);
        errorFilterBtn.setStyle("-fx-text-fill: #E74C3C; -fx-font-weight: bold;");
        errorFilterBtn.setOnAction(e -> applyErrorFilters());

        warningFilterBtn = new ToggleButton("Warnings");
        warningFilterBtn.setSelected(true);
        warningFilterBtn.setStyle("-fx-text-fill: #F39C12; -fx-font-weight: bold;");
        warningFilterBtn.setOnAction(e -> applyErrorFilters());

        infoFilterBtn = new ToggleButton("Infos/Hints");
        infoFilterBtn.setSelected(true);
        infoFilterBtn.setStyle("-fx-text-fill: #3498DB; -fx-font-weight: bold;");
        infoFilterBtn.setOnAction(e -> applyErrorFilters());

        HBox filterBar = new HBox(10, new Label("Filter: "), errorFilterBtn, warningFilterBtn, infoFilterBtn);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(5));
        filterBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");

        return new VBox(filterBar, errorListView);
    }

    private void applyErrorFilters() {
        if (allDiagnostics == null) return;

        List<Diagnostic> filtered = allDiagnostics.stream()
                .filter(d -> {
                    DiagnosticSeverity severity = d.getSeverity();
                    if (severity == DiagnosticSeverity.Error) return errorFilterBtn.isSelected();
                    if (severity == DiagnosticSeverity.Warning) return warningFilterBtn.isSelected();
                    if (severity == DiagnosticSeverity.Information || severity == DiagnosticSeverity.Hint) return infoFilterBtn.isSelected();
                    return true;
                })
                .collect(Collectors.toList());

        errorListView.getItems().setAll(filtered);
    }

    private void configureErrorList(ListView<Diagnostic> lv) {
        lv.setPlaceholder(new Label("No issues found."));
        lv.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Diagnostic diagnostic, boolean empty) {
                super.updateItem(diagnostic, empty);

                if (empty || diagnostic == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    setOnMouseClicked(null);
                } else {
                    String message = ErrorTranslator.getShortSummary(diagnostic);
                    int line = diagnostic.getRange().getStart().getLine() + 1;

                    // --- NEW: Extract Filename from Data Field ---
                    String filename = "";
                    if (diagnostic.getData() instanceof String) {
                        String uri = (String) diagnostic.getData();
                        try {
                            // Try to parse as URI to get clean filename (e.g. Main.java)
                            java.nio.file.Path p = java.nio.file.Path.of(new java.net.URI(uri));
                            filename = "[" + p.getFileName().toString() + "] ";
                        } catch (Exception e) {
                            // Fallback for non-standard URIs
                            if (uri.contains("/")) {
                                filename = "[" + uri.substring(uri.lastIndexOf('/') + 1) + "] ";
                            } else {
                                filename = "[" + uri + "] ";
                            }
                        }
                    }
                    // ---------------------------------------------

                    String icon = "";
                    String colorStyle = "";
                    String iconColorStyle = "";

                    if (diagnostic.getSeverity() == DiagnosticSeverity.Error) {
                        icon = "❌";
                        colorStyle = "-fx-text-fill: #C0392B;";
                        iconColorStyle = "-fx-text-fill: #E74C3C;";
                    } else if (diagnostic.getSeverity() == DiagnosticSeverity.Warning) {
                        icon = "⚠️";
                        colorStyle = "-fx-text-fill: #D35400;";
                        iconColorStyle = "-fx-text-fill: #F39C12;";
                    } else {
                        icon = "ℹ️";
                        colorStyle = "-fx-text-fill: #2980B9;";
                        iconColorStyle = "-fx-text-fill: #3498DB;";
                    }

                    Label iconLabel = new Label(icon);
                    iconLabel.setStyle(iconColorStyle + "-fx-font-size: 14px; -fx-padding: 0 8 0 0;");

                    // Add filename to the text
                    setText(String.format("%sLine %d: %s", filename, line, message));
                    setStyle(colorStyle + "-fx-font-family: 'Segoe UI', sans-serif; -fx-font-weight: normal;");
                    setGraphic(iconLabel);

                    setOnMouseClicked(event -> {
                        if (event.getClickCount() >= 1) {
                            diagnosticsManager.findBlockForDiagnostic(diagnostic).ifPresent(block -> {
                                Node uiNode = block.getUINode();
                                if (uiNode != null) uiNode.requestFocus();
                            });
                        }
                    });
                }
            }
        });
    }

    private void addContextMenu(Control control) {
        ContextMenu cm = new ContextMenu();
        if (control instanceof TextArea) {
            TextArea ta = (TextArea) control;
            MenuItem copy = new MenuItem("Copy");
            copy.setOnAction(e -> ta.copy());
            MenuItem clear = new MenuItem("Clear");
            clear.setOnAction(e -> ta.clear());
            cm.getItems().addAll(copy, new SeparatorMenuItem(), clear);
            ta.setContextMenu(cm);
        } else if (control instanceof ListView) {
            ListView<?> lv = (ListView<?>) control;
            MenuItem copy = new MenuItem("Copy Selection");
            copy.setOnAction(e -> {
                Object selected = lv.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();

                    // MODIFIED: Copy only message if it's a Diagnostic object
                    String textToCopy;
                    if (selected instanceof Diagnostic) {
                        textToCopy = ((Diagnostic) selected).getMessage();
                    } else {
                        textToCopy = selected.toString();
                    }

                    content.putString(textToCopy);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                }
            });
            cm.getItems().add(copy);
            lv.setContextMenu(cm);
        }
    }

    private void updateErrors(List<Diagnostic> diagnostics) {
        this.allDiagnostics = (diagnostics != null) ? new ArrayList<>(diagnostics) : new ArrayList<>();
        applyErrorFilters();
        updateFilterButtonCounts();

        boolean hasErrors = allDiagnostics.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
        if (hasErrors) {
            selectBottomTab(1);
        }
    }

    private void updateFilterButtonCounts() {
        long errCount = allDiagnostics.stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Error).count();
        long warnCount = allDiagnostics.stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Warning).count();
        long infoCount = allDiagnostics.stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Information || d.getSeverity() == DiagnosticSeverity.Hint).count();

        errorFilterBtn.setText(String.format("Errors (%d)", errCount));
        warningFilterBtn.setText(String.format("Warnings (%d)", warnCount));
        infoFilterBtn.setText(String.format("Infos (%d)", infoCount));
    }

    private void selectBottomTab(int index) {
        if (bottomTabPane != null && index < bottomTabPane.getTabs().size()) {
            bottomTabPane.getSelectionModel().select(index);
        }
    }

    public void setOnSelectProject(Consumer<Void> callback) { this.onSelectProject = callback; }
}