package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.PrimitiveKind;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;

public class DefaultValueHelper {

    /**
     * Creates a default expression for primitive types and String.
     */
    public static Expression createDefaultForPrimitive(AST ast, ResolvedType type) {
        if (type == null) return null;

        if (type.isNumeric()) {
            return ast.newNumberLiteral(isFloatingPoint(type) ? "0.0" : "0");
        }

        if (type.isBoolean()) {
            return ast.newBooleanLiteral(false);
        }

        if (isCharacter(type)) {
            CharacterLiteral literal = ast.newCharacterLiteral();
            literal.setCharValue('a');
            return literal;
        }

        if (type.isString()) {
            StringLiteral str = ast.newStringLiteral();
            str.setLiteralValue("");
            return str;
        }

        return null;
    }

    private static boolean isFloatingPoint(ResolvedType type) {
        return type.is(PrimitiveKind.DOUBLE) || type.is(PrimitiveKind.FLOAT)
                || type.is(JdkType.DOUBLE) || type.is(JdkType.FLOAT);
    }

    private static boolean isCharacter(ResolvedType type) {
        return type.is(PrimitiveKind.CHAR) || type.is(JdkType.CHARACTER);
    }

    public static Expression createDefaultForPrimitive(AST ast, String typeName) {
        return createDefaultForPrimitive(ast, ResolvedType.named(typeName));
    }

    public static boolean isNumeric(String type) { return ResolvedType.named(type).isNumeric(); }
    public static boolean isBoolean(String type) { return ResolvedType.named(type).isBoolean(); }
}
