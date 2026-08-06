package com.botmaker.studio.ui.app;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Errors tab's severity filtering and its button counts.
 *
 * <p>Both are {@code static} and JavaFX-free precisely so this test can exist: the panel they live in needs a
 * toolkit, but the rule "which diagnostics does the user see, and does the button agree with the list" does
 * not. The pairing is the part that used to be able to drift — the filter treated {@code Hint} as an info and
 * the counter had its own separate copy of that decision.
 */
class DiagnosticsPanelFilterTest {

    private static Diagnostic diagnostic(DiagnosticSeverity severity) {
        Diagnostic d = new Diagnostic();
        d.setSeverity(severity);
        d.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        d.setMessage("boom");
        return d;
    }

    private static final List<Diagnostic> MIXED = List.of(
            diagnostic(DiagnosticSeverity.Error),
            diagnostic(DiagnosticSeverity.Error),
            diagnostic(DiagnosticSeverity.Warning),
            diagnostic(DiagnosticSeverity.Information),
            diagnostic(DiagnosticSeverity.Hint));

    @Test
    void eachToggleHidesOnlyItsOwnSeverity() {
        Diagnostic error = diagnostic(DiagnosticSeverity.Error);
        Diagnostic warning = diagnostic(DiagnosticSeverity.Warning);
        Diagnostic info = diagnostic(DiagnosticSeverity.Information);

        assertFalse(DiagnosticsPanel.matches(error, false, true, true), "errors hidden, an error showed");
        assertTrue(DiagnosticsPanel.matches(error, true, false, false));

        assertFalse(DiagnosticsPanel.matches(warning, true, false, true));
        assertTrue(DiagnosticsPanel.matches(warning, false, true, false));

        assertFalse(DiagnosticsPanel.matches(info, true, true, false));
        assertTrue(DiagnosticsPanel.matches(info, false, false, true));
    }

    /** A hint is an info to both halves of the panel — the toggle is labelled "Infos/Hints" for that reason. */
    @Test
    void aHintFollowsTheInfoToggleAndCountsAsAnInfo() {
        Diagnostic hint = diagnostic(DiagnosticSeverity.Hint);
        assertTrue(DiagnosticsPanel.matches(hint, false, false, true));
        assertFalse(DiagnosticsPanel.matches(hint, true, true, false));

        assertEquals(2, DiagnosticsPanel.counts(MIXED).infos(), "the Hint didn't land in the info count");
    }

    /** Hiding something the user never asked to hide is the worse failure — an unknown severity always shows. */
    @Test
    void aDiagnosticWithNoSeverityAlwaysShowsAndIsCountedNowhere() {
        Diagnostic unknown = diagnostic(null);
        assertTrue(DiagnosticsPanel.matches(unknown, false, false, false));

        DiagnosticsPanel.Counts counts = DiagnosticsPanel.counts(List.of(unknown));
        assertEquals(new DiagnosticsPanel.Counts(0, 0, 0), counts);
    }

    @Test
    void theCountsMatchWhatTheFiltersLetThrough() {
        DiagnosticsPanel.Counts counts = DiagnosticsPanel.counts(MIXED);
        assertEquals(new DiagnosticsPanel.Counts(2, 1, 2), counts);

        assertEquals(counts.errors(),
                MIXED.stream().filter(d -> DiagnosticsPanel.matches(d, true, false, false)).count());
        assertEquals(counts.warnings(),
                MIXED.stream().filter(d -> DiagnosticsPanel.matches(d, false, true, false)).count());
        assertEquals(counts.infos(),
                MIXED.stream().filter(d -> DiagnosticsPanel.matches(d, false, false, true)).count());
    }

    @Test
    void allTogglesOnShowsEverythingAndAllOffShowsNothing() {
        assertEquals(MIXED.size(),
                MIXED.stream().filter(d -> DiagnosticsPanel.matches(d, true, true, true)).count());
        assertEquals(0,
                MIXED.stream().filter(d -> DiagnosticsPanel.matches(d, false, false, false)).count());
    }
}
