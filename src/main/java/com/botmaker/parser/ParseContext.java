package com.botmaker.parser;

import com.botmaker.core.CodeBlock;
import com.botmaker.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.List;
import java.util.Map;

/**
 * Immutable per-{@code convert()} parsing context. Threaded through every recursive
 * step of {@link BlockConverter} so the converter holds no per-parse mutable state.
 *
 * @param cu                            the binding-resolved compilation unit being parsed
 * @param sourceCode                    the original source (used to recover comment text)
 * @param comments                      non-Javadoc comments collected from {@code cu}
 * @param nodeToBlockMap                canonical AST-node → block registry being populated
 * @param manager                       drag-and-drop manager handed to interactive blocks
 * @param readOnly                      whether the parsed file is a read-only library file
 * @param markNewIdentifiersAsUnedited  whether freshly created identifiers/field accesses
 *                                      should be visually marked as auto-generated
 */
public record ParseContext(
        CompilationUnit cu,
        String sourceCode,
        List<Comment> comments,
        Map<ASTNode, CodeBlock> nodeToBlockMap,
        BlockDragAndDropManager manager,
        boolean readOnly,
        boolean markNewIdentifiersAsUnedited
) {}
