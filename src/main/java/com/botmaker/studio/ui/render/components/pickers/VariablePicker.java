package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.QualifiedName;

/**
 * A dropdown over the project's variables, shown inline on a slot that already holds one
 * ({@code Activities.RETRIES}). Swapping one variable for another was a walk down Change ▸ Activities ▸ tag ▸
 * name to reach a list the slot could have been showing all along — the same picker the expression submenu
 * offers, one click away instead of four.
 *
 * <p>It claims only slots that <em>already</em> reference a variable. A picker that claimed every slot a
 * variable could fill would replace the generic pill on every {@code int} in the project, which is a different
 * (and much larger) decision than "let me change which variable this is".
 *
 * <p>Offered variables are filtered to the slot's type, so a {@code boolean} slot lists the flags. The one
 * already in the slot is always listed even when it doesn't match — the type it was chosen for may have moved,
 * and a dropdown that omits its own current value reads as though the code says something it doesn't.
 */
public final class VariablePicker {

    /** The generated holder class every project variable is a static field of. */
    private static final String HOLDER = "Activities";

    private VariablePicker() {}

    /** The field name this slot references, or null when it isn't a variable reference at all. */
    static String referencedVariable(ExpressionBlock arg) {
        if (!(arg instanceof AbstractCodeBlock block)) return null;
        ASTNode node = block.getAstNode();
        if (node instanceof QualifiedName qualified) {
            return HOLDER.equals(qualified.getQualifier().toString()) ? qualified.getName().getIdentifier() : null;
        }
        if (node instanceof FieldAccess access) {
            return HOLDER.equals(access.getExpression().toString()) ? access.getName().getIdentifier() : null;
        }
        return null;
    }

    public static Node create(CodeEditorService context, ExpressionBlock arg, ResolvedType slotType) {
        String current = referencedVariable(arg);
        ComboBox<String> combo = new ComboBox<>();
        combo.getStyleClass().add("block-selector");
        combo.setTooltip(new Tooltip("Which project variable this is — edit them in Project ▸ Parameters"));
        for (ActivityVariable variable : context.getProjectAnalyzer().getActivityVariables(slotType)) {
            combo.getItems().add(variable.name());
        }
        if (current != null && !combo.getItems().contains(current)) combo.getItems().add(current);
        combo.setValue(current);
        combo.setOnAction(e -> {
            String picked = combo.getValue();
            if (picked == null || picked.equals(current)) return;
            context.getCodeEditor().replaceWithFieldReference(
                    (Expression) ((AbstractCodeBlock) arg).getAstNode(), HOLDER, picked);
        });
        return combo;
    }

    /** The {@link SpecialTypePicker} entry: matches a slot already holding {@code Activities.<name>}. */
    public static SpecialTypePicker asSpecialType() {
        return new SpecialTypePicker() {
            @Override public boolean matches(PickerContext ctx) {
                return ctx.context() != null && ctx.context().getProjectAnalyzer() != null
                        && referencedVariable(ctx.arg()) != null;
            }
            @Override public Node create(PickerContext ctx) {
                return VariablePicker.create(ctx.context(), ctx.arg(), ctx.paramType());
            }
        };
    }
}
