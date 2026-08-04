package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.handlers.LambdaCallHandler;
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

        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        LambdaCallHandler.switchVariant(cu.getAST(), cu, rewriter, null, found.get(), newMethod, group, param);

        IDocument doc = new Document(source);
        TextEdit edits = rewriter.rewriteAST(doc, null);
        assertDoesNotThrow(() -> edits.apply(doc));
        return doc.get();
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
