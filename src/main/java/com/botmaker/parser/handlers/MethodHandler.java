package com.botmaker.parser.handlers;

import com.botmaker.parser.ExpressionChoice;

import com.botmaker.core.BodyBlock;
import com.botmaker.parser.NodeCreator;
import com.botmaker.parser.helpers.AstRewriteHelper;
import com.botmaker.project.ProjectState;
import com.botmaker.palette.ExpressionType;
import com.botmaker.types.ResolvedType;
import com.botmaker.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;

public class MethodHandler {

    public static String addMethodToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl,
                                   String methodName, String returnType, int index) {
        // Wrapper for string-based calls
        return addMethodToClass(cu, originalCode, typeDecl, methodName, ResolvedType.named(returnType), index);
    }
    public static String replaceWithMethodCall(CompilationUnit cu, String originalCode, Expression toReplace, ExpressionChoice.Method choice, ProjectState state) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        MethodInvocation mi = createMethodInvocation(ast, choice);

        rewriter.replace(toReplace, mi, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String addMethodCallStatement(CompilationUnit cu, String originalCode, BodyBlock targetBody, ExpressionChoice.Method choice, int index) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        MethodInvocation mi = createMethodInvocation(ast, choice);
        ExpressionStatement stmt = ast.newExpressionStatement(mi);

        // Insert into body
        ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        listRewrite.insertAt(stmt, index, null);

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /** Builds a {@code MethodInvocation} from a menu pick; shared by NodeCreator's unified expression builder. */
    public static MethodInvocation createMethodInvocation(AST ast, ExpressionChoice.Method choice) {
        MethodInvocation mi = ast.newMethodInvocation();

        // Scope
        if (choice.scope() != null && !choice.scope().isEmpty()) {
            mi.setExpression(ast.newSimpleName(choice.scope()));
        }

        // Name
        mi.setName(ast.newSimpleName(choice.methodName()));

        // Arguments (Defaults)
        for (ResolvedType paramType : choice.paramTypes()) {
            mi.arguments().add(NodeCreator.createDefaultInitializer(ast, paramType));
        }
        return mi;
    }

    public static String addMethodToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl,
                                   String methodName, ResolvedType returnType, int index) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        MethodDeclaration newMethod = ast.newMethodDeclaration();
        newMethod.setName(ast.newSimpleName(methodName));
        newMethod.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        newMethod.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));

        if (returnType.isVoid()) {
            newMethod.setReturnType2(ast.newPrimitiveType(PrimitiveType.VOID));
        } else {
            newMethod.setReturnType2(ProjectAnalyzer.createTypeNode(ast, returnType));
        }

        Block body = ast.newBlock();
        newMethod.setBody(body);

        ListRewrite listRewrite = rewriter.getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        listRewrite.insertAt(newMethod, index, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String deleteMethodFromClass(CompilationUnit cu, String originalCode, MethodDeclaration method) {
        return AstRewriteHelper.removeNode(cu, originalCode, method);
    }

    public static String renameMethod(CompilationUnit cu, String originalCode, MethodDeclaration method, String newName) {
        return AstRewriteHelper.renameSimpleName(cu, originalCode, method.getName(), newName);
    }

    public static String moveBodyDeclaration(CompilationUnit cu, String originalCode, BodyDeclaration declToMove,
                                      TypeDeclaration targetType, int targetIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        ListRewrite listRewrite = rewriter.getListRewrite(targetType, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        ASTNode moveTarget = rewriter.createMoveTarget(declToMove);
        listRewrite.insertAt(moveTarget, targetIndex, null);

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String updateMethodInvocation(CompilationUnit cu, String originalCode,
                                         MethodInvocation mi, String newScope,
                                         String newMethodName, List<ResolvedType> newParamTypes) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        // Update scope
        if (newScope == null || newScope.isEmpty() || newScope.equals("Local")) {
            if (mi.getExpression() != null) {
                rewriter.remove(mi.getExpression(), null);
            }
        } else {
            SimpleName newScopeNode = ast.newSimpleName(newScope);
            if (mi.getExpression() == null) {
                rewriter.set(mi, MethodInvocation.EXPRESSION_PROPERTY, newScopeNode, null);
            } else {
                rewriter.replace(mi.getExpression(), newScopeNode, null);
            }
        }

        // Update method name
        if (!mi.getName().getIdentifier().equals(newMethodName)) {
            rewriter.replace(mi.getName(), ast.newSimpleName(newMethodName), null);
        }

        syncArguments(ast,rewriter, mi, newParamTypes);

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String setMethodReturnType(CompilationUnit cu, String originalCode,
                                      MethodDeclaration method, ResolvedType newType) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        Type newTypeNode = newType.isVoid() ?
                ast.newPrimitiveType(PrimitiveType.VOID) :
                ProjectAnalyzer.createTypeNode(ast, newType);

        rewriter.replace(method.getReturnType2(), newTypeNode, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String addParameterToMethod(CompilationUnit cu, String originalCode,
                                       MethodDeclaration method, ResolvedType type, String paramName) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite listRewrite = rewriter.getListRewrite(method, MethodDeclaration.PARAMETERS_PROPERTY);

        SingleVariableDeclaration newParam = ast.newSingleVariableDeclaration();
        newParam.setType(ProjectAnalyzer.createTypeNode(ast, type));
        newParam.setName(ast.newSimpleName(paramName));

        listRewrite.insertLast(newParam, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String renameMethodParameter(CompilationUnit cu, String originalCode,
                                        MethodDeclaration method, int index, String newName) {
        List<?> params = method.parameters();
        if (index >= 0 && index < params.size()) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) params.get(index);
            return AstRewriteHelper.renameSimpleName(cu, originalCode, param.getName(), newName);
        }
        return originalCode;
    }

    public static String deleteParameterFromMethod(CompilationUnit cu, String originalCode,
                                            MethodDeclaration method, int index) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        ListRewrite listRewrite = rewriter.getListRewrite(method, MethodDeclaration.PARAMETERS_PROPERTY);
        List<?> params = method.parameters();

        if (index >= 0 && index < params.size()) {
            listRewrite.remove((ASTNode) params.get(index), null);
        }

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }


    public static String changeMethodParameterType(CompilationUnit cu, String originalCode,
                                            MethodDeclaration method, int index, ResolvedType newType) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        List<?> params = method.parameters();

        if (index >= 0 && index < params.size()) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) params.get(index);

            // Create the new type node using TypeManager (Phase 1 fix applies here)
            Type newTypeNode = ProjectAnalyzer.createTypeNode(ast, newType);

            // Replace the old type
            rewriter.replace(param.getType(), newTypeNode, null);

            // Ensure import exists
            com.botmaker.parser.ImportManager.addImport(cu, rewriter, newType, null);
        }

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String addConstructorToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        MethodDeclaration newConstructor = ast.newMethodDeclaration();
        newConstructor.setConstructor(true);
        // Constructor name MUST match class name
        newConstructor.setName(ast.newSimpleName(typeDecl.getName().getIdentifier()));
        newConstructor.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        // No return type for constructors

        Block body = ast.newBlock();
        newConstructor.setBody(body);

        ListRewrite listRewrite = rewriter.getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        // Insert at the beginning (index 0) or after fields? Let's default to index 0 for visibility
        listRewrite.insertAt(newConstructor, 0, null);

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String addArgumentToMethodInvocation(CompilationUnit cu, String originalCode,
                                                MethodInvocation mi, ExpressionType type) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Expression newArg = NodeCreator.createDefaultExpression(ast, type, cu, rewriter);
        if (newArg != null) {
            rewriter.getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY).insertLast(newArg, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String addArgumentToMethodInvocation(CompilationUnit cu, String originalCode,
                                                MethodInvocation mi, Expression newArgument) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY).insertLast(newArgument, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static void syncArguments(AST ast, ASTRewrite rewriter, MethodInvocation mi, List<ResolvedType> targetTypes) {
        ListRewrite argsRewrite = rewriter.getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY);
        List<?> currentArgs = mi.arguments();

        int targetCount = targetTypes.size();
        int currentCount = currentArgs.size();

        // 1. Update/Keep existing arguments
        for (int i = 0; i < Math.min(currentCount, targetCount); i++) {
            Expression currentArg = (Expression) currentArgs.get(i);
            ResolvedType targetType = targetTypes.get(i);

            // Resolve type of current argument
            ResolvedType currentType = ProjectAnalyzer.resolveType(currentArg);

            // If types are NOT compatible, replace the argument
            if (!currentType.isAssignmentCompatible(targetType)) {
                Expression defaultExpr = NodeCreator.createDefaultInitializer(ast, targetType);
                argsRewrite.replace(currentArg, defaultExpr, null);
            }
        }

        // 2. Remove excess arguments
        if (currentCount > targetCount) {
            for (int i = currentCount - 1; i >= targetCount; i--) {
                argsRewrite.remove((ASTNode) currentArgs.get(i), null);
            }
        }
        // 3. Add missing arguments
        else if (currentCount < targetCount) {
            for (int i = currentCount; i < targetCount; i++) {
                ResolvedType type = targetTypes.get(i);
                Expression defaultExpr = NodeCreator.createDefaultInitializer(ast, type);
                argsRewrite.insertLast(defaultExpr, null);
            }
        }
    }
}