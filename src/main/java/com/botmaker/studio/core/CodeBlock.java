package com.botmaker.studio.core;

import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Statement;

public interface CodeBlock {
    String getId();
    ASTNode getAstNode();

    /**
     * The statement this block lives in: its own node when that is already a {@link Statement}, else the nearest
     * enclosing one. Deleting a block means deleting <em>that</em> statement, and the two are not the same node
     * for every expression-backed statement block — {@code MethodInvocationBlock}'s node is the
     * {@code MethodInvocation}, not the {@code ExpressionStatement} wrapping it. Every delete site resolves the
     * target through here, because the two spellings that grew instead (an {@code instanceof Statement} test on
     * the block's own node, and a bare {@code getParent()} cast) had already drifted: the test silently refused
     * to delete any call row at all.
     *
     * @return the enclosing statement, or {@code null} for a block that is not inside one
     */
    default Statement enclosingStatement() {
        for (ASTNode n = getAstNode(); n != null; n = n.getParent()) {
            if (n instanceof Statement s) return s;
        }
        return null;
    }
    Node getUINode(CodeEditorService context);
    Node getUINode();

    // Visual State
    void highlight();
    void unhighlight();
    void setError(String message);
    void clearError();

    // Breakpoint Logic
    void setBreakpoint(boolean enabled);
    boolean isBreakpoint();
    void toggleBreakpoint();

    // Read-Only Logic
    void setReadOnly(boolean readOnly);
    boolean isReadOnly();

    // Debugging
    int getBreakpointLine(CompilationUnit cu);
    CodeBlock getHighlightTarget();
    String getDetails();
}
