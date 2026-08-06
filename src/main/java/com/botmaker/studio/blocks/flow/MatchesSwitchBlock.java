package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BranchingBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.MatchesCheck;
import com.botmaker.studio.parser.handlers.MatchesSwitchHandler;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.pickers.ImageTemplateGroupPicker;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code switch} over a {@code Matches} value, rendered as one row per branch: an <b>any/all</b> toggle and
 * the set of templates that branch tests for.
 *
 * <p>It answers the question a bot actually asks of a group — "which of these are on screen <em>together</em>?"
 * — which an ordinary condition can state but not organise. The source it edits is a real Java 21 guarded
 * switch, so it compiles, runs and reads correctly outside Studio:
 *
 * <pre>{@code
 * switch (found) {
 *     case Matches m when m.hasAny(new ImageTemplate("popups/mail.png"),
 *                                  new ImageTemplate("popups/gift.png")) -> { … }
 *     case Matches m when m.hasAll(new ImageTemplate("popups/chest.png")) -> { … }
 *     default -> { }
 * }
 * }</pre>
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
    private BodyBlock defaultBody;
    private SwitchCase defaultCase;

    public MatchesSwitchBlock(String id, SwitchStatement astNode) {
        super(id, astNode);
    }

    public void addCase(SwitchCase caseNode, MatchesSwitchHandler.Guard guard, BodyBlock body) {
        rows.add(new CaseRow(caseNode, guard, body));
    }

    public void setDefault(SwitchCase caseNode, BodyBlock body) {
        this.defaultCase = caseNode;
        this.defaultBody = body;
    }

    /** One branch: its label node, what it tests, and the body it runs. */
    private record CaseRow(SwitchCase caseNode, MatchesSwitchHandler.Guard guard, BodyBlock body) {}

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
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

    private static String caption(MatchesSwitchHandler.Guard guard) {
        StringBuilder sb = new StringBuilder(guard.check().label() + ": ");
        for (int i = 0; i < guard.paths().size(); i++) {
            if (i > 0) sb.append(", ");
            String path = guard.paths().get(i);
            int slash = path.lastIndexOf('/');
            sb.append(slash >= 0 ? path.substring(slash + 1) : path);
        }
        return sb.toString();
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
            if (!row.guard().paths().isEmpty()) return row.guard().paths().getFirst();
        }
        return null;
    }

    private Node caseRowNode(CodeEditorService context, SwitchStatement switchStmt, CaseRow row,
                             List<String> allowed) {
        VBox rowBox = new VBox(5);

        MatchesCheck check = row.guard().check();
        ToggleButton mode = new ToggleButton(check.label());
        mode.getStyleClass().add("matches-case-mode");
        mode.setSelected(check == MatchesCheck.ALL);
        mode.setTooltip(new Tooltip(check == MatchesCheck.ALL
                ? "Runs only when every image below was found. Click for \"any of\"."
                : "Runs when at least one image below was found. Click for \"all of\"."));
        mode.setDisable(isReadOnly());
        mode.setOnAction(e ->
                context.getCodeEditor().setMatchesCaseMode(row.caseNode(), MatchesCheck.of(mode.isSelected())));

        Node chips = ImageTemplateGroupPicker.chipRow(context, row.guard().paths(),
                ImageTemplateGroupPicker.Restrictions.of(allowed, 1),
                paths -> context.getCodeEditor().setMatchesCaseTemplates(row.caseNode(), paths));

        HBox header = BlockLayout.sentence()
                .addKeyword("if")
                .addNode(mode)
                .addNode(chips)
                .build();

        // Only removable while there is more than one branch: a switch whose every case is gone is a
        // `default`-only switch, which is a block that no longer says anything.
        if (!isReadOnly() && rows.size() > 1) {
            header.getChildren().add(BlockUIComponents.createSpacer());
            header.getChildren().add(BlockUIComponents.createDeleteButton(
                    () -> context.getCodeEditor().removeMatchesCase(row.caseNode())));
        }

        rowBox.getChildren().add(header);
        VBox body = createIndentedBody(row.body(), context, "switch-case-body");
        if (body != null) rowBox.getChildren().add(body);
        return rowBox;
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
