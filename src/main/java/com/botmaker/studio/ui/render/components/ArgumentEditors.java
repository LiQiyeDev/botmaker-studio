package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import javafx.scene.Node;

/**
 * Thin facade over {@link PickerRegistry}: chooses the specialized, bot-first editor widget for a
 * call/list argument (image picker for {@code ImageTemplate}, group picker for
 * {@code ImageTemplateGroup}, region picker for {@code Rect}, magnifier picker for {@code Point}, a
 * dropdown for any enum, and the method-specific Steam/executable pickers). Returns {@code null} when
 * no specialized editor applies, so callers fall back to the generic argument pill / expression menu.
 *
 * <p>Detection + dispatch now live in {@link PickerRegistry}; this class only adapts the caller
 * arguments into a {@link PickerContext}. Shared by
 * {@link com.botmaker.studio.blocks.func.MethodInvocationBlock} and
 * {@link com.botmaker.studio.blocks.expr.ListBlock} so call arguments and list elements behave identically.
 */
public final class ArgumentEditors {

    private ArgumentEditors() {}

    /** The specialized editor for {@code paramType}, or {@code null} to use the generic pill. */
    public static Node editorFor(CodeEditorService context, ExpressionBlock arg, ResolvedType paramType) {
        return editorFor(context, arg, paramType, null, null, -1);
    }

    /**
     * The specialized editor for an argument, or {@code null} to use the generic pill. {@code className} /
     * {@code methodName} identify the enclosing call so method-specific editors (e.g. the Steam game picker
     * for {@code Game.launchSteam}) can be chosen; {@code argIndex} is the argument's position in that call
     * so index-specific editors (e.g. the program path vs. launch-option slots of {@code Game.launch}) can
     * be distinguished. Pass {@code null}/{@code null}/{@code -1} when there is no call context.
     */
    public static Node editorFor(CodeEditorService context, ExpressionBlock arg, ResolvedType paramType,
                                 String className, String methodName, int argIndex) {
        return PickerRegistry.pickerNodeFor(new PickerContext(context, arg, paramType, className, methodName, argIndex));
    }
}
