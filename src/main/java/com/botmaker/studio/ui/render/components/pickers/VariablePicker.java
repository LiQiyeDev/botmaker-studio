package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.VariableHolder;
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
 * ({@code Parameters.RETRIES}). Swapping one variable for another was a walk down Change ▸ Activities ▸ tag ▸
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

    private VariablePicker() {}

    /**
     * The field name this slot references, or null when it isn't a variable reference at all.
     *
     * <p>Either holder counts: {@code Activities.MINING} is a flag and {@code Parameters.REST} is a value, and
     * a slot holding one of them is a slot the user may want to point at the other. Which class the
     * <em>replacement</em> is written on is asked of the model, not copied from what is there — see
     * {@link #create}.
     */
    static String referencedVariable(ValueSlot arg) {
        ASTNode node = arg == null ? null : arg.node();
        if (node instanceof QualifiedName qualified) {
            return isHolder(qualified.getQualifier().toString())
                    ? qualified.getName().getIdentifier() : null;
        }
        if (node instanceof FieldAccess access) {
            return isHolder(access.getExpression().toString()) ? access.getName().getIdentifier() : null;
        }
        return null;
    }

    private static boolean isHolder(String qualifier) {
        return VariableHolder.ofClassName(qualifier) != null;
    }

    public static Node create(CodeEditorService context, ValueSlot arg, ResolvedType slotType) {
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
            // The class the picked name is declared on, asked of the model rather than taken from the
            // qualifier already in the slot: swapping a flag for a value moves the reference between the two
            // generated classes, and keeping the old qualifier would write Activities.REST.
            context.getCodeEditor().replaceWithFieldReference(
                    arg.node(), context.getProjectAnalyzer().variableQualifier(picked), picked);
        });
        return combo;
    }

    /**
     * The {@link SpecialTypePicker} entry: matches a slot already holding {@code Parameters.<name>} or
     * {@code Activities.<name>}.
     */
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
