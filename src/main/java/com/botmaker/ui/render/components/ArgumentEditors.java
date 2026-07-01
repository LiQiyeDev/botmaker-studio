package com.botmaker.ui.render.components;

import com.botmaker.core.AbstractCodeBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import org.eclipse.jdt.core.dom.Expression;

/**
 * Chooses the specialized, bot-first editor widget for a call/list argument based on its expected type:
 * an image picker for {@code ImageTemplate}, a region picker for {@code Rect}, a magnifier picker for
 * {@code Point}, and a dropdown for any enum (including the SDK {@code Direction}). Returns {@code null}
 * when no specialized editor applies, so callers fall back to the generic argument pill / expression menu.
 *
 * <p>Shared by {@link com.botmaker.blocks.func.MethodInvocationBlock} and
 * {@link com.botmaker.blocks.expr.ListBlock} so call arguments and list elements behave identically.
 */
public final class ArgumentEditors {

    private ArgumentEditors() {}

    /** The specialized editor for {@code paramType}, or {@code null} to use the generic pill. */
    public static Node editorFor(CodeEditorService context, ExpressionBlock arg, ResolvedType paramType) {
        if (paramType == null) return null;
        if (ImageTemplatePicker.isImageTemplateType(paramType)) return ImageTemplatePicker.create(context, arg);
        if (isType(paramType, "Rect")) return RectPicker.create(context, arg);
        if (isType(paramType, "Point")) return PointPicker.create(context, arg);

        ResolvedType enumType = resolveEnum(context, paramType);
        if (enumType != null) return enumDropdown(context, arg, enumType);
        return null;
    }

    private static boolean isType(ResolvedType type, String simpleName) {
        return type.simpleName().equals(simpleName) || type.qualifiedName().endsWith("." + simpleName);
    }

    /**
     * Resolves {@code paramType} to an enum-aware {@link ResolvedType}, or {@code null} if it isn't an enum.
     * SDK library params arrive as name-only types whose {@code isEnum()} is always false, so we re-resolve
     * them through the project/library index ({@link com.botmaker.suggestions.ProjectAnalyzer#findTypeByName}).
     */
    private static ResolvedType resolveEnum(CodeEditorService context, ResolvedType paramType) {
        if (paramType.isEnum()) return paramType;
        if (context == null || context.getProjectAnalyzer() == null) return null;
        ResolvedType resolved = context.getProjectAnalyzer().findTypeByName(paramType.simpleName());
        return resolved.isEnum() ? resolved : null;
    }

    private static Node enumDropdown(CodeEditorService context, ExpressionBlock arg, ResolvedType enumType) {
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
}
