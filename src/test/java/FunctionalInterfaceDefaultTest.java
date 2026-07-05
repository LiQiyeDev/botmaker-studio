import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.parser.handlers.MethodHandler;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Regression coverage for the {@code whileExists}/lambda default-argument path: a parameterized type name must not
 * crash {@code createTypeNode}, and a functional-interface parameter must default to a block-bodied lambda (so it
 * round-trips into an editable {@code LambdaCallBlock}) rather than an uncompilable {@code new Consumer<>()}.
 */
public class FunctionalInterfaceDefaultTest {

    private static AST newAst() {
        return AST.newAST(AST.getJLSLatest(), false);
    }

    @Test
    void createTypeNodeDoesNotThrowOnGenericName() {
        AST ast = newAst();
        // The exact shape that previously threw "Invalid identifier : …Consumer<com…".
        Type type = assertDoesNotThrow(() ->
                ProjectAnalyzer.createTypeNode(ast, "java.util.function.Consumer<com.botmaker.sdk.api.vision.MatchResult>"));
        // Generics stripped down to the raw type.
        assertEquals("java.util.function.Consumer", type.toString());
    }

    @Test
    void consumerDefaultsToSingleParamBlockLambda() {
        AST ast = newAst();
        Expression expr = InitializerFactory.createDefaultInitializer(
                ast, ResolvedType.named("java.util.function.Consumer<com.botmaker.sdk.api.vision.MatchResult>"));

        LambdaExpression lambda = assertInstanceOf(LambdaExpression.class, expr);
        assertEquals(1, lambda.parameters().size());
        assertInstanceOf(Block.class, lambda.getBody(), "body must be a { } block so it round-trips to a droppable body");
    }

    @Test
    void switchingMethodToWhileExistsProducesBlockLambdaAndDoesNotThrow() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.find(template);
                    }
                }
                """;
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);

        AtomicReference<MethodInvocation> found = new AtomicReference<>();
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation mi) {
                if (mi.getName().getIdentifier().equals("find")) found.set(mi);
                return true;
            }
        });

        // Switch find(ImageTemplate) -> whileExists(ImageTemplate, Consumer<MatchResult>): the second arg is a
        // functional interface, exactly the shape that used to throw "Invalid identifier".
        List<ResolvedType> whileExistsParams = List.of(
                ResolvedType.named("ImageTemplate"),
                ResolvedType.named("java.util.function.Consumer<MatchResult>"));

        String result = assertDoesNotThrow(() -> MethodHandler.updateMethodInvocation(
                cu, source, found.get(), "ImageFinder", "whileExists", whileExistsParams));

        assertTrue(result.replace(" ", "").contains("whileExists(template,it->{}"),
                () -> "kept image arg and added a block-bodied lambda: " + result);
    }

    @Test
    void runnableDefaultsToNoArgBlockLambda() {
        AST ast = newAst();
        Expression expr = InitializerFactory.createDefaultInitializer(ast, ResolvedType.named("Runnable"));

        LambdaExpression lambda = assertInstanceOf(LambdaExpression.class, expr);
        assertTrue(lambda.parameters().isEmpty());
        assertTrue(lambda.hasParentheses(), "no-arg lambda renders as () -> {}");
        assertInstanceOf(Block.class, lambda.getBody());
    }
}
