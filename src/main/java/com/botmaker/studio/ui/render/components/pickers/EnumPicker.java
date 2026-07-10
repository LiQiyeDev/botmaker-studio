package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import org.eclipse.jdt.core.dom.Expression;

/**
 * A dropdown editor for any enum-typed argument (including SDK enums like {@code Direction}). Picking a
 * constant rewrites the backing expression to {@code EnumType.CONSTANT} via
 * {@link com.botmaker.studio.parser.CodeEditor#replaceWithEnumConstant}.
 *
 * <p>(Formerly the inline {@code enumDropdown} in {@code ArgumentEditors}; now a first-class
 * {@link SpecialTypePicker}.)
 */
public final class EnumPicker {

    private EnumPicker() {}

    /**
     * Resolves {@code paramType} to an enum-aware {@link ResolvedType}, or {@code null} if it isn't an
     * enum. SDK library params arrive as name-only types whose {@code isEnum()} is always false, so we
     * re-resolve them through the project/library index
     * ({@link com.botmaker.studio.suggestions.ProjectAnalyzer#findTypeByName}).
     */
    public static ResolvedType resolveEnum(CodeEditorService context, ResolvedType paramType) {
        ResolvedType resolved = resolveEnumType(context, paramType);
        // Only claim the arg when the enum actually resolved its constants. An enum whose constants can't be
        // indexed (e.g. an SDK MouseButton not in the type index yet) would otherwise render an *empty*
        // dropdown — picking nothing wipes the argument to empty parens. Returning null there lets the caller
        // fall back to the generic pill, which preserves the existing value.
        return (resolved != null && !resolved.enumConstants().isEmpty()) ? resolved : null;
    }

    private static ResolvedType resolveEnumType(CodeEditorService context, ResolvedType paramType) {
        if (paramType == null) return null;
        if (paramType.isEnum()) return paramType;
        if (context == null || context.getProjectAnalyzer() == null) return null;
        ResolvedType resolved = context.getProjectAnalyzer().findTypeByName(paramType.simpleName());
        return resolved.isEnum() ? resolved : null;
    }

    public static Node create(CodeEditorService context, ExpressionBlock arg, ResolvedType enumType) {
        ComboBox<String> combo = new ComboBox<>();
        combo.getStyleClass().add("enum-arg-dropdown");
        combo.getItems().addAll(enumType.enumConstants());
        combo.setValue(currentConstant(arg));
        combo.setStyle("-fx-font-size: 11px;");
        combo.setOnAction(e -> {
            String constant = combo.getValue();
            if (constant == null) return;
            context.getCodeEditor().replaceWithEnumConstant(
                    (Expression) ((AbstractCodeBlock) arg).getAstNode(), enumType.simpleName(), constant);
        });
        return combo;
    }

    /** Current constant name from an enum reference like {@code Direction.NORTH} or {@code NORTH}, else null. */
    private static String currentConstant(ExpressionBlock arg) {
        String text = ((AbstractCodeBlock) arg).getAstNode().toString();
        int dot = text.lastIndexOf('.');
        String name = dot >= 0 ? text.substring(dot + 1) : text;
        return name.isBlank() ? null : name;
    }

    /** The {@link SpecialTypePicker} entry: matches any enum type, builds the dropdown. */
    public static SpecialTypePicker asSpecialType() {
        return new SpecialTypePicker() {
            @Override public boolean matches(PickerContext ctx) {
                return resolveEnum(ctx.context(), ctx.paramType()) != null;
            }
            @Override public Node create(PickerContext ctx) {
                ResolvedType enumType = resolveEnum(ctx.context(), ctx.paramType());
                return enumType == null ? null : EnumPicker.create(ctx.context(), ctx.arg(), enumType);
            }
        };
    }
}
