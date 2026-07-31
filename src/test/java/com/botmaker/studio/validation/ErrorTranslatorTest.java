package com.botmaker.studio.validation;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio remainder MISSING 1 — {@link ErrorTranslator} maps every registered JDT problem id.</b>
 *
 * <p>376 lines at 1.3%, no dependencies, and every compile message the user reads passes through it — which
 * is what made the audit call this the cheapest test in the repo. Writing it turned up that the second half
 * of that sentence is not true (see the last section, and <b>B20</b> in {@code docs/refactor/bugs.md}): no
 * compiler diagnostic reaches this class at all. The table is still asserted here, in full, because the day
 * one does the mapping is what the user will see, and because a table nobody can enumerate is a table that
 * rots.
 */
class ErrorTranslatorTest {

    /** The 26-entry problem-id table, read straight off the class so a new entry cannot escape this file. */
    @SuppressWarnings("unchecked")
    private static Map<Integer, ErrorTranslator.ErrorInfo> mappings() {
        try {
            Field f = ErrorTranslator.class.getDeclaredField("ERROR_MAPPINGS");
            f.setAccessible(true);
            return new TreeMap<>((Map<Integer, ErrorTranslator.ErrorInfo>) f.get(null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("ERROR_MAPPINGS moved; this test is the only enumeration of it", e);
        }
    }

    /** A diagnostic shaped like the ones this class was written for: the JDT id embedded in the text. */
    private static Diagnostic diagnostic(String message) {
        Diagnostic d = new Diagnostic();
        d.setSeverity(DiagnosticSeverity.Error);
        d.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        d.setMessage(message);
        return d;
    }

    private static Diagnostic forProblem(int problemId, String text) {
        return diagnostic(text + " [" + problemId + "]");
    }

    // --- The table ---

    /**
     * The completeness check. Every id in the table must be reachable <em>by that id</em> and must carry a
     * non-blank message and suggestion — otherwise an entry can be added, spelled wrong, and silently never
     * fire. Same shape as the SDK's reflective accessor check, and for the same reason.
     */
    @Test
    void everyRegisteredProblemIdIsReachableAndCarriesBothHalvesOfTheAdvice() {
        Map<Integer, ErrorTranslator.ErrorInfo> table = mappings();
        assertFalse(table.isEmpty(), "the table is the class");

        for (Map.Entry<Integer, ErrorTranslator.ErrorInfo> e : table.entrySet()) {
            int id = e.getKey();
            ErrorTranslator.ErrorInfo info = e.getValue();

            assertFalse(info.userMessage == null || info.userMessage.isBlank(),
                    "problem " + id + " has no user message");
            assertFalse(info.suggestion == null || info.suggestion.isBlank(),
                    "problem " + id + " has no suggestion — the 💡 line would be empty");

            Diagnostic d = forProblem(id, "some compiler wording");
            assertEquals(info.suggestion, ErrorTranslator.getSuggestion(d),
                    "problem " + id + " is in the table but not reachable through it");
            assertTrue(ErrorTranslator.translateSingleDiagnostic(d).contains(info.suggestion),
                    "problem " + id + "'s full translation must carry the suggestion");
        }
    }

    /** The ids are JDT's own constants, not literals — pinning one keeps the table honest about its source. */
    @Test
    void theTableIsKeyedByJdtsOwnProblemConstants() {
        Map<Integer, ErrorTranslator.ErrorInfo> table = mappings();
        assertTrue(table.containsKey(IProblem.MissingSemiColon), "missing semicolon is the canonical entry");
        assertTrue(table.containsKey(IProblem.UndefinedName));
        assertTrue(table.containsKey(IProblem.TypeMismatch));
    }

    @Test
    void aKnownProblemIsRewrittenIntoPlainLanguageWithItsSuggestion() {
        String out = ErrorTranslator.translateSingleDiagnostic(
                forProblem(IProblem.MissingSemiColon, "Syntax error, insert \";\" to complete Statement"));

        assertTrue(out.startsWith("Missing semicolon"), out);
        assertTrue(out.contains("💡 Add a semicolon"), out);
    }

    // --- Placeholders ---

    /**
     * <b>B21.</b> The name is lifted out of the compiler's wording and substituted — and arrives
     * double-quoted. {@code enrichMessage} wraps every extracted value in {@code '…'}, but roughly half the
     * templates already quote their own placeholder ({@code "Variable or name '{0}' doesn't exist"}), so
     * those render {@code ''health''}. The other half ({@code "a {0} where a {1} is expected"}) do not, which
     * is why the inconsistency survived: whether the user sees one quote or two depends on which entry fired.
     *
     * <p>Characterised, not fixed: the fix is one or the other convention across all 26 entries, and it
     * belongs with the entry-by-entry pass, not here.
     */
    @Test
    void aQuotedNameFromTheCompilerFillsTheFirstPlaceholderAndArrivesDoubleQuoted() {
        String out = ErrorTranslator.getShortSummary(
                forProblem(IProblem.UndefinedName, "health cannot be resolved to a variable: 'health'"));

        assertEquals("Variable or name ''health'' doesn't exist", out,
                "the template quotes {0} and the enricher quotes the value again");
    }

    /**
     * JDT's own type-mismatch wording carries no quoted names, so both placeholders in the two-slot template
     * go unfilled. They become "the value" rather than a literal {@code {0}} — worth pinning, because it is
     * the difference between a clumsy sentence and one that shows the user a brace.
     */
    @Test
    void anUnfilledPlaceholderBecomesTheValueRatherThanABrace() {
        String out = ErrorTranslator.getShortSummary(
                forProblem(IProblem.TypeMismatch, "Type mismatch: cannot convert from int to String"));

        assertEquals("Wrong type used: You're trying to use a the value where a the value is expected", out);
        assertFalse(out.contains("{0}"), "a raw placeholder must never reach the user");
    }

    /**
     * The enricher stops at the first placeholder it cannot fill: {@code {1}} is only substituted while
     * {@code {0}} was, so a message quoting exactly one name leaves the second slot to the sweep above.
     */
    @Test
    void oneQuotedNameFillsOnlyTheFirstOfTwoSlots() {
        String out = ErrorTranslator.getShortSummary(
                forProblem(IProblem.TypeMismatch, "cannot convert from 'int' to String"));

        assertEquals("Wrong type used: You're trying to use a 'int' where a the value is expected", out);
    }

    // --- The pattern fallback ---

    @Test
    void anUnmappedMessageFallsBackToPatternMatching() {
        assertTrue(ErrorTranslator.translateSingleDiagnostic(
                        diagnostic("Foo cannot be resolved to a type")).startsWith("A type or class"),
                "the pattern fallback is what covers every id not in the table");

        assertTrue(ErrorTranslator.translateSingleDiagnostic(
                diagnostic("Duplicate local variable count")).startsWith("A variable with this name"));
    }

    @Test
    void anInsertSuggestionIsLiftedOutOfTheCompilersWording() {
        String out = ErrorTranslator.translateSingleDiagnostic(
                diagnostic("Syntax error, insert \"}\" to complete ClassBody"));

        assertTrue(out.contains("Try adding '}'"), out);
    }

    /** A message that matches nothing is handed through unchanged rather than swallowed. */
    @Test
    void anUnrecognisedMessageIsPassedThroughVerbatim() {
        assertEquals("something entirely new",
                ErrorTranslator.translateSingleDiagnostic(diagnostic("something entirely new")));
    }

    // --- The report ---

    @Test
    void theReportCountsErrorsAndWarningsSeparatelyAndNumbersLinesFromOne() {
        Diagnostic error = forProblem(IProblem.MissingSemiColon, "no semicolon");
        Diagnostic warning = forProblem(IProblem.LocalVariableIsNeverUsed, "unused 'tmp'");
        warning.setSeverity(DiagnosticSeverity.Warning);
        warning.setRange(new Range(new Position(4, 0), new Position(4, 3)));

        String report = ErrorTranslator.translate(List.of(error, warning));

        assertTrue(report.contains("❌ Found 1 error:"), report);
        assertTrue(report.contains("⚠️  Found 1 warning:"), report);
        assertTrue(report.contains("Line 1:"), "the first line of a file is line 1, not line 0");
        assertTrue(report.contains("Line 5:"), report);
    }

    @Test
    void noDiagnosticsReadsAsSuccessRatherThanAnEmptyPanel() {
        assertTrue(ErrorTranslator.translate(List.of()).startsWith("✅"));
        assertTrue(ErrorTranslator.translate(null).startsWith("✅"));
    }

    // --- What actually reaches this class ---

    /**
     * <b>B20.</b> The whole table above is unreachable in the running application. The only {@link Diagnostic}
     * Studio ever constructs is {@code DiagnosticsManager.validateBlocks}'s empty-slot error, published as the
     * single {@code DiagnosticsUpdatedEvent} from {@code CodeExecutionService.runCode} — and its message
     * carries no JDT id, because it never came from JDT. Compiler output reaches the user as raw javac text on
     * the output pane instead, so "every compile message the user reads" passes through {@code translate}
     * exactly zero times.
     *
     * <p>Asserted as a characterisation, not red-by-design: this is today's behaviour, and the fix is to route
     * JDT's problems here (they carry {@link IProblem#getID()} as a field, so the 7-digit scrape below goes
     * away with it), which flips these two assertions.
     */
    @Test
    void theOnlyDiagnosticStudioProducesMissesTheTableEntirely() {
        Diagnostic real = diagnostic("This value is empty — choose an expression to fill it in before running.");

        assertEquals("Check your code for issues.", ErrorTranslator.getSuggestion(real),
                "the generic fallback is what every user actually gets");
        assertEquals(real.getMessage(), ErrorTranslator.getShortSummary(real),
                "and the message survives only because it was already written for a human");
    }

    /**
     * <b>B20, the other half.</b> The id is scraped out of the message text with {@code \b(\d{7,})\b} rather
     * than read from a field, so any 7-digit run in a diagnostic is treated as a problem id. A user who writes
     * a long number literal and gets an error on that line is mistranslated into whichever entry it collides
     * with — here, a real {@link IProblem} value quoted back from the user's own source.
     */
    @Test
    void aLongNumberInTheUsersOwnCodeIsMistakenForAProblemId() {
        Diagnostic d = diagnostic("The value " + IProblem.MissingSemiColon + " is not a valid duration");

        assertTrue(ErrorTranslator.getShortSummary(d).startsWith("Missing semicolon"),
                "a number the user typed selected the translation");
        assertNotEquals("Check your code for issues.", ErrorTranslator.getSuggestion(d));
    }
}
