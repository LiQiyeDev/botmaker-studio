package com.botmaker.parser;

import com.botmaker.project.ProjectFile;
import com.botmaker.project.ProjectState;
import com.botmaker.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;
import java.util.Set;

public class ImportManager {

    private static final Set<String> COMMON_JAVA_UTIL_CLASSES = Set.of(
            "List", "ArrayList", "Map", "HashMap", "Set", "HashSet", "Arrays"
    );

    /**
     * Ensures that the specific class is imported.
     * Attempts to resolve simple names to FQNs using ApplicationState.
     */
    /**
     * Adds an import for the given type.
     * Uses the resolved FQN for {@link ResolvedType.Bound}/{@link ResolvedType.FromIndex},
     * otherwise attempts to resolve the simple name.
     */
    public static void addImport(CompilationUnit cu, ASTRewrite rewriter, ResolvedType type, ProjectState state) {
        if (cu == null || type == null || type.isPrimitive() || type.isVoid()) return;

        String qualifiedName = switch (type) {
            case ResolvedType.Bound b     -> b.qualifiedName();
            case ResolvedType.FromIndex f -> f.qualifiedName();
            default                       -> resolveQualifiedName(type.simpleName(), state);
        };

        if (qualifiedName == null || shouldSkipImport(cu, qualifiedName)) {
            return;
        }

        addImportInternal(cu, rewriter, qualifiedName);
    }

    /**
     * Raw add import (expects FQN).
     */
    public static void addImport(CompilationUnit cu, ASTRewrite rewriter, String qualifiedClassName) {
        if (cu == null || qualifiedClassName == null || !qualifiedClassName.contains(".")) {
            return;
        }
        if (shouldSkipImport(cu, qualifiedClassName)) {
            return;
        }
        addImportInternal(cu, rewriter, qualifiedClassName);
    }

    /**
     * Resolves a simple class name to its fully qualified name.
     */
    private static String resolveQualifiedName(String className, ProjectState state) {
        // If already qualified, return as-is
        if (className.contains(".")) {
            return className;
        }

        // Try to resolve from project files
        if (state != null) {
            for (ProjectFile file : state.getAllFiles()) {
                if (file.getClassName().equals(className)) {
                    CompilationUnit cu = file.getAst();
                    if (cu != null && cu.getPackage() != null) {
                        return cu.getPackage().getName().getFullyQualifiedName() + "." + className;
                    }
                }
            }
        }

        // Check if it's a common java.util class
        if (COMMON_JAVA_UTIL_CLASSES.contains(className)) {
            return "java.util." + className;
        }

        // Cannot resolve - assume same package or already available
        return null;
    }

    /**
     * Checks if an import should be skipped (same package or already imported).
     */
    private static boolean shouldSkipImport(CompilationUnit cu, String qualifiedName) {
        if (qualifiedName.isEmpty()) return true;

        String targetPackage = packageOf(qualifiedName);

        // Unqualified (default package) imports are meaningless; java.lang is implicitly imported.
        if (targetPackage.isEmpty() || targetPackage.equals("java.lang")) {
            return true;
        }

        // Check if in same package
        if (cu.getPackage() != null) {
            String currentPackage = cu.getPackage().getName().getFullyQualifiedName();
            if (currentPackage.equals(targetPackage)) {
                return true;
            }
        }

        // Check existing imports
        return isAlreadyImported(cu, qualifiedName);
    }

    /** Package portion of a qualified name, or {@code ""} when it has no package (no dot). */
    private static String packageOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? "" : qualifiedName.substring(0, lastDot);
    }

    /**
     * Checks if a class is already imported.
     */
    private static boolean isAlreadyImported(CompilationUnit cu, String qualifiedClassName) {
        List<ImportDeclaration> imports = cu.imports();

        for (ImportDeclaration imp : imports) {
            if (imp.isOnDemand()) {
                // e.g., java.util.*
                String packageName = imp.getName().getFullyQualifiedName();
                String targetPackage = packageOf(qualifiedClassName);
                if (packageName.equals(targetPackage)) {
                    return true; // Covered by wildcard
                }
            } else {
                // Exact match
                if (imp.getName().getFullyQualifiedName().equals(qualifiedClassName)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Removes the on-demand-or-single import matching {@code qualifiedClassName}, if present.
     */
    public static void removeImport(CompilationUnit cu, ASTRewrite rewriter, String qualifiedClassName) {
        if (cu == null || qualifiedClassName == null) return;
        ListRewrite listRewrite = rewriter.getListRewrite(cu, CompilationUnit.IMPORTS_PROPERTY);
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            if (imp.getName().getFullyQualifiedName().equals(qualifiedClassName)) {
                listRewrite.remove(imp, null);
            }
        }
    }

    /** The fully-qualified names of the current file's import declarations, in source order. */
    public static List<String> listImports(CompilationUnit cu) {
        if (cu == null) return List.of();
        List<String> names = new java.util.ArrayList<>();
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            String name = imp.getName().getFullyQualifiedName();
            names.add(imp.isOnDemand() ? name + ".*" : name);
        }
        return names;
    }

    /**
     * Internal method to add import declaration.
     */
    private static void addImportInternal(CompilationUnit cu, ASTRewrite rewriter, String qualifiedClassName) {
        AST ast = cu.getAST();
        ImportDeclaration newImport = ast.newImportDeclaration();
        newImport.setName(ast.newName(qualifiedClassName));

        ListRewrite listRewrite = rewriter.getListRewrite(cu, CompilationUnit.IMPORTS_PROPERTY);
        listRewrite.insertLast(newImport, null);
    }
}