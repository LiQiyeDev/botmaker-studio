package com.botmaker.studio.services;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What every refactor left for the user to look at, read back out of the bot's own source.
 *
 * <h2>Why a scan, and not a list something kept</h2>
 *
 * <p>The marks are written into the code ({@link ReviewMarks}) precisely so that nothing has to keep a list:
 * an edit that moves a function moves its mark, deleting the function deletes it, and a revert through Project
 * History takes the marks out with the change they describe. The price of that is that the list has to be
 * <em>derived</em> — every reader of it re-reads the sources. That is cheap (a bot is tens of files) and it
 * cannot go stale, which a cached list demonstrably would: four different refactors write marks, and two of
 * them do it outside the editor entirely.
 *
 * <p>So there is exactly one rule here: <b>the source is the truth</b>. {@link #scan} parses it, and
 * {@link #markReviewed} writes back through the same {@link BotSources} walk the template rewrites use, so the
 * open buffer and the file on disk never disagree.
 */
public final class ReviewService {

    private ReviewService() {}

    /**
     * One thing to look at: an entry from one function's mark.
     *
     * <p>{@code line} is where the function starts, not where the change is — a mark names the enclosing
     * function, which is the unit the user reviews and the unit the canvas can scroll to. The entry text is
     * the identity: two entries on one function are two rows, and marking one reviewed leaves the other.
     */
    public record Item(Path file, String function, int line, String entry) {

        /** {@code "Miner.java · mine()"} — the row's own heading. */
        public String where() {
            return file.getFileName() + " · " + function + "()";
        }
    }

    /** Every review entry in the project, in file then source order. */
    public static List<Item> scan(ProjectConfig config, ProjectState state) {
        List<Item> items = new ArrayList<>();
        if (config == null) return items;
        BotSources.forEach(config, state, (file, source) -> {
            // Cheap reject before parsing: most files carry no mark at all, and parsing is the expensive half.
            if (!source.contains(ReviewMarks.ANNOTATION)) return null;
            CompilationUnit unit = SourceParser.parse(source);
            if (unit == null) return null;
            for (MethodDeclaration method : ReviewMarks.markedIn(unit)) {
                int line = unit.getLineNumber(method.getStartPosition());
                for (String entry : ReviewMarks.entriesOf(method)) {
                    items.add(new Item(file, method.getName().getIdentifier(), Math.max(line, 1), entry));
                }
            }
            return null;   // reading only
        });
        return List.copyOf(items);
    }

    /**
     * Removes {@code item}'s entry from the function it marks — the "I have looked at this" gesture — and
     * answers whether anything changed. The last entry removed takes the annotation with it, and the import
     * once the file holds no marks at all ({@link ReviewMarks#strip}).
     *
     * <p>A file that no longer holds the entry is not an error: the user may have edited the mark away by
     * hand, or reverted the change through Project History since the list was drawn. It answers false and the
     * caller re-scans, which is what it would do anyway.
     */
    public static boolean markReviewed(ProjectConfig config, ProjectState state, Item item) {
        if (config == null || item == null) return false;
        boolean[] stripped = {false};
        BotSources.forEach(config, state, (file, source) -> {
            if (stripped[0] || !file.equals(item.file())) return null;
            CompilationUnit unit = SourceParser.parse(source);
            if (unit == null || SourceParser.hasSyntaxErrors(unit)) return null;

            MethodDeclaration target = null;
            for (MethodDeclaration method : ReviewMarks.markedIn(unit)) {
                if (!method.getName().getIdentifier().equals(item.function())) continue;
                // The entry, not the name: two overloads can both be marked, and only one of them carries this.
                if (ReviewMarks.entriesOf(method).contains(item.entry())) {
                    target = method;
                    break;
                }
            }
            if (target == null) return null;

            EditContext ctx = EditContext.of(unit, null, null);
            if (!ReviewMarks.strip(ctx, target, item.entry())) return null;
            String rewritten = ctx.applyTo(source);
            // Same rule as every other rewrite in Studio: a result that does not parse is not written.
            if (rewritten == null || SourceParser.hasSyntaxErrors(SourceParser.parse(rewritten))) return null;
            stripped[0] = true;
            return rewritten;
        });
        return stripped[0];
    }
}
