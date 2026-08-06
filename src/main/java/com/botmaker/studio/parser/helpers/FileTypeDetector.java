package com.botmaker.studio.parser.helpers;

import org.eclipse.jdt.core.dom.*;

/**
 * Detects file and method types in the AST.
 */
public class FileTypeDetector {

    /**
     * Checks if a type declaration is a standalone enum file.
     */
    public static boolean isStandaloneEnumFile(AbstractTypeDeclaration typeDecl) {
        return typeDecl instanceof EnumDeclaration;
    }

    /**
     * Checks if a type declaration is a class file.
     */
    public static boolean isClassFile(AbstractTypeDeclaration typeDecl) {
        return typeDecl instanceof TypeDeclaration;
    }

    /**
     * The name of a Java entry point. A generated bot's is fixed and cannot be renamed (the rename field in
     * {@code MethodDeclarationBlock} refuses it), and {@code MainBlock} identifies itself by it — so it is one
     * constant rather than a literal in each of those places.
     */
    public static final String MAIN_METHOD = "main";

    /**
     * Checks if a method is a main method.
     */
    public static boolean isMainMethod(MethodDeclaration method) {
        if (!MAIN_METHOD.equals(method.getName().getIdentifier())) {
            return false;
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            return false;
        }
        if (method.parameters().size() != 1) {
            return false;
        }
        return true;
    }

    /**
     * Finds the main method in a type declaration.
     */
    public static MethodDeclaration findMainMethod(TypeDeclaration type) {
        for (MethodDeclaration method : type.getMethods()) {
            if (isMainMethod(method)) {
                return method;
            }
        }
        return null;
    }
}
