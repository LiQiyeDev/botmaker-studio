package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.util.MethodSignature;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * A call whose last argument is a body — {@code Receiver.method(args…, param -> { … })} — drawn as a header
 * sentence with a fillable slot per leading argument and the lambda's body as a droppable {@link BodyBlock}.
 *
 * <h2>It knows no library's vocabulary, and that is what it is for</h2>
 *
 * <p>This replaced {@code blocks.vision.LambdaCallBlock} on 2026-09-01, and the difference is the whole point.
 * That block drew exactly one library's feature: the facade was a {@code ImageFinder.class} literal, the method
 * dropdown listed a {@code VisionLoop} enum of nine {@code ImageFinder} members, and the argument slot was
 * typed {@code ImageTemplate} or {@code ImageTemplateGroup} depending on which of them was picked. Three SDK
 * imports to render one shape — and no second plugin offering a method that takes a body could be drawn at
 * all, because every caption came from an enum written here.
 *
 * <p>Everything drawn here comes from the <em>source</em> instead: the receiver and the method are the text
 * the user's code actually holds, and the slot types are asked of the bot's own resolved classpath through
 * {@code ProjectAnalyzer} — the jar it pins, not the one this editor was built against. So the same block
 * draws any library's body-taking call, and no SDK type is named in this file. It is the same rule
 * {@link BranchChainBlock} follows, for the same reason.
 *
 * <p><b>What the three deleted controls were, and why none of them came across.</b> The <em>facade
 * dropdown</em> repointed the call at another SDK class and dropped the body — a rewrite that only makes
 * sense inside one library's own set of facades. The <em>method dropdown</em> switched between the nine
 * vision variants and converted the leading argument single↔group with it; both halves were the SDK's
 * vocabulary spelled here. The {@code → boolean} <em>return badge</em> read the same enum. A user who wants a
 * different method still has the ordinary path every other call has: the member menus, served by the plugin
 * that owns the type.
 *
 * <p><b>The value handed to the body is not drawn</b>, which the block it replaced had already settled: the
 * lambda's parameter is registered as an in-scope variable for the body (see
 * {@code suggestions.ProjectAnalyzer#enclosingLambdaParameters}), so {@code found} and its members are
 * offered by the body's expression menu, and the name is the library's choice rather than a decision the user
 * makes.
 */
public class BodyCallBlock extends AbstractStatementBlock implements BlockWithChildren {

    private final String receiver;
    private final String method;
    private final List<ExpressionBlock> arguments = new ArrayList<>();
    private BodyBlock body;

    public BodyCallBlock(String id, ASTNode astNode, String receiver, String method) {
        super(id, astNode);
        this.receiver = receiver == null ? "" : receiver;
        this.method = method;
    }

    public void addArgument(ExpressionBlock argument) { arguments.add(argument); }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>(arguments);
        if (body != null) children.add(body);
        return children;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);

        List<ResolvedType> slotTypes = leadingParameterTypes(context);

        var sentence = BlockLayout.sentence();
        if (!receiver.isEmpty()) {
            Label scope = new Label(receiver);
            scope.getStyleClass().addAll("sdk-class-selector", "block-chip");
            sentence.addNode(scope).addLabel(".");
        }
        sentence.addLabel(method).addLabel("(");

        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) sentence.addLabel(",");
            ExpressionBlock argument = arguments.get(i);
            ResolvedType slotType = i < slotTypes.size() ? slotTypes.get(i) : ResolvedType.UNKNOWN;
            sentence.addExpressionSlot(argument, context, slotType);
            sentence.addNode(createAddButton(e -> showExpressionMenuAndReplace(
                    (Button) e.getSource(),
                    context,
                    slotType,
                    argument != null ? (Expression) argument.getAstNode() : null)));
        }
        sentence.addLabel(")");

        HBox headerContent = sentence.build();

        // The frame wraps header AND body, so the body reads as enclosed by the call rather than detached.
        container.getStyleClass().add("sdk-call-block");
        container.getChildren().add(BlockLayout.header()
                .withCustomNode(headerContent)
                .withDeleteButton(deleteAction(context))
                .build());
        container.getChildren().add(createIndentedBody(body, context, "sdk-lambda-body"));

        return container;
    }

    /**
     * The declared types of the arguments before the body, read off the bot's <em>own</em> resolved classpath.
     *
     * <p>Asked rather than known: the method may be one this editor has never heard of, on a library it does
     * not compile against, in a version older than the one it ships with. A method that resolves to nothing —
     * or to an overload with a different arity — leaves every slot {@link ResolvedType#UNKNOWN}, which costs
     * the expression menu its type filter and costs the block nothing else.
     */
    private List<ResolvedType> leadingParameterTypes(CodeEditorService context) {
        if (receiver.isEmpty()) return List.of();
        List<MethodSignature> overloads = context.getProjectAnalyzer().getMethods(receiver, true).stream()
                .filter(m -> m.name().equals(method))
                .toList();
        // arguments + the trailing body: the overload we are on is the one whose arity counts them all.
        MethodSignature match = MethodSignature.bestForArity(overloads, arguments.size() + 1);
        if (match == null) return List.of();
        List<ResolvedType> params = match.paramTypes();
        return params.size() > arguments.size() ? params.subList(0, arguments.size()) : params;
    }
}
