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
                () -> assertProduces("System.out.println(\"hello\");", "PrintBlock"),
                () -> assertProduces("// a note", "CommentBlock"));
        // The ReadInputBlock arm — `int n = BotMaker.readInt();` — went on 2026-09-01 with the block, which
        // was the rendering of one SDK facade's four read methods.
    }

    /**
     * {@code System.out.println} is a call like any other, and the converter deliberately routes it to a
     * dedicated block before the generic library-call branch. Asserted from the other side too, because the
     * failure mode is silent: a reordered {@code instanceof} chain gives a working block with the wrong chrome.
     *
     * <p>It matched {@code BotMaker.print} until 2026-09-01 — the editor recognising one library's facade by
     * name, which is the same coupling as writing it. Print emits {@code System.out.println} now, so both
     * halves of the round trip name the JDK and nobody's plugin.
     */
    @Test
    void printIsNotJustAnotherLibraryCall() {
        assertTrue(blockKinds("System.out.println(\"hello\");").contains("PrintBlock"));
        assertTrue(!blockKinds("Mouse.click(1, 2);").contains("PrintBlock"),
                "an ordinary facade call must not become a print block");
        assertTrue(!blockKinds("BotMaker.print(\"hello\");").contains("PrintBlock"),
                "a library's own print is that library's call, not the editor's print block");
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

    /**
     * A facade call renders through the generic {@code LibraryCallBlock}, whatever library it is on.
     *
     * <p>This used to assert the reverse of a deletion: {@code blocks.misc.ClickBlock} existed, had no
     * construction site anywhere in the module, and the test held it dead so that a future change wanting a
     * bespoke click block would fail here and read why. The class is deleted (2026-09-01), with every other
     * file that spelled a {@code com.botmaker.sdk} type — it named {@code api.geometry.Point} to type a slot
     * nothing ever filled. What the test is for now is the generic path itself: a call on <em>anyone's</em>
     * library gets the standard block, and nothing here knows whose library it is.
     */
    @Test
    void aMouseClickUsesTheGenericLibraryCallBlock() {
        assertTrue(blockKinds("Mouse.click(1, 2);").contains("LibraryCallBlock"));
    }
}
