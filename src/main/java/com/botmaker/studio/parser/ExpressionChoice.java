package com.botmaker.studio.parser;

import com.botmaker.studio.types.ResolvedType;

import java.util.List;

/**
 * A user's selection from the expression-insertion menu that is richer than a plain
 * {@link com.botmaker.studio.palette.ExpressionType} — it carries the specific method, constructor,
 * enum constant or variable the user picked. Produced by the expression menu factory and consumed by
 * {@link CodeEditor} to drive the corresponding rewrite.
 *
 * <p>Sealed so the editor can dispatch with an exhaustive {@code switch}.
 */
public sealed interface ExpressionChoice
        permits ExpressionChoice.Method, ExpressionChoice.Constructor,
                ExpressionChoice.EnumConstant, ExpressionChoice.Variable, ExpressionChoice.Field,
                ExpressionChoice.NewVariable, ExpressionChoice.RawExpression {

    /** Call {@code methodName} on {@code scope} (a variable name or a type name for statics). */
    record Method(String scope, String methodName, List<ResolvedType> paramTypes, boolean isStatic)
            implements ExpressionChoice {}

    /** Read the field {@code fieldName} off {@code scope} ({@code Class.FIELD} for statics, {@code var.field}). */
    record Field(String scope, String fieldName) implements ExpressionChoice {}

    /** {@code new typeName(...)} with the given constructor parameter types. */
    record Constructor(String typeName, List<ResolvedType> paramTypes) implements ExpressionChoice {}

    /** The enum constant {@code typeName.constantName}. */
    record EnumConstant(String typeName, String constantName) implements ExpressionChoice {}

    /** A reference to the in-scope variable {@code variableName}. */
    record Variable(String variableName) implements ExpressionChoice {}

    /**
     * Declare a new local variable of {@code type} named {@code name} just before the current statement, then
     * reference it in the slot. Lets the user create (e.g.) a {@code Direction} variable inline from the
     * Variables submenu.
     */
    record NewVariable(ResolvedType type, String name) implements ExpressionChoice {}

    /**
     * Insert a ready-made Java expression snippet verbatim (parsed into AST). Used by the capture-source
     * picker, which emits a fully-qualified inline call (e.g. {@code …capture.CaptureSource.screen()}).
     */
    record RawExpression(String code) implements ExpressionChoice {}
}
