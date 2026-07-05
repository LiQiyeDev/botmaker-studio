package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;

public class DefaultValueHelper {

    /**
     * Creates a default expression for primitive types and String.
     */
    public static Expression createDefaultForPrimitive(AST ast, ResolvedType type) {
        if (type == null) return null;

        if (type.isNumeric()) {
            String name = type.simpleName();
            // Check for floating point
            if ("double".equalsIgnoreCase(name) || "float".equalsIgnoreCase(name) ||
                    "Double".equals(name) || "Float".equals(name)) {
                return ast.newNumberLiteral("0.0");
            }
            return ast.newNumberLiteral("0");
        }

        if (type.isBoolean()) {
            return ast.newBooleanLiteral(false);
        }

        if (type.isString() || "char".equals(type.simpleName()) || "Character".equals(type.simpleName())) {
            if ("char".equals(type.simpleName()) || "Character".equals(type.simpleName())) {
                CharacterLiteral literal = ast.newCharacterLiteral();
                literal.setCharValue('a');
                return literal;
            }
            StringLiteral str = ast.newStringLiteral();
            str.setLiteralValue("");
            return str;
        }

        return null;
    }

    public static Expression createDefaultForPrimitive(AST ast, String typeName) {
        return createDefaultForPrimitive(ast, ResolvedType.named(typeName));
    }

    public static boolean isNumeric(String type) { return ResolvedType.named(type).isNumeric(); }
    public static boolean isBoolean(String type) { return ResolvedType.named(type).isBoolean(); }
}