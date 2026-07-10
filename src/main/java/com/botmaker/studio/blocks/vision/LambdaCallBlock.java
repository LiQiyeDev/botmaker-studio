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
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * A body-carrying block for a facade "lambda call" — {@code Class.method(image, m -> { … })}. It renders like a
 * loop: a plain-English header with a fillable image slot plus an indented, droppable body (the lambda body).
 * Backs the {@code ImageFinder.whileFind/ifFind/untilFind} vision helpers <em>and their {@code …Any}/
 * {@code …All} group variants</em>. A ⚙ overload picker switches between the single / any / all forms — which
 * rewrites the method, swaps the image slot between a single {@code ImageTemplate} and an
 * {@code ImageTemplateGroup} (engaging the multi-image group picker), and fixes the lambda parameter
 * (see {@code parser.handlers.LambdaCallHandler#switchVariant}). Header wording is keyed on the method name so a
 * single class serves every variant.
 */
public class LambdaCallBlock extends AbstractStatementBlock implements BlockWithChildren {

    /** One selectable form of a vision loop: its method, whether it takes a group, and whether its lambda has a param. */
    private record Variant(String method, boolean group, boolean hasParam, String label) {}

    private static final List<Variant> VARIANTS = List.of(
            new Variant("whileFind", false, true, "while image is visible"),
            new Variant("whileFindAny", true, true, "while ANY image is visible"),
            new Variant("whileFindAll", true, false, "while ALL images are visible"),
            new Variant("ifFind", false, true, "if image is visible"),
            new Variant("ifFindAny", true, true, "if ANY image is visible"),
            new Variant("ifFindAll", true, false, "if ALL images are visible"),
            new Variant("untilFind", false, false, "until image appears"),
            new Variant("untilFindAny", true, false, "until ANY image appears"),
            new Variant("untilFindAll", true, false, "until ALL images appear"));

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

        // prefix · [image slot · +] · suffix · ⚙  — the change button sits next to the picker, and the ⚙
        // overload picker switches single ↔ any ↔ all.
        var sentence = BlockLayout.sentence()
                .addKeyword(prefixFor(method))
                .addExpressionSlot(image, context, slotType)
                .addNode(changeBtn)
                .addKeyword(suffixFor(method))
                .addNode(createVariantSelector(context));
        addInfoButton(sentence, context);
        Node headerContent = sentence.build();

        container.getChildren().add(BlockLayout.header()
                .withCustomNode(headerContent)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((Statement) this.astNode))
                .build());

        container.getChildren().add(createIndentedBody(body, context, "loop-body"));

        return container;
    }

    /** The vision loop helpers all live on the SDK's {@code ImageFinder} facade — the docs key for the ⓘ button. */
    private static final String SDK_CLASS = "ImageFinder";

    /**
     * Adds the "learn about it" (ⓘ) button when the sources-jar Javadoc documents this loop method — a
     * click-open popover with the method summary and the image/action parameter descriptions. No-op when
     * nothing is documented (sources unresolved / offline).
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

    /** The ⚙ overload picker: lists the current family's single / any / all forms and rewrites on pick. */
    private MenuButton createVariantSelector(CodeEditorService context) {
        MenuButton selector = new MenuButton("⚙");
        selector.setStyle("-fx-font-size: 9px; -fx-padding: 2 4 2 4; -fx-background-radius: 10;");
        selector.setTooltip(new Tooltip("Match a single image, any of a group, or all of a group"));
        for (Variant v : familyVariants()) {
            MenuItem item = new MenuItem(v.label());
            item.setOnAction(e -> context.getCodeEditor()
                    .switchLambdaVariant((Statement) this.astNode, v.method(), v.group(), v.hasParam()));
            selector.getItems().add(item);
        }
        return selector;
    }

    private ResolvedType slotType() {
        return ResolvedType.named(current().group() ? "ImageTemplateGroup" : "ImageTemplate");
    }

    private Variant current() {
        return VARIANTS.stream().filter(v -> v.method().equals(method)).findFirst()
                .orElse(new Variant(method, false, true, method));
    }

    private List<Variant> familyVariants() {
        String family = familyOf(method);
        return VARIANTS.stream().filter(v -> familyOf(v.method()).equals(family)).toList();
    }

    private static String familyOf(String method) {
        if (method.startsWith("whileFind")) return "whileFind";
        if (method.startsWith("ifFind")) return "ifFind";
        if (method.startsWith("untilFind")) return "untilFind";
        return method;
    }

    private static String prefixFor(String method) {
        return switch (familyOf(method)) {
            case "whileFind" -> "while";
            case "ifFind" -> "if";
            case "untilFind" -> "repeat until";
            default -> method + " (";
        };
    }

    private static String suffixFor(String method) {
        return switch (method) {
            case "whileFind", "ifFind" -> "is visible";
            case "whileFindAny", "ifFindAny" -> "(any) is visible";
            case "whileFindAll", "ifFindAll" -> "(all) are visible";
            case "untilFind" -> "appears";
            case "untilFindAny" -> "(any) appears";
            case "untilFindAll" -> "(all) appear";
            default -> ")";
        };
    }
}
