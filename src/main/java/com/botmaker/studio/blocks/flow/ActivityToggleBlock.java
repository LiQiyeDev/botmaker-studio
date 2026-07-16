package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.util.ArrayList;
import java.util.List;

/**
 * A fixed-label statement that turns an activity on or off at runtime by name — the generated
 * {@code Activity.disable("Mining");} / {@code Activity.enable("Mining");} static call (see the SDK's
 * {@code Activity.disable(String)} / {@code Activity.enable(String)}). Valid anywhere (an activity's
 * {@code run()}, {@code GoHome}, {@code Startup}, or one activity toggling another), unlike a bare
 * {@code disable()} self-call which only compiles inside an {@code Activity} and only targets itself.
 *
 * <p>Renders the target as an activity-name picker (a {@link ComboBox} of the project's activities); changing
 * it rewrites the string-literal argument via {@code CodeEditor.replaceLiteralValue}.
 */
public class ActivityToggleBlock extends AbstractStatementBlock {

    private final boolean enable;
    private final String activityName;

    public ActivityToggleBlock(String id, ExpressionStatement astNode, boolean enable) {
        super(id, astNode);
        this.enable = enable;
        this.activityName = readName(astNode);
    }

    /** The string-literal argument of {@code Activity.disable("X")}, or "" if the shape is unexpected. */
    private static String readName(ExpressionStatement stmt) {
        if (stmt.getExpression() instanceof MethodInvocation mi
                && !mi.arguments().isEmpty()
                && mi.arguments().get(0) instanceof StringLiteral lit) {
            return lit.getLiteralValue();
        }
        return "";
    }

    private StringLiteral literalNode() {
        if (((ExpressionStatement) astNode).getExpression() instanceof MethodInvocation mi
                && !mi.arguments().isEmpty()
                && mi.arguments().get(0) instanceof StringLiteral lit) {
            return lit;
        }
        return null;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        Node target = isReadOnly() ? readOnlyName() : picker(context);
        return BlockLayout.header()
                .withKeyword(enable ? "enable activity" : "disable activity")
                .withCustomNode(target)
                .withDeleteButton(deleteAction(context))
                .build();
    }

    private Label readOnlyName() {
        Label label = new Label(activityName.isEmpty() ? "(none)" : activityName);
        label.getStyleClass().add("static-value-label");
        return label;
    }

    private ComboBox<String> picker(CodeEditorService context) {
        ComboBox<String> combo = new ComboBox<>();
        List<String> names = activityNames(context);
        // Keep the current selection visible even if it names an activity that no longer exists.
        if (!activityName.isEmpty() && !names.contains(activityName)) names.add(0, activityName);
        combo.getItems().addAll(names);
        combo.setValue(activityName.isEmpty() ? null : activityName);
        combo.setPromptText("pick an activity");
        combo.setOnAction(e -> {
            String picked = combo.getValue();
            StringLiteral lit = literalNode();
            if (picked != null && !picked.equals(activityName) && lit != null) {
                context.getCodeEditor().replaceLiteralValue(lit, picked);
            }
        });
        return combo;
    }

    private static List<String> activityNames(CodeEditorService context) {
        List<String> names = new ArrayList<>();
        if (context.getState().getActivities() != null) {
            for (ActivityDefinition a : context.getState().getActivities().activities()) {
                names.add(a.name());
            }
        }
        return names;
    }
}
