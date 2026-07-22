package com.botmaker.studio.ui.dnd;

import com.botmaker.studio.ui.render.menu.StatementMenu;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.parser.StatementPlacement;
import com.botmaker.studio.blocks.ClassBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.StyleBuilder;
import javafx.css.PseudoClass;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.function.Consumer;

/**
 * Installs JavaFX drag-and-drop handlers on palette items, existing blocks and drop zones.
 * <p>
 * Drops do not call the editor directly: each drop publishes a {@link CoreApplicationEvents.BlockDropRequestedEvent}
 * or {@link CoreApplicationEvents.BlockMoveRequestedEvent} on the {@link EventBus}. {@code CodeEditorService}
 * subscribes and resolves the drop into an AST edit. This keeps the manager free of any back-reference to the
 * service layer (matching the existing Copy/Paste/Undo event pattern).
 */
public class BlockDragAndDropManager {

    public static final DataFormat ADDABLE_BLOCK_FORMAT = new DataFormat("application/x-java-addable-block");
    public static final DataFormat EXISTING_BLOCK_FORMAT = new DataFormat("application/x-java-existing-block");

    /**
     * The {@link StatementPlacement.Jump} the dragged block is, when it is one. Carried on the dragboard so a
     * drop zone can judge legality during drag-over without resolving a block id back through the service — for
     * an existing-block move the id is all the other format carries, and the AST node it names lives service-side.
     */
    public static final DataFormat JUMP_KIND_FORMAT = new DataFormat("application/x-java-jump-kind");

    // Drag-over feedback is driven by pseudo-classes (styled in blocks.css), not inline -fx-style strings,
    // consistent with the :highlighted / :error / :breakpoint approach in AbstractCodeBlock.
    private static final PseudoClass DRAG_OVER_COPY = PseudoClass.getPseudoClass("drag-over-copy");
    private static final PseudoClass DRAG_OVER_MOVE = PseudoClass.getPseudoClass("drag-over-move");
    /** A drop the target would refuse — e.g. a {@code break} over a body with no enclosing loop or switch. */
    private static final PseudoClass DRAG_OVER_ILLEGAL = PseudoClass.getPseudoClass("drag-over-illegal");

    private final EventBus eventBus;

