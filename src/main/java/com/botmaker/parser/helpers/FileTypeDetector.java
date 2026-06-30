package com.botmaker.parser.helpers;

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
     * Checks if a method is a main method.
     */
    public static boolean isMainMethod(MethodDeclaration method) {
        if (!"main".equals(method.getName().getIdentifier())) {
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