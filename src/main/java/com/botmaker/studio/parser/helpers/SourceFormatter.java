package com.botmaker.studio.parser.helpers;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

import java.util.Map;

/**
 * Lays out a whole Java source file — the step Studio never had.
 *
 * <p><b>Why.</b> Every rewrite in the write path is an {@code ASTRewrite} applied to the previous text, and
 * {@code ASTRewrite} only formats what it inserts, relative to what it finds. Nothing ever re-laid-out the
 * file, so a generated bot degraded edit by edit: one user's activity had its whole lambda, {@code switch},
 * both guarded labels and the first {@code ->{}} packed onto a single line. That is unreadable on its own
 * terms, and it also makes every positional bug report unreproducible, because the formatting is the input.
 *
 * <p><b>Never destructive.</b> Source that doesn't parse is returned untouched. JDT will happily format it
 * anyway — it recovers a tree and lays that out — and the write path does legitimately produce broken source
 * mid-edit, so a best-effort reflow of a recovered tree is a real way to lose text that was going to be
 * refused (and logged, and dumped) intact. Everything else is caught and falls back to the input: a lost
 * layout is a cosmetic problem, a lost edit is not.
 *
 * <p><b>Code only, never comments.</b> {@code F_INCLUDE_COMMENTS} is deliberately not set. Re-wrapping a
 * user's prose to fit a column is an opinion about their writing, not about their code, and it is the one part
 * of a file where reflowing changes what a reader sees rather than only where they see it.
 *
 * <p>Settings follow the repo's {@code .editorconfig} (4 spaces, 120 columns, LF) so a file Studio writes and
 * a file a human edits in the IDE agree, and compliance comes from {@link SourceParser#latestLevelOptions()}
 * rather than a second copy — the formatter parses too, and one left at JDT's 1.3 default mangles the very
 * {@code switch} rules this exists to lay out.
 */
public final class SourceFormatter {

    private SourceFormatter() {}

    /** Matches {@code .editorconfig}: {@code indent_size = 4}, {@code indent_style = space}. */
    private static final int INDENT = 4;

    /** Matches {@code .editorconfig}: {@code max_line_length = 120}. */
    private static final int LINE_WIDTH = 120;

    /** Matches {@code .editorconfig}: {@code end_of_line = lf} — and keeps a diff free of line-ending churn. */
    private static final String LINE_SEPARATOR = "\n";

    private static final CodeFormatter FORMATTER = ToolFactory.createCodeFormatter(options());

    /**
     * {@code source} laid out, or {@code source} unchanged when the formatter declines it. Never null for
     * non-null input, and never throws: this sits on the edit path, where failing closed means losing a user's
     * change to a cosmetic pass.
     */
    public static String format(String source) {
        if (source == null || source.isBlank()) return source;
        if (SourceParser.hasSyntaxErrors(SourceParser.parse(source))) return source;
        try {
            TextEdit edit = FORMATTER.format(
                    CodeFormatter.K_COMPILATION_UNIT, source, 0, source.length(), 0, LINE_SEPARATOR);
            if (edit == null) return source;
            IDocument document = new Document(source);
            edit.apply(document);
            return document.get();
        } catch (Exception e) {
            return source;
        }
    }

    private static Map<String, String> options() {
        Map<String, String> options = SourceParser.latestLevelOptions();
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, String.valueOf(INDENT));
        options.put(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, String.valueOf(INDENT));
        options.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, String.valueOf(LINE_WIDTH));
        // The one setting that is not taste: joining lines would undo a user's own paragraphing on every save.
        options.put(DefaultCodeFormatterConstants.FORMATTER_JOIN_WRAPPED_LINES, DefaultCodeFormatterConstants.FALSE);
        options.put(DefaultCodeFormatterConstants.FORMATTER_NUMBER_OF_EMPTY_LINES_TO_PRESERVE, "1");
        return options;
    }
}
