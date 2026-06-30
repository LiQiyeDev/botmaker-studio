package com.botmaker.core;

import com.botmaker.services.CodeEditorService;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

public interface CodeBlock {
    String getId();
    ASTNode getAstNode();
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