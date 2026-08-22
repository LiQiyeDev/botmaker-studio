package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review marker, on its own: writing one, merging into one that is there, reading it back, and taking
 * entries off it again.
 *
 * <p>Every test that writes asserts the result <b>parses</b>. An annotation is one of the few edits that can
 * be recorded happily by {@code ASTRewrite} and still land in a position Java does not allow — before the
 * javadoc, after {@code public} — so "it compiles" is the assertion that matters, not "the text is there".
 *
 * <p>The round trip (write, re-parse, read back what was written) is tested rather than assumed, because the
 * two halves have separate reasons to be wrong: writing picks a form (a bare string for one entry, a braced
 * array for several) and reading has to accept every form a person might have left behind.
 */
class ReviewMarksTest {

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    /** Applies {@code edit} to the one method named {@code method} in {@code source}, and returns the result. */
    private static String edit(String source, String method, Editor edit) {
        CompilationUnit unit = SourceParser.parse(source);
        assertNotNull(unit);
        EditContext ctx = EditContext.of(unit, null, null);
        edit.apply(ctx, methodNamed(unit, method));
        String rewritten = ctx.applyTo(source);
        assertNotNull(rewritten, "the rewrite did not apply");
        assertFalse(SourceParser.hasSyntaxErrors(SourceParser.parse(rewritten)),
                "a marked file must still compile:\n" + rewritten);
        return rewritten;
    }

    private interface Editor {
        void apply(EditContext ctx, MethodDeclaration method);
    }

