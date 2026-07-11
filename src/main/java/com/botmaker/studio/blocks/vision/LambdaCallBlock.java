package com.botmaker.studio.blocks.vision;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.palette.SdkDocs;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
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
 * fixes the lambda parameter ({@code Consumer<MatchResult>} vs {@code Runnable}) — rewriting <em>in place</em> so
 * the user's body survives the switch. (The generic method-invocation overload path can't be reused here: it
 * syncs arguments positionally and would clobber the trailing lambda.)
 */
public class LambdaCallBlock extends AbstractStatementBlock implements BlockWithChildren {

    /** One selectable form of a vision loop: its method, whether it takes a group, and whether its lambda has a param. */
    private record Variant(String method, boolean group, boolean hasParam) {}

    private static final List<Variant> VARIANTS = List.of(
            new Variant("ifFind", false, true),
            new Variant("ifFindAny", true, true),
            new Variant("ifFindAll", true, false),
            new Variant("whileFind", false, true),
            new Variant("whileFindAny", true, true),
            new Variant("whileFindAll", true, false),
            new Variant("untilFind", false, false),
            new Variant("untilFindAny", true, false),
            new Variant("untilFindAll", true, false));

    /** The vision loop helpers all live on the SDK's {@code ImageFinder} facade. */
    private static final String SDK_CLASS = "ImageFinder";

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

        Label classChip = new Label(SDK_CLASS);
        classChip.getStyleClass().add("sdk-class-selector");
        classChip.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        sentence.addNode(classChip);

        sentence.addLabel(".")
                .addNode(createMethodSelector(context))
                .addLabel("(")
                .addExpressionSlot(image, context, slotType)
                .addNode(changeBtn)
                .addLabel(")");

        addReturnBadge(sentence);
        addInfoButton(sentence, context);

        HBox headerContent = sentence.build();
        headerContent.getStyleClass().add("sdk-call-block");

        container.getChildren().add(BlockLayout.header()
                .withCustomNode(headerContent)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((Statement) this.astNode))
                .build());

        container.getChildren().add(createIndentedBody(body, context, "loop-body"));

        return container;
    }

    /**
     * The method dropdown — the standard SDK-block affordance, here listing the nine {@code ImageFinder} lambda
     * helpers. Picking one rewrites the call in place (preserving the body) via {@code switchLambdaVariant}.
     */
    private ComboBox<String> createMethodSelector(CodeEditorService context) {
        ComboBox<String> selector = new ComboBox<>();
        for (Variant v : VARIANTS) selector.getItems().add(v.method());
        selector.setValue(method);
        selector.setEditable(false);
        selector.setStyle("-fx-font-size: 11px; -fx-pref-width: 130px; -fx-font-weight: bold;");
        selector.setTooltip(new Tooltip(
                "if / while / until  ×  a single image, ANY of a group, or ALL of a group"));
        selector.setOnAction(e -> {
            String picked = selector.getValue();
            if (picked == null || picked.equals(method)) return;
            VARIANTS.stream().filter(v -> v.method().equals(picked)).findFirst().ifPresent(v ->
                    context.getCodeEditor()
                            .switchLambdaVariant((Statement) this.astNode, v.method(), v.group(), v.hasParam()));
        });
        return selector;
    }

    /** {@code → boolean} badge for the {@code if…} variants (they return a boolean); the {@code while…}/{@code until…} forms are void. */
    private void addReturnBadge(SentenceLayoutBuilder sentence) {
        if (!method.startsWith("if")) return;
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
        String slot = current().group() ? "ImageTemplateGroup" : "ImageTemplate";
        String action = current().hasParam() ? "Consumer" : "Runnable";
        var overload = context.getSdkDocs().lookup(SDK_CLASS, method, List.of(slot, action));
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
        return ResolvedType.named(current().group() ? "ImageTemplateGroup" : "ImageTemplate");
    }

    private Variant current() {
        return VARIANTS.stream().filter(v -> v.method().equals(method)).findFirst()
                .orElse(new Variant(method, false, true));
    }
}
