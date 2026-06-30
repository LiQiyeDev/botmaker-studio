package com.botmaker.parser;

import org.eclipse.jdt.core.dom.ASTNode;

/**
 * Generates a block id from its backing AST node.
 *
 * <p>The id is {@code <nodeKind>_<startPosition>_<length>}, which is unique within a compilation unit
 * (two distinct nodes cannot share the same kind, start and length) and stable across re-parses of
 * unchanged code — so id-keyed state such as breakpoints survives an edit elsewhere in the file.
 */
public final class BlockId {

    private BlockId() {}

    public static String of(ASTNode node) {
        return node.getClass().getSimpleName() + "_" + node.getStartPosition() + "_" + node.getLength();
    }
}
