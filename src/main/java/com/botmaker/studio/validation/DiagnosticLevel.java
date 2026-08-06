package com.botmaker.studio.validation;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

/**
 * The three bands the Problems panel sorts LSP diagnostics into, and the presentation each one carries.
 *
 * <p>lsp4j's {@link DiagnosticSeverity} is already a typed closed set — but the panel immediately converted it
 * <em>down</em> to {@code "error"}/{@code "warning"}/{@code "info"} and then switched on the string again to
 * pick a glyph, and concatenated it a third time to build the {@code severity-filter--<x>} and
 * {@code diagnostic-cell--<x>} style classes. Three spellings of the same three-way split, with the filter
 * buttons constructed from a fourth. This keeps the type all the way to the CSS: the suffix is derived from
 * the constant, so a class the stylesheet knows and a glyph the user sees cannot disagree about which band a
 * diagnostic is in.
 *
 * <p>It is a band, not a rename of {@link DiagnosticSeverity} — {@code Information} and {@code Hint} both land
 * on {@link #INFO}, which is the collapse the panel wanted and the reason this cannot simply be lsp4j's enum.
 */
public enum DiagnosticLevel {

    ERROR("error", "❌"),
    WARNING("warning", "⚠️"),
    /** {@code Information}, {@code Hint}, and anything a server sends with no severity at all. */
    INFO("info", "ℹ️");

    private final String cssSuffix;
    private final String glyph;

    DiagnosticLevel(String cssSuffix, String glyph) {
        this.cssSuffix = cssSuffix;
        this.glyph = glyph;
    }

    /** The modifier appended to a base style class, as in {@code diagnostic-cell--error}. */
    public String cssSuffix() {
        return cssSuffix;
    }

    /** The icon shown beside the message — chosen from the same constant that colours it. */
    public String glyph() {
        return glyph;
    }

    /** The full modifier class for {@code base}, e.g. {@code "diagnostic-icon" → "diagnostic-icon--warning"}. */
    public String styleClass(String base) {
        return base + "--" + cssSuffix;
    }

    /** The band {@code severity} falls in. Total: a null severity (the LSP spec allows it) reads as {@link #INFO}. */
    public static DiagnosticLevel of(DiagnosticSeverity severity) {
        if (severity == DiagnosticSeverity.Error) return ERROR;
        if (severity == DiagnosticSeverity.Warning) return WARNING;
        return INFO;
    }

    /** The band a diagnostic falls in. */
    public static DiagnosticLevel of(Diagnostic diagnostic) {
        return of(diagnostic.getSeverity());
    }
}
