package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;

public class InstantiationHandler {

    public static String updateInstantiation(EditContext ctx, String originalCode,
                                             ClassInstanceCreation node,
                                             ResolvedType newType,
                                             List<ResolvedType> newParamTypes) {

        if (newType != null && !newType.simpleName().equals(node.getType().toString())) {
            Type newTypeNode = ProjectAnalyzer.createTypeNode(ctx.ast(), newType);
            ctx.rewriter().replace(node.getType(), newTypeNode, null);
            ctx.addImportForType(newType);
        }

        if (newParamTypes != null) {
            syncArguments(ctx, node, newParamTypes);
        }

        return ctx.applyTo(originalCode);
    }

    public static String replaceWithInstantiation(EditContext ctx, String originalCode,
                                                  Expression toReplace,
                                                  ResolvedType type,
                                                  List<ResolvedType> paramTypes) {
        AST ast = ctx.ast();

        ctx.addImportForType(type);

        ClassInstanceCreation creation = ast.newClassInstanceCreation();
        creation.setType(ProjectAnalyzer.createTypeNode(ast, type));

        if (paramTypes != null) {
            for (ResolvedType pType : paramTypes) {
                creation.arguments().add(InitializerFactory.createDefaultInitializer(ctx, pType));
                ctx.addImportForType(pType);
            }
        }

        ctx.rewriter().replace(toReplace, creation, null);
        return ctx.applyTo(originalCode);
    }

    private static void syncArguments(EditContext ctx, ClassInstanceCreation node,
                                      List<ResolvedType> targetTypes) {
        ListRewrite argsRewrite = ctx.rewriter().getListRewrite(node, ClassInstanceCreation.ARGUMENTS_PROPERTY);
        List<?> currentArgs = node.arguments();
        int currentCount = currentArgs.size();
        int targetCount = targetTypes.size();

        if (currentCount > targetCount) {
            for (int i = currentCount - 1; i >= targetCount; i--) {
                argsRewrite.remove((ASTNode) currentArgs.get(i), null);
            }
        } else if (currentCount < targetCount) {
            for (int i = currentCount; i < targetCount; i++) {
                ResolvedType type = targetTypes.get(i);
                Expression defaultExpr = InitializerFactory.createDefaultInitializer(ctx, type);
                argsRewrite.insertLast(defaultExpr, null);
                ctx.addImportForType(type);
            }
        }
    }
}
