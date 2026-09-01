package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;


import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for the vision-loop variant switch ({@link LambdaCallHandler#switchVariant}): it moves
 * the leading image argument between a single template and an {@code ImageTemplateGroup.of(…)}, and adds,
 * renames or removes the lambda parameter to match the target's shape.
 *
 * <p>The parameter is the part that changed with the SDK's {@code Matches}: the {@code …Any}/{@code …All} group
 * forms take a {@code Consumer<Matches>} now, not a {@code Runnable}, so switching to one must <em>rename</em>
 * the parameter (the value's type changed from {@code MatchResult} to {@code Matches}) rather than drop it.
 * Only the {@code untilFind…} forms are still parameterless.
 */
public class LambdaVariantSwitchTest {

    /** Rewrite {@code call}'s method to {@code newMethod} via switchVariant and return the new source. */
    private static String switchTo(String source, String call, String newMethod, boolean group, String param) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        cu.recordModifications();

        AtomicReference<MethodInvocation> found = new AtomicReference<>();
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation mi) {
                if (mi.getName().getIdentifier().equals(call)) found.set(mi);
                return true;
            }
        });

        EditContext ctx = EditContext.of(cu, null, null);
        ASTRewrite rewriter = ctx.rewriter();
        LambdaCallHandler.switchVariant(ctx, found.get(), newMethod, group, param);

        IDocument doc = new Document(source);
        TextEdit edits = rewriter.rewriteAST(doc, null);
        assertDoesNotThrow(() -> edits.apply(doc));
        return doc.get();
    }

    // ---- Seeding a group form's body ----
    //
    // switchingToAGroupFormSeedsTheMatchesSwitch and aGroupHeldInAConstantSeedsFromItsFirstTemplate stood
    // here until 2026-09-01. Switching to a group form used to fill an empty body with a guarded
    // switch (Matches) seeded from the group's first template — reading the group through a constant when
    // it was held in one. That whole construct is gone: an empty body is the honest starting state, and
    // branching on what was found is written as a chain of catalogued calls the palette offers inside it.
    //
    // The two cases below stay, and they are now the whole of what this section asserts: a body with
    // anything in it is untouched, and a parameterless variant gets nothing. Both used to guard the seeding
    // against overreach and now simply describe what always happens.

    /** A body with anything in it is left exactly as it was — seeding must never displace the user's work. */
    @Test
    void aNonEmptyBodyIsNeverSeeded() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFind(new ImageTemplate("popups/mail.png"), match -> {
                            ImageClicker.click(match);
                        });
                    }
                }
                """;
        String result = switchTo(source, "whileFind", "whileFindAny", true, "found");

        assertTrue(result.contains("ImageClicker.click(match)"), () -> "the body must survive: " + result);
        assertFalse(result.contains("switch"), () -> "an occupied body must not be seeded: " + result);
    }

    /** {@code untilFind…} takes a {@code Runnable} — there is no value to switch on, so nothing is seeded. */
    @Test
    void aParameterlessVariantIsNotSeeded() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFind(new ImageTemplate("popups/mail.png"), match -> {});
                    }
                }
                """;
        String result = switchTo(source, "whileFind", "untilFindAny", true, null);

        assertFalse(result.contains("switch"), () -> "a Runnable body has no Matches to switch on: " + result);
    }

    @Test
    void singleToAnyWrapsGroupAndRenamesTheParamToTheGroupValue() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFind(coin, match -> {});
                    }
                }
                """;
        String result = switchTo(source, "whileFind", "whileFindAny", true, "found").replace(" ", "");
        // The body's value went from one MatchResult to the whole Matches, so the name goes with it.
        assertTrue(result.contains("whileFindAny(ImageTemplateGroup.of(coin),found->{}"),
                () -> "expected group-wrapped call with the renamed param: " + result);
    }

    @Test
    void anyToAllKeepsTheGroupAndTheParameter() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFindAny(ImageTemplateGroup.of(coin), found -> {});
                    }
                }
                """;
        String result = switchTo(source, "whileFindAny", "whileFindAll", true, "found").replace(" ", "");
        // Both group forms take a Consumer<Matches> now — this used to drop the param for a Runnable body.
        assertTrue(result.contains("whileFindAll(ImageTemplateGroup.of(coin),found->{}"),
                () -> "expected group kept and the Matches param kept: " + result);
    }

    @Test
    void anyToUntilDropsTheParameter() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFindAny(ImageTemplateGroup.of(coin), found -> {});
                    }
                }
                """;
        String result = switchTo(source, "whileFindAny", "untilFindAny", true, null).replace(" ", "");
        // untilFind… loops while nothing is found, so there is no value to hand over — a Runnable body.
        assertTrue(result.contains("untilFindAny(ImageTemplateGroup.of(coin),()->{}"),
                () -> "expected a no-arg lambda: " + result);
    }

    @Test
    void anyToSingleUnwrapsFirstTemplate() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFindAny(ImageTemplateGroup.of(coin, gem), found -> {});
                    }
                }
                """;
        String result = switchTo(source, "whileFindAny", "whileFind", false, "match").replace(" ", "");
        assertTrue(result.contains("whileFind(coin,match->{}"),
                () -> "expected first template unwrapped from the group: " + result);
    }

    /**
     * The other direction of {@link #anyToUntilDropsTheParameter}: a parameterless {@code untilFind…} body gains
     * one. This is the switch the user reported as doing nothing, and it is the one that pairs an insert into
     * {@code PARAMETERS_PROPERTY} with a {@code PARENTHESES_PROPERTY} flip — the combination JDT's rewriter is
     * suspected of handling badly (leaving the original {@code ()} in place, so the result doesn't parse).
     */
    @Test
    void untilToIfAddsTheParameterAndDropsTheEmptyParentheses() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.untilFindAll(ImageTemplateGroup.of(coin), () -> {});
                    }
                }
                """;
        String result = switchTo(source, "untilFindAll", "ifFindAll", true, "found").replace(" ", "");
        assertTrue(result.contains("ifFindAll(ImageTemplateGroup.of(coin),found->{}"),
                () -> "expected the no-arg lambda to gain its Matches parameter: " + result);
    }

    /**
     * A parameter-count change rebuilds the lambda, so the body has to be carried across — and carried as its
     * own source text, not re-printed. A switch that silently emptied the body would be worse than the no-op
     * this replaced.
     */
    @Test
    void aParameterCountChangeCarriesTheBodyAcrossVerbatim() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.untilFindAll(ImageTemplateGroup.of(coin), () -> {
                            // keep me
                            Mouse.click(10, 20);
                        });
                    }
                }
                """;
        String result = switchTo(source, "untilFindAll", "ifFindAll", true, "found");
        assertTrue(result.contains("// keep me"), () -> "the body's comment was dropped: " + result);
        assertTrue(result.contains("Mouse.click(10, 20);"),
                () -> "the body's statement was re-printed or dropped: " + result);
    }

    /** A user-renamed parameter survives a switch that doesn't change the value's type (group → group). */
    @Test
    void aRenamedParameterIsCarriedAcrossASameShapeSwitch() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.ifFindAny(ImageTemplateGroup.of(coin), popups -> {});
                    }
                }
                """;
        String result = switchTo(source, "ifFindAny", "whileFindAny", true, "popups").replace(" ", "");
        assertTrue(result.contains("whileFindAny(ImageTemplateGroup.of(coin),popups->{}"),
                () -> "expected the user's own parameter name kept: " + result);
    }
}
