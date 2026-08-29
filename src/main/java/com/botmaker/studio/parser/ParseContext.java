package com.botmaker.studio.parser;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.component.Audience;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
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
 * @param readOnly                      whether blocks parsed under this context are locked
 * @param resolver                      the file's lock rules; null when there is no project (tests)
 * @param audience                      who the canvas is being drawn for. It used to decide whole members —
 *                                      a {@code USER} parse left the Studio's own scaffolding out of the
 *                                      tree — and now reaches only the component level, because there is no
 *                                      scaffolding: every member of every file is the user's
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
        LockResolver resolver,
        Audience audience,
        boolean markNewIdentifiersAsUnedited
) {
    /**
     * This context with {@code readOnly} set to {@code ro}, for parsing a method body whose lock differs from
     * the file around it. Every block created under the returned context inherits the verdict.
     *
     * <p><b>This is two-way, and deliberately so</b> — it can unlock a subtree the file around it locked, not
     * only lock one. That mattered when a method's verdict could differ from its file's; today the two agree
     * everywhere, since the only read-only cases left are whole-file (bundled library source, and a bot open
     * for reading). The only thing allowed to widen a lock is a {@link LockResolver} verdict — do not call
     * this with a hand-derived boolean.
     */
    public ParseContext withReadOnly(boolean ro) {
        return ro == readOnly ? this
                : new ParseContext(cu, sourceCode, comments, nodeToBlockMap, manager, ro, resolver, audience,
                        markNewIdentifiersAsUnedited);
    }
}
