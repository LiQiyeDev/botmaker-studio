package com.botmaker.studio.parser;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockType;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

/**
 * Where a statement is <em>allowed</em> to go. Today that's only the jump statements: {@code break} and
 * {@code continue} are legal solely inside an enclosing loop ({@code break} also inside a {@code switch}), and
 * anywhere else they don't compile.
 *
 * <p>This is the single implementation behind all four enforcement points, which is the whole reason it's a
 * class rather than an inline check: the drag-over handler (so an illegal drop zone never lights up), the "+"
 * insert menu (so an illegal block is never offered), the palette-drop path and the block-move path. Three of
 * those can be bypassed by the fourth, so they have to agree.
 *
 * <p>It deliberately lives here and not as a {@code BlockType} default method: {@code palette} is the
 * dependency-light catalog package and answering this question needs the JDT AST.
 */
public final class StatementPlacement {

    private StatementPlacement() {}

    /** The two statements whose legality depends on where they land. */
    public enum Jump {
        BREAK("Break", "a loop or switch"),
        CONTINUE("Continue", "a loop");

        private final String label;
        private final String where;

        Jump(String label, String where) {
            this.label = label;
            this.where = where;
        }

        /** User-facing reason a drop was refused. */
        public String rejectionMessage() {
            return label + " can only be placed inside " + where + ".";
        }
    }

    /** The jump a palette block would insert, or {@code null} for every other block. */
    public static Jump jumpOf(BlockType type) {
        if (type == BlockCatalog.BREAK) return Jump.BREAK;
        if (type == BlockCatalog.CONTINUE) return Jump.CONTINUE;
        return null;
    }

    /** The jump an existing statement <em>is</em>, or {@code null} for every other statement. */
    public static Jump jumpOf(ASTNode statement) {
        if (statement instanceof BreakStatement) return Jump.BREAK;
        if (statement instanceof ContinueStatement) return Jump.CONTINUE;
        return null;
    }

    /**
     * Whether {@code jump} may be placed in the body rooted at {@code targetBodyNode}. A {@code null} jump (any
     * ordinary block) is always allowed; a {@code null} target is not, since nothing can be verified about it.
     */
    public static boolean allows(Jump jump, ASTNode targetBodyNode) {
        if (jump == null) return true;
        if (targetBodyNode == null) return false;
        for (ASTNode n = targetBodyNode; n != null; n = n.getParent()) {
            if (n instanceof WhileStatement || n instanceof DoStatement
                    || n instanceof ForStatement || n instanceof EnhancedForStatement) {
                return true;
            }
            if (jump == Jump.BREAK && n instanceof SwitchStatement) return true;
        }
        return false;
    }

    /** Convenience for the palette side: is this block type legal in this body? */
    public static boolean allows(BlockType type, ASTNode targetBodyNode) {
        return allows(jumpOf(type), targetBodyNode);
    }
}
