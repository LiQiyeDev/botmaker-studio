package com.botmaker.studio.parser;

import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.SdkSurfaceService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * Immutable per-edit context for the <b>write</b> path — the counterpart of {@link ParseContext}, which has
 * done the same job for the read path since the converter was written.
 *
 * <p>Every factory and handler under {@code parser.factories} / {@code parser.handlers} needs the same five
 * things to build a node: the {@link AST} to create it in, the {@link CompilationUnit} to import into, the
 * {@link ASTRewrite} recording the edit, the {@link ProjectAnalyzer} to ask what exists, and the
 * {@link ProjectState} to ask what the project prefers. They were threaded as five separate parameters, which
 * is why the widest signatures in the package ran to eight — and why adding a sixth thing (the analyzer, when
 * {@code new T()} learned to name a real constructor) meant editing seven call chains.
 *
 * <p><b>Deliberately not in here:</b> the {@code ASTNode context} — the drop site whose visible variables a
 * seeded block is named from. That varies <em>within</em> a single edit as the factories recurse, so it is a
 * real argument, not ambient state. Same for the original source text: only the outermost entry point holds
 * it, and {@link #applyTo} is where it belongs.
 *
 * <p>{@code analyzer}, {@code state} and {@code surface} are all nullable — the shorter entry points and most
 * tests have none of them, and every consumer already treats them as best-effort.
 *
 * @param surface what this project's SDK offers ({@code @Palette}), so a block created from the menu is
 *                seeded with an overload the menu would actually propose. Null means "offer everything",
 *                which is what an uncurated SDK, a headless edit and every migration path answer anyway —
 *                hence the {@code of} overloads that omit it, which is most of them.
 */
public record EditContext(AST ast, CompilationUnit cu, ASTRewrite rewriter,
                          ProjectAnalyzer analyzer, ProjectState state, SdkSurfaceService surface) {

    /**
     * A context over {@code cu} with a freshly created rewriter — what an entry point that takes
     * {@code (CompilationUnit cu, String originalCode, …)} and returns new source builds first.
     */
    public static EditContext of(CompilationUnit cu, ProjectAnalyzer analyzer, ProjectState state) {
        return of(cu, analyzer, state, null);
    }

    /** As above, carrying the SDK's curation — the interactive edit path, which is the only one that offers. */
    public static EditContext of(CompilationUnit cu, ProjectAnalyzer analyzer, ProjectState state,
                                 SdkSurfaceService surface) {
        AST ast = cu.getAST();
        return new EditContext(ast, cu, ASTRewrite.create(ast), analyzer, state, surface);
    }

    /** As above, over an <em>existing</em> rewriter — for a nested edit that must land in the same rewrite. */
    public static EditContext of(CompilationUnit cu, ASTRewrite rewriter, ProjectAnalyzer analyzer,
                                 ProjectState state) {
        return new EditContext(cu.getAST(), cu, rewriter, analyzer, state, null);
    }

    /** This context with {@code state} attached, for a path that acquires one partway down. */
    public EditContext withState(ProjectState newState) {
        return newState == state ? this : new EditContext(ast, cu, rewriter, analyzer, newState, surface);
    }

    // addTemplatesImport() went on 2026-09-01 with the ImportManager method behind it. Nothing in Studio
    // writes a reference to the generated Templates class any more, so nothing needs it imported: a picture
    // is the SDK plugin's concept and its editors carry their own imports through SlotContext.replaceWith.

    /** Imports a type by identity — cannot fail to resolve. See {@link ImportManager#addImport}. */
    public void addImport(Class<?> type) {
        ImportManager.addImport(cu, rewriter, type);
    }

    /** Imports a fully-qualified name. */
    public void addImport(String qualifiedName) {
        ImportManager.addImport(cu, rewriter, qualifiedName);
    }

    /** Imports {@code type}, using its FQN when it carries one and resolving its simple name otherwise. */
    public void addImport(ResolvedType type) {
        ImportManager.addImport(cu, rewriter, type, state);
    }

    /** Imports a type known only by simple name — the paste path and the menu-created references. */
    public void addImportForSimpleName(String simpleName) {
        ImportManager.addImportForSimpleName(cu, rewriter, simpleName, analyzer, state);
    }

    /** Imports {@code type}'s <em>leaf</em> — a {@code Color[]} parameter needs {@code java.awt.Color}. */
    public void addImportForType(ResolvedType type) {
        ImportManager.addImportForType(cu, rewriter, type, analyzer, state);
    }

    /** Applies this context's rewrite to {@code originalCode} and returns the new source. */
    public String applyTo(String originalCode) {
        return com.botmaker.studio.parser.helpers.AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}