    public BlockDragAndDropManager(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    // --- Drag sources ---

    public void makeDraggable(Node node, BlockType blockType) {
        node.setOnDragDetected(event -> {
            Dragboard db = node.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.put(ADDABLE_BLOCK_FORMAT, blockType.id());
            putJumpKind(content, StatementPlacement.jumpOf(blockType));
            db.setContent(content);
            node.setOpacity(0.5);
            event.consume();
        });
        node.setOnDragDone(event -> {
            node.setOpacity(1.0);
            event.consume();
        });
    }

    /**
     * Makes any existing block (statement or class member) draggable for reordering. Only the block's stable id
     * is placed on the dragboard; the resolution from id back to a block/source-body happens service-side.
     */
    public void makeBlockMovable(Node node, CodeBlock block) {
        if (block.isReadOnly()) return;

        node.setOnDragDetected(event -> {
            // Allow grabbing the block by its text/labels, but not by inline interactive controls
            // (buttons, text fields, combo boxes…) anywhere up the chain to this drag root.
            if (startsOnInteractiveControl(event.getTarget(), node)) return;
            Dragboard db = node.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.put(EXISTING_BLOCK_FORMAT, block.getId());
            putJumpKind(content, StatementPlacement.jumpOf(block.getAstNode()));
            db.setContent(content);
            node.setOpacity(0.5);
            event.consume();
        });
        node.setOnDragDone(event -> {
            node.setOpacity(1.0);
            event.consume();
        });
    }

    private static boolean startsOnInteractiveControl(EventTarget target, Node dragRoot) {
        if (!(target instanceof Node)) return false;
        for (Node cur = (Node) target; cur != null && cur != dragRoot; cur = cur.getParent()) {
            // Labels are Controls but are non-interactive text — treat them as part of the block surface.
            if (cur instanceof Control && !(cur instanceof Label)) return true;
        }
        return false;
    }

    private static void putJumpKind(ClipboardContent content, StatementPlacement.Jump jump) {
        if (jump != null) content.put(JUMP_KIND_FORMAT, jump.name());
    }

    // --- Drag-over feedback helpers ---

    /**
     * Whether {@code targetBody} would accept whatever is on the dragboard. Only the jump statements can be
     * refused; everything else is unconditionally legal, so this is a cheap check on the common path.
     */
    private static boolean accepts(Dragboard db, BodyBlock targetBody) {
        Object kind = db.getContent(JUMP_KIND_FORMAT);
        if (kind == null) return true;
        StatementPlacement.Jump jump;
        try {
            jump = StatementPlacement.Jump.valueOf((String) kind);
        } catch (IllegalArgumentException unknownKind) {
            return true;
        }
        return StatementPlacement.allows(jump, targetBody.getAstNode());
    }

    private void applyDragOver(Node target, Dragboard db) {
        applyDragOver(target, db, true);
    }

    private void applyDragOver(Node target, Dragboard db, boolean accepted) {
        boolean copy = db.hasContent(ADDABLE_BLOCK_FORMAT);
        target.pseudoClassStateChanged(DRAG_OVER_COPY, accepted && copy);
        target.pseudoClassStateChanged(DRAG_OVER_MOVE, accepted && !copy && db.hasContent(EXISTING_BLOCK_FORMAT));
        target.pseudoClassStateChanged(DRAG_OVER_ILLEGAL, !accepted);
    }

    private void clearDragOver(Node target) {
        target.pseudoClassStateChanged(DRAG_OVER_COPY, false);
        target.pseudoClassStateChanged(DRAG_OVER_MOVE, false);
        target.pseudoClassStateChanged(DRAG_OVER_ILLEGAL, false);
    }

    // --- Smart Separator Implementation (Styled) ---

    public Pane createSeparator() {
        return createSeparator(true);
    }

    /**
     * A slot between two statements. With {@code withInsertButton} false it is a plain gap — no hover, no "+".
     *
     * <p>Read-only bodies pass false. Leaving the button out of the scene is the point: it is hidden until
     * hover, so merely not wiring its action produced a "+" that appeared under the cursor and then did
     * nothing — an invitation to insert into scaffolding that could never be accepted.
     */
    public Pane createSeparator(boolean withInsertButton) {
        // Changed to Pane to allow absolute positioning of the button
        Pane separator = new Pane();
        double height = 12.0;
        separator.setMinHeight(height);
        separator.setMaxHeight(height);
        // No inline -fx-background-color here: it would override the :drag-over-* pseudo-class rules in
        // blocks.css (inline styles beat author stylesheets). A Pane is transparent by default anyway.

        if (!withInsertButton) return separator;

        // 1. The Insert Button (+)
        Button insertBtn = new Button("+");
        insertBtn.setFocusTraversable(false);
        insertBtn.setVisible(false); // Hidden by default
        insertBtn.getStyleClass().add("separator-insert-button");

        // Use Theme Colors
        String primaryColor = BlockTheme.current().colors().primary(); // e.g. #3498DB
        String hoverColor = BlockTheme.current().colors().hover();

        double btnWidth = 40.0;
        double btnHeight = 16.0;

        // Apply base style using StyleBuilder
        StyleBuilder.create()
                .backgroundColor(primaryColor)
                .backgroundRadius(10) // Pill shape
                .textColor("white")
                .fontSize(10)
                .fontWeight("bold")
                .padding(0)
                .cursor("hand")
                .applyTo(insertBtn);

        // Enforce pill shape dimensions
        insertBtn.setMinWidth(btnWidth);
        insertBtn.setMaxWidth(btnWidth);
        insertBtn.setMinHeight(btnHeight);
        insertBtn.setMaxHeight(btnHeight);

        // Center vertically in the Pane
        insertBtn.setLayoutY((height - btnHeight) / 2.0);

        // Add internal hover effect for the button itself
        insertBtn.setOnMouseEntered(e -> {
            insertBtn.setStyle(insertBtn.getStyle().replace(primaryColor, hoverColor));
        });
        insertBtn.setOnMouseExited(e -> {
            insertBtn.setStyle(insertBtn.getStyle().replace(hoverColor, primaryColor));
        });

        // 2. Logic to show button when hovering the separator area
        separator.setOnMouseEntered(e -> {
            if (!e.isPrimaryButtonDown()) { // Don't show if dragging
                insertBtn.setVisible(true);
                insertBtn.toFront(); // Ensure button is on top within the pane

                // Bring the whole separator to the visual front so adjacent blocks don't cover the button
                // We use setViewOrder (negative is closer to camera/top) instead of toFront()
                // because toFront() reorders the VBox children, breaking layout.
                separator.setViewOrder(-100.0);

                // Initial placement on entry
                updateButtonPosition(insertBtn, separator.getWidth(), e.getX());
            }
        });

        separator.setOnMouseMoved(e -> {
            if (insertBtn.isVisible()) {
                updateButtonPosition(insertBtn, separator.getWidth(), e.getX());
            }
        });

        separator.setOnMouseExited(e -> {
            // Restore visual order
            separator.setViewOrder(0.0);

            // Fix: Don't hide if menu is open
            if (insertBtn.getUserData() instanceof ContextMenu) {
                ContextMenu menu = (ContextMenu) insertBtn.getUserData();
                if (menu.isShowing()) return;
            }
            insertBtn.setVisible(false);
        });

        separator.getChildren().add(insertBtn);
        return separator;
    }

    private void updateButtonPosition(Button btn, double containerWidth, double mouseX) {
        double btnWidth = 40.0; // Fixed width from creation
        double newX = mouseX - (btnWidth / 2.0);

        // Clamp to bounds
        if (newX < 0) newX = 0;
        if (newX + btnWidth > containerWidth) newX = containerWidth - btnWidth;

        btn.setLayoutX(newX);
    }

    /** @param targetBody the body the "+" inserts into — blocks illegal there are left out of the menu */
    public void enableSeparatorClick(Pane separator, com.botmaker.studio.suggestions.ProjectAnalyzer analyzer,
                                     org.eclipse.jdt.core.dom.ASTNode targetBody, Consumer<BlockType> onInsert) {
        for (Node child : separator.getChildren()) {
            if (child instanceof Button) {
                Button btn = (Button) child;
                btn.setOnAction(e -> {
                    ContextMenu menu = StatementMenu.create(analyzer, targetBody, onInsert);

                    // Store reference to menu so MouseExited knows not to hide button
                    btn.setUserData(menu);

                    // Clean up on hide
                    menu.setOnHidden(ev -> {
                        btn.setUserData(null);
                        // If mouse isn't over separator anymore, hide button now
                        if (!separator.isHover()) {
                            btn.setVisible(false);
                            separator.setViewOrder(0.0);
                        }
                    });

                    menu.show(btn, javafx.geometry.Side.BOTTOM, 0, 0);
                    e.consume();
                });
                break;
            }
        }
    }

    // --- Drop targets ---

    public void addSeparatorDragHandlers(Pane separator, BodyBlock targetBody, int insertionIndex) {
        if (targetBody.isReadOnly()) return;

        separator.setOnDragEntered(event -> {
            hideInsertButton(separator); // Hide the "+" button while dragging over
            applyDragOver(separator, event.getDragboard(), accepts(event.getDragboard(), targetBody));
            event.consume();
        });

        separator.setOnDragExited(event -> {
            clearDragOver(separator);
            event.consume();
        });

        separator.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (!accepts(db, targetBody)) { event.consume(); return; }
            if (db.hasContent(ADDABLE_BLOCK_FORMAT)) event.acceptTransferModes(TransferMode.COPY);
            else if (db.hasContent(EXISTING_BLOCK_FORMAT)) event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        });

