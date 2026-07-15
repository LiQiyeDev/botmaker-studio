package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.control.Tooltip;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeMethodReference;

/**
 * A Java method reference used as an expression — {@code GameLoop::run}, {@code String::valueOf}.
 *
 * <p>Renders as {@code Target::method}. Clicking navigates to the referenced method when it lives in the
 * user's own project, which is the common case: the generated game-bot entry point is
 * {@code Bot.supervise(GameLoop::run, GoHome::run, Startup::run)}, and each argument is a jump-off point into
 * the file the user actually wants to edit.
 *
 * <p>Before this block existed, method references matched no branch of {@code BlockConverter}'s expression
 * dispatch, so every one of those three arguments was silently dropped and the call rendered as
 * {@code supervise()} — with the real arguments invisible but still present in the source.
 */
public class MethodReferenceBlock extends AbstractExpressionBlock {

    private final String targetName;   // the qualifier, e.g. "GameLoop"
    private final String methodName;   // the referenced method, e.g. "run"

    public MethodReferenceBlock(String id, MethodReference astNode) {
        super(id, astNode);
        this.targetName = readTarget(astNode);
        this.methodName = readMethod(astNode);
    }

    /** The qualifier of the reference ({@code GameLoop} in {@code GameLoop::run}), or "" if unresolvable. */
    public String getTargetName() { return targetName; }

    /** The referenced method name ({@code run} in {@code GameLoop::run}), or "" if unresolvable. */
    public String getMethodName() { return methodName; }

    private static String readTarget(MethodReference ref) {
        if (ref instanceof ExpressionMethodReference emr) return String.valueOf(emr.getExpression());
        if (ref instanceof TypeMethodReference tmr) return String.valueOf(tmr.getType());
        if (ref instanceof SuperMethodReference) return "super";
        return "";
    }

    private static String readMethod(MethodReference ref) {
        if (ref instanceof ExpressionMethodReference emr) return emr.getName().getIdentifier();
        if (ref instanceof TypeMethodReference tmr) return tmr.getName().getIdentifier();
        if (ref instanceof SuperMethodReference smr) return smr.getName().getIdentifier();
        return "";
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        Label target = new Label(targetName);
        target.getStyleClass().add("method-ref-target");

        Label sep = createOperatorLabel("::");

        Label method = new Label(methodName);
        method.getStyleClass().add("method-ref-name");

        HBox box = new HBox(2, target, sep, method);
        box.getStyleClass().add("method-ref-block");

        // Only offer navigation when the qualifier actually resolves to a file in this project — a reference
        // to a JDK/SDK type (String::valueOf) has nothing to open.
        java.nio.file.Path targetFile = resolveProjectFile(context);
        if (targetFile != null) {
            Tooltip.install(box, new Tooltip("Method reference — click to open " + targetName + ".java"));
            box.getStyleClass().add("method-ref-navigable");
            box.setOnMouseClicked(e -> {
                context.switchToFile(targetFile);
                e.consume();
            });
        } else {
            Tooltip.install(box, new Tooltip("Method reference: " + targetName + "::" + methodName));
        }

        return box;
    }

    /** The project file declaring {@link #targetName}, or {@code null} when it isn't one of ours. */
    private java.nio.file.Path resolveProjectFile(CodeEditorService context) {
        if (targetName == null || targetName.isBlank()) return null;
        String fileName = targetName + ".java";
        return context.getState().getAllFiles().stream()
                .map(f -> f.getPath())
                .filter(p -> p.getFileName().toString().equals(fileName))
                .findFirst()
                .orElse(null);
    }
}
