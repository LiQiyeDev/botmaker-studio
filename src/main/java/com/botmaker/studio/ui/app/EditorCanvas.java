package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.SdkSurfaceService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.dnd.BlockEvent;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The centre column: the scrolling block canvas, and — for an installed bot opened read-only — the Reader-mode
 * banner above it.
 *
 * <p>It re-renders the whole program on every {@code UIBlocksUpdatedEvent}, which is what makes the scroll
 * position its problem: the swap empties the {@link ScrollPane}, so the viewport's position is lost unless
 * something puts it back. See {@link #handleBlocksUpdate}.
 */
final class EditorCanvas {

    private final CodeEditorService codeEditorService;
    private final EventBus eventBus;

    private final VBox blocksContainer;
    private final ScrollPane scrollPane;
    private final VBox column;

    /**
     * @param readerMode        renders without controls, under a banner offering the switch to Editor mode
     * @param projectName       named in that banner
     * @param onSwitchToEditor  the banner's button
     * @param sdkSurface        this project's SDK surface; a version below the floor adds a second banner
     * @param onUpgradeSdk      that banner's button — Manage Libraries, where the version is actually changed
     */
    EditorCanvas(CodeEditorService codeEditorService, EventBus eventBus,
                 boolean readerMode, String projectName, Runnable onSwitchToEditor,
                 SdkSurfaceService sdkSurface, Runnable onUpgradeSdk) {
        this.codeEditorService = codeEditorService;
        this.eventBus = eventBus;

        this.blocksContainer = new VBox(10);
        blocksContainer.getStyleClass().add("blocks-canvas");
        blocksContainer.setPadding(new Insets(20));

        // Accept block drags over the whole canvas so the OS "forbidden" cursor doesn't flash over gaps/padding.
        // Real drop zones (separators / block hitboxes) sit on top and consume the event; this only fires over
        // bare canvas, where a release is simply a no-op (no onDragDropped here).
        blocksContainer.setOnDragOver(e -> {
            var db = e.getDragboard();
            if (db.hasContent(BlockDragAndDropManager.ADDABLE_BLOCK_FORMAT)
                    || db.hasContent(BlockDragAndDropManager.EXISTING_BLOCK_FORMAT)) {
                e.acceptTransferModes(TransferMode.COPY, TransferMode.MOVE);
            }
        });

        this.scrollPane = new ScrollPane(blocksContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.getStyleClass().add("code-scroll-pane");

        // Reader mode: a full-colour, control-free view of someone else's bot. A single banner carries the
        // state; the blocks themselves render without any controls (LockResolver suppresses interaction).
        this.column = new VBox(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        if (readerMode) {
            blocksContainer.getStyleClass().add("reader-mode");
            column.getChildren().addFirst(readerBanner(projectName, onSwitchToEditor));
        }
        // Below the floor: say so once, above everything, and let the project open anyway. Added last so it
        // lands above the Reader banner when a project is both — the SDK is the more actionable of the two.
        if (sdkSurface != null && sdkSurface.isBelowMinimum()) {
            column.getChildren().addFirst(sdkFloorBanner(sdkSurface.sdkVersion(), onUpgradeSdk));
        }
    }

    VBox node() {
        return column;
    }

    /**
     * The state the canvas is in between the window appearing and the first file being parsed.
     *
     * <p>Project open shows the shell before it has any blocks to put in it — parsing the entry point with
     * bindings is the slow step and it runs a pulse later, so the frame in between would otherwise be an empty
     * white canvas indistinguishable from a broken open. There is no matching {@code hideLoading}: the first
     * {@link #handleBlocksUpdate} clears the container, which removes this along with everything else.
     */
    void showLoading() {
        Label loading = new Label("Loading project…");
        loading.getStyleClass().add("canvas-placeholder");
        VBox centred = new VBox(loading);
        centred.setAlignment(Pos.CENTER);
        centred.setPadding(new Insets(60, 0, 0, 0));
        blocksContainer.getChildren().setAll(centred);
    }

    /**
     * Re-renders the program.
     *
     * <p>The vertical position is captured and restored across the swap. Clearing the container collapses the
     * {@code ScrollPane}'s content height to zero, and {@code vvalue} is clamped against that height — so the
     * canvas silently jumped back to the top after every single edit. The restore runs on the next pulse,
     * once the new root node has been laid out and the scrollable range exists again.
     */
    void handleBlocksUpdate(CoreApplicationEvents.UIBlocksUpdatedEvent event) {
        double vvalue = scrollPane.getVvalue();
        blocksContainer.getChildren().clear();
        if (event.rootBlock() != null) {
            Node rootNode = event.rootBlock().getUINode(codeEditorService);
            rootNode.addEventHandler(BlockEvent.BreakpointToggleEvent.TOGGLE_BREAKPOINT, e ->
                    eventBus.publish(new CoreApplicationEvents.BreakpointToggledEvent(e.getBlock(), e.isEnabled())));
            blocksContainer.getChildren().add(rootNode);
        }
        Platform.runLater(() -> scrollPane.setVvalue(vvalue));
    }

    /**
     * Brings {@code block} into view when its error is clicked in the Errors panel: highlights it (reusing the
     * debugger's {@link CoreApplicationEvents.BlockHighlightEvent} path) and scrolls the canvas so the block is
     * visible. Runs the scroll on the next pulse so the node's layout bounds are current.
     */
    void scrollToBlock(CodeBlock block) {
        if (block == null) return;
        CodeBlock target = block.getHighlightTarget();
        eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(target));
        Node node = target != null ? target.getUINode() : null;
        if (node == null) return;
        Platform.runLater(() -> {
            Bounds nodeInContent = blocksContainer.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double contentH = blocksContainer.getBoundsInLocal().getHeight();
            double viewportH = scrollPane.getViewportBounds().getHeight();
            if (contentH > viewportH) {
                double vvalue = (nodeInContent.getMinY() - 20) / (contentH - viewportH);
                scrollPane.setVvalue(Math.max(0, Math.min(1, vvalue)));
            }
            node.requestFocus();
        });
    }

    /** The "Reading — switch to Editor to change" banner shown above the canvas for an installed bot. */
    private static HBox readerBanner(String projectName, Runnable onSwitchToEditor) {
        Label msg = new Label("Reading “" + projectName
                + "”. Switch to Editor mode to make it yours and start changing it.");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button toEditor = new Button("Switch to Editor mode");
        toEditor.setOnAction(e -> onSwitchToEditor.run());
        HBox banner = new HBox(10, msg, spacer, toEditor);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("reader-banner");
        return banner;
    }

    /**
     * The "this bot pins an SDK older than Studio supports" banner.
     *
     * <p>Deliberately <em>not</em> a modal, a refusal, or a red error. The bot compiles and runs exactly as it
     * did yesterday; what it loses is that Studio's palette is a superset of what its jar has, so some blocks
     * it offers would not compile. That is worth one line above the canvas and nothing more — a Studio that
     * won't open an old project is strictly worse than one that mentions it.
     */
    private static HBox sdkFloorBanner(String version, Runnable onUpgradeSdk) {
        Label msg = new Label("This bot uses SDK " + version + ", older than the "
                + MavenService.MIN_SDK_VERSION + " this Studio is built for. It still runs — but some blocks"
                + " in the palette may not compile against it.");
        msg.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button upgrade = new Button("Upgrade SDK…");
        upgrade.setOnAction(e -> onUpgradeSdk.run());
        HBox banner = new HBox(10, msg, spacer, upgrade);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("sdk-floor-banner");
        return banner;
    }
}
