package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code new T()} placeholder has to name a constructor {@code T} actually declares.
 *
 * <p>It used to be emitted unconditionally, so every type without a no-arg constructor produced source that
 * does not compile — the SDK's {@code ImageTemplate} declares only {@code (String)} and {@code (String, double)},
 * and {@code new ImageTemplate()} reached two user projects on disk before this was found. The five hand-written
 * exemptions in {@code InitializerFactory} were the same bug, patched one type at a time.
 */
class ConstructorPlaceholderTest {

    /** The reported case: only a {@code (String)} constructor, so the placeholder has to pass one. */
    @Test
    void aTypeWithNoNoArgConstructorGetsItsShortestOne() {
        assertEquals("new Template(\"\")", seed("Template"));
    }

    /** Zero-arg wins when it exists — it is the slot the user is going to fill anyway. */
    @Test
    void aZeroArgConstructorIsPreferredOverAShorterLoad() {
        assertEquals("new Plain()", seed("Plain"));
    }

    /**
     * A constructor whose parameters aren't literals is left alone, because this factory can add no imports:
     * filling {@code new Holder(Template)} would trade "no such constructor" for "cannot find symbol Template".
     * The bare {@code new T()} that comes back is the old behaviour — still uncompilable for this type, but no
     * worse, and honest about what we can see.
     */
    @Test
    void aConstructorNeedingAnImportedArgumentIsNotGuessedAt() {
        assertEquals("new Holder()", seed("Holder"));
    }

    /**
     * Without an analyzer there is nothing to ask, so the old text stands. This is the live path for the short
     * {@code createDefaultInitializer} overloads ({@code CodeEditor}, {@code TypeHandler}) — which is also why
     * the special cases below cannot be retired in favour of the rule.
     */
    @Test
    void withNoAnalyzerTheOldPlaceholderStands() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        Expression seeded = InitializerFactory.createDefaultInitializer(ast, ResolvedType.named("Template"));

        assertEquals("new Template()", seeded.toString());
    }

    /**
     * The named-default seeds still beat the rule. {@code Precision}'s shortest constructor would compile, but
     * {@code Precision.DEFAULT} is the entire point of the type; {@code java.awt.Color}'s would give
     * {@code new Color(0)}, which compiles and then lies — {@code ColorArgPicker} reads RGB back out of
     * {@code new Color(r, g, b)} and returns null for anything else, so the swatch would silently disagree with
     * the source again. Both are asserted here so a later "the rule subsumes these" cleanup has to face them.
     */
    @Test
    void theNamedDefaultSeedsStillWin() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        ProjectAnalyzer analyzer = analyzer();

        // Fully qualified since the seed became the plugin's (SdkPlugin.sourceSeeds), for the reason the
        // Color line below has always been: an expression the host drops into a file it is not importing
        // into has to name its type completely. Studio's ImportManager shortens it on the next pass.
        assertEquals("com.botmaker.sdk.api.vision.Precision.DEFAULT",
                InitializerFactory.createDefaultInitializer(ast, ResolvedType.named("Precision"), null, null, analyzer).toString());
        assertEquals("new java.awt.Color(255,255,255)",
                InitializerFactory.createDefaultInitializer(ast, ResolvedType.named("Color"), null, null, analyzer).toString());
    }

    /**
     * The four {@code java.time} values, none of which has a public constructor.
     *
     * <p>{@code LocalDate} was the one missing from that list, so it fell through to the rule above and — with
     * no analyzer able to see the JDK's constructors — came back as {@code new LocalDate()}. That reached a
     * user's project through "delete this variable, replace its uses with the default" and failed to compile
     * with "constructor LocalDate cannot be applied to given types". Asserted alongside its three neighbours so
     * the next JDK value added here is added to all four.
     */
    @Test
    void theJavaTimeValuesAreSeededWithCallsRatherThanConstructors() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        ProjectAnalyzer analyzer = analyzer();

        assertEquals("java.time.LocalDate.now()", seedOf(ast, "LocalDate", analyzer));
        assertEquals("java.time.LocalTime.of(12,0)", seedOf(ast, "LocalTime", analyzer));
        assertEquals("java.time.DayOfWeek.MONDAY", seedOf(ast, "DayOfWeek", analyzer));
        assertEquals("java.time.Month.JANUARY", seedOf(ast, "Month", analyzer));
    }

    private static String seedOf(AST ast, String typeName, ProjectAnalyzer analyzer) {
        return InitializerFactory.createDefaultInitializer(
                ast, ResolvedType.named(typeName), null, null, analyzer).toString();
    }

    /** The placeholder this factory produces for {@code typeName}, rendered as source. */
    private static String seed(String typeName) {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        return InitializerFactory.createDefaultInitializer(
                ast, ResolvedType.named(typeName), null, null, analyzer()).toString();
    }

    /**
     * An analyzer over a three-class project. No library index — {@code getConstructors} resolves these from
     * the project's own bindings, which is the same answer path an SDK type takes through the index.
     */
    private static ProjectAnalyzer analyzer() {
        ProjectState state = new ProjectState();
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());
        ProjectAnalyzer analyzer = new ProjectAnalyzer(null, state);

        addClass(state, analyzer, "Template", """
                package test;
                public class Template {
                    public Template(String path) {}
                    public Template(String path, double precision) {}
                }
                """);
        addClass(state, analyzer, "Plain", """
                package test;
                public class Plain {
                    public Plain(String name) {}
                    public Plain() {}
                }
                """);
        addClass(state, analyzer, "Holder", """
                package test;
                public class Holder {
                    public Holder(Template template) {}
                }
                """);
        return analyzer;
    }

    private static void addClass(ProjectState state, ProjectAnalyzer analyzer, String name, String source) {
        Path path = Paths.get(name + ".java").toAbsolutePath();
        ProjectFile file = new ProjectFile(path, source);
        CompilationUnit cu = analyzer.createCompilationUnit(source);
        file.setAst(cu);
        state.addFile(file);
    }
}
