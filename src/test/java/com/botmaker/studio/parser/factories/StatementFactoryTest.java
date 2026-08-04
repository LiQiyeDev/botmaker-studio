package com.botmaker.studio.parser.factories;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.BlockType.ControlFlow.Kind;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio blocks/parser MISSING 4 and 6 — what a dropped palette block actually becomes.</b>
 *
 * <p>{@link StatementFactory} builds the AST for every insertable statement in the palette, and nothing
 * asserted any of the shapes: {@code parser.factories} sat at 23.0% over 395 lines. Every one of these is a
 * pure {@code (AST, BlockType) -> Statement} call with no display, no process and no fixture beyond a source
 * string — which is why this is the cheapest coverage in the module.
 *
 * <p>The contract worth protecting is not the exact text but the rule the file's own comment states, learned
 * from a bug: <b>name only something that exists at the drop site.</b> These four blocks used to be seeded
 * with invented identifiers ({@code switch (variable)}, {@code variable = 0},
 * {@code for (String item : array)}, {@code BotMaker.DefaultMethod()}), so every drop produced an immediate
 * "cannot resolve symbol". With no analyzer — nothing in scope — the answer must be an empty slot the user
 * fills, never a name.
 */
class StatementFactoryTest {

    private static final String HOST = """
            package com.mybot;
            public class Subject {
                public void run() {
                }
            }
            """;

    /** A parsed host unit, its AST and a rewriter — the three things every factory call takes. */
    private record Env(CompilationUnit cu, AST ast, ASTRewrite rewriter, ProjectState state) {}

