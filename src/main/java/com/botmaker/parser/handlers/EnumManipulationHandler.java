package com.botmaker.parser.handlers;

import com.botmaker.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;

public class EnumManipulationHandler {

    // ... (Keep existing methods: addEnumToClass, deleteEnumFromClass, renameEnum, addEnumConstant, deleteEnumConstant, renameEnumConstant)

    /**
     * Replaces an expression with a specific enum constant.
     */
    public static String replaceWithEnumConstant(CompilationUnit cu, String originalCode,
                                          Expression toReplace, String enumType, String constantName) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        QualifiedName newConst = ast.newQualifiedName(
                ast.newSimpleName(enumType),
                ast.newSimpleName(constantName)
        );

        rewriter.replace(toReplace, newConst, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    // ... [Previous methods omitted for brevity, ensure they are kept]
    // Re-inserting required methods to ensure compilation if copy-pasted:
    public static String addEnumToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl, String enumName, int index) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        EnumDeclaration newEnum = ast.newEnumDeclaration();
        newEnum.setName(ast.newSimpleName(enumName));
        newEnum.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        EnumConstantDeclaration const1 = ast.newEnumConstantDeclaration();
        const1.setName(ast.newSimpleName("OPTION_A"));
        newEnum.enumConstants().add(const1);
        ListRewrite listRewrite = rewriter.getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        listRewrite.insertAt(newEnum, index, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
    public static String deleteEnumFromClass(CompilationUnit cu, String originalCode, EnumDeclaration enumDecl) {
        return AstRewriteHelper.removeNode(cu, originalCode, enumDecl);
    }
    public static String renameEnum(CompilationUnit cu, String originalCode, EnumDeclaration enumNode, String newName) {
        return AstRewriteHelper.renameSimpleName(cu, originalCode, enumNode.getName(), newName);
    }
    public static String addEnumConstant(CompilationUnit cu, String originalCode, EnumDeclaration enumNode, String constantName) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite listRewrite = rewriter.getListRewrite(enumNode, EnumDeclaration.ENUM_CONSTANTS_PROPERTY);
        EnumConstantDeclaration newConst = ast.newEnumConstantDeclaration();
        newConst.setName(ast.newSimpleName(constantName));
        listRewrite.insertLast(newConst, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
    public static String deleteEnumConstant(CompilationUnit cu, String originalCode, EnumDeclaration enumNode, int index) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        ListRewrite listRewrite = rewriter.getListRewrite(enumNode, EnumDeclaration.ENUM_CONSTANTS_PROPERTY);
        List<?> constants = enumNode.enumConstants();
        if (index >= 0 && index < constants.size()) {
            listRewrite.remove((ASTNode) constants.get(index), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
    public static String renameEnumConstant(CompilationUnit cu, String originalCode, EnumDeclaration enumNode, int index, String newName) {
        List<?> constants = enumNode.enumConstants();
        if (index >= 0 && index < constants.size()) {
            EnumConstantDeclaration constDecl = (EnumConstantDeclaration) constants.get(index);
            return AstRewriteHelper.renameSimpleName(cu, originalCode, constDecl.getName(), newName);
        }
        return originalCode;
    }
}