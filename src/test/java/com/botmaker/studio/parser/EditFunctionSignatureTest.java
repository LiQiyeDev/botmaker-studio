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
    private CodeEditor editor;
    private String lastCode;
    private int updates;

    @BeforeEach
    void setUp() {
        state = new ProjectState();
        Path path = Paths.get("Subject.java").toAbsolutePath();
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

    private TypeDeclaration subject() {
        return (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
    }

    private MethodDeclaration clickAt() {
        return subject().getMethods()[0];
    }

    private static FunctionDraft.Parameter param(String name, BotType type) {
        return new FunctionDraft.Parameter(name, BotType.Choice.of(type));
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
                List.of(param("target", BotType.POINT), param("attempts", BotType.WHOLE_NUMBER))));

        assertTrue(lastCode.contains("clickAt(Point target, int attempts)"), lastCode);
        assertTrue(lastCode.contains("System.out.println(attempts);"), lastCode);
        assertTrue(lastCode.contains("return target != null;"), lastCode);
        assertFalse(lastCode.contains("tries"), "no reference to the old name survives:\n" + lastCode);
    }

    @Test
    void retypingAParameterInPlaceLeavesTheBodyAlone() {
        // Parameters match the draft by position, so changing a type is not "remove then add" — the body's
        // references to that name keep working.
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.YES_NO),
                List.of(param("where", BotType.RECT), param("tries", BotType.WHOLE_NUMBER))));

        assertTrue(lastCode.contains("clickAt(Rect where, int tries)"), lastCode);
        assertTrue(lastCode.contains("return where != null;"), lastCode);
    }

    @Test
    void aSurplusParameterIsRemovedAndANewOneAppended() {
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.YES_NO),
                List.of(param("where", BotType.POINT), param("howLong", BotType.DURATION))));

        assertTrue(lastCode.contains("clickAt(Point where, java.time.Duration howLong)"), lastCode);
        assertFalse(lastCode.contains("int tries"), lastCode);
    }

    @Test
    void aReturnTypeChangedToNothingDropsTheTrailingReturn() {
        editor.applyFunctionSignature(clickAt(), new FunctionDraft("clickAt",
                BotType.Choice.of(BotType.NOTHING), List.of(param("where", BotType.POINT))));

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
        String withMain = SOURCE.replace("public void other() {", "public static void main(String[] args) {");
        state.setCompilationUnit(com.botmaker.studio.parser.helpers.SourceParser.parse(withMain));

        Optional<FunctionDraft> draft = MethodSignatures.draftOf(subject().getMethods()[1]);
        assertTrue(draft.isEmpty(), "a String[] parameter has no BotType to render it with");
    }
}
