// FILE: rs\bgroi\Documents\dev\IntellijProjects\BotMaker\src\main\java\com\botmaker\parser\factories\InitializerFactory.java
package com.botmaker.parser.factories;

import com.botmaker.parser.helpers.DefaultValueHelper;
import com.botmaker.project.ProjectState;
import com.botmaker.types.ResolvedType;
import com.botmaker.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import java.util.List;

public class InitializerFactory {

    public static Expression createDefaultInitializer(AST ast, ResolvedType type, CompilationUnit cu, ProjectState state) {
        if (type == null) return ast.newNullLiteral();

        // ENRICHMENT: If the type isn't binding-backed, try to find the rich type in the project
        ResolvedType richType = type;
        if (!(type instanceof ResolvedType.Bound) && !type.isPrimitive() && state != null) {
            ResolvedType found = ProjectAnalyzer.findTypeInProject(state, type.leafType().simpleName());
            // If found, re-apply array dimensions
            if (!found.isUnknown()) {
                richType = found.asArray(type.arrayDimensions());
            }
        }

        if (richType.isArray()) {
            return createArrayInitializer(ast, richType, java.util.Collections.emptyList(), cu, state);
        }

        // 1. Enum Handling (Now works because richType has binding)
        if (richType.isEnum()) {
            List<String> constants = richType.enumConstants();
            String constName = constants.isEmpty() ? "VALUE" : constants.getFirst();

            return ast.newQualifiedName(
                    ast.newSimpleName(richType.simpleName()),
                    ast.newSimpleName(constName)
            );
        }

        // 2. Primitives & Strings
        Expression primitiveDefault = DefaultValueHelper.createDefaultForPrimitive(ast, richType);
        if (primitiveDefault != null) {
            return primitiveDefault;
        }

        // 3. Objects
        if (!richType.isUnknown()) {
            ClassInstanceCreation cic = ast.newClassInstanceCreation();
            cic.setType(ProjectAnalyzer.createTypeNode(ast, richType));
            return cic;
        }

        return ast.newNullLiteral();
    }

    // Overload for backward compatibility
    public static Expression createDefaultInitializer(AST ast, ResolvedType type) {
        return createDefaultInitializer(ast, type, null, null);
    }

    public static Expression createArrayInitializer(AST ast, ResolvedType type, List<Expression> valuesToPreserve, CompilationUnit cu, ProjectState state) {
        int dimensions = type.arrayDimensions();
        ResolvedType leafType = type.leafType();

        if (dimensions == 0) {
            return createDefaultInitializer(ast, leafType, cu,state);
        }

        ArrayCreation arrayCreation = ast.newArrayCreation();
        Type elementType = ProjectAnalyzer.createTypeNode(ast, type);
        arrayCreation.setType((ArrayType) elementType);

        ArrayInitializer initializer = createNestedArrayInitializer(ast, leafType, dimensions, valuesToPreserve, cu, state);
        arrayCreation.setInitializer(initializer);

        return arrayCreation;
    }

    private static ArrayInitializer createNestedArrayInitializer(AST ast, ResolvedType leafType, int dimensions,
                                                          List<Expression> valuesToPreserve, CompilationUnit cu, ProjectState state) {
        ArrayInitializer initializer = ast.newArrayInitializer();

        if (dimensions == 1) {
            if (valuesToPreserve != null && !valuesToPreserve.isEmpty()) {
                for (Expression value : valuesToPreserve) {
                    initializer.expressions().add(ASTNode.copySubtree(ast, value));
                }
            } else {
                Expression defaultValue = createDefaultInitializer(ast, leafType, cu,state);
                initializer.expressions().add(defaultValue);
            }
        } else {
            ArrayInitializer subArray = createNestedArrayInitializer(ast, leafType, dimensions - 1, valuesToPreserve, cu,state);
            initializer.expressions().add(subArray);
        }

        return initializer;
    }

    public static Expression createRecursiveListInitializer(AST ast, String typeName, CompilationUnit cu,
                                                     org.eclipse.jdt.core.dom.rewrite.ASTRewrite rewriter,
                                                     List<Expression> leavesToPreserve, ProjectState state) {
        return createArrayInitializer(ast, ResolvedType.named(typeName), leavesToPreserve, cu,state);
    }
}