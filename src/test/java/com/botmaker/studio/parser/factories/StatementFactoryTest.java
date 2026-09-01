package com.botmaker.studio.parser.factories;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.BlockType.ControlFlow.Kind;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.Initializer;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** A parsed host unit wrapped in the write-path context every factory call takes. */
    private static EditContext env() {
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), HOST, TestSupport.SOURCE_ROOT);
        assertNotNull(cu, "host unit must parse");
        return EditContext.of(cu, null, new ProjectState());
    }

    /** Builds {@code type} with nothing in scope — the "dropped into an empty method" case. */
    private static Statement build(BlockType type) {
        return StatementFactory.createStatement(env(), type, null);
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
     * The other half of the same rule, and the correction to it. Where a name would have to <em>resolve</em>,
     * the factory names nothing — but "names nothing" used to mean {@code null} in every such position, and
     * {@code switch (null)} / {@code for (var item : null)} are compile errors in Java outright, so a drop into
     * an empty method produced source that could not build. Where a value can stand, one that compiles now
     * does; only {@link UnfilledSlot}'s name positions stay empty.
     */
    @Test
    void thePositionsThatCanHoldAValueAreSeededWithOneThatCompiles() {
        assertAll(
                () -> assertTrue(text(controlFlow(Kind.FOR)).contains(": new String[0]"),
                        "the iterated expression: " + text(controlFlow(Kind.FOR))),
                () -> assertTrue(text(controlFlow(Kind.SWITCH)).startsWith("switch (0)"),
                        "the switch subject: " + text(controlFlow(Kind.SWITCH))));
    }

    /** And the one that can't: an lvalue is a name, so there is no literal to stand in for it. */
    @Test
    void theAssignmentTargetIsLeftUnfilledBecauseNoValueWouldCompileThere() {
        assertTrue(text(controlFlow(Kind.ASSIGNMENT)).contains("null"),
                "the assignment target: " + text(controlFlow(Kind.ASSIGNMENT)));
    }

    // ---- The data-carrying variants are built generically from their fields ----

    @Test
    void aVarDeclCarriesItsOwnTypeAndName() {
        String source = text(BlockCatalog.DECLARE_INT);
        assertTrue(source.startsWith("int "), source);
        assertTrue(source.endsWith(";"), source);
    }

    // aScannerReadBecomesTheBotMakerCallItNames went on 2026-09-01 with READ_INT and the whole ScannerRead
    // variant. It asserted the factory wrote `BotMaker.readInt()` — which was the defect, not the contract:
    // the editor was emitting one library's facade call from a palette entry of its own.

    /**
     * The fixtures are built here rather than taken from {@link BlockCatalog}, and the reason is the point of
     * the change that forced it: the palette's own {@code LibraryCall}s and {@code LambdaCall}s were calls on
     * the SDK's facades, and they were deleted on 2026-09-01 because a call on a plugin's type is that
     * plugin's to offer. What the factory does with either shape is not about who owns the facade, so the test
     * names a JDK class and asserts the shape.
     */
    private static BlockType.LibraryCall libraryCall() {
        return new BlockType.LibraryCall("TEST_LIB", "Library Call", BlockCategory.UTILITY,
                java.util.Objects.class, "requireNonNull", List.of(new Initializer.StrLit("x")));
    }

    @Test
    void aLibraryCallBecomesTheStaticCallItNames() {
        String source = text(libraryCall());
        assertTrue(source.startsWith("Objects.requireNonNull("), source);
    }

    @Test
    void aLambdaCallEndsInABodyLambda() {
        String source = text(new BlockType.LambdaCall("TEST_LAMBDA", "Lambda Call", BlockCategory.UTILITY,
                java.util.Optional.class, "ifPresent", List.of(), "value"));

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

    // theWaitBlockInsertsAnSdkCallAndNotARawThreadSleep stood here, asserting that the palette's WAIT entry
    // emitted Wait.time(Duration.…) rather than a raw Thread.sleep. Its subject is gone: WAIT was a hand-written
    // LibraryCall on the SDK's Wait facade, deleted on 2026-09-01 with every other palette entry that named a
    // plugin's type, and a Wait block now comes from the plugin's own catalog. The test's own javadoc had
    // already concluded that what remained of SP8 was a deletion; this is it.
    //
    // What it also asserted — that no generated bot source carries a printStackTrace — is not lost: the dead
    // Kind.WAIT branch below is still asserted dead, and that branch is the only place in the factory that ever
    // emitted one. BlockConverter.isWait is likewise untouched, so an existing bot's hand-written Thread.sleep
    // still round-trips through the editor.

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

    /**
     * Date, Time of day and Duration used to insert <b>nothing at all</b>. Their seed names the JDK type
     * fully ({@code java.time.LocalDate}) and the factory passed that dotted string to
     * {@code ast.newSimpleName}, which rejects it — so the drop threw and the palette entry looked broken.
     * The Colour seed had the same defect latent, masked only because it is filtered out of the menu.
     */
    @Test
    void theDateTimeAndDurationBlocksInsertSomething() {
        assertAll(
                () -> assertTrue(text(BlockCatalog.declareBlockFor(BotType.DATE))
                        .contains("java.time.LocalDate.now()"), "Date"),
                () -> assertTrue(text(BlockCatalog.declareBlockFor(BotType.TIME_OF_DAY))
                        .contains("java.time.LocalTime.of(0,0)"), "Time of day"),
                () -> assertTrue(text(BlockCatalog.declareBlockFor(BotType.DURATION))
                        .contains("java.time.Duration.ofSeconds(0)"), "Duration"),
                () -> assertTrue(text(BlockCatalog.declareBlockFor(BotType.COLOR))
                        .contains("new java.awt.Color(255,255,255)"), "Colour"));
    }

    /**
     * The collision fix. Dropping the same declare block twice used to declare the same name twice, because
     * {@code uniqueName} asked {@code getVisibleVariables} — a binding walk that comes back empty whenever the
     * classpath is unresolved, which in the editor is most of the time. Here the drop site is a real method
     * that already declares {@code number}, and the analyzer is deliberately null.
     */
    @Test
    void asecondDropOfTheSameDeclareBlockGetsItsOwnName() {
        String host = """
                package com.mybot;
                public class Subject {
                    public void run() {
                        int number = 0;
                    }
                }
                """;
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), host, TestSupport.SOURCE_ROOT);
        assertNotNull(cu);
        MethodDeclaration run = (MethodDeclaration) ((TypeDeclaration) cu.types().getFirst())
                .bodyDeclarations().getFirst();

        Statement second = StatementFactory.createStatement(
                EditContext.of(cu, null, new ProjectState()),
                BlockCatalog.declareBlockFor(BotType.WHOLE_NUMBER), run.getBody());

        assertNotNull(second);
        assertTrue(second.toString().contains("number2"),
                "the second drop must not redeclare `number`: " + second);
    }

    /**
     * {@link BlockType#producesValue()} is what an expression slot asks during a drag, and the write path
     * ({@code CodeEditor.fillSlotFromPalette}) answers the same question structurally — it builds the statement
     * and keeps it only if it is an {@code ExpressionStatement}. If the two disagree the slot accepts a block
     * with a green outline and then does nothing, which is the failure mode the drag layer had before: it
     * asked {@code instanceof BlockType.LibraryCall} and so refused every other block that is in fact a call.
     */
    @Test
    void aBlockThatSaysItProducesAValueBuildsAnExpressionStatement() {
        assertAll(BlockCatalog.all().stream()
                .filter(BlockType::producesValue)
                .map(type -> (org.junit.jupiter.api.function.Executable) () ->
                        assertTrue(build(type) instanceof org.eclipse.jdt.core.dom.ExpressionStatement,
                                type.id() + " claims to be a value but builds " + build(type)))
                .toList());
    }

    @Test
    void aWholeStatementDoesNotClaimToBeAValue() {
        assertAll(
                () -> assertTrue(controlFlow(Kind.FUNCTION_CALL).producesValue(), "a call is a value"),
                () -> assertFalse(controlFlow(Kind.IF).producesValue()),
                () -> assertFalse(controlFlow(Kind.WHILE).producesValue()),
                () -> assertFalse(controlFlow(Kind.BREAK).producesValue()),
                () -> assertFalse(controlFlow(Kind.ASSIGNMENT).producesValue(),
                        "an assignment is an expression in Java, but nobody drags one into a print meaning to"),
                () -> assertFalse(BlockCatalog.declareBlockFor(BotType.WHOLE_NUMBER).producesValue(),
                        "a declaration is a line, not a value"));
    }
}
