package com.botmaker.studio.blocks;

import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.parser.EditorFixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio blocks/parser MISSING 7 — {@code blocks.misc} and {@code blocks.var} construction.</b>
 * 19.4% and 43.0%.
 *
 * <p>These blocks are only ever built one way — {@code BlockConverter} picks the class from the AST shape —
 * so what needs asserting is the <b>mapping</b>: which source produces which block. Nothing else in the repo
 * pins it, and it is exactly the kind of thing a later refactor of {@code dispatchStatement}'s
 * {@code instanceof} chain reorders without noticing, because most of these shapes look interchangeable until
 * you know that {@code BotMaker.print(…)} must not become an ordinary method-invocation block.
 *
 * <p>Rendering is out of scope here (it needs the FX toolkit and is covered by the {@code ui/fx} tests); this
 * is the construction half, which is pure and costs nothing.
 */
class BlockConstructionTest {

    private static String inRun(String body) {
        return "package com.mybot;\n"
                + "public class Subject {\n"
                + "    private int field = 7;\n"
                + "    public void run() {\n"
                + body.indent(8)
                + "    }\n"
                + "}\n";
    }

    private static List<CodeBlock> flatten(CodeBlock from) {
        List<CodeBlock> out = new ArrayList<>();
        out.add(from);
        if (from instanceof BlockWithChildren p) {
            for (CodeBlock c : p.getChildren()) out.addAll(flatten(c));
        }
        return out;
    }

    /** The simple names of every block class in the tree built from {@code body} inside {@code run()}. */
    private static List<String> blockKinds(String body) {
        EditorFixture f = new EditorFixture(inRun(body));
        return flatten(f.root).stream().map(b -> b.getClass().getSimpleName()).toList();
    }

    private static void assertProduces(String body, String blockClass) {
        List<String> kinds = blockKinds(body);
        assertTrue(kinds.contains(blockClass),
                "'" + body.strip() + "' produced " + kinds + ", with no " + blockClass);
    }

    // ---- blocks.misc ----

    @Test
    void theMiscBlocksAreBuiltFromTheShapesTheyName() {
        assertAll(
                () -> assertProduces("BotMaker.print(\"hello\");", "PrintBlock"),
                () -> assertProduces("int n = BotMaker.readInt();", "ReadInputBlock"),
                () -> assertProduces("// a note", "CommentBlock"));
    }

    /**
     * {@code BotMaker.print} is a facade call like any other, and the converter deliberately routes it to a
     * dedicated block before the generic library-call branch. Asserted from the other side too, because the
     * failure mode is silent: a reordered {@code instanceof} chain gives a working block with the wrong chrome.
     */
    @Test
    void printIsNotJustAnotherLibraryCall() {
        assertTrue(blockKinds("BotMaker.print(\"hello\");").contains("PrintBlock"));
        assertTrue(!blockKinds("Mouse.click(1, 2);").contains("PrintBlock"),
                "an ordinary facade call must not become a print block");
    }

    /** A read is a variable declaration whose initializer is a {@code BotMaker.readX()} — not a plain one. */
    @Test
    void aReadInputIsDistinguishedFromAPlainDeclaration() {
        assertTrue(blockKinds("int n = BotMaker.readInt();").contains("ReadInputBlock"));
        assertTrue(!blockKinds("int n = 4;").contains("ReadInputBlock"));
    }

    // ---- blocks.var ----

    @Test
    void theVarBlocksAreBuiltFromTheShapesTheyName() {
        assertAll(
                () -> assertProduces("int local = 1;", "VariableDeclarationBlock"),
                () -> assertProduces("field = 9;", "AssignmentBlock"),
                () -> assertProduces("enum Colour { RED, BLUE }", "DeclareEnumBlock"));
    }

    /**
     * {@code i++} and {@code ++i} are assignments as far as the editor is concerned — the converter maps both
     * onto {@link com.botmaker.studio.blocks.var.AssignmentBlock} rather than leaving them as bare expression
     * statements, which is the difference between a loop counter you can edit and one you cannot.
     */
    @Test
    void incrementAndDecrementAreAssignments() {
        assertAll(
                () -> assertProduces("field++;", "AssignmentBlock"),
                () -> assertProduces("++field;", "AssignmentBlock"),
                () -> assertProduces("field--;", "AssignmentBlock"));
    }

    /** A field is a class-level member, so it hangs off the root rather than off a body. */
    @Test
    void aFieldBecomesAClassVariableBlock() {
        assertTrue(blockKinds("int local = 1;").contains("DeclareClassVariableBlock"),
                "the fixture's `private int field = 7;` must render as a class variable");
    }

    // ---- Dead code, asserted as dead ----

    /**
     * {@code blocks.misc.ClickBlock} has <b>no construction site</b> anywhere in the module: the converter
     * routes {@code Mouse.click(…)} through the generic {@code LibraryCallBlock} path like every other facade
     * call, which is what the code comment above that branch says it deliberately does. 45 lines that can
     * never render.
     *
     * <p>Asserted rather than just deleted so the deletion is a decision with a record: if a future change
     * wants a bespoke click block, this test fails and says why the class was empty-handed.
     */
    @Test
    void aMouseClickUsesTheGenericLibraryCallBlock() {
        List<String> kinds = blockKinds("Mouse.click(1, 2);");

        assertTrue(kinds.contains("LibraryCallBlock"), kinds.toString());
        assertEquals(0, kinds.stream().filter("ClickBlock"::equals).count(),
                "ClickBlock is unreachable — nothing in the module constructs it");
    }
}
