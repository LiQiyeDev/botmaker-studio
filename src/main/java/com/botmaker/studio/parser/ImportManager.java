package com.botmaker.studio.parser;

import com.botmaker.sdk.api.authoring.TemplateNames;
import com.botmaker.studio.plugin.PluginHost;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ImportManager {

    /**
     * The packages the JDK probe walks, in priority order, when a simple name resolves to nothing else.
     *
     * <p>This replaced a hand-written map of 14 simple-name→FQN entries. Two things make a probe strictly
     * better than that map: it deduces every name in these packages rather than the fourteen someone thought
     * to list ({@code Optional}, {@code Instant}, {@code Files}, {@code Pattern}, {@code BigDecimal},
     * {@code Collectors}…), and it cannot go stale.
     *
     * <p><b>Order is the disambiguation.</b> First hit wins, so {@code java.util} ahead of {@code java.awt}
     * settles {@code List} on {@code java.util.List}. The names that used to make this tier dangerous —
     * {@code Point}, {@code Window}, {@code Desktop}, {@code Text}, all of which the SDK also ships — never
     * reach it at all: the plugins' catalogs are consulted first (see {@link #resolveQualifiedName}). The old map
     * had to omit {@code Point} entirely and explain why in a comment; the tier above now handles that.
     */
    private static final List<String> JDK_PACKAGES = List.of(
            "java.util",
            "java.util.function",
            "java.util.stream",
            "java.io",
            "java.nio.file",
            "java.time",
            "java.math",
            "java.util.regex",
            "java.awt",
            "java.awt.image");

    /**
     * Memoized results of {@link #probeJdkPackages} — <em>including misses</em>, held as {@link #NOT_FOUND}.
     * Every keystroke-driven edit can ask about the same unresolvable name, and an uncached miss costs one
     * failed {@code Class.forName} per package in {@link #JDK_PACKAGES}.
     */
    private static final Map<String, String> JDK_PROBE_CACHE = new ConcurrentHashMap<>();

    /** Sentinel for a cached miss — {@link ConcurrentHashMap} cannot hold a null value. */
    private static final String NOT_FOUND = "";

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
     * When the analyzer is absent or resolves nothing, this falls through to
     * {@link #resolveQualifiedName}, which still knows the project's own classes, the SDK and the JDK — that
     * fallback is what makes a name-only {@code Color} importable.
     *
     * <p>Use this only where the name genuinely <em>is</em> all the caller has: a pasted snippet's type, an
     * enum constant's scope, the leaf of an unbound {@link ResolvedType}. When the type is known at compile
     * time, call {@link #addImport(CompilationUnit, ASTRewrite, Class)} instead — searching for an answer
     * you already hold is how an import ends up silently missing.
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
     * Imports an SDK type by identity — the one call that <em>cannot</em> fail to resolve.
     *
     * <p>Prefer this wherever the type being written into the source is known at compile time (the block
     * factories and handlers that emit {@code new ImageTemplate(…)}, {@code ImageTemplateGroup.of(…)}, a
     * {@code Matches} switch). Those sites used to call {@link #addImportForSimpleName} with a string
     * literal, which then had to <em>search</em> the analyzer index for a name the caller already knew — and
     * silently emitted nothing when the index was cold or the bot's classpath had not resolved yet.
     */
    public static void addImport(CompilationUnit cu, ASTRewrite rewriter, Class<?> type) {
        if (type == null) return;
        addImport(cu, rewriter, type.getName());
    }

    /**
     * Imports the project's generated {@code Templates} class when the file being edited needs it — i.e. when
     * it isn't already in the base package the class lives in.
     *
     * <p>The base package is read off the file itself rather than passed in, because these rewrites run from
     * both the main class and an activity stub and only one of them has the project config to hand. An
     * activity lives in {@code com.<pkg>.activities}, so the base package is this file's own minus that one
     * trailing segment — the layout {@code ProjectConfig} creates and the only one Studio generates.
     */
    public static void addTemplatesImport(CompilationUnit cu, ASTRewrite rewriter) {
        if (cu == null || cu.getPackage() == null) return;
        String pkg = cu.getPackage().getName().getFullyQualifiedName();
        String base = pkg.endsWith(".activities") ? pkg.substring(0, pkg.length() - ".activities".length()) : pkg;
        if (base.equals(pkg)) return;   // same package — the class is already visible
        addImport(cu, rewriter, base + "." + TemplateNames.CLASS_NAME);
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
     * Resolves a simple class name to its fully-qualified name, in three ordered tiers:
     *
     * <ol>
     *   <li><b>the project's own sources</b> — a class the user wrote always wins over anything on a
     *       classpath, exactly as {@code javac} would resolve it;</li>
     *   <li><b>{@link PluginHost the plugins' catalogs}</b> — the names the SDK (and any future plugin) owns.
     *       This is what
     *       makes the tier below safe, and it is the only tier that can supply a <em>sub-package</em> FQN
     *       ({@code api.vision.ImageFinder}) from a bare name;</li>
     *   <li><b>{@link #probeJdkPackages the JDK probe}</b> — a last resort for names like {@code Color} or
     *       {@code Duration} that no index carries.</li>
     * </ol>
     *
     * <p>Returns {@code null} when nothing matches, which the callers treat as "same package or already
     * available" and skip. Guessing wrong here writes an import that does not compile, so a miss is the
     * safer answer than a plausible one.
     */
    private static String resolveQualifiedName(String className, ProjectState state) {
        // If already qualified, return as-is
        if (className.contains(".")) {
            return className;
        }

        // Tier 1: the project's own sources.
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

        // Tier 2: the plugins. Must precede the probe — Point/Window/Desktop/Text all collide with java.awt.
        String owned = PluginHost.qualifiedName(className);
        if (owned != null) {
            return owned;
        }

        // Tier 3: the JDK.
        return probeJdkPackages(className);
    }

    /**
     * The first package in {@link #JDK_PACKAGES} that actually declares {@code simpleName}, or {@code null}.
     *
     * <p>Loads with {@code initialize = false} and the <em>platform</em> class loader: this asks "does the JDK
     * declare this name?", so it must not see Studio's own classpath (where it would happily resolve some
     * unrelated internal class of the same name), and must not run a static initializer as a side effect of
     * the user typing a type name.
     */
    private static String probeJdkPackages(String simpleName) {
        String cached = JDK_PROBE_CACHE.computeIfAbsent(simpleName, name -> {
            for (String pkg : JDK_PACKAGES) {
                String candidate = pkg + "." + name;
                try {
                    Class.forName(candidate, false, ClassLoader.getPlatformClassLoader());
                    return candidate;
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // Not in this package — try the next one.
                }
            }
            return NOT_FOUND;
        });
        return NOT_FOUND.equals(cached) ? null : cached;
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

    /**
     * Repoints this file's {@code com.botmaker.sdk.api.*} imports at wherever those classes live <em>now</em>,
     * and reports what it changed.
     *
     * <p>SDK 1.1.0 reorganised the api package — {@code api.Point} became {@code api.geometry.Point},
     * {@code api.Debug} became {@code api.util.Debug}, and so on. A bot written against an earlier SDK
     * carries the old import lines, and a stale import is a hard compile error on a line the user never
     * wrote: it would open every existing project with a wall of red for a rename nobody asked for. This is
     * the repair, and it costs nothing because the bundled catalog already holds the current FQN for every
     * simple name the SDK owns. SDK 1.2.0 added one more source of stale lines — {@code shared.ocr} moved
     * into {@code api.vision} — handled by the same lookup; see {@link #SHARED_OCR_PREFIX}.
     *
     * <p><b>An unrecognised name is left alone, deliberately.</b> A simple name the catalog does not know
     * is either a class that left the public API (the {@code CaptureSource} implementations, the observation
     * stack) or one this Studio is too old to know about, and there is no honest FQN to write for either. A
     * wrong import compiles into a different type; an untouched one fails to compile where the user can read
     * why. Nothing a bot could actually have named falls in that gap — those classes were only ever
     * <em>returned</em>, never written down.
     *
     * @return the old FQNs that were repointed, in source order — empty when the file needed nothing
     */
    public static List<String> repairSdkImports(CompilationUnit cu, ASTRewrite rewriter) {
        if (cu == null) return List.of();
        List<String> repaired = new java.util.ArrayList<>();
        ListRewrite listRewrite = rewriter.getListRewrite(cu, CompilationUnit.IMPORTS_PROPERTY);
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            if (imp.isOnDemand() || imp.isStatic()) continue;
            String stale = imp.getName().getFullyQualifiedName();
            if (!stale.startsWith(SDK_API_PREFIX) && !stale.startsWith(SHARED_OCR_PREFIX)) continue;

            String simpleName = stale.substring(stale.lastIndexOf('.') + 1);
            String current = PluginHost.qualifiedName(simpleName);
            if (current == null || current.equals(stale)) continue;

            ImportDeclaration fixed = cu.getAST().newImportDeclaration();
            fixed.setName(cu.getAST().newName(current));
            listRewrite.replace(imp, fixed, null);
            repaired.add(stale);
        }
        return List.copyOf(repaired);
    }

    /** Package prefix every SDK API class shares — the scope {@link #repairSdkImports} is allowed to touch. */
    private static final String SDK_API_PREFIX = "com.botmaker.sdk.api.";

    /**
     * The second scope, and the only non-SDK one: SDK 1.2.0 moved {@code com.botmaker.shared.ocr} into the
     * SDK, so a bot that tuned OCR carries {@code import com.botmaker.shared.ocr.OcrOptions;} and would open
     * with a compile error on a line it never wrote. The three names a bot could have written down —
     * {@code OcrOptions}, {@code OcrLanguage}, {@code TextResult} — are all in the catalog now, so the same
     * simple-name lookup below answers them; the engine classes are internal and were never importable.
     * This is a fixed, closed list of one dead package, not an invitation to repair arbitrary imports.
     */
    private static final String SHARED_OCR_PREFIX = "com.botmaker.shared.ocr.";

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
