package com.botmaker.studio.validation;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DiagnosticLevelTest {

    @Test
    void everyLspSeverityLandsInABand() {
        assertAll(
                () -> assertEquals(DiagnosticLevel.ERROR, DiagnosticLevel.of(DiagnosticSeverity.Error)),
                () -> assertEquals(DiagnosticLevel.WARNING, DiagnosticLevel.of(DiagnosticSeverity.Warning)),
                () -> assertEquals(DiagnosticLevel.INFO, DiagnosticLevel.of(DiagnosticSeverity.Information)),
                () -> assertEquals(DiagnosticLevel.INFO, DiagnosticLevel.of(DiagnosticSeverity.Hint)),
                // The LSP spec allows an absent severity; the panel must still render the row.
                () -> assertEquals(DiagnosticLevel.INFO, DiagnosticLevel.of((DiagnosticSeverity) null)),
                () -> assertEquals(DiagnosticLevel.ERROR, DiagnosticLevel.of(errorDiagnostic())));
    }

    @Test
    void theStyleClassAndTheGlyphComeFromTheSameConstant() {
        // The panel built the class by concatenating a string and picked the glyph by switching on it again;
        // this is the property that pairing them on the enum guarantees.
        assertAll(
                () -> assertEquals("diagnostic-cell--error", DiagnosticLevel.ERROR.styleClass("diagnostic-cell")),
                () -> assertEquals("severity-filter--info", DiagnosticLevel.INFO.styleClass("severity-filter")),
                () -> assertNotEquals(DiagnosticLevel.ERROR.glyph(), DiagnosticLevel.WARNING.glyph()),
                () -> assertNotEquals(DiagnosticLevel.WARNING.glyph(), DiagnosticLevel.INFO.glyph()));
    }

    private static Diagnostic errorDiagnostic() {
        Diagnostic d = new Diagnostic();
        d.setSeverity(DiagnosticSeverity.Error);
        return d;
    }
}
