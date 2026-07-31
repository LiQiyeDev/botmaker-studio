package com.botmaker.studio.parser;

import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;
import java.util.Map;

public class ImportManager {

    /**
     * Simple name → FQN for JDK types a name-only {@link ResolvedType} would otherwise never resolve to.
     * This is the last-resort tier of {@link #resolveQualifiedName}, reached only after the project's own
     * classes (and, via {@link #addImportForSimpleName}, the analyzer's index) have come up empty.
     *
     * <p>Deliberately <em>not</em> here: {@code Point}. The SDK ships {@code com.botmaker.sdk.api.Point} and
     * bots use it constantly, so mapping the bare name to {@code java.awt.Point} would silently import the
     * wrong one whenever resolution failed. {@code Rectangle} is safe — the SDK's equivalent is {@code Rect}.
     */
    private static final Map<String, String> WELL_KNOWN_JDK_TYPES = Map.ofEntries(
            Map.entry("List", "java.util.List"),
            Map.entry("ArrayList", "java.util.ArrayList"),
            Map.entry("Map", "java.util.Map"),
            Map.entry("HashMap", "java.util.HashMap"),
            Map.entry("Set", "java.util.Set"),
            Map.entry("HashSet", "java.util.HashSet"),
            Map.entry("Arrays", "java.util.Arrays"),
            Map.entry("Color", "java.awt.Color"),
            Map.entry("Rectangle", "java.awt.Rectangle"),
            Map.entry("Dimension", "java.awt.Dimension"),
            Map.entry("BufferedImage", "java.awt.image.BufferedImage"),
            Map.entry("Path", "java.nio.file.Path"),
            Map.entry("File", "java.io.File"),
            Map.entry("Duration", "java.time.Duration"));

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
     * Resolves a (possibly already-qualified) type name to its fully-qualified name via the
     * {@code analyzer} (project source first, then the library index) and imports it. This is the
     * import path for palette/menu-created references — static call scopes, {@code new T(...)} types,
     * variable-declaration types and enum-constant scopes — which only carry a simple name.
     *
     * <p>Best-effort: a no-op when the type is primitive/{@code java.lang}/same-package/already-imported.
     * When the analyzer is absent or resolves nothing, this falls through to the name-based path, which
     * still knows the project's own classes and {@link #WELL_KNOWN_JDK_TYPES} — that fallback is what makes
     * a name-only {@code Color} importable.
     */
    public static void addImportForSimpleName(CompilationUnit cu, ASTRewrite rewriter, String typeName,
                                              ProjectAnalyzer analyzer, ProjectState state) {
        if (cu == null || typeName == null || typeName.isBlank()) return;
        String name = typeName.trim();
        ResolvedType resolved = analyzer != null ? analyzer.findTypeByName(name) : null;
        if (resolved != null && !resolved.isUnknown()) {
            addImport(cu, rewriter, resolved, state);
            return;
        }
        addImport(cu, rewriter, ResolvedType.named(name), state);
    }

    /**
     * Imports {@code type} using the strongest resolution available: a {@link ResolvedType.Bound} or
     * {@link ResolvedType.FromIndex} already carries its FQN, so it is used directly; anything else is
     * looked up by simple name through the {@code analyzer}. Arrays import their <em>leaf</em> — a
     * {@code Color[]} parameter needs {@code java.awt.Color}.
     *
     * <p>This is the entry point for importing a method/constructor's <em>parameter</em> types, which the
     * insert and overload-switch paths both build default arguments for.
     */
    public static void addImportForType(CompilationUnit cu, ASTRewrite rewriter, ResolvedType type,
                                        ProjectAnalyzer analyzer, ProjectState state) {
        if (cu == null || type == null) return;
        ResolvedType leaf = type.leafType();
        if (leaf.isPrimitive() || leaf.isVoid() || leaf.isUnknown()) return;
        if (leaf instanceof ResolvedType.Bound || leaf instanceof ResolvedType.FromIndex) {
            addImport(cu, rewriter, leaf, state);
            return;
        }
        addImportForSimpleName(cu, rewriter, leaf.simpleName(), analyzer, state);
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

        // Check if it's a well-known JDK class
        String jdk = WELL_KNOWN_JDK_TYPES.get(className);
        if (jdk != null) {
            return jdk;
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
