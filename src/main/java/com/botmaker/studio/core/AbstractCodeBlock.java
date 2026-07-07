package com.botmaker.studio.core;

import com.botmaker.studio.core.render.BlockDecorator;
import com.botmaker.studio.core.render.GutterDecorator;
import com.botmaker.studio.core.render.InteractionDecorator;
import com.botmaker.studio.core.render.ReadOnlyDecorator;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.ui.dnd.BlockEvent;
import com.botmaker.studio.palette.ExpressionType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;

import java.util.List;

public abstract class AbstractCodeBlock implements CodeBlock {
    protected final String id;
    protected final ASTNode astNode;

    protected Node uiNode;
    private Tooltip errorTooltip;

    // Breakpoint state — exposed as a property so the gutter circle and the :breakpoint pseudo-class
    // both track it without manual visual refreshes.
    private final BooleanProperty breakpointActive = new SimpleBooleanProperty(false);

    // Read-Only State
    protected boolean isReadOnly = false;

    // Cross-cutting state styling (backed by blocks.css), and the one-time decoration pipeline.
    private static final PseudoClass HIGHLIGHTED = PseudoClass.getPseudoClass("highlighted");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass BREAKPOINT = PseudoClass.getPseudoClass("breakpoint");

    private static final List<BlockDecorator> DECORATORS = List.of(
            new GutterDecorator(),
            new ReadOnlyDecorator(),
            new InteractionDecorator()
    );

    public AbstractCodeBlock(String id, ASTNode astNode) {
        this.id = id;
        this.astNode = astNode;
    }

    @Override
    public String getId() { return id; }

    @Override
    public ASTNode getAstNode() { return astNode; }

    @Override
    public void setReadOnly(boolean readOnly) {
        this.isReadOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() {
        return isReadOnly;
    }

    @Override
    public Node getUINode(CodeEditorService context) {
        if (uiNode == null) {
            uiNode = createUINode(context);
            // Toggle the :breakpoint pseudo-class whenever the breakpoint state changes.
            uiNode.pseudoClassStateChanged(BREAKPOINT, breakpointActive.get());
            breakpointActive.addListener((obs, was, on) -> uiNode.pseudoClassStateChanged(BREAKPOINT, on));

            for (BlockDecorator decorator : DECORATORS) {
                decorator.decorate(uiNode, this, context);
            }
        }
        return uiNode;
    }

    @Override
    public Node getUINode() { return uiNode; }

    @Override
    public void highlight() {
        if (uiNode != null) uiNode.pseudoClassStateChanged(HIGHLIGHTED, true);
    }

    @Override
    public void unhighlight() {
        if (uiNode != null) uiNode.pseudoClassStateChanged(HIGHLIGHTED, false);
    }

    @Override
    public void setError(String message) {
        if (uiNode == null) return;
        uiNode.pseudoClassStateChanged(ERROR, true);
        if (errorTooltip == null) {
            errorTooltip = new Tooltip(message);
            Tooltip.install(uiNode, errorTooltip);
        } else {
            errorTooltip.setText(message);
        }
    }

    @Override
    public void clearError() {
        if (uiNode == null) return;
        uiNode.pseudoClassStateChanged(ERROR, false);
        if (errorTooltip != null) {
            Tooltip.uninstall(uiNode, errorTooltip);
            errorTooltip = null;
        }
    }

    @Override
    public int getBreakpointLine(CompilationUnit cu) {
        if (cu == null || astNode == null) return -1;
        return cu.getLineNumber(astNode.getStartPosition());
    }

    @Override
    public CodeBlock getHighlightTarget() { return this; }

    @Override
    public String getDetails() {
        return this.getClass().getSimpleName() + " (ID: " + this.getId() + ")";
    }

    @Override
    public boolean isBreakpoint() { return breakpointActive.get(); }

    @Override
    public void setBreakpoint(boolean enabled) {
        breakpointActive.set(enabled);
    }

    @Override
    public void toggleBreakpoint() {
        setBreakpoint(!isBreakpoint());
        if (uiNode != null) {
            uiNode.fireEvent(new BlockEvent.BreakpointToggleEvent(this, isBreakpoint()));
        }
    }

    /** Backs the gutter circle's visibility and the {@code :breakpoint} pseudo-class. */
    public BooleanProperty breakpointActiveProperty() { return breakpointActive; }

    protected Node createExpressionDropZone(CodeEditorService context) {
        // If Read-Only, return a static label or empty region instead of a drop zone
        if (isReadOnly) {
            javafx.scene.control.Label lbl = new javafx.scene.control.Label("");
            lbl.setMinWidth(20);
            return lbl;
        }
        Region dropZone = new Region();
        context.getDragAndDropManager().addExpressionDropHandlers(dropZone);
        return dropZone;
    }

    protected abstract Node createUINode(CodeEditorService context);

    /**
     * Applies the user's pick from {@link com.botmaker.studio.ui.render.menu.ExpressionMenuFactory} to
     * {@code toReplace}: a plain {@link ExpressionType} swaps in a fresh expression block, while an
     * {@link ExpressionChoice} drives a richer rewrite (method call, instantiation, enum constant, or
     * variable reference). Shared by statement and expression blocks.
     */
    protected void applyExpressionSelection(CodeEditorService context, Expression toReplace, Object selection) {
        if (toReplace == null || selection == null) return;
        if (selection instanceof ExpressionType expr) {
            context.getCodeEditor().replaceExpression(toReplace, expr);
            return;
        }
        if (selection instanceof ExpressionChoice choice) {
            switch (choice) {
                case ExpressionChoice.Method m ->
                        context.getCodeEditor().replaceWithMethodCall(toReplace, m);
                case ExpressionChoice.Constructor c ->
                        context.getCodeEditor().replaceWithInstantiation(toReplace, c.typeName(), c.paramTypes());
                case ExpressionChoice.EnumConstant en ->
                        context.getCodeEditor().replaceWithEnumConstant(toReplace, en.typeName(), en.constantName());
                case ExpressionChoice.Variable v ->
                        context.getCodeEditor().replaceWithVariable(toReplace, v.variableName());
                case ExpressionChoice.Field f ->
                        context.getCodeEditor().replaceWithFieldReference(toReplace, f.scope(), f.fieldName());
                case ExpressionChoice.NewVariable nv ->
                        context.getCodeEditor().declareVariableBeforeAndReference(toReplace, nv.type(), nv.name());
                case ExpressionChoice.RawExpression rx ->
                        context.getCodeEditor().replaceWithRawExpression(toReplace, rx.code());
            }
        }
    }
}