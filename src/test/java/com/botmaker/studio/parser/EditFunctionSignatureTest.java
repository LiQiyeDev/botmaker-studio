package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing a signature that already exists — the Edit button on a method header.
 *
 * <p>The header used to offer five controls that each rewrote the file on their own: a name field, a
 * return-type chip, and a type chip plus a name field per parameter. Getting from {@code clickAt(Point, int)}
 * to {@code tapAt(Rect)} therefore went through three or four intermediate signatures, every one of them
 * written to disk, compiled, and wrong. So the assertion that matters most here is the count: <b>one</b> code
 * update for the whole change.
 */
class EditFunctionSignatureTest {

    private static final String SOURCE = """
            package test;

            import com.botmaker.sdk.api.Point;

            public class Subject {
                public boolean clickAt(Point where, int tries) {
                    System.out.println(tries);
                    return where != null;
                }

                public void other() {
                }
            }
            """;

    private ProjectState state;
    private Path path;
    private CodeEditor editor;
    private String lastCode;
    private int updates;

    @BeforeEach
    void setUp() {
        state = new ProjectState();
        path = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, SOURCE));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        EventBus bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> {
            lastCode = e.newCode();
            updates++;
        });

        state.setCompilationUnit(com.botmaker.studio.parser.helpers.SourceParser.parse(SOURCE));
        editor = new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state));
    }

    /** Points the editor at a different source — the file, the current code and the parse, which must agree. */
    private void reopen(String source) {
        state.addFile(new ProjectFile(path, source));
        state.setCurrentCode(source);
        state.setCompilationUnit(com.botmaker.studio.parser.helpers.SourceParser.parse(source));
    }

    private TypeDeclaration subject() {
        return (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
    }

    private MethodDeclaration clickAt() {
        return subject().getMethods()[0];
    }

    /** A parameter the user has just added — no origin, so the write treats it as new. */
    private static FunctionDraft.Parameter param(String name, BotType type) {
        return new FunctionDraft.Parameter(name, BotType.Choice.of(type));
    }

    /** The parameter that stood at {@code origin} in the method being edited — what the dialog stamps. */
    private static FunctionDraft.Parameter param(String name, BotType type, int origin) {
        return param(name, type).withOrigin(origin);
    }

    @Test
    void theWholeSignatureIsRewrittenInOneEdit() {
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("tapAt",
                BotType.Choice.of(BotType.NOTHING), List.of(param("area", BotType.RECT))));

        assertEquals(1, updates, "a half-applied signature must not be reachable, so there is one write");
        assertTrue(lastCode.contains("public void tapAt(Rect area)"), lastCode);
        assertTrue(lastCode.contains("import com.botmaker.sdk.api.Rect;"),
                "a new parameter type brings its import:\n" + lastCode);
    }

    @Test
    void renamingAParameterRenamesItsUsesInTheBody() {
        // What the per-parameter name field never did: it renamed the declaration only, so the body was left
        // referring to a name that no longer existed.
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.YES_NO),
                List.of(param("target", BotType.POINT, 0), param("attempts", BotType.WHOLE_NUMBER, 1))));

        assertTrue(lastCode.contains("clickAt(Point target, int attempts)"), lastCode);
        assertTrue(lastCode.contains("System.out.println(attempts);"), lastCode);
        assertTrue(lastCode.contains("return target != null;"), lastCode);
        assertFalse(lastCode.contains("tries"), "no reference to the old name survives:\n" + lastCode);
    }

    @Test
    void retypingAParameterInPlaceLeavesTheBodyAlone() {
        // Parameters match the draft by origin, so changing a type is not "remove then add" — the body's
        // references to that name keep working.
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.YES_NO),
                List.of(param("where", BotType.RECT, 0), param("tries", BotType.WHOLE_NUMBER, 1))));

        assertTrue(lastCode.contains("clickAt(Rect where, int tries)"), lastCode);
        assertTrue(lastCode.contains("return where != null;"), lastCode);
    }

    @Test
    void aSurplusParameterIsRemovedAndANewOneAppended() {
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.YES_NO),
                List.of(param("where", BotType.POINT, 0), param("howLong", BotType.DURATION))));

        assertTrue(lastCode.contains("clickAt(Point where, java.time.Duration howLong)"), lastCode);
        assertFalse(lastCode.contains("int tries"), lastCode);
    }

    @Test
    void movingAParameterMovesItRatherThanRetypingWhatSatThere() {
        // The whole reason a parameter carries an origin. By position this reads as "parameter 0 is now an int
        // called tries" and "parameter 1 is now a Point called where" — two retypes, and every reference in the
        // body silently changed meaning. By origin it is one move.
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.YES_NO),
                List.of(param("tries", BotType.WHOLE_NUMBER, 1), param("where", BotType.POINT, 0))));

        assertEquals(1, updates, "a reorder is still one write");
        assertTrue(lastCode.contains("clickAt(int tries, Point where)"),
                "both parameters kept their own name and type on the way:\n" + lastCode);
        assertTrue(lastCode.contains("System.out.println(tries);"), lastCode);
        assertTrue(lastCode.contains("return where != null;"), lastCode);
    }

    @Test
    void aMovedParameterCanBeRenamedInTheSameEdit() {
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.YES_NO),
                List.of(param("attempts", BotType.WHOLE_NUMBER, 1), param("target", BotType.POINT, 0))));

        assertTrue(lastCode.contains("clickAt(int attempts, Point target)"), lastCode);
        assertTrue(lastCode.contains("System.out.println(attempts);"),
                "the body follows the parameter it was renamed from:\n" + lastCode);
        assertTrue(lastCode.contains("return target != null;"), lastCode);
    }

    @Test
    void aDraftReadOutOfTheFileKnowsWhereItsParametersCameFrom() {
        FunctionDraft draft = MethodSignatures.draftOf(clickAt()).orElseThrow();

        assertEquals(0, draft.parameters().getFirst().origin());
        assertEquals(1, draft.parameters().get(1).origin());
        assertFalse(draft.parameters().getFirst().isNew(),
                "a parameter the file already has is never new");
    }

    @Test
    void aReturnTypeChangedToNothingDropsTheTrailingReturn() {
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.NOTHING), List.of(param("where", BotType.POINT, 0))));

        assertTrue(lastCode.contains("public void clickAt(Point where)"), lastCode);
        assertFalse(lastCode.contains("return where != null;"),
                "a void method cannot keep returning a value:\n" + lastCode);
    }

    @Test
    void theClassReportsItsSignaturesAndReadsOneBackAsADraft() {
        assertEquals(Set.of("clickAt(Point,int)", "other()"), MethodSignatures.declaredIn(subject()));

        FunctionDraft draft = MethodSignatures.draftOf(clickAt()).orElseThrow();
        assertEquals("boolean clickAt(Point where, int tries)", draft.signature(),
                "what the Edit dialog opens on is what the file says");
        assertEquals(MethodSignatures.keyOf(clickAt()), draft.signatureKey(),
                "both sides of the clash check spell the same signature the same way");
    }

    @Test
    void aSignatureTheDialogCannotRepresentIsNotOfferedForEditing() {
        // main(String[] args): the dialog can only offer the curated BotType list, and silently retyping
        // String[] to String on the way through would be worse than refusing to open.
        reopen(SOURCE.replace("public void other() {", "public static void main(String[] args) {"));

        Optional<FunctionDraft> draft = MethodSignatures.draftOf(subject().getMethods()[1]);
        assertTrue(draft.isEmpty(), "a String[] parameter has no BotType to render it with");
    }

    @Test
    void aTypeTheEditorCannotDescribeIsKeptRatherThanRefused() {
        // An activity's `Outcome run(int)`: the editor has no Outcome in its catalogue, and it does not need
        // one — nobody asked it to change that type. Refusing the whole dialog over it took the name and the
        // inputs with it, which is what the user actually came to edit.
        reopen(withOutcomeRun());

        FunctionDraft draft = MethodSignatures.draftOf(subject().getMethods()[1]).orElseThrow();
        assertTrue(draft.returnType().isKept(), "Outcome is carried, not described");
        assertEquals("Outcome run(int attempts)", draft.signature());
    }

    @Test
    void aKeptTypeIsWrittenBackExactlyAsTheFileHadIt() {
        reopen(withOutcomeRun());
        MethodDeclaration run = subject().getMethods()[1];

        // The rename the dialog is open for, with the return type left as the file wrote it.
        editor.applyFunctionSignature(run, new FunctionDraft("runOnce",
                MethodSignatures.draftOf(run).orElseThrow().returnType(),
                List.of(param("tries", BotType.WHOLE_NUMBER, 0))));

        assertTrue(lastCode.contains("public Outcome runOnce(int tries)"), lastCode);
        assertTrue(lastCode.contains("return Outcome.done();"),
                "the trailing return belongs to a type the editor never described:\n" + lastCode);
    }

    /** {@link #SOURCE} with a second method whose return type is outside the editor's catalogue. */
    private static String withOutcomeRun() {
        return SOURCE.replace("""
                    public void other() {
                    }
                """, """
                    public Outcome run(int attempts) {
                        return Outcome.done();
                    }
                """);
    }
}