    private static MethodDeclaration methodNamed(CompilationUnit unit, String name) {
        AtomicReference<MethodDeclaration> found = new AtomicReference<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getName().getIdentifier().equals(name)) found.set(node);
                return true;
            }
        });
        assertNotNull(found.get(), "no method named " + name);
        return found.get();
    }

    /** The entries {@code source} says {@code method} carries, read back through a fresh parse. */
    private static List<String> entriesIn(String source, String method) {
        CompilationUnit unit = SourceParser.parse(source);
        return ReviewMarks.entriesOf(methodNamed(unit, method));
    }

    private static final String PLAIN = """
            package com.mybot;
            class Bot {
                void run() {
                    int x = 1;
                }
            }
            """;

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    @Test
    void aFunctionWithNoMarkGainsOne() {
        String source = edit(PLAIN, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of("look at this")));

        assertTrue(source.contains("@NeedsReview(\"look at this\")"), source);
        assertEquals(List.of("look at this"), entriesIn(source, "run"));
    }

    @Test
    void severalEntriesAreWrittenAsAnArrayAndReadBackInOrder() {
        String source = edit(PLAIN, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of("first", "second")));

        assertTrue(source.contains("{"), source);
        assertEquals(List.of("first", "second"), entriesIn(source, "run"));
    }

    @Test
    void aSecondRefactorMergesIntoTheMarkAlreadyThereRatherThanAddingAnother() {
        String once = edit(PLAIN, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of("first")));
        String twice = edit(once, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of("second")));

        assertEquals(1, twice.split("@NeedsReview", -1).length - 1,
                "Java allows a method one of these:\n" + twice);
        assertEquals(List.of("first", "second"), entriesIn(twice, "run"));
    }

    @Test
    void thesSameEntryTwiceIsStillOneEntry() {
        String once = edit(PLAIN, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of("the same thing")));
        String twice = edit(once, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of("the same thing")));

        assertEquals(List.of("the same thing"), entriesIn(twice, "run"));
    }

    @Test
    void quotesAndBackslashesInAnEntrySurviveTheRoundTrip() {
        String entry = "Vision.find(\"a\\b\") is gone";
        String source = edit(PLAIN, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of(entry)));

        assertEquals(List.of(entry), entriesIn(source, "run"));
    }

    @Test
    void aMarkGoesAheadOfTheModifiersAndBelowTheJavadoc() {
        String source = edit("""
                package com.mybot;
                class Bot {
                    /** Does the thing. */
                    public static void run() {
                        int x = 1;
                    }
                }
                """, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of("entry")));

        int javadoc = source.indexOf("Does the thing");
        int mark = source.indexOf("@NeedsReview");
        int modifiers = source.indexOf("public static");
        assertTrue(javadoc < mark && mark < modifiers, "wrong order:\n" + source);
    }

    @Test
    void anEmptyListOfEntriesMarksNothingAtAll() {
        String source = edit(PLAIN, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, "com.mybot", List.of()));

        assertFalse(source.contains("@NeedsReview"), source);
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    @Test
    void aHandWrittenNormalAnnotationReadsBackToo() {
        String source = """
                package com.mybot;
                class Bot {
                    @NeedsReview(value = {"one", "two"})
                    void run() {}
                }
                """;
        assertEquals(List.of("one", "two"), entriesIn(source, "run"));
    }

    @Test
    void anUnmarkedFunctionHasNoEntriesRatherThanAnEmptyMark() {
        assertEquals(List.of(), entriesIn(PLAIN, "run"));
        assertFalse(ReviewMarks.anyIn(SourceParser.parse(PLAIN)));
    }

    @Test
    void everyMarkedFunctionInAFileIsFoundInSourceOrder() {
        CompilationUnit unit = SourceParser.parse("""
                package com.mybot;
                class Bot {
                    @NeedsReview("a")
                    void first() {}
                    void second() {}
                    @NeedsReview("b")
                    void third() {}
                }
                """);
        List<MethodDeclaration> marked = ReviewMarks.markedIn(unit);

        assertEquals(List.of("first", "third"),
                marked.stream().map(m -> m.getName().getIdentifier()).toList());
    }

    // -------------------------------------------------------------------------
    // Reviewing it away
    // -------------------------------------------------------------------------

    @Test
    void strippingOneOfSeveralEntriesLeavesTheRest() {
        String marked = """
                package com.mybot;
                class Bot {
                    @NeedsReview({"first", "second"})
                    void run() {}
                }
                """;
        String source = edit(marked, "run", (ctx, method) -> ReviewMarks.strip(ctx, method, "first"));

        assertEquals(List.of("second"), entriesIn(source, "run"));
    }

    @Test
    void strippingTheLastEntryTakesTheAnnotationWithIt() {
        String marked = """
                package com.mybot;
                class Bot {
                    @NeedsReview("only")
                    void run() {}
                }
                """;
        String source = edit(marked, "run", (ctx, method) -> ReviewMarks.strip(ctx, method, "only"));

        assertFalse(source.contains("@NeedsReview"), "an empty mark is not a mark:\n" + source);
    }

    @Test
    void theLastMarkInAFileTakesTheImportWithItToo() {
        String marked = """
                package com.mybot.activities;

                import com.mybot.NeedsReview;

                class Mining {
                    @NeedsReview("only")
                    void run() {}
                }
                """;
        String source = edit(marked, "run", (ctx, method) -> ReviewMarks.strip(ctx, method, "only"));

        assertFalse(source.contains("NeedsReview"), source);
    }

    @Test
    void theImportStaysWhileAnotherFunctionInTheFileIsStillMarked() {
        String marked = """
                package com.mybot.activities;

                import com.mybot.NeedsReview;

                class Mining {
                    @NeedsReview("mine")
                    void first() {}
                    @NeedsReview("keep")
                    void second() {}
                }
                """;
        String source = edit(marked, "first", (ctx, method) -> ReviewMarks.strip(ctx, method, "mine"));

        assertTrue(source.contains("import com.mybot.NeedsReview;"), source);
        assertEquals(List.of("keep"), entriesIn(source, "second"));
    }

    @Test
    void strippingSomethingThatIsNotThereChangesNothingAndSaysSo() {
        CompilationUnit unit = SourceParser.parse(PLAIN);
        EditContext ctx = EditContext.of(unit, null, null);

        assertFalse(ReviewMarks.strip(ctx, methodNamed(unit, "run"), "never written"));
        assertEquals(PLAIN, ctx.applyTo(PLAIN));
    }

    @Test
    void stripAllTakesEveryEntryAtOnce() {
        String marked = """
                package com.mybot;
                class Bot {
                    @NeedsReview({"first", "second"})
                    void run() {}
                }
                """;
        String source = edit(marked, "run", (ctx, method) -> ReviewMarks.stripAll(ctx, method));

        assertFalse(source.contains("@NeedsReview"), source);
    }

    // -------------------------------------------------------------------------
    // The generated annotation itself
    // -------------------------------------------------------------------------

    @Test
    void theGeneratedAnnotationCompilesAndSaysWhatItIsFor() {
        String source = ReviewMarks.annotationSource("com.mybot");

        assertFalse(SourceParser.hasSyntaxErrors(SourceParser.parse(source)), source);
        assertTrue(source.contains("package com.mybot;"), source);
        assertTrue(source.contains("RetentionPolicy.SOURCE"), "never reaches the running bot:\n" + source);
        assertTrue(source.contains("String[] value();"), source);
    }

    @Test
    void theFileIsWrittenOnceAndNeverOverwritten(@TempDir Path dir) throws IOException {
        Path pkg = dir.resolve("com").resolve("mybot");

        assertTrue(ReviewMarks.ensureFile(pkg, "com.mybot"));
        Files.writeString(pkg.resolve("NeedsReview.java"), "// the user's now\n");

        assertFalse(ReviewMarks.ensureFile(pkg, "com.mybot"), "it exists, so it is not Studio's to replace");
        assertEquals("// the user's now\n", Files.readString(pkg.resolve("NeedsReview.java")));
    }
}
