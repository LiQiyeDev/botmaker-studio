package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.NodeCreator;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;

public class InstantiationHandler {

    public static String updateInstantiation(CompilationUnit cu, String originalCode,
                                             ClassInstanceCreation node,
                                             ResolvedType newType,
                                             List<ResolvedType> newParamTypes,
                                             ProjectState state) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        if (newType != null && !newType.simpleName().equals(node.getType().toString())) {
            Type newTypeNode = ProjectAnalyzer.createTypeNode(ast, newType);
            rewriter.replace(node.getType(), newTypeNode, null);
            ImportManager.addImport(cu, rewriter, newType, state);
        }

        if (newParamTypes != null) {
            syncArguments(ast, rewriter, node, newParamTypes);
        }

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String replaceWithInstantiation(CompilationUnit cu, String originalCode,
                                                  Expression toReplace,
                                                  ResolvedType type,
                                                  List<ResolvedType> paramTypes,
                                                  ProjectState state) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        ImportManager.addImport(cu, rewriter, type, state);

        ClassInstanceCreation creation = ast.newClassInstanceCreation();
        creation.setType(ProjectAnalyzer.createTypeNode(ast, type));

        if (paramTypes != null) {
            for (ResolvedType pType : paramTypes) {
                Expression arg = NodeCreator.createDefaultInitializer(ast, pType);
                creation.arguments().add(arg);
            }
        }

        rewriter.replace(toReplace, creation, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static void syncArguments(AST ast, ASTRewrite rewriter, ClassInstanceCreation node, List<ResolvedType> targetTypes) {
        ListRewrite argsRewrite = rewriter.getListRewrite(node, ClassInstanceCreation.ARGUMENTS_PROPERTY);
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
                Expression defaultExpr = NodeCreator.createDefaultInitializer(ast, type);
                argsRewrite.insertLast(defaultExpr, null);
            }
        }
    }
}