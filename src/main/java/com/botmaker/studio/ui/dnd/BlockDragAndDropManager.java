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
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.types.TypeExpectation;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.StyleBuilder;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import javafx.css.PseudoClass;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.function.Consumer;
import java.util.function.Function;

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

    /**
     * The qualified type of the value a dragged statement <em>evaluates to</em>, when it is an expression
     * statement — so an expression slot can refuse a drop during drag-over rather than after it.
     *
     * <p>Carried on the dragboard for the same reason {@link #JUMP_KIND_FORMAT} is: drag-over has only the
     * block id, and the binding that answers this lives on the AST node the id names, service-side. Absent
     * whenever the type doesn't resolve — and absent is read as unknown, which
     * {@link com.botmaker.studio.types.TypeExpectation#fits} accepts, so an unresolved file keeps working.
     */
    public static final DataFormat EXPRESSION_TYPE_FORMAT = new DataFormat("application/x-java-expression-type");

    // Drag-over feedback is driven by pseudo-classes (styled in blocks.css), not inline -fx-style strings,
    // consistent with the :highlighted / :error / :breakpoint approach in AbstractCodeBlock.
    private static final PseudoClass DRAG_OVER_COPY = PseudoClass.getPseudoClass("drag-over-copy");
    private static final PseudoClass DRAG_OVER_MOVE = PseudoClass.getPseudoClass("drag-over-move");
    /** A drop the target would refuse — e.g. a {@code break} over a body with no enclosing loop or switch. */
    private static final PseudoClass DRAG_OVER_ILLEGAL = PseudoClass.getPseudoClass("drag-over-illegal");

    private final EventBus eventBus;

    /**
     * What a dragged call gives back, when the AST carries no binding to say. Set by {@code BotProject} from
     * the {@code ProjectAnalyzer}; a function rather than the analyzer itself, so this class keeps its one
     * structural property — it knows the event bus and nothing else about the service layer.
     */
    private Function<ExpressionStatement, ResolvedType> returnTypeResolver;

    public BlockDragAndDropManager(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** @see #returnTypeResolver */
    public void setReturnTypeResolver(Function<ExpressionStatement, ResolvedType> returnTypeResolver) {
        this.returnTypeResolver = returnTypeResolver;
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
            putExpressionType(content, block.getAstNode());
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

    /**
     * Advertises the value an expression statement produces, so it can be dropped into a slot expecting that
     * type. Only {@code ExpressionStatement} qualifies: it is the one statement shape that <em>is</em> an
     * expression wearing a semicolon, so moving it into a slot loses nothing.
     */
    private void putExpressionType(ClipboardContent content, ASTNode node) {
        String type = expressionTypeName(node);
        if (type == null) return;
        content.put(EXPRESSION_TYPE_FORMAT, resolvedName(node, type));
    }

    /**
     * {@code bound}, unless it is the unknown placeholder and the resolver can do better. The editor parses
     * without bindings for most of a session, so "unknown" is the usual answer and it is the one that let a
     * {@code void} call into an {@code if} condition — the resolver reaches the same index the menus use.
     */
    private String resolvedName(ASTNode node, String bound) {
        if (returnTypeResolver == null || !ResolvedType.UNKNOWN.qualifiedName().equals(bound)) return bound;
        if (!(node instanceof ExpressionStatement stmt)) return bound;
        ResolvedType resolved = returnTypeResolver.apply(stmt);
        return resolved == null || resolved.isUnknown() ? bound : resolved.qualifiedName();
    }

    /**
     * The qualified type name to advertise for {@code node}, or null when it isn't an expression statement at
     * all — the format's presence is what marks a drag as slot-fillable, so the two answers are one decision.
     *
     * <p>An unresolved binding reports {@link ResolvedType#UNKNOWN}'s name rather than nothing. It used to
     * report nothing, and since the editor parses without bindings for most of a session, that meant the
     * format was almost never on the dragboard — and the slot requires it, so dragging an
     * {@code ImageClicker.click(…)} into an {@code if} condition was refused every time. Unknown is what
     * {@link TypeExpectation#fits} already accepts everywhere else; saying it out loud is the fix.
     */
    static String expressionTypeName(ASTNode node) {
        if (!(node instanceof ExpressionStatement stmt)) return null;
        ITypeBinding binding = stmt.getExpression().resolveTypeBinding();
        return binding == null ? ResolvedType.UNKNOWN.qualifiedName() : ResolvedType.of(binding).qualifiedName();
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


    /**
     * The empty {@code ⟨expression⟩} placeholder — a real drop target, named by the statement around it rather
     * than by an expression it does not have.
     *
     * <p>It used to take no drops at all, on the grounds that every fill path rewrites an <em>existing</em>
     * expression node. The result was the one slot in the editor that visibly asks for a value being the one
     * that refused every value: a drag over it lit nothing up, accepted nothing, and said nothing about why.
     * A dashed rectangle that means "drop here" has to accept a drop.
     *
     * <p>It keeps {@code expression-drop-zone} for the dashed empty look and gains
     * {@code expression-drop-target} for the drag-over outline, so an empty slot answers a drag exactly the way
     * a filled one does — green when the value fits, red when it doesn't.
     *
     * @param owner the block whose slot this is; its id is what the drop names
     */
    public void markEmptyExpressionSlot(Region target, CodeBlock owner) {
        target.getStyleClass().addAll("expression-drop-zone", "expression-drop-target");
        target.setMinWidth(50);
        target.setMinHeight(25);
        if (owner == null) return;

        // UNKNOWN, not a declared type: an empty slot's own type is whatever the statement around it declares,
        // and the only rule that matters here — a void call is not a value — needs no slot type.
        installSlotFilters(target, ResolvedType.UNKNOWN, db -> publishEmptySlotDrop(db, owner));
    }

    private boolean publishEmptySlotDrop(Dragboard db, CodeBlock owner) {
        if (owner == null || !acceptsExpression(db, ResolvedType.UNKNOWN)) return false;
        BlockType palette = db.hasContent(ADDABLE_BLOCK_FORMAT) ? blockTypeFrom(db) : null;
        String sourceId = db.hasContent(EXISTING_BLOCK_FORMAT) ? (String) db.getContent(EXISTING_BLOCK_FORMAT) : null;
        if (palette == null && sourceId == null) return false;
        eventBus.publish(new CoreApplicationEvents.ExpressionDropRequestedEvent(
                ExpressionDropInfo.intoEmptySlot(owner.getId(), palette, sourceId)));
        return true;
    }

    /**
     * Makes a filled expression slot accept a drop, replacing the expression it holds. This is the "drag a
     * {@code Window.title()} call into a print" path.
     *
     * <p>Both dragboard formats are accepted, each narrowed to what can legally become an expression:
     * <ul>
     *   <li>a palette {@link BlockType.LibraryCall} — an SDK facade call, whose statement form is only the
     *       invocation plus a semicolon;</li>
     *   <li>an existing statement that carries an {@link #EXPRESSION_TYPE_FORMAT}, i.e. one already parsed as an
     *       expression statement — dropping it <em>moves</em> its expression into the slot.</li>
     * </ul>
     * Anything else (an {@code if}, a loop, a declaration) is refused during drag-over — but visibly, with a
     * red outline and a tooltip naming the reason, rather than by the cursor alone.
     *
     * @param slotType what the slot expects; unresolved slots pass {@link ResolvedType#UNKNOWN} and accept
     *                 anything, matching how {@link com.botmaker.studio.types.TypeExpectation} filters menus
     */
    public void addExpressionDropHandlers(Region target, CodeBlock slot, ResolvedType slotType) {
        target.getStyleClass().add("expression-drop-target");
        installSlotFilters(target, slotType, db -> publishExpressionDrop(db, slot, slotType));
    }

    /**
     * Wires one expression slot's drag feedback and drop.
     *
     * <p><b>Filters, not handlers</b>, and that is the whole point. A slot is rarely a bare {@code Region}: it
     * is usually the picker {@code PickerRegistry} built for its type — a {@code ComboBox}, a {@code Button},
     * a {@code TextField}. Those controls install their own {@code DragEvent} handling (a text field accepts
     * dropped text), which runs on the control itself and consumes the event, so a handler registered through
     * {@code setOnDragOver} could be pre-empted and the slot answered a block drag with nothing at all — no
     * outline, no cursor, no reason. A filter runs in the capture phase, before any descendant's or skin's
     * handler, so the slot decides first.
     *
     * <p>Only a drag actually carrying a block is intercepted; anything else (dragging text into a text field)
     * passes through untouched.
     */
    private void installSlotFilters(Region target, ResolvedType slotType, java.util.function.Predicate<Dragboard> drop) {
        target.addEventFilter(javafx.scene.input.DragEvent.DRAG_OVER, event -> {
            Dragboard db = event.getDragboard();
            if (!carriesBlock(db)) return;
            String refusal = refusalReason(db, slotType);
            if (refusal == null) event.acceptTransferModes(TransferMode.ANY);
            paintSlot(target, db, refusal);
            event.consume();
        });

        target.addEventFilter(javafx.scene.input.DragEvent.DRAG_EXITED, event -> {
            clearDragOver(target);
            uninstallTip(target);
        });

        target.addEventFilter(javafx.scene.input.DragEvent.DRAG_DROPPED, event -> {
            Dragboard db = event.getDragboard();
            if (!carriesBlock(db)) return;
            clearDragOver(target);
            uninstallTip(target);
            event.setDropCompleted(drop.test(db));
            event.consume();
        });
    }

    /**
     * The one tooltip the refusal reason is shown through. Shared rather than per-slot: only one slot is under
     * the pointer at a time, and a tooltip per expression in the editor would be thousands of them.
     *
     * <p>Built on first use, never in the constructor: a {@code Tooltip} needs the JavaFX toolkit running, and
     * this class is constructed by the headless parser fixture the block tests are built on.
     */
    private Tooltip refusalTip;

    private Tooltip refusalTip() {
        if (refusalTip == null) refusalTip = new Tooltip();
        return refusalTip;
    }

    /** Takes the shared tooltip off {@code target}, doing nothing if it was never needed. */
    private void uninstallTip(Region target) {
        if (refusalTip != null) Tooltip.uninstall(target, refusalTip);
    }

    /** Outlines the slot green (it fits) or red (it doesn't, with {@code refusal} said out loud). */
    private void paintSlot(Region target, Dragboard db, String refusal) {
        applyDragOver(target, db, refusal == null);
        if (refusal == null) {
            uninstallTip(target);
            return;
        }
        refusalTip().setText(refusal);
        Tooltip.install(target, refusalTip());
    }

    /** Whether the drag is a block drag at all — the gate on intercepting the event from the slot's own control. */
    private static boolean carriesBlock(Dragboard db) {
        return db.hasContent(ADDABLE_BLOCK_FORMAT) || db.hasContent(EXISTING_BLOCK_FORMAT);
    }

    /**
     * Why this slot will not take what is being dragged, or null when it will.
     *
     * <p>Every refusal has a sentence. The previous version answered a boolean and painted nothing when the
     * dragboard fell short of {@code carriesExpression}, which is precisely the case a user hits by dragging a
     * loop or an {@code if} onto a slot — so the editor's answer to its most common mistake was silence.
     */
    private static String refusalReason(Dragboard db, ResolvedType slotType) {
        if (db.hasContent(ADDABLE_BLOCK_FORMAT) && !producesValue(blockTypeFrom(db)))
            return "Only a value can go in a slot — this block is a whole statement.";
        if (db.hasContent(EXISTING_BLOCK_FORMAT) && !db.hasContent(EXPRESSION_TYPE_FORMAT))
            return "This line is a statement, not a value — there is nothing to put in the slot.";
        Object typeName = db.getContent(EXPRESSION_TYPE_FORMAT);
        // A palette call carries no type: nothing has resolved its return type yet, and doing so here would
        // mean running the analyzer on every drag-over. Unknown is accepted, as everywhere else.
        if (typeName == null) return null;
        ResolvedType actual = ResolvedType.named((String) typeName);
        if (TypeExpectation.fits(slotType, actual)) return null;
        if (actual.isVoid()) return "This line produces nothing, so it cannot fill a slot.";
        return "This slot needs " + expectationText(slotType) + ", and that line gives " + actual.simpleName() + ".";
    }

    private static String expectationText(ResolvedType slotType) {
        return switch (TypeExpectation.of(slotType)) {
            case BOOLEAN -> "a yes/no";
            case NUMERIC -> "a number";
            case STRING -> "text";
            case VOID -> "nothing";
            case ANY -> "a " + slotType.simpleName();
        };
    }

    /** Null-tolerant {@link BlockType#producesValue()} — an unknown palette id resolves to null and fills nothing. */
    private static boolean producesValue(BlockType type) {
        return type != null && type.producesValue();
    }

    /** {@link #refusalReason} as the boolean the drop path asks for. */
    private static boolean acceptsExpression(Dragboard db, ResolvedType slotType) {
        return carriesBlock(db) && refusalReason(db, slotType) == null;
    }

    private boolean publishExpressionDrop(Dragboard db, CodeBlock slot, ResolvedType slotType) {
        if (slot == null || !acceptsExpression(db, slotType)) return false;
        ExpressionDropInfo info;
        if (db.hasContent(ADDABLE_BLOCK_FORMAT)) {
            BlockType type = blockTypeFrom(db);
            if (!producesValue(type)) return false;
            info = ExpressionDropInfo.fromPalette(slot.getId(), type);
        } else {
            info = ExpressionDropInfo.fromExistingBlock(slot.getId(), (String) db.getContent(EXISTING_BLOCK_FORMAT));
        }
        eventBus.publish(new CoreApplicationEvents.ExpressionDropRequestedEvent(info));
        return true;
    }
}
