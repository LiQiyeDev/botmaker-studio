package com.botmaker.studio.blocks.vision;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.palette.SdkDocs;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.palette.VisionLoop;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.util.MethodSignature;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * A body-carrying variant of the standard SDK call block for the {@code ImageFinder} vision helpers that take a
 * trailing action lambda — {@code ImageFinder.ifFind/whileFind/untilFind(image, m -> { … })} and their
 * {@code …Any}/{@code …All} group forms. It renders like the ordinary SDK block (a {@code 🤖 SDK} badge, the
 * {@code ImageFinder} class chip, a <em>method dropdown</em>, a fillable image/group argument slot, and a
 * {@code → return} badge) but — because these methods need a body — it also carries an indented, droppable
 * {@link BodyBlock} (the lambda body).
 *
 * <p>Selecting a different method in the dropdown rewrites the call via
 * {@code parser.handlers.LambdaCallHandler#switchVariant}: it renames the method, swaps the image slot between a
 * single {@code ImageTemplate} and an {@code ImageTemplateGroup} (engaging the multi-image group picker), and
 * fixes the lambda parameter — rewriting <em>in place</em> so the user's body survives the switch. (The generic
 * method-invocation overload path can't be reused here: it syncs arguments positionally and would clobber the
 * trailing lambda.)
 *
 * <p><b>The value handed to the body is not drawn.</b> A {@code MatchResult} for the single-template forms, a
 * {@code Matches} for the group forms — it was briefly rendered as an editable chip ({@code found →}) between
 * the header and the body, on the reasoning that a value with no on-screen presence was unreachable. It isn't:
 * the name is registered as an in-scope variable for the body (see
 * {@code suggestions.ProjectAnalyzer#enclosingLambdaParameters}), which is what puts {@code found} and its
 * {@code has}/{@code hasAll}/{@code get}/{@code best} members in the body's expression menu — and the name
 * itself is the SDK's choice, not a decision the user makes, so a chip for editing it was chrome offering to
 * change something nobody wants changed. What the body receives is said in words on the method dropdown
 * instead ({@link #bodyValueHint}).
 */
public class LambdaCallBlock extends AbstractStatementBlock implements BlockWithChildren {

    private final String method;
    private ExpressionBlock image;
    private BodyBlock body;

    public LambdaCallBlock(String id, ASTNode astNode, String method) {
        super(id, astNode);
        this.method = method;
    }

    public void setImage(ExpressionBlock image) { this.image = image; }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (image != null) children.add(image);
        if (body != null) children.add(body);
        return children;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);

        ResolvedType slotType = slotType();
        Button changeBtn = createAddButton(e ->
                showExpressionMenuAndReplace(
                        (Button) e.getSource(),
                        context,
                        slotType,
                        image != null ? (Expression) image.getAstNode() : null
                )
        );

        // SDK-style header: 🤖 SDK  ImageFinder . [method ▾] ( [image · +] )  → ret  [?]
        var sentence = BlockLayout.sentence();

        Label sdkBadge = new Label("🤖 SDK");
        sdkBadge.getStyleClass().add("sdk-badge");
        sentence.addNode(sdkBadge);

        sentence.addNode(createClassSelector(context));

        sentence.addLabel(".")
                .addNode(createMethodSelector(context))
                .addLabel("(")
                .addExpressionSlot(image, context, slotType)
                .addNode(changeBtn)
                .addLabel(")");

        addReturnBadge(sentence);
        addInfoButton(sentence, context);

        HBox headerContent = sentence.build();

        // The SDK frame (light-purple fill + border) now wraps the WHOLE block — header AND lambda body — so
        // the action body reads as enclosed by the call, rather than a header-only frame with a detached body.
        container.getStyleClass().add("sdk-call-block");

        container.getChildren().add(BlockLayout.header()
                .withCustomNode(headerContent)
                .withDeleteButton(deleteAction(context))
                .build());

        container.getChildren().add(createIndentedBody(body, context, "sdk-lambda-body"));

        return container;
    }

    /**
     * The facade dropdown — the same {@code SdkType.FACADE_NAMES} selector every other SDK call block carries.
     * It was a plain {@link Label} until now, which made this block a one-way door: a call that became a vision
     * loop could never be pointed anywhere else, because nothing else on the block names the class.
     *
     * <p>Picking another facade rewrites the statement into an ordinary call on it
     * ({@code CodeEditor.replaceLambdaCallWithFacadeCall}) — the lambda has no meaning off {@code ImageFinder},
     * so it goes, and a body with statements in it is confirmed away first rather than deleted silently.
     */
    private Node createClassSelector(CodeEditorService context) {
        String facade = SdkType.IMAGE_FINDER.simpleName();
        if (isReadOnly()) {
            Label chip = new Label(facade);
            chip.getStyleClass().add("sdk-class-selector");
            chip.getStyleClass().add("block-chip");
            return chip;
        }
        ComboBox<String> selector = new ComboBox<>();
        selector.getStyleClass().add("sdk-class-selector");
        selector.getItems().addAll(SdkType.FACADE_NAMES);
        if (!selector.getItems().contains(facade)) selector.getItems().add(0, facade);
        selector.setValue(facade);
        selector.getStyleClass().add("block-selector");
        selector.setTooltip(new Tooltip("Point this call at another SDK class (the action body is dropped)"));
        selector.setOnAction(e -> {
            String picked = selector.getValue();
            if (picked == null || picked.equals(facade)) return;
            if (!confirmBodyDiscard(picked)) {
                selector.setValue(facade);
                return;
            }
            switchFacade(context, picked);
        });
        return selector;
    }

    /**
     * Asks before throwing away a body the user has written. An empty body is discarded without a prompt —
     * there is nothing to lose, and a confirmation on every pick would train the user to click through the one
     * that matters.
     */
    private boolean confirmBodyDiscard(String newClass) {
        if (body == null || body.getChildren().isEmpty()) return true;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Only ImageFinder's loop helpers take an action body.\n\n"
                        + "Switching to " + newClass + " will delete the " + body.getChildren().size()
                        + " statement(s) inside this loop.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText("Discard the action body?");
        confirm.setTitle("Change SDK class");
        return confirm.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /**
     * Rewrites the call onto {@code newClass}: keeps the method name when that class declares one too, else its
     * first method — the same rule {@code MethodInvocationBlock.switchSdkClass} follows, so the two SDK class
     * dropdowns behave identically. Arity 0 because nothing of this call's arguments survives the move.
     */
    private void switchFacade(CodeEditorService context, String newClass) {
        List<MethodSignature> methods = context.getProjectAnalyzer().getMethods(newClass, true);
        String targetMethod = methods.stream().map(MethodSignature::name).anyMatch(n -> n.equals(method))
                ? method
                : methods.stream().map(MethodSignature::name).sorted().findFirst().orElse(method);

        MethodSignature best = MethodSignature.bestForArity(
                methods.stream().filter(m -> m.name().equals(targetMethod)).toList(), 0);
        List<ResolvedType> paramTypes = best != null ? best.paramTypes() : List.of();

        context.getCodeEditor().replaceLambdaCallWithFacadeCall((Statement) this.astNode,
                new ExpressionChoice.Method(newClass, targetMethod, paramTypes, true));
    }

    /**
     * The method dropdown — the standard SDK-block affordance, here listing the nine {@code ImageFinder} lambda
     * helpers. Picking one rewrites the call in place (preserving the body) via {@code switchLambdaVariant}.
     */
    private ComboBox<String> createMethodSelector(CodeEditorService context) {
        ComboBox<String> selector = new ComboBox<>();
        for (VisionLoop v : VisionLoop.values()) selector.getItems().add(v.methodName());
        selector.setValue(method);
        selector.setEditable(false);
        selector.getStyleClass().add("block-selector");
        selector.setPrefWidth(130);
        selector.setTooltip(new Tooltip(
                "if / while / until  ×  a single image, ANY of a group, or ALL of a group\n\n" + bodyValueHint()));
        selector.setOnAction(e -> {
            String picked = selector.getValue();
            if (picked == null || picked.equals(method)) return;
            VisionLoop.fromMethodName(picked).ifPresent(v ->
                    context.getCodeEditor()
                            .switchLambdaVariant((Statement) this.astNode, v.methodName(), v.group(), targetParamName(v)));
        });
        return selector;
    }

    /**
     * What the chosen variant hands the body, in words. This sentence used to be the tooltip on an editable
     * chip rendering the lambda parameter ({@code found →}). The chip is gone — the name is one the SDK
     * chooses and the user has no reason to care about, so a control for renaming it was a control for a
     * decision nobody makes — but it carried the only in-UI statement of what crosses into the body, so it
     * moved onto the dropdown that decides it. The parameter itself still exists in the source, and the body's
     * expression menu still offers it (see {@code ProjectAnalyzer#enclosingLambdaParameters}); it is simply
     * not drawn.
     */
    private String bodyValueHint() {
        VisionLoop v = current();
        if (!v.hasParam()) return "This form loops until something is found, so the body is handed nothing.";
        String name = paramName() != null ? paramName() : v.defaultParamName();
        return v.group()
                ? "The body is handed the whole combination as \"" + name + "\" (a Matches) — e.g. "
                        + name + ".has(image)"
                : "The body is handed the hit as \"" + name + "\" (a MatchResult) — e.g. " + name + ".getCenter()";
    }

    /**
     * The name the body's value keeps after a variant switch: the user's own name when they renamed it
     * <em>and</em> the value's type is unchanged (single→single, group→group), otherwise the target's default.
     * Carrying a {@code match} across {@code whileFind} → {@code whileFindAny} would name a {@code Matches}
     * after a {@code MatchResult}.
     */
    private String targetParamName(VisionLoop target) {
        if (!target.hasParam()) return null;
        String current = paramName();
        VisionLoop self = current();
        boolean sameShape = self.hasParam() && self.group() == target.group();
        return sameShape && current != null ? current : target.defaultParamName();
    }

    /** The trailing lambda's declared parameter name as it stands in the source, or {@code null} if it has none. */
    private String paramName() {
        SimpleName declared = declaredParamName();
        return declared != null ? declared.getIdentifier() : null;
    }

    private SimpleName declaredParamName() {
        if (this.astNode instanceof ExpressionStatement es && es.getExpression() instanceof MethodInvocation mi) {
            return LambdaCallHandler.lambdaParamName(mi);
        }
        return null;
    }

    /** {@code → boolean} badge for the {@code if…} variants (they return a boolean); the {@code while…}/{@code until…} forms are void. */
    private void addReturnBadge(SentenceLayoutBuilder sentence) {
        if (VisionLoop.fromMethodName(method).filter(VisionLoop::returnsBoolean).isEmpty()) return;
        Label badge = new Label("→ boolean");
        badge.getStyleClass().add("return-type-badge");
        badge.setTooltip(new Tooltip("This call returns boolean"));
        sentence.addNode(badge);
    }

    /**
     * Adds the explanation (?) button when the sources-jar Javadoc documents this method — a click-open popover
     * with the method summary and the image/action parameter descriptions. No-op when nothing is documented
     * (sources unresolved / offline).
     */
    private void addInfoButton(SentenceLayoutBuilder sentence, CodeEditorService context) {
        String slot = slotType(current()).simpleName();
        String action = current().hasParam() ? "Consumer" : "Runnable";
        var overload = context.getSdkDocs()
                .lookup(SdkType.IMAGE_FINDER.simpleName(), method, List.of(slot, action));
        if (overload.isEmpty()) return;
        SdkDocs.Overload o = overload.get();

        StringBuilder body = new StringBuilder();
        if (o.summary() != null && !o.summary().isBlank()) body.append(o.summary().trim());
        for (SdkDocs.Param p : o.params()) {
            if (p.desc() != null && !p.desc().isBlank()) {
                if (body.length() > 0) body.append("\n\n");
                body.append("• ").append(p.name()).append(" — ").append(p.desc().trim());
            }
        }
        if (body.length() == 0) return;
        sentence.addNode(BlockUIComponents.createInfoButton(method + "()", body.toString()));
    }

    private ResolvedType slotType() {
        return ResolvedType.of(slotType(current()));
    }

    /** The image argument a form takes: a whole group for the {@code …Any}/{@code …All} forms, else one template. */
    private static SdkType slotType(VisionLoop loop) {
        return loop.group() ? SdkType.IMAGE_TEMPLATE_GROUP : SdkType.IMAGE_TEMPLATE;
    }

    /**
     * The form this block renders. A call the enum doesn't know — an {@code ImageFinder} helper added by a
     * newer SDK than Studio's palette — falls back to the single-template shape, which is what the block used
     * to synthesise on the spot: the dropdown then shows the unknown name and the slot stays editable.
     */
    private VisionLoop current() {
        return VisionLoop.fromMethodName(method).orElse(VisionLoop.IF_FIND);
    }
}