    private static Env env() {
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), HOST, TestSupport.SOURCE_ROOT);
        assertNotNull(cu, "host unit must parse");
        return new Env(cu, cu.getAST(), ASTRewrite.create(cu.getAST()), new ProjectState());
    }

    /** Builds {@code type} with nothing in scope — the "dropped into an empty method" case. */
    private static Statement build(BlockType type) {
        Env e = env();
        return StatementFactory.createStatement(e.ast(), type, e.cu(), e.rewriter(), e.state(), null, null);
    }

    private static BlockType controlFlow(Kind kind) {
        return new BlockType.ControlFlow(kind.name(), kind.name(), BlockCategory.CONTROL, kind);
    }

    private static String text(BlockType type) {
        Statement s = build(type);
        assertNotNull(s, "the factory built nothing for " + type.id());
        return s.toString().replaceAll("\\s+", " ").trim();
    }

    // ---- Every ControlFlow kind builds something ----

    @Test
    void everyControlFlowKindBuildsAStatement() {
        assertAll(java.util.Arrays.stream(Kind.values())
                .map(k -> (org.junit.jupiter.api.function.Executable) () ->
                        assertNotNull(build(controlFlow(k)),
                                "Kind." + k + " built no statement — a palette entry mapped to it would drop "
                                        + "silently, which is the shape CodeEditor.addStatement turns into a "
                                        + "no-op publish"))
                .toList());
    }

    @Test
    void theSimpleFlowKindsHaveTheObviousShape() {
        assertAll(
                () -> assertEquals("break;", text(controlFlow(Kind.BREAK))),
                () -> assertEquals("continue;", text(controlFlow(Kind.CONTINUE))),
                () -> assertEquals("return;", text(controlFlow(Kind.RETURN))),
                () -> assertTrue(text(controlFlow(Kind.IF)).startsWith("if ("), text(controlFlow(Kind.IF))),
                () -> assertTrue(text(controlFlow(Kind.WHILE)).startsWith("while ("), text(controlFlow(Kind.WHILE))),
                () -> assertTrue(text(controlFlow(Kind.DO_WHILE)).startsWith("do "), text(controlFlow(Kind.DO_WHILE))),
                () -> assertTrue(text(controlFlow(Kind.PRINT)).contains("print"), text(controlFlow(Kind.PRINT))));
    }

    /**
     * The regression this whole section exists for. With no analyzer nothing is in scope, so a seeded
     * identifier would be a guaranteed compile error the moment the block lands.
     */
    @Test
    void nothingIsSeededWithAnInventedIdentifier() {
        // Reference positions only. A for-loop's `var item :` is a *declaration* — it introduces the name
        // rather than resolving one — so it is not an invented identifier; what mattered there was the
        // iterated expression, asserted separately below.
        List<String> invented = List.of("variable", "array", "DefaultMethod");
        assertAll(java.util.Arrays.stream(Kind.values())
                .map(k -> (org.junit.jupiter.api.function.Executable) () -> {
                    String source = text(controlFlow(k));
                    for (String name : invented) {
                        assertTrue(!source.contains(name),
                                "Kind." + k + " seeded the identifier '" + name + "', which resolves to nothing "
                                        + "at the drop site: " + source);
                    }
                })
                .toList());
    }

    /**
     * The other half of the same rule: where a name would have to <em>resolve</em>, the factory leaves the
     * empty slot the user fills from the expression menu — a null literal, the convention
     * {@code buildLibraryCall} already used.
     */
    @Test
    void thePositionsThatNeedAnExistingNameAreLeftAsAnEmptySlot() {
        assertAll(
                () -> assertTrue(text(controlFlow(Kind.FOR)).contains(": null"),
                        "the iterated expression: " + text(controlFlow(Kind.FOR))),
                () -> assertTrue(text(controlFlow(Kind.SWITCH)).startsWith("switch (null)"),
                        "the switch subject: " + text(controlFlow(Kind.SWITCH))),
                () -> assertTrue(text(controlFlow(Kind.ASSIGNMENT)).contains("null"),
                        "the assignment target: " + text(controlFlow(Kind.ASSIGNMENT))));
    }

    // ---- The data-carrying variants are built generically from their fields ----

    @Test
    void aVarDeclCarriesItsOwnTypeAndName() {
        String source = text(BlockCatalog.DECLARE_INT);
        assertTrue(source.startsWith("int "), source);
        assertTrue(source.endsWith(";"), source);
    }

    @Test
    void aScannerReadBecomesTheBotMakerCallItNames() {
        String source = text(BlockCatalog.READ_INT);
        assertTrue(source.contains("BotMaker.read"), source);
    }

    @Test
    void aLibraryCallBecomesTheStaticCallItNames() {
        String source = text(BlockCatalog.CLICK);
        assertTrue(source.startsWith("Mouse.click("), source);
    }

    @Test
    void aLambdaCallEndsInABodyLambda() {
        String source = text(BlockCatalog.FIND_IMAGE_ACTIONS);
        assertTrue(source.contains("->"), "the trailing argument must be a lambda: " + source);
        assertTrue(source.contains("{"), "and it must have a block body to drop statements into: " + source);
    }

    @Test
    void anEnumDeclarationIsAStatement() {
        assertTrue(text(BlockCatalog.DECLARE_ENUM).contains("enum"), text(BlockCatalog.DECLARE_ENUM));
    }

    /**
     * A method is a class member, not a body statement. The factory says so by returning {@code null} — the
     * one live null path through {@code CodeEditor.addStatement}, and the reason B11 has a third site.
     */
    @Test
    void aMethodMemberIsNotABodyStatement() {
        assertNull(build(new BlockType.MethodMember("METHOD", "Method", BlockCategory.FUNCTIONS)),
                "a method must not be buildable as a statement");
    }

    // ---- MISSING 6: what the Wait block emits, and what it no longer emits ----

    /**
     * <b>What running this showed: SP8's target is already unreachable.</b>
     *
     * <p>SP8 is listed as a cross-module item — "{@code StatementFactory} stops emitting
     * {@code printStackTrace} into bot source", with the SDK's {@code Debug} channel as the replacement, which
     * would make it the only blocks/parser item needing a coordinated SDK release. It doesn't: the palette's
     * {@code WAIT} entry is a {@code LibraryCall} onto the SDK's {@code Wait.time}, and
     * {@code Kind.WAIT} — the only route to {@code createWaitStatement}, the only place in the factory that
     * emits {@code printStackTrace} — has <b>no reference anywhere in the module</b>.
     *
     * <p>So what remains of SP8 is a deletion, not a release. {@code BlockConverter.isWait} must stay: it is
     * what lets an existing bot's hand-written {@code Thread.sleep} still round-trip through the editor.
     */
    @Test
    void theWaitBlockInsertsAnSdkCallAndNotARawThreadSleep() {
        String inserted = text(BlockCatalog.WAIT);

        assertTrue(inserted.startsWith("Wait.time(Duration."),
                "the palette's Wait must insert the SDK call, in its editable Duration form: " + inserted);
        assertTrue(!inserted.contains("Thread.sleep"), inserted);
        assertTrue(!inserted.contains("printStackTrace"),
                "no generated bot source may carry a printStackTrace: " + inserted);
    }

    /**
     * The dead branch, asserted as dead. {@code Kind.WAIT} still builds the old template — including the
     * {@code printStackTrace} — so this pins what SP8 is deleting rather than pretending it is already gone.
     */
    @Test
    void theUnreachableWaitKindStillBuildsTheOldTemplate() {
        String legacy = text(controlFlow(Kind.WAIT));

        assertTrue(legacy.contains("Thread.sleep(1000)"), legacy);
        assertTrue(legacy.contains("printStackTrace"),
                "the branch SP8 exists to remove — reachable only from a Kind no palette entry uses: " + legacy);
    }
}
