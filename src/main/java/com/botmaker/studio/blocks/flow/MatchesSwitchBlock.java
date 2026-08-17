package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BranchingBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.MatchesCheck;
import com.botmaker.studio.palette.MatchesJoin;
import com.botmaker.studio.parser.handlers.GuardTree;
import com.botmaker.studio.parser.handlers.MatchesSwitchHandler;
import com.botmaker.studio.parser.handlers.MatchesSwitchHandler.Guard;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.pickers.ImageTemplateGroupPicker;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.css.PseudoClass;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@code switch} over a {@code Matches} value, rendered as one row group per branch: an <b>any/all</b> toggle
 * over the set of templates that branch tests for, joined by <b>and</b>/<b>or</b> and inverted by <b>not</b>
 * when the branch asks for more than one thing.
 *
 * <p>It answers the question a bot actually asks of a group — "which of these are on screen <em>together</em>?"
 * — which an ordinary condition can state but not organise. The source it edits is a real Java 21 guarded
 * switch, so it compiles, runs and reads correctly outside Studio:
 *
 * <pre>{@code
 * switch (found) {
 *     case Matches m when m.hasAny(new ImageTemplate("popups/mail.png"),
 *                                  new ImageTemplate("popups/gift.png")) -> { … }
 *     case Matches m when m.hasAll(new ImageTemplate("popups/chest.png"))
 *                        && !m.hasAny(new ImageTemplate("popups/ad.png")) -> { … }
 *     default -> { }
 * }
 * }</pre>
 *
 * <p><b>A branch's condition is a tree of containers, and renders as one.</b> {@link Guard} reads the guard as
 * checks at the leaves under {@code and}/{@code or}/{@code not}; this block draws each junction as an
 * <b>all of</b> / <b>any of</b> container — the word once, in a header above an indented stack of its
 * conditions, with the container's own {@code ＋} at the foot of that stack. A leaf the chip row cannot describe
 * (a template held in a constant, a comparison, anything hand-written) renders as an ordinary expression slot,
 * so it stays droppable rather than degrading to text. What did not change is the common case: one check is
 * still one row, still an any/all toggle over chips.
 *
 * <p><b>Why the container, and not a word per gap.</b> The word used to be drawn between every pair of rows
 * while the source had only one operator for the whole chain, so clicking one "and" flipped its siblings; the
 * {@code ＋} hung off whichever node was nearest rather than off a group; and the brackets were inferred from
 * operator precedence, so a hand-written grouping came back flattened. Making the container the thing on
 * screen dissolves all three: it owns its word, it owns its {@code ＋}, and — because
 * {@link MatchesSwitchHandler#buildGuard} brackets every nested container — every bracket in the source is a
 * container here and back again. Conditions move between containers by dragging the {@code ⋮} handle.
 *
 * <p><b>Nothing but the branches is shown.</b> The {@code case Matches m when} boilerplate is identical on
 * every branch; the selector — {@code switch (found)} — names a lambda parameter the user never chose and
 * which the block, appearing only inside the find call that produced it, could not be switching over anything
 * else anyway. Both are chrome, so the block renders the combinations and the {@code + Add branch} control and
 * no header at all. Two other things are chrome for a harder reason — they are compile errors when absent.
 * The trailing <b>otherwise</b> row is the {@code default} rule a pattern switch must have to be exhaustive,
 * and a branch can never drop to zero templates because an unguarded {@code case Matches m} is unconditional
 * and would dominate every branch after it. Both are enforced where they are edited rather than validated
 * afterwards, so the block cannot express source that doesn't build.
 *
 * <p>This is deliberately <em>not</em> a specialization of {@link SwitchBlock}, which renders the colon form
 * ({@code case X:} plus a {@code break} label) and parses its label as an expression. The two share no part of
 * a case, so specializing would have meant one class with two disjoint halves.
 */
public class MatchesSwitchBlock extends AbstractStatementBlock implements BlockWithChildren, BranchingBlock {

    /**
     * The caption of the {@code default} rule, in both places it appears: the label the editor renders and the
     * {@link BranchingBlock.Branch} caption the overlay's one-line rows read. They are the same word by
     * design — the caption vocabulary {@code BranchingBlock} documents — and were built independently.
     */
    private static final String OTHERWISE = "otherwise";

    /**
     * What a dragged {@code ⋮} handle carries: a key into {@link #dragRefs}, not the guard itself. A dragboard
     * only takes serializable content, and a guard is a tree of live AST nodes — the key keeps the identity the
     * edit needs (see {@link GuardTree}) without asking JavaFX to carry it.
     */
    private static final DataFormat GUARD_OPERAND = new DataFormat("application/x-botmaker-guard-operand");

    /** Set on a container's operand stack while a condition is hovering over it. */
    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");

    private final List<CaseRow> rows = new ArrayList<>();
    /** The expression block behind each {@code Guard.Other} leaf, keyed by the node it renders. */
    private final Map<Expression, ExpressionBlock> guardSlots = new LinkedHashMap<>();
    /** Every draggable condition in the current rendering, by the key its handle puts on the dragboard. */
    private final Map<String, DragRef> dragRefs = new HashMap<>();
    private BodyBlock defaultBody;
    private SwitchCase defaultCase;

    public MatchesSwitchBlock(String id, SwitchStatement astNode) {
        super(id, astNode);
    }

    public void addCase(SwitchCase caseNode, Guard guard, BodyBlock body) {
        rows.add(new CaseRow(caseNode, guard, body));
    }

    /**
     * Registers the expression block for a guard leaf this block cannot say in chips. Filled by
     * {@code BlockConverter}, which is the only place that can parse one.
     */
    public void putGuardSlot(Expression node, ExpressionBlock block) {
        if (node != null && block != null) guardSlots.put(node, block);
    }

    public void setDefault(SwitchCase caseNode, BodyBlock body) {
        this.defaultCase = caseNode;
        this.defaultBody = body;
    }

    /** One branch: its label node, what it tests, and the body it runs. */
    private record CaseRow(SwitchCase caseNode, Guard guard, BodyBlock body) {}

    /** A draggable condition: which branch's tree it belongs to, and which node of it it is. */
    private record DragRef(CaseRow row, Guard guard) {}

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>(guardSlots.values());
        for (CaseRow row : rows) {
            if (row.body() != null) children.add(row.body());
        }
        if (defaultBody != null) children.add(defaultBody);
        return children;
    }

    /**
     * One branch per case, captioned the way its row reads — {@code "any of: mail, gift"} — plus the
     * mandatory {@code otherwise}. The template <em>file names</em> stand in for the chip row: a
     * one-line caption has no room for full paths, and the name is what the user picked the image by.
     */
    @Override
    public List<Branch> branches() {
        List<Branch> out = new ArrayList<>();
        for (CaseRow row : rows) {
            if (row.body() != null) out.add(new Branch(caption(row.guard()), row.body()));
        }
        if (defaultBody != null) out.add(new Branch(OTHERWISE, defaultBody));
        return out;
    }

    /**
     * A guard as one line of words — {@code "any of: mail, gift and not all of: chest"}. Recursive because the
     * guard is a tree; a nested group is bracketed, since that is the only thing distinguishing
     * {@code A and (B or C)} from {@code (A and B) or C}.
     */
    private static String caption(Guard guard) {
        return switch (guard) {
            case Guard.Check check -> {
                StringBuilder sb = new StringBuilder(check.check().label() + ": ");
                for (int i = 0; i < check.paths().size(); i++) {
                    if (i > 0) sb.append(", ");
                    String path = check.paths().get(i);
                    int slash = path.lastIndexOf('/');
                    sb.append(slash >= 0 ? path.substring(slash + 1) : path);
                }
                yield sb.toString();
            }
            case Guard.Not not -> "not " + bracketed(not.operand());
            case Guard.Container container -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < container.operands().size(); i++) {
                    if (i > 0) sb.append(' ').append(container.join().label()).append(' ');
                    sb.append(bracketed(container.operands().get(i)));
                }
                yield sb.toString();
            }
            case Guard.Other other -> other.node().toString();
        };
    }

    /** {@link #caption} of a sub-guard, bracketed when it is itself a container. */
    private static String bracketed(Guard guard) {
        return guard instanceof Guard.Container ? "(" + caption(guard) + ")" : caption(guard);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FLOW;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);
        SwitchStatement switchStmt = (SwitchStatement) this.astNode;
        // Keys are handed out per rendering, and a rendering is thrown away on every edit.
        dragRefs.clear();

        VBox branches = new VBox(5);
        // The branches carry what the header used to say. It read "check <found> for", and every word of that
        // was chrome: the block only ever appears inside the find call that produced the value, the value's
        // name is one the user never chose, and the keywords rendered as light-on-light. What is left is the
        // content — the combinations themselves — with the explanation moved onto them.
        Tooltip.install(branches, new Tooltip(
                "Which of this group's images were found in the same frame. Each branch tests a combination "
                        + "of them; the first one that matches runs."));

        // Every branch of a switch sees the same group, so the narrowing is resolved once for the whole block
        // rather than per row — it walks out to the enclosing find call, which is the same walk each time.
        List<String> allowed = MatchesGroupScope.allowedPaths(switchStmt);

        for (CaseRow row : rows) {
            branches.getChildren().add(caseRowNode(context, switchStmt, row, allowed));
        }
        branches.getChildren().add(otherwiseNode(context));
        container.getChildren().add(branches);

        // The footer carries both controls the block still needs. Delete used to live on the header; with the
        // header gone it would otherwise have become unreachable, and a row holding nothing but an X is worse
        // than one that pairs it with the only other action there is.
        if (!isReadOnly()) {
            Button addCase = new Button("+ Add branch");
            addCase.setTooltip(new Tooltip("Another combination to check, before the catch-all below."));
            addCase.setOnAction(e -> context.getCodeEditor().addMatchesCase(switchStmt, seedTemplate(allowed)));
            container.getChildren().add(BlockLayout.header()
                    .withCustomNode(addCase)
                    .withDeleteButton(deleteAction(context))
                    .build());
        }
        return container;
    }

    /**
     * The template a new branch starts on. It is the group's first, or — when the group couldn't be resolved —
     * the first one this switch already mentions, so a new branch is always born with a real image rather than
     * an empty guard that wouldn't compile.
     */
    private String seedTemplate(List<String> allowed) {
        if (allowed != null && !allowed.isEmpty()) return allowed.getFirst();
        for (CaseRow row : rows) {
            String fromGuard = firstPath(row.guard());
            if (fromGuard != null) return fromGuard;
        }
        return null;
    }

    /** The first template named anywhere in a guard tree, or null when it names none. */
    private static String firstPath(Guard guard) {
        return switch (guard) {
            case Guard.Check check -> check.paths().getFirst();
            case Guard.Not not -> firstPath(not.operand());
            case Guard.Container container -> container.operands().stream()
                    .map(MatchesSwitchBlock::firstPath)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            case Guard.Other ignored -> null;
        };
    }

    /**
     * A second template for a newly added group, so its two seeded conditions aren't the same image twice.
     * Falls back to {@code seed} when the group has only one — a duplicate is editable; an empty guard is not.
     */
    private static String secondSeed(List<String> allowed, String seed) {
        if (allowed == null) return seed;
        for (String path : allowed) {
            if (!path.equals(seed)) return path;
        }
        return seed;
    }

    private Node caseRowNode(CodeEditorService context, SwitchStatement switchStmt, CaseRow row,
                             List<String> allowed) {
        VBox rowBox = new VBox(5);

        Label ifWord = new Label("if");
        ifWord.getStyleClass().add("keyword-label");
        Node condition = guardNode(context, row, row.guard(), null, ifWord, allowed);

        // The branch's delete sits on the condition's first row, where the header used to be. A composed
        // condition is several rows tall, so hanging it off the group as a whole would leave it floating.
        if (!isReadOnly() && rows.size() > 1) {
            HBox first = firstRowOf(condition);
            if (first != null) {
                first.getChildren().add(BlockUIComponents.createSpacer());
                first.getChildren().add(BlockUIComponents.createDeleteButton(
                        () -> context.getCodeEditor().removeMatchesCase(row.caseNode())));
            }
        }

        rowBox.getChildren().add(condition);
        VBox body = createIndentedBody(row.body(), context, "switch-case-body");
        if (body != null) rowBox.getChildren().add(body);
        return rowBox;
    }

    /** The topmost sentence row of a rendered condition — where the branch-level controls belong. */
    private static HBox firstRowOf(Node condition) {
        if (condition instanceof HBox row) return row;
        if (condition instanceof VBox group && !group.getChildren().isEmpty()) {
            return firstRowOf(group.getChildren().getFirst());
        }
        return null;
    }

    /**
     * One guard, as a row or — when it is a container — a header over an indented stack of them.
     *
     * <p>Recursive, because the guard is a tree. A leaf is the any/all toggle over a chip row, or an ordinary
     * expression slot when the chips cannot say it. Flat wherever the source is flat: JDT models
     * {@code A && B && C} as one junction, so that is one container of three rows rather than two nested ones.
     *
     * @param guard  the guard at this position, negation included — what a delete here would remove
     * @param parent the container it is an operand of, or null when it is the whole condition (which is
     *               therefore not removable: a case with no guard is unconditional and dominates the rest)
     * @param prefix the node that opens the first row — the {@code if} keyword, or nothing inside a container
     */
    private Node guardNode(CodeEditorService context, CaseRow row, Guard guard, Guard.Container parent,
                           Node prefix, List<String> allowed) {
        Guard inner = guard instanceof Guard.Not not ? not.operand() : guard;
        return inner instanceof Guard.Container nested
                ? containerNode(context, row, guard, nested, parent, prefix, allowed)
                : leafRow(context, row, guard, inner, parent, prefix, allowed);
    }

    /**
     * An <b>all of</b> / <b>any of</b> container: its word on a header row, its conditions indented under it,
     * and its own {@code ＋} at the foot. The indent is also the drop zone — dragging a condition's {@code ⋮}
     * onto it is what moves that condition into this group.
     */
    private Node containerNode(CodeEditorService context, CaseRow row, Guard guard, Guard.Container container,
                               Guard.Container parent, Node prefix, List<String> allowed) {
        VBox box = new VBox(4);
        HBox header = BlockLayout.sentence()
                .addNode(prefix)
                .addNode(dragHandle(row, guard, parent))
                .addNode(notToggle(context, row, guard))
                .addNode(containerToggle(context, row, container))
                .build();
        if (parent != null && !isReadOnly()) {
            header.getChildren().add(BlockUIComponents.createDeleteButton(
                    () -> apply(context, row, GuardTree.remove(row.guard(), guard))));
        }
        box.getChildren().add(header);

        VBox operands = new VBox(4);
        operands.getStyleClass().add("matches-guard-container");
        for (Guard operand : container.operands()) {
            operands.getChildren().add(guardNode(context, row, operand, container, null, allowed));
        }
        Node add = addRow(context, row, container, allowed);
        if (add != null) operands.getChildren().add(add);
        installDropTarget(operands, context, row, container);

        box.getChildren().add(operands);
        return box;
    }

    /** A check ({@code any of} + chips) or an expression slot, plus the controls that act on it. */
    private HBox leafRow(CodeEditorService context, CaseRow row, Guard guard, Guard inner,
                         Guard.Container parent, Node prefix, List<String> allowed) {
        var sentence = BlockLayout.sentence()
                .addNode(prefix)
                .addNode(dragHandle(row, guard, parent))
                .addNode(notToggle(context, row, guard));

        if (inner instanceof Guard.Check check) {
            sentence.addNode(modeToggle(context, check))
                    .addNode(ImageTemplateGroupPicker.chipRow(context, check.paths(),
                            ImageTemplateGroupPicker.Restrictions.of(allowed, 1),
                            paths -> context.getCodeEditor().setMatchesCheckTemplates(check.call(), paths)));
        } else {
            // A condition the chip row cannot describe — a check against a template held in a constant, a
            // comparison, anything hand-written. It renders as the ordinary expression slot it is, so it stays
            // droppable and editable instead of being flattened to text.
            ExpressionBlock slot = guardSlots.get(inner.node());
            if (slot != null) {
                sentence.addExpressionSlot(slot, context, ResolvedType.BOOLEAN);
            } else {
                sentence.addLabel(inner.node().toString());
            }
        }

        HBox built = sentence.build();
        // With no container yet, this leaf *is* the whole condition, so growing it means wrapping it in one.
        // Inside a container the group's own ＋ owns that, and this row carries a delete instead.
        if (parent == null) {
            Node add = groupButton(context, row, guard, allowed);
            if (add != null) built.getChildren().add(add);
        } else if (!isReadOnly()) {
            built.getChildren().add(BlockUIComponents.createDeleteButton(
                    () -> apply(context, row, GuardTree.remove(row.guard(), guard))));
        }
        return built;
    }

    /** Writes a branch's recomposed condition. A null tree is a refused gesture — see {@link GuardTree}. */
    private static void apply(CodeEditorService context, CaseRow row, Guard newTree) {
        context.getCodeEditor().setMatchesGuard(row.caseNode(), newTree);
    }

    /** The any/all toggle — the fast path, and the shape almost every branch has. */
    private ToggleButton modeToggle(CodeEditorService context, Guard.Check check) {
        MatchesCheck current = check.check();
        ToggleButton mode = new ToggleButton(current.label());
        mode.getStyleClass().addAll("matches-case-mode", "matches-leaf-mode");
        mode.setSelected(current == MatchesCheck.ALL);
        mode.setTooltip(new Tooltip(current == MatchesCheck.ALL
                ? "Runs only when every image below was found. Click for \"any of\"."
                : "Runs when at least one image below was found. Click for \"all of\"."));
        mode.setDisable(isReadOnly());
        mode.setOnAction(e -> context.getCodeEditor()
                .setMatchesCheckMode(check.call(), MatchesCheck.of(mode.isSelected())));
        return mode;
    }

    /**
     * A container's word. It flips this container and nothing else — which is the whole point of drawing it
     * once, above the group, instead of once per gap over an operator the gaps were sharing.
     */
    private ToggleButton containerToggle(CodeEditorService context, CaseRow row, Guard.Container container) {
        MatchesJoin join = container.join();
        ToggleButton toggle = new ToggleButton(join.containerLabel());
        toggle.getStyleClass().addAll("matches-case-mode", "matches-container-word");
        toggle.setSelected(join == MatchesJoin.AND);
        toggle.setTooltip(new Tooltip(join == MatchesJoin.AND
                ? "Every condition in this group has to hold. Click for \"any of\"."
                : "At least one condition in this group has to hold. Click for \"all of\"."));
        toggle.setDisable(isReadOnly());
        toggle.setOnAction(e -> apply(context, row, GuardTree.setJoin(row.guard(), container, join.flipped())));
        return toggle;
    }

    /** The {@code not} in front of a condition — a toggle, so clicking it again removes the negation. */
    private ToggleButton notToggle(CodeEditorService context, CaseRow row, Guard guard) {
        boolean negated = guard instanceof Guard.Not;
        ToggleButton toggle = new ToggleButton("not");
        toggle.getStyleClass().addAll("matches-case-mode", "matches-not-toggle");
        toggle.setSelected(negated);
        toggle.setTooltip(new Tooltip(negated
                ? "Runs when this condition does *not* hold. Click to drop the \"not\"."
                : "Click to invert this condition."));
        toggle.setDisable(isReadOnly());
        toggle.setOnAction(e -> apply(context, row, GuardTree.negate(row.guard(), guard)));
        return toggle;
    }

    /**
     * A container's trailing {@code ＋} row: another condition, or another group nested inside this one. Null
     * when read-only or when no template could seed what it would add — a guard with no templates would not
     * compile. A new group is born with two conditions because a group of one is not a group: the source has
     * no bracket to hold it, so it would come back as the single condition it contains.
     */
    private Node addRow(CodeEditorService context, CaseRow row, Guard.Container container, List<String> allowed) {
        String seed = seedTemplate(allowed);
        if (isReadOnly() || seed == null) return null;

        Button add = new Button("＋");
        add.getStyleClass().addAll("matches-case-mode", "matches-container-word");
        add.setTooltip(new Tooltip("Add to this group."));

        MenuItem condition = new MenuItem("Condition");
        condition.setOnAction(e ->
                apply(context, row, GuardTree.add(row.guard(), container, GuardTree.check(seed))));
        MenuItem group = new MenuItem("Group of conditions");
        group.setOnAction(e -> apply(context, row, GuardTree.add(row.guard(), container,
                GuardTree.container(container.join().flipped(),
                        List.of(GuardTree.check(seed), GuardTree.check(secondSeed(allowed, seed)))))));

        ContextMenu menu = new ContextMenu(condition, group);
        add.setOnAction(e -> menu.show(add, Side.BOTTOM, 0, 0));
        return BlockLayout.sentence().addNode(add).build();
    }

    /**
     * The {@code ＋} on a condition that is a branch's whole guard: it has no container to join, so both menu
     * items make one — and the user says which word it gets rather than inheriting a guess.
     */
    private Node groupButton(CodeEditorService context, CaseRow row, Guard guard, List<String> allowed) {
        String seed = seedTemplate(allowed);
        if (isReadOnly() || seed == null) return null;

        Button add = new Button("＋");
        add.getStyleClass().addAll("matches-case-mode", "matches-container-word");
        add.setTooltip(new Tooltip("Add another condition to this branch."));
        ContextMenu menu = new ContextMenu();
        for (MatchesJoin join : MatchesJoin.values()) {
            MenuItem item = new MenuItem(join.containerLabel() + " …");
            item.setOnAction(e -> apply(context, row,
                    GuardTree.group(row.guard(), guard, join, GuardTree.check(seed))));
            menu.getItems().add(item);
        }
        add.setOnAction(e -> menu.show(add, Side.BOTTOM, 0, 0));
        return add;
    }

    // =================================================================================
    // MOVING A CONDITION BETWEEN CONTAINERS
    // =================================================================================

    /** The {@code ⋮} a condition is dragged by, or null for a read-only block or a guard with no container. */
    private Node dragHandle(CaseRow row, Guard guard, Guard.Container parent) {
        if (isReadOnly() || parent == null) return null;

        Label handle = new Label("⋮");
        handle.getStyleClass().add("matches-guard-handle");
        handle.setTooltip(new Tooltip("Drag into another group."));

        String key = String.valueOf(dragRefs.size());
        dragRefs.put(key, new DragRef(row, guard));
        handle.setOnDragDetected(e -> {
            Dragboard board = handle.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.put(GUARD_OPERAND, key);
            board.setContent(content);
            e.consume();
        });
        return handle;
    }

    /** Makes a container's operand stack accept conditions dragged out of another one. */
    private void installDropTarget(Node zone, CodeEditorService context, CaseRow row,
                                   Guard.Container container) {
        if (isReadOnly()) return;

        zone.setOnDragOver(e -> {
            if (movable(e.getDragboard(), row, container) != null) {
                e.acceptTransferModes(TransferMode.MOVE);
                zone.pseudoClassStateChanged(DROP_TARGET, true);
            }
            e.consume();
        });
        zone.setOnDragExited(e -> zone.pseudoClassStateChanged(DROP_TARGET, false));
        zone.setOnDragDropped(e -> {
            Guard moved = movable(e.getDragboard(), row, container);
            zone.pseudoClassStateChanged(DROP_TARGET, false);
            if (moved != null) {
                apply(context, row, GuardTree.move(row.guard(), moved, container));
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    /**
     * The condition a drag is carrying, when it may land in {@code container} — and null otherwise, which is
     * what stops the drop being offered at all. Refused across branches (each is its own tree), into a
     * container the condition already sits in, and into one of its own descendants.
     */
    private Guard movable(Dragboard board, CaseRow row, Guard.Container container) {
        if (!(board.getContent(GUARD_OPERAND) instanceof String key)) return null;
        DragRef ref = dragRefs.get(key);
        if (ref == null || ref.row() != row) return null;

        Guard moved = ref.guard();
        if (moved == container || GuardTree.contains(moved, container)) return null;
        if (GuardTree.ownerOf(row.guard(), moved) == container) return null;
        return moved;
    }

    /**
     * The {@code default} rule. It renders as a labelled body with no delete control at all — a statement
     * switch over patterns must be exhaustive, so removing it would stop the bot compiling.
     */
    private Node otherwiseNode(CodeEditorService context) {
        VBox box = new VBox(5);
        Label label = new Label(OTHERWISE);
        label.getStyleClass().addAll("keyword-label", "switch-case-break");
        Tooltip.install(label, new Tooltip(
                "Runs when no branch above matched. Always present — a switch like this has to cover "
                        + "every case, so it can't be removed."));
        box.getChildren().add(label);

        VBox body = createIndentedBody(defaultBody, context, "switch-case-body");
        if (body != null) box.getChildren().add(body);
        return box;
    }

    /** The {@code default} label node, for callers that need to insert before it. */
    public SwitchCase defaultCase() {
        return defaultCase;
    }
}
