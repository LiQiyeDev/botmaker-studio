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
import com.botmaker.studio.parser.handlers.MatchesSwitchHandler;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.pickers.ImageTemplateGroupPicker;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;

import java.util.ArrayList;
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
 * <p><b>A branch's condition is a tree, and renders as one.</b> {@code MatchesSwitchHandler.Guard} reads the
 * guard as checks at the leaves under {@code and}/{@code or}/{@code not}; this block walks that tree into rows,
 * one per leaf, with the join word between them and a bracketed indent wherever the source is bracketed —
 * because a flat list cannot distinguish {@code A and (B or C)} from {@code (A and B) or C}. A leaf the chip
 * row cannot describe (a template held in a constant, a comparison, anything hand-written) renders as an
 * ordinary expression slot, so it stays droppable rather than degrading to text. What did not change is the
 * common case: one check is still one row, still an any/all toggle over chips.
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

    private final List<CaseRow> rows = new ArrayList<>();
    /** The expression block behind each {@code Guard.Other} leaf, keyed by the node it renders. */
    private final Map<Expression, ExpressionBlock> guardSlots = new LinkedHashMap<>();
    private BodyBlock defaultBody;
    private SwitchCase defaultCase;

    public MatchesSwitchBlock(String id, SwitchStatement astNode) {
        super(id, astNode);
    }

    public void addCase(SwitchCase caseNode, MatchesSwitchHandler.Guard guard, BodyBlock body) {
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
    private record CaseRow(SwitchCase caseNode, MatchesSwitchHandler.Guard guard, BodyBlock body) {}

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
    private static String caption(MatchesSwitchHandler.Guard guard) {
        return switch (guard) {
            case MatchesSwitchHandler.Guard.Check check -> {
                StringBuilder sb = new StringBuilder(check.check().label() + ": ");
                for (int i = 0; i < check.paths().size(); i++) {
                    if (i > 0) sb.append(", ");
                    String path = check.paths().get(i);
                    int slash = path.lastIndexOf('/');
                    sb.append(slash >= 0 ? path.substring(slash + 1) : path);
                }
                yield sb.toString();
            }
            case MatchesSwitchHandler.Guard.Not not -> "not " + bracketed(not.operand());
            case MatchesSwitchHandler.Guard.Junction junction -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < junction.operands().size(); i++) {
                    if (i > 0) sb.append(' ').append(junction.join().label()).append(' ');
                    sb.append(bracketed(junction.operands().get(i)));
                }
                yield sb.toString();
            }
            case MatchesSwitchHandler.Guard.Other other -> other.node().toString();
        };
    }

    /** {@link #caption} of a sub-guard, bracketed when it is itself a group. */
    private static String bracketed(MatchesSwitchHandler.Guard guard) {
        return guard instanceof MatchesSwitchHandler.Guard.Junction
                ? "(" + caption(guard) + ")"
                : caption(guard);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FLOW;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);
        SwitchStatement switchStmt = (SwitchStatement) this.astNode;

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
    private static String firstPath(MatchesSwitchHandler.Guard guard) {
        return switch (guard) {
            case MatchesSwitchHandler.Guard.Check check -> check.paths().getFirst();
            case MatchesSwitchHandler.Guard.Not not -> firstPath(not.operand());
            case MatchesSwitchHandler.Guard.Junction junction -> junction.operands().stream()
                    .map(MatchesSwitchBlock::firstPath)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            case MatchesSwitchHandler.Guard.Other ignored -> null;
        };
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
     * One guard, as a row or — when it is a group — a stack of them.
     *
     * <p>Recursive, because the guard is a tree: a junction becomes one row per operand with the
     * {@code and}/{@code or} word between them, a negation puts {@code not} in front of what it negates, and a
     * leaf is either the any/all toggle over a chip row or an ordinary expression slot. Flat wherever the
     * source is flat — JDT models {@code A && B && C} as one junction, and it renders as three rows rather than
     * stepping right twice.
     *
     * @param guard  the guard at this position, negation included — what a delete here would remove
     * @param parent the junction it is an operand of, or null when it is the whole condition (which is
     *               therefore not removable: a case with no guard is unconditional and dominates the rest)
     * @param prefix the node that opens the first row — the {@code if} keyword, or the parent's join word
     */
    private Node guardNode(CodeEditorService context, CaseRow row, MatchesSwitchHandler.Guard guard,
                           MatchesSwitchHandler.Guard.Junction parent, Node prefix, List<String> allowed) {
        MatchesSwitchHandler.Guard inner =
                guard instanceof MatchesSwitchHandler.Guard.Not not ? not.operand() : guard;
        boolean negated = inner != guard;

        if (inner instanceof MatchesSwitchHandler.Guard.Junction junction) {
            VBox operands = new VBox(4);
            for (int i = 0; i < junction.operands().size(); i++) {
                Node operandPrefix = i == 0
                        ? (negated ? null : prefix)
                        : joinToggle(context, junction);
                operands.getChildren().add(
                        guardNode(context, row, junction.operands().get(i), junction, operandPrefix, allowed));
            }
            Node addRow = joinRow(context, junction.node(), allowed);
            if (addRow != null) operands.getChildren().add(addRow);

            // Bracketed on screen exactly when it is bracketed in the source: a nested group, or one a `not`
            // applies to as a whole.
            if (parent != null || negated) operands.getStyleClass().add("matches-guard-group");
            if (!negated) return operands;

            VBox negatedGroup = new VBox(4);
            negatedGroup.getChildren().add(BlockLayout.sentence()
                    .addNode(prefix)
                    .addNode(notToggle(context, guard))
                    .build());
            negatedGroup.getChildren().add(operands);
            return negatedGroup;
        }

        return leafRow(context, row, guard, inner, parent, prefix, allowed);
    }

    /** A check ({@code any of} + chips) or an expression slot, plus the controls that act on it. */
    private HBox leafRow(CodeEditorService context, CaseRow row, MatchesSwitchHandler.Guard guard,
                         MatchesSwitchHandler.Guard inner, MatchesSwitchHandler.Guard.Junction parent,
                         Node prefix, List<String> allowed) {
        var sentence = BlockLayout.sentence()
                .addNode(prefix)
                .addNode(notToggle(context, guard));

        if (inner instanceof MatchesSwitchHandler.Guard.Check check) {
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
        // The ＋ belongs to the whole condition when this leaf *is* the whole condition; inside a junction the
        // group's own ＋ row owns it, so every operand doesn't carry a duplicate.
        if (parent == null) {
            Node add = joinButton(context, guard.node(), allowed);
            if (add != null) built.getChildren().add(add);
        } else if (!isReadOnly()) {
            MatchesSwitchHandler.Guard.Junction owner = parent;
            built.getChildren().add(BlockUIComponents.createDeleteButton(
                    () -> context.getCodeEditor().removeMatchesGuardOperand(owner, guard)));
        }
        return built;
    }

    /** The any/all toggle — the fast path, and the shape almost every branch has. */
    private ToggleButton modeToggle(CodeEditorService context, MatchesSwitchHandler.Guard.Check check) {
        MatchesCheck current = check.check();
        ToggleButton mode = new ToggleButton(current.label());
        mode.getStyleClass().add("matches-case-mode");
        mode.setSelected(current == MatchesCheck.ALL);
        mode.setTooltip(new Tooltip(current == MatchesCheck.ALL
                ? "Runs only when every image below was found. Click for \"any of\"."
                : "Runs when at least one image below was found. Click for \"all of\"."));
        mode.setDisable(isReadOnly());
        mode.setOnAction(e -> context.getCodeEditor()
                .setMatchesCheckMode(check.call(), MatchesCheck.of(mode.isSelected())));
        return mode;
    }

    /** The {@code and}/{@code or} word between two operands. One operator per group, so it flips all of them. */
    private ToggleButton joinToggle(CodeEditorService context, MatchesSwitchHandler.Guard.Junction junction) {
        MatchesJoin join = junction.join();
        ToggleButton toggle = new ToggleButton(join.label());
        toggle.getStyleClass().add("matches-case-mode");
        toggle.setSelected(join == MatchesJoin.OR);
        toggle.setTooltip(new Tooltip(join == MatchesJoin.AND
                ? "Every condition in this group has to hold. Click for \"or\"."
                : "At least one condition in this group has to hold. Click for \"and\"."));
        toggle.setDisable(isReadOnly());
        toggle.setOnAction(e ->
                context.getCodeEditor().setMatchesGuardJoin(junction.infix(), join.flipped()));
        return toggle;
    }

    /** The {@code not} in front of a condition — a toggle, so clicking it again removes the negation. */
    private ToggleButton notToggle(CodeEditorService context, MatchesSwitchHandler.Guard guard) {
        boolean negated = guard instanceof MatchesSwitchHandler.Guard.Not;
        ToggleButton toggle = new ToggleButton("not");
        toggle.getStyleClass().add("matches-case-mode");
        toggle.setSelected(negated);
        toggle.setTooltip(new Tooltip(negated
                ? "Runs when this condition does *not* hold. Click to drop the \"not\"."
                : "Click to invert this condition."));
        toggle.setDisable(isReadOnly());
        toggle.setOnAction(e -> context.getCodeEditor().toggleMatchesGuardNegation(guard));
        return toggle;
    }

    /** The trailing {@code ＋} row of a group, or null when there is nothing to add (read-only, or no seed). */
    private Node joinRow(CodeEditorService context, Expression junctionNode, List<String> allowed) {
        Node add = joinButton(context, junctionNode, allowed);
        return add == null ? null : BlockLayout.sentence().addNode(add).build();
    }

    /**
     * The {@code ＋} that joins another check onto {@code target}: {@code and} keeps the group's shape,
     * {@code or} starts a new one. Null when read-only or when no template could seed the new check — a guard
     * with no templates would not compile.
     */
    private Node joinButton(CodeEditorService context, Expression target, List<String> allowed) {
        String seed = seedTemplate(allowed);
        if (isReadOnly() || seed == null) return null;

        Button add = new Button("+");
        add.getStyleClass().add("matches-case-mode");
        add.setTooltip(new Tooltip("Add another condition to this branch."));
        ContextMenu menu = new ContextMenu();
        for (MatchesJoin join : MatchesJoin.values()) {
            MenuItem item = new MenuItem(join.label() + " …");
            item.setOnAction(e -> context.getCodeEditor().joinMatchesGuard(target, join, seed));
            menu.getItems().add(item);
        }
        add.setOnAction(e -> menu.show(add, Side.BOTTOM, 0, 0));
        return add;
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
