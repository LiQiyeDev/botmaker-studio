package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.parser.refactor.SignatureMigration;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.theme.StyleBuilder;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;

import java.util.ArrayList;
import java.util.List;

public class ReturnBlock extends AbstractStatementBlock {

    /** The nested enum an activity reports through — the return's outcome is one of its constants. */
    private static final String OUTCOME_ENUM = "Outcome";

    private ExpressionBlock expression;

    public ReturnBlock(String id, ReturnStatement astNode) {
        super(id, astNode);
    }

    public void setExpression(ExpressionBlock expression) {
        this.expression = expression;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // The pinned trailing return of an activity's run() is special: which outcome it reports stays the
        // user's to choose, but it can't be deleted, moved, or turned into an arbitrary expression. So it gets
        // a dedicated outcome picker (the Outcome enum's constants) instead of the generic expression menu, and
        // no delete button — the flow canvas routes each outcome; this only says which one.
        if (expression != null && isPinnedActivityReturn(context)) {
            return buildPinnedOutcomeReturn(context);
        }

        String methodReturnType = findParentMethodReturnType();
        boolean isVoid = "void".equals(methodReturnType);

        var sentenceBuilder = BlockLayout.sentence().addKeyword("return");

        if (expression != null) {
            sentenceBuilder
                    .addExpressionSlot(expression, context, ResolvedType.named(methodReturnType))
                    .addNode(createChangeButton(e ->
                            showExpressionMenuAndReplace((Button)e.getSource(), context, ResolvedType.named(methodReturnType),
                                    (org.eclipse.jdt.core.dom.Expression)expression.getAstNode())
                    ));
        } else if (!isVoid) {
            sentenceBuilder.addNode(createAddButton(e ->
                    ExpressionMenu.create(
                            ResolvedType.named(methodReturnType), false, context, this.astNode, x -> true,
                            selection -> context.getCodeEditor().setReturnExpression((ReturnStatement) this.astNode, selection)
                    ).show((Button)e.getSource(), javafx.geometry.Side.BOTTOM, 0, 0)
            ));
        } else {
            Label voidLabel = new Label("(void)");
            StyleBuilder.create()
                    .textColor("#aaa")
                    .fontSize(10)
                    .build();
            sentenceBuilder.addNode(voidLabel);
        }

        return BlockLayout.header()
                .withCustomNode(sentenceBuilder.build())
                .withDeleteButton(deleteAction(context))
                .build();
    }

    /**
     * The delete, unless this is the {@code return} the signature depends on — then it explains instead.
     *
     * <p>Removing the last {@code return} of a function that gives something back leaves a file that does not
     * compile, from one click, with the reason visible only in the Errors tab afterwards. It is not really a
     * statement the user owns: it is the body's half of the signature, and the way to be rid of it is to say
     * the function gives nothing back — which removes it as part of that change, calls and all.
     *
     * <p>A refusal that says so, rather than no button at all: an early {@code return} inside an {@code if}
     * deletes normally, so a cross that is simply missing here would read as a rendering bug rather than a
     * rule.
     */
    @Override
    protected Runnable deleteAction(CodeEditorService context) {
        Runnable delete = super.deleteAction(context);
        if (delete == null || !closesAFunctionThatGivesSomethingBack()) return delete;
        return this::explainPinnedBySignature;
    }

    /** Whether this is the trailing {@code return} of a non-{@code void} function — the one it cannot lose. */
    private boolean closesAFunctionThatGivesSomethingBack() {
        MethodDeclaration method = enclosingMethod();
        if (method == null || method.getReturnType2() == null) return false;
        if ("void".equals(method.getReturnType2().toString())) return false;
        return SignatureMigration.trailingReturnOf(method) == this.astNode;
    }

    private void explainPinnedBySignature() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("This line has to stay");
        alert.setHeaderText("The function gives back " + findParentMethodReturnType());
        alert.setContentText("Every path through it has to end by giving that value back, so this line can't "
                + "be removed on its own.\n\nTo be rid of it, edit the function with the ✎ beside its name and "
                + "set it to give nothing back — the return goes with it, and every call is updated.");
        alert.showAndWait();
    }

    private MethodDeclaration enclosingMethod() {
        for (ASTNode n = this.astNode; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) return method;
        }
        return null;
    }

    /** Whether this is the pinned {@code return Outcome.X;} that closes an activity's {@code run()}. */
    private boolean isPinnedActivityReturn(CodeEditorService context) {
        return LockResolver.forActiveFile(context.getConfig(), context.getState()).isPinnedReturn(this.astNode);
    }

    /**
     * The pinned return: {@code return} + the current outcome + an outcome-only picker; no delete/move.
     *
     * <p>Outcomes are labelled through {@link FlowEdge#outcomeLabel}, which is the constant itself — the same
     * name the flow canvas and the activity dialog now show, and the same one that appears in the generated
     * source. Nothing is translated into friendlier words on the way to the screen: the implicit outcome used
     * to read "then" on a port and {@code NEXT} here, so the same outcome looked like two different things
     * depending on which editor the user was in.
     */
    private Node buildPinnedOutcomeReturn(CodeEditorService context) {
        MenuButton picker = new MenuButton(FlowEdge.outcomeLabel(currentOutcome()));
        picker.getStyleClass().add("outcome-picker");
        for (String outcome : outcomeConstants()) {
            MenuItem item = new MenuItem(FlowEdge.outcomeLabel(outcome));
            item.setOnAction(e -> context.getCodeEditor()
                    .replaceWithEnumConstant((Expression) expression.getAstNode(), OUTCOME_ENUM, outcome));
            picker.getItems().add(item);
        }

        var sentence = BlockLayout.sentence()
                .addKeyword("return")
                .addNode(picker)
                .build();
        return BlockLayout.header()
                .withCustomNode(sentence)
                .build();
    }

    /** The constant name the return currently reports (the {@code X} in {@code Outcome.X}), or its raw text. */
    private String currentOutcome() {
        ASTNode node = expression.getAstNode();
        if (node instanceof QualifiedName qn) {
            return qn.getName().getIdentifier();
        }
        return node.toString();
    }

    /** The constants of the enclosing activity's nested {@code Outcome} enum, in declaration order. */
    private List<String> outcomeConstants() {
        for (ASTNode n = this.astNode.getParent(); n != null; n = n.getParent()) {
            if (n instanceof AbstractTypeDeclaration type) {
                for (Object member : type.bodyDeclarations()) {
                    if (member instanceof EnumDeclaration en
                            && OUTCOME_ENUM.equals(en.getName().getIdentifier())) {
                        List<String> names = new ArrayList<>();
                        for (Object c : en.enumConstants()) {
                            names.add(((EnumConstantDeclaration) c).getName().getIdentifier());
                        }
                        return names;
                    }
                }
            }
        }
        return List.of();
    }

    private String findParentMethodReturnType() {
        ASTNode current = this.astNode.getParent();
        while (current != null) {
            if (current instanceof MethodDeclaration) {
                MethodDeclaration md = (MethodDeclaration) current;
                if (md.getReturnType2() != null) {
                    return md.getReturnType2().toString();
                }
                return "void";
            }
            current = current.getParent();
        }
        return "void";
    }
}
