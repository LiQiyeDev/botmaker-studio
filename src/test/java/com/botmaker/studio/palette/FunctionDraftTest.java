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
 */
class FunctionDraftTest {

    private static final Set<String> TAKEN = Set.of("run", "isEnabled");

    private static String problem(String name) {
        return FunctionDraft.nameProblem(name, TAKEN).orElse("");
    }

    @Test
    void anOrdinaryNameIsAccepted() {
        assertEquals(Optional.empty(), FunctionDraft.nameProblem("clickLoginButton", TAKEN));
        assertEquals(Optional.empty(), FunctionDraft.nameProblem("  spaced  ", TAKEN), "trimmed, not refused");
    }

    @Test
    void aNameTheClassAlreadyUsesIsRefused() {
        // Including one the editor no longer draws: isEnabled() is MemberVisibility.NOBODY in an activity, so
        // the taken set comes off the AST. A name being invisible is not a name being free.
        assertTrue(problem("run").contains("already has a function"));
        assertTrue(problem("isEnabled").contains("already has a function"));
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
        assertEquals(Optional.empty(), FunctionDraft.nameProblem("record", TAKEN));
        assertEquals(Optional.empty(), FunctionDraft.nameProblem("yield", TAKEN));
    }

    @Test
    void twoParametersCannotShareAName() {
        FunctionDraft draft = new FunctionDraft("go", BotType.Choice.of(BotType.NOTHING), List.of(
                new FunctionDraft.Parameter("where", BotType.Choice.of(BotType.POINT)),
                new FunctionDraft.Parameter("where", BotType.Choice.of(BotType.RECT))));

        assertTrue(draft.problem(TAKEN).orElse("").contains("Two parameters"));
    }

    @Test
    void theSignaturePreviewIsWhatWillBeWritten() {
        FunctionDraft draft = new FunctionDraft("findAll", new BotType.Choice(BotType.MATCH_RESULT, true), List.of(
                new FunctionDraft.Parameter("target", BotType.Choice.of(BotType.IMAGE_TEMPLATE)),
                new FunctionDraft.Parameter("tries", BotType.Choice.of(BotType.WHOLE_NUMBER))));

        assertEquals("List<MatchResult> findAll(ImageTemplate target, int tries)", draft.signature());
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
