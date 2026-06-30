import com.botmaker.core.AbstractCodeBlock;
import com.botmaker.core.BlockWithChildren;
import com.botmaker.core.BodyBlock;
import com.botmaker.core.CodeBlock;
import com.botmaker.core.StatementBlock;
import com.botmaker.events.CoreApplicationEvents;
import com.botmaker.events.EventBus;
import com.botmaker.parser.BlockConverter;
import com.botmaker.parser.CodeEditor;
import com.botmaker.project.ProjectFile;
import com.botmaker.project.ProjectState;
import com.botmaker.palette.BlockCatalog;
import com.botmaker.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the AST edits that a drag-and-drop gesture produces. A drop publishes a
 * {@code BlockDropRequestedEvent} / {@code BlockMoveRequestedEvent}; {@code CodeEditorService} resolves it
 * (by id, via the same traversal mirrored here in {@link #findById}) and calls the matching {@link CodeEditor}
 * operation. This test drives those operations directly and asserts the resulting source, so a regression in the
 * add/move path is caught without needing the JavaFX toolkit.
 */
public class BlockDragDropEditTest {

    private static final String SOURCE = """
            package test;

            public class Subject {
                public void first() {
                    int x = 1;
                    int y = 2;
                }

                public void second() {
                }
            }
            """;

    private ProjectState state;
    private CodeEditor editor;
    private AbstractCodeBlock root;
    private String lastCode;

    @BeforeEach
    void setUp() {
        state = new ProjectState();
        Path path = Paths.get("Subject.java").toAbsolutePath();
        ProjectFile file = new ProjectFile(path, SOURCE);
        state.addFile(file);
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        EventBus bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode = e.newCode());

        BlockConverter converter = new BlockConverter(state);
        BlockConverter.ConvertResult result = converter.convert(
                SOURCE, state.getMutableNodeToBlockMap(),
                new BlockDragAndDropManager(bus), false, false);
        state.setCompilationUnit(result.cu());
        root = result.root();
        assertNotNull(root, "Converter should produce a root block for the class");

        editor = new CodeEditor(state, bus, new com.botmaker.suggestions.ProjectAnalyzer(null, state));
    }

    @Test
    void dropFromPalette_addsStatementToMethodBody() {
        BodyBlock body = firstBodyWithStatements();
        // Mirrors CodeEditorService.handleBlockDrop: targetBody present -> addStatement.
        editor.addStatement(body, BlockCatalog.PRINT, body.getStatements().size());

        assertNotNull(lastCode, "Drop should have produced a code update");
        assertTrue(lastCode.contains("BotMaker.print("), "Added Print block should appear in the source:\n" + lastCode);
    }

    @Test
    void dropDataDrivenVariants_buildTheRightSource() {
        // Exercises the data-carrying BlockType variants (VarDecl with a NewInstance initializer, ScannerRead),
        // proving the source comes from the block's data rather than a name-decoding switch.
        BodyBlock body = firstBodyWithStatements();

        editor.addStatement(body, BlockCatalog.DECLARE_RECT, body.getStatements().size());
        assertNotNull(lastCode, "Drop should have produced a code update");
        assertTrue(lastCode.contains("new Rect(0, 0, 0, 0)"),
                "DECLARE_RECT should build a Rect constructor:\n" + lastCode);

        editor.addStatement(body, BlockCatalog.READ_INT, body.getStatements().size());
        assertTrue(lastCode.contains("BotMaker.readInt()"),
                "READ_INT should build a BotMaker.readInt() read:\n" + lastCode);
    }

    @Test
    void dropMethodFromPalette_addsMethodToClass() {
        TypeDeclaration typeDecl = (TypeDeclaration) root.getAstNode();
        // BlockCatalog.METHOD_DECLARATION is a class member; this mirrors handleBlockDrop's class branch.
        editor.addMethodToClass(typeDecl, "newMethod", "void", 0);

        assertNotNull(lastCode, "Drop should have produced a code update");
        assertTrue(lastCode.contains("newMethod"), "Added method should appear in the source:\n" + lastCode);
    }

    @Test
    void moveExistingStatement_reordersWithinBody() {
        BodyBlock body = firstBodyWithStatements();
        StatementBlock xDecl = body.getStatements().get(0); // int x = 1;

        // Mirrors CodeEditorService.handleBlockMove: resolve the dragged block by id, then moveStatement.
        CodeBlock resolved = findById(root, xDecl.getId());
        assertTrue(resolved instanceof StatementBlock, "Block id should resolve back to the dragged statement");

        // Move "int x = 1;" to the end (after "int y = 2;").
        editor.moveStatement((StatementBlock) resolved, body, body, 2);

        assertNotNull(lastCode, "Move should have produced a code update");
        int xPos = lastCode.indexOf("int x");
        int yPos = lastCode.indexOf("int y");
        assertTrue(xPos > yPos && yPos >= 0, "After the move, y should precede x:\n" + lastCode);
    }

    @Test
    void moveExistingMethod_reordersClassMembers() {
        // Resolve the second method (the one with an empty body) and move it before the first.
        CodeBlock secondMethod = methodBlocks().get(1);
        ASTNode node = secondMethod.getAstNode();
        assertTrue(node instanceof BodyDeclaration, "Method block should be backed by a BodyDeclaration");

        editor.moveBodyDeclaration((BodyDeclaration) node, (TypeDeclaration) root.getAstNode(), 0);

        assertNotNull(lastCode, "Move should have produced a code update");
        int firstPos = lastCode.indexOf("first(");
        int secondPos = lastCode.indexOf("second(");
        assertTrue(secondPos < firstPos && secondPos >= 0, "After the move, second() should precede first():\n" + lastCode);
    }

    // --- helpers ---

    private BodyBlock firstBodyWithStatements() {
        BodyBlock body = collect(root, BodyBlock.class).stream()
                .filter(b -> !b.getStatements().isEmpty())
                .findFirst().orElse(null);
        assertNotNull(body, "Expected a method body with statements");
        return body;
    }

    private List<CodeBlock> methodBlocks() {
        // The class's direct children are its member declarations (methods).
        List<CodeBlock> methods = ((BlockWithChildren) root).getChildren();
        assertTrue(methods.size() >= 2, "Expected at least two methods on the class");
        return methods;
    }

    /** Mirror of CodeEditorService.findBlockById: depth-first search by stable block id. */
    private static CodeBlock findById(CodeBlock block, String id) {
        if (id.equals(block.getId())) return block;
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) {
                CodeBlock found = findById(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends CodeBlock> List<T> collect(CodeBlock block, Class<T> type) {
        List<T> out = new ArrayList<>();
        collectInto(block, type, out);
        return out;
    }

    private static <T extends CodeBlock> void collectInto(CodeBlock block, Class<T> type, List<T> out) {
        if (type.isInstance(block)) out.add(type.cast(block));
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) collectInto(child, type, out);
        }
    }
}
