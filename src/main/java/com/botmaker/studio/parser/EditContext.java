package com.botmaker.studio.parser;

import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.project.ProjectState;
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
 * <p>{@code analyzer} and {@code state} are both nullable — the shorter entry points and most tests have
 * neither, and every consumer already treats them as best-effort.
 */
public record EditContext(AST ast, CompilationUnit cu, ASTRewrite rewriter,
                          ProjectAnalyzer analyzer, ProjectState state) {

    /**
     * A context over {@code cu} with a freshly created rewriter — what an entry point that takes
     * {@code (CompilationUnit cu, String originalCode, …)} and returns new source builds first.
     */
    public static EditContext of(CompilationUnit cu, ProjectAnalyzer analyzer, ProjectState state) {
        AST ast = cu.getAST();
        return new EditContext(ast, cu, ASTRewrite.create(ast), analyzer, state);
    }

    /** As above, over an <em>existing</em> rewriter — for a nested edit that must land in the same rewrite. */
    public static EditContext of(CompilationUnit cu, ASTRewrite rewriter, ProjectAnalyzer analyzer,
                                 ProjectState state) {
        return new EditContext(cu.getAST(), cu, rewriter, analyzer, state);
    }

    /** This context with {@code state} attached, for a path that acquires one partway down. */
    public EditContext withState(ProjectState newState) {
        return newState == state ? this : new EditContext(ast, cu, rewriter, analyzer, newState);
    }

    /** Imports the project's generated {@code Templates} class if this file isn't already in its package. */
    public void addTemplatesImport() {
        ImportManager.addTemplatesImport(cu, rewriter);
    }

    /** Imports an SDK type by identity — cannot fail to resolve. See {@link ImportManager#addImport}. */
    public void addImport(SdkType type) {
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
