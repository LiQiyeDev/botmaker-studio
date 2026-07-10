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
 * Round-trip coverage for the ⚙ vision-loop overload switch ({@link LambdaCallHandler#switchVariant}):
 * single → {@code …Any} wraps the leading image into {@code ImageTemplateGroup.of(…)} and keeps the
 * {@code Consumer<MatchResult>} lambda parameter; {@code …Any} → {@code …All} unwraps back to a single
 * template and drops the parameter (the target is a {@code Runnable}).
 */
public class LambdaVariantSwitchTest {

    /** Rewrite {@code call}'s method to {@code newMethod} via switchVariant and return the new source. */
    private static String switchTo(String source, String call, String newMethod, boolean group, boolean param) {
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
    void singleToAnyWrapsGroupAndKeepsParam() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFind(coin, m -> {});
                    }
                }
                """;
        String result = switchTo(source, "whileFind", "whileFindAny", true, true).replace(" ", "");
        assertTrue(result.contains("whileFindAny(ImageTemplateGroup.of(coin),m->{}"),
                () -> "expected group-wrapped call keeping the match param: " + result);
    }

    @Test
    void anyToAllUnwrapsGroupAndDropsParam() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFindAny(ImageTemplateGroup.of(coin), m -> {});
                    }
                }
                """;
        String result = switchTo(source, "whileFindAny", "whileFindAll", true, false).replace(" ", "");
        // still a group (All takes a group) but the lambda loses its parameter (Runnable target).
        assertTrue(result.contains("whileFindAll(ImageTemplateGroup.of(coin),()->{}"),
                () -> "expected group kept and a no-arg lambda: " + result);
    }

    @Test
    void anyToSingleUnwrapsFirstTemplate() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFindAny(ImageTemplateGroup.of(coin, gem), m -> {});
                    }
                }
                """;
        String result = switchTo(source, "whileFindAny", "whileFind", false, true).replace(" ", "");
        assertTrue(result.contains("whileFind(coin,m->{}"),
                () -> "expected first template unwrapped from the group: " + result);
    }
}
