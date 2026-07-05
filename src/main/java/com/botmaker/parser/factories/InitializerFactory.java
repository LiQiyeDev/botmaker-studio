package com.botmaker.parser.factories;

import com.botmaker.parser.handlers.LambdaCallHandler;
import com.botmaker.parser.helpers.DefaultValueHelper;
import com.botmaker.project.ProjectState;
import com.botmaker.types.ResolvedType;
import com.botmaker.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import java.util.List;
import java.util.Map;

public class InitializerFactory {

    /**
     * Common functional interfaces → SAM (single-abstract-method) parameter count. Keyed on the SIMPLE name so it
     * matches whether the type resolved from the library index or is name-only (the JDK {@code java.util.function.*}
     * types are not in our index). A default value for one of these is a block-bodied lambda (not {@code new I()},
     * which is uncompilable) so it round-trips into an editable {@code LambdaCallBlock}.
     */
    private static final Map<String, Integer> FUNCTIONAL_INTERFACE_ARITY = Map.ofEntries(
            Map.entry("Runnable", 0),
            Map.entry("Supplier", 0), Map.entry("Consumer", 1), Map.entry("Predicate", 1),
            Map.entry("Function", 1), Map.entry("UnaryOperator", 1),
            Map.entry("IntConsumer", 1), Map.entry("IntSupplier", 0), Map.entry("IntPredicate", 1),
            Map.entry("BiConsumer", 2), Map.entry("BiFunction", 2), Map.entry("BiPredicate", 2),
            Map.entry("BinaryOperator", 2), Map.entry("Comparator", 2));

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

        // Functional interface (Consumer/Runnable/…): an empty block-bodied lambda, so the call round-trips into an
        // editable LambdaCallBlock instead of an uncompilable `new Consumer<>()`. Strip generics off the qualified
        // name first — a name-only Consumer<…> otherwise yields the garbled simple name "MatchResult>".
        String raw = richType.leafType().qualifiedName();
        int generic = raw.indexOf('<');
        if (generic >= 0) raw = raw.substring(0, generic);
        String simpleName = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        Integer arity = FUNCTIONAL_INTERFACE_ARITY.get(simpleName);
        if (arity != null) {
            List<String> params = arity == 1 ? List.of("it")
                    : java.util.stream.IntStream.range(0, arity).mapToObj(i -> "arg" + i).toList();
            return LambdaCallHandler.emptyBlockLambda(ast, params);
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