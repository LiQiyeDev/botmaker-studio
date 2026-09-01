package com.botmaker.studio.palette;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Add Function dialog refuses, and why.
 *
 * <p>Each of these was reachable before the dialog existed: "+ Add Function" wrote {@code newMethod} straight
 * into the class, so the second click produced a duplicate method — a compile error the user met as a red
 * squiggle in a file they had not knowingly edited. The rules are Java's own, said before the edit rather than
 * after it, which is why they live in a record with no JDT in it and are tested without a parser.
 *
 * <p>"Java's own" is also why the clash check moved from the name to the whole signature: refusing
 * {@code click(Point)} because {@code click(int, int)} exists refuses an overload the compiler would have
 * accepted, which is the rule being stricter than the language rather than earlier than it.
 */
class FunctionDraftTest {

    private static final Set<String> TAKEN = Set.of("run()", "isEnabled()");

    private static String problem(String name) {
        return FunctionDraft.nameProblem(name).orElse("");
    }

    /** A no-parameter draft named {@code name}, checked against {@code taken}. */
    private static String problemOf(String name, Set<String> taken) {
        return new FunctionDraft(name, BotType.Choice.of(BotType.NOTHING), List.of())
                .problem(taken).orElse("");
    }

    @Test
    void anOrdinaryNameIsAccepted() {
        assertEquals(Optional.empty(), FunctionDraft.nameProblem("clickLoginButton"));
        assertEquals(Optional.empty(), FunctionDraft.nameProblem("  spaced  "), "trimmed, not refused");
    }

    @Test
    void aSignatureTheClassAlreadyDeclaresIsRefused() {
        // Including one the editor no longer draws: isEnabled() is MemberVisibility.NOBODY in an activity, so
        // the taken set comes off the AST. A member being invisible is not a signature being free.
        assertTrue(problemOf("run", TAKEN).contains("already has a function"));
        assertTrue(problemOf("isEnabled", TAKEN).contains("already has a function"));
    }

    @Test
    void anOverloadIsNotADuplicate() {
        Set<String> taken = Set.of("click(int,int)");
        FunctionDraft overload = new FunctionDraft("click", BotType.Choice.of(BotType.NOTHING),
                List.of(new FunctionDraft.Parameter("where", BotType.Choice.of(BotType.DATE))));
        FunctionDraft duplicate = new FunctionDraft("click", BotType.Choice.of(BotType.NOTHING),
                List.of(new FunctionDraft.Parameter("x", BotType.Choice.of(BotType.WHOLE_NUMBER)),
                        new FunctionDraft.Parameter("y", BotType.Choice.of(BotType.WHOLE_NUMBER))));

        assertEquals(Optional.empty(), overload.problem(taken), "different parameter types, so a new method");
        assertTrue(duplicate.problem(taken).orElse("").contains("exactly these types"),
                "same name and same types — this is the one Java refuses");
        assertEquals(Optional.empty(), duplicate.problem(Set.of()),
                "and the same signature is fine when nothing else declares it — the edit-mode case");
    }

    @Test
    void aSignatureKeyErasesTypeArgumentsAndPackages() {
        // Both halves of the comparison have to agree, and they read the type from different places: the
        // dialog writes "java.time.Duration", a hand-written file says "Duration", and to the compiler
        // List<Point> and List<Rect> are one parameter type.
        assertEquals("wait(Duration)", FunctionDraft.signatureKey("wait", List.of("java.time.Duration")));
        assertEquals("wait(Duration)", FunctionDraft.signatureKey("wait", List.of("Duration")));
        assertEquals("all(List)", FunctionDraft.signatureKey("all", List.of("List<Point>")));
        assertEquals("all(List)", FunctionDraft.signatureKey("all", List.of("List<Rect>")));
        assertEquals("List[]", FunctionDraft.erase("java.util.List<Point>[]"), "an array of a generic is still an array");
    }

    @Test
    void aDraftSpellsItsOwnKeyTheSameWay() {
        FunctionDraft draft = new FunctionDraft("wait", BotType.Choice.of(BotType.NOTHING),
                List.of(new FunctionDraft.Parameter("howLong", BotType.Choice.of(BotType.DURATION))));

        assertEquals("wait(Duration)", draft.signatureKey());
    }

    @Test
    void anIllegalIdentifierIsRefusedWithTheOffendingCharacter() {
        assertTrue(problem("").contains("Give the function a name"));
        assertTrue(problem("2fast").contains("start with a letter"));
        assertTrue(problem("click button").contains("\" \""), "the message names what is wrong: " + problem("click button"));
    }

    @Test
    void aKeywordIsRefusedEvenThoughItLooksLikeAnIdentifier() {
        assertTrue(problem("class").contains("Java keyword"));
        assertTrue(problem("null").contains("Java keyword"), "not a keyword in the spec, just as unusable");
        // Contextual keywords are legal method names, and refusing them would be inventing a rule.
        assertEquals(Optional.empty(), FunctionDraft.nameProblem("record"));
        assertEquals(Optional.empty(), FunctionDraft.nameProblem("yield"));
    }

    @Test
    void twoParametersCannotShareAName() {
        FunctionDraft draft = new FunctionDraft("go", BotType.Choice.of(BotType.NOTHING), List.of(
                new FunctionDraft.Parameter("where", BotType.Choice.of(BotType.DATE)),
                new FunctionDraft.Parameter("where", BotType.Choice.of(BotType.DURATION))));

        assertTrue(draft.problem(TAKEN).orElse("").contains("Two parameters"));
    }

    @Test
    void theSignaturePreviewIsWhatWillBeWritten() {
        FunctionDraft draft = new FunctionDraft("findAll", BotType.Choice.listOf(BotType.DATE), List.of(
                new FunctionDraft.Parameter("target", BotType.Choice.of(BotType.TEXT)),
                new FunctionDraft.Parameter("tries", BotType.Choice.of(BotType.WHOLE_NUMBER))));

        assertEquals("List<java.time.LocalDate> findAll(String target, int tries)", draft.signature());
    }

    @Test
    void theDropPathTakesTheNextFreeNameInsteadOfColliding() {
        // Dropping "Declare Function" on a class header has no dialog to refuse into. It wrote "newMethod"
        // unconditionally, so a second drop produced two methods of the same name.
        assertEquals("newMethod", FunctionDraft.freeName("newMethod", Set.of()));
        assertEquals("newMethod2", FunctionDraft.freeName("newMethod", Set.of("newMethod")));
        assertEquals("newMethod3", FunctionDraft.freeName("newMethod", Set.of("newMethod", "newMethod2")));
    }

    @Test
    void anUnfinishedDraftPreviewsWithoutThrowing() {
        // The preview updates on every keystroke, including the ones before there is a name.
        assertEquals("void …()", FunctionDraft.empty().signature());
    }
}