        separator.setOnDragDropped(event -> {
            clearDragOver(separator);
            boolean success = publishBodyDrop(event.getDragboard(), targetBody, insertionIndex, null);
            event.setDropCompleted(success);
            event.consume();
        });
    }

    /**
     * Makes a whole statement node a drop target: the top half inserts above it (index {@code blockIndex}), the
     * bottom half below it (index {@code blockIndex + 1}). The corresponding separator ({@code sepAbove} /
     * {@code sepBelow}) is lit as the single insertion indicator. The thin separators keep their own handlers as a
     * precise fallback (and for placing directly around container blocks).
     */
    public void addBlockDropHitbox(Node blockNode, com.botmaker.studio.core.StatementBlock block, BodyBlock targetBody,
                                   int blockIndex, Pane sepAbove, Pane sepBelow) {
        if (targetBody.isReadOnly()) return;

        blockNode.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            boolean legal = accepts(db, targetBody);
            boolean known = db.hasContent(ADDABLE_BLOCK_FORMAT) || db.hasContent(EXISTING_BLOCK_FORMAT);
            if (legal && db.hasContent(ADDABLE_BLOCK_FORMAT)) event.acceptTransferModes(TransferMode.COPY);
            else if (legal && db.hasContent(EXISTING_BLOCK_FORMAT)) event.acceptTransferModes(TransferMode.MOVE);

            clearDragOver(sepAbove);
            clearDragOver(sepBelow);
            if (known) applyDragOver(isTopHalf(event, blockNode) ? sepAbove : sepBelow, db, legal);
            event.consume();
        });

        blockNode.setOnDragExited(event -> {
            clearDragOver(sepAbove);
            clearDragOver(sepBelow);
            event.consume();
        });

        blockNode.setOnDragDropped(event -> {
            clearDragOver(sepAbove);
            clearDragOver(sepBelow);
            int index = isTopHalf(event, blockNode) ? blockIndex : blockIndex + 1;
            boolean success = publishBodyDrop(event.getDragboard(), targetBody, index, block.getId());
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private static boolean isTopHalf(javafx.scene.input.DragEvent event, Node node) {
        return event.getY() < node.getBoundsInLocal().getHeight() / 2.0;
    }

    /**
     * Publishes the add/move event for a drop into a body. {@code selfId}, when non-null, is the id of the block
     * under the cursor — a move of that same block onto its own slot is skipped (no AST churn / undo entry).
     */
    private boolean publishBodyDrop(Dragboard db, BodyBlock targetBody, int insertionIndex, String selfId) {
        if (db.hasContent(ADDABLE_BLOCK_FORMAT)) {
            BlockType type = blockTypeFrom(db);
            if (type == null) return false;
            eventBus.publish(new CoreApplicationEvents.BlockDropRequestedEvent(
                    new DropInfo(type, targetBody, insertionIndex)));
            return true;
        } else if (db.hasContent(EXISTING_BLOCK_FORMAT)) {
            String blockId = (String) db.getContent(EXISTING_BLOCK_FORMAT);
            if (blockId.equals(selfId)) return false; // dropped onto itself
            eventBus.publish(new CoreApplicationEvents.BlockMoveRequestedEvent(
                    new MoveBlockInfo(blockId, targetBody, insertionIndex)));
            return true;
        }
        return false;
    }

    private void hideInsertButton(Pane separator) {
        for (Node child : separator.getChildren()) {
            if (child instanceof Button) {
                child.setVisible(false);
            }
        }
    }

    public void addClassMemberDropHandlers(Region separator, ClassBlock targetClass, int insertionIndex) {
        if (targetClass.isReadOnly()) return;

        separator.setOnDragEntered(event -> {
            if (acceptsClassMember(event.getDragboard())) applyDragOver(separator, event.getDragboard());
            event.consume();
        });

        separator.setOnDragExited(event -> {
            clearDragOver(separator);
            event.consume();
        });

        separator.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasContent(ADDABLE_BLOCK_FORMAT) && isClassMemberType(db)) event.acceptTransferModes(TransferMode.COPY);
            else if (db.hasContent(EXISTING_BLOCK_FORMAT)) event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        });

        separator.setOnDragDropped(event -> {
            clearDragOver(separator);
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasContent(ADDABLE_BLOCK_FORMAT)) {
                BlockType type = blockTypeFrom(db);
                if (type != null && type.isClassMember()) {
                    eventBus.publish(new CoreApplicationEvents.BlockDropRequestedEvent(
                            new DropInfo(type, null, insertionIndex, targetClass)));
                    success = true;
                }
            } else if (db.hasContent(EXISTING_BLOCK_FORMAT)) {
                String blockId = (String) db.getContent(EXISTING_BLOCK_FORMAT);
                eventBus.publish(new CoreApplicationEvents.BlockMoveRequestedEvent(
                        new MoveBlockInfo(blockId, null, targetClass, insertionIndex)));
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private static boolean acceptsClassMember(Dragboard db) {
        return (db.hasContent(ADDABLE_BLOCK_FORMAT) && isClassMemberType(db)) || db.hasContent(EXISTING_BLOCK_FORMAT);
    }

    private static boolean isClassMemberType(Dragboard db) {
        BlockType type = blockTypeFrom(db);
        return type != null && type.isClassMember();
    }

    /** Resolves the {@link BlockType} carried by a palette drag, or {@code null} if absent/unknown. */
    private static BlockType blockTypeFrom(Dragboard db) {
        Object id = db.getContent(ADDABLE_BLOCK_FORMAT);
        return id == null ? null : BlockCatalog.byId((String) id).orElse(null);
    }

    public void addEmptyBodyDropHandlers(Region target, BodyBlock targetBody) {
        if (targetBody.isReadOnly()) return;

        target.setOnDragEntered(e -> {
            if (e.getDragboard().hasContent(ADDABLE_BLOCK_FORMAT) || e.getDragboard().hasContent(EXISTING_BLOCK_FORMAT))
                applyDragOver(target, e.getDragboard(), accepts(e.getDragboard(), targetBody));
            e.consume();
        });

        target.setOnDragExited(e -> {
            clearDragOver(target);
            e.consume();
        });

        target.setOnDragOver(e -> {
            if ((e.getDragboard().hasContent(ADDABLE_BLOCK_FORMAT) || e.getDragboard().hasContent(EXISTING_BLOCK_FORMAT))
                    && accepts(e.getDragboard(), targetBody))
                e.acceptTransferModes(TransferMode.ANY);
            e.consume();
        });

        target.setOnDragDropped(event -> {
            clearDragOver(target);
            boolean success = publishBodyDrop(event.getDragboard(), targetBody, 0, null);
            event.setDropCompleted(success);
            event.consume();
        });
    }

    public void addExpressionDropHandlers(Region target) {
        String defaultStyle = "-fx-background-color: #f0f0f0; -fx-border-color: #c0c0c0; -fx-border-style: dashed; -fx-min-width: 50; -fx-min-height: 25;";
        String hoverStyle = defaultStyle + "-fx-border-color: #007bff;";
        target.setStyle(defaultStyle);
        target.setOnDragEntered(event -> {
            if (event.getDragboard().hasContent(ADDABLE_BLOCK_FORMAT)) target.setStyle(hoverStyle);
            event.consume();
        });
        target.setOnDragExited(event -> {
            target.setStyle(defaultStyle);
            event.consume();
        });
        target.setOnDragOver(event -> {
            if (event.getDragboard().hasContent(ADDABLE_BLOCK_FORMAT)) event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        target.setOnDragDropped(event -> {
            boolean success = event.getDragboard().hasContent(ADDABLE_BLOCK_FORMAT);
            event.setDropCompleted(success);
            event.consume();
        });
    }
}
