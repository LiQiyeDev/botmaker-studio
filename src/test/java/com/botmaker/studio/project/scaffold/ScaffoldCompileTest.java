package com.botmaker.studio.project.scaffold;

import com.botmaker.sdk.api.bot.Activity;
import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every project in {@link ScaffoldCorpus} is written to disk and compiled against the real SDK jar.
 *
 * <h2>Why this is the check, and what it replaced</h2>
 *
 * <p>The question the scaffolding apparatus has always been trying to answer is <em>does the code Studio
 * writes actually work against the SDK?</em> Until 2026-08-24 it was answered indirectly: a JDT visitor
 * ({@code ScaffoldScan}, 484 lines) parsed the generators' text-block output to recover the SDK members it
 * named, a hand-written declaration was compared against that, and every entry was then resolved by
 * reflection. Three mechanisms, all approximating a compile — and all of them blind to anything a compiler
 * checks and a symbol table does not: a wrong argument <em>type</em>, a generic that does not unify, a
 * missing import, an outcome constant routed from the wrong activity's enum.
 *
 * <p>Now the frame comes out of the SDK jar as text and the fragments are dropped into it, so the assembled
 * file can simply be handed to {@code javac} with that same jar on the classpath. That is a strictly stronger
 * answer, and it is the same one the user would get — a project that does not compile is precisely the
 * failure every one of those mechanisms existed to prevent.
 *
 * <h2>One test per model</h2>
 *
 * <p>{@link TestFactory} rather than a loop, so a failure names the shape that broke rather than the first
 * one the loop reached, and so each model's diagnostics are read on their own.
 */
class ScaffoldCompileTest {

    @TestFactory
    Stream<DynamicTest> everyGeneratedProjectCompilesAgainstTheSdk(@TempDir Path root) {
        return ScaffoldCorpus.models().stream().map(model -> DynamicTest.dynamicTest(model.name(), () -> {
            ProjectConfig config = ProjectConfig.forProject("actbot", root.resolve(slug(model.name())));
            Map<Path, String> rendered = ScaffoldCorpus.render(model, config);

            for (Map.Entry<Path, String> file : rendered.entrySet()) {
                Files.createDirectories(file.getKey().getParent());
                Files.writeString(file.getKey(), file.getValue());
            }

            Path classes = compile(config, rendered.keySet());
            // A compiler invocation that returned 0 having read nothing would satisfy the line above without
            // checking one file, so the class files are counted: at least one per source, and more than that
            // wherever a nested enum or a lambda was compiled too.
            try (Stream<Path> out = Files.walk(classes)) {
                assertTrue(out.filter(p -> p.toString().endsWith(".class")).count() >= rendered.size(),
                        "javac reported success but produced fewer classes than there were sources");
            }
        }));
    }

    /**
     * A rendered file may not still carry a fill marker. The fences are the SDK's own machinery for saying
     * where project data goes; they are stripped whether or not Studio filled them, because a seed file is
     * the user's from the moment it is written and nobody should have to read around our comments in it.
     *
     * <p>Checked here rather than in {@code TemplateStoreTest} because it is the assembled output that
     * matters — a token filled by one generator and left behind by another would pass a per-template check.
     */
    @Test
    void noRenderedFileCarriesAFillMarker(@TempDir Path root) throws Exception {
        for (ScaffoldCorpus.Model model : ScaffoldCorpus.models()) {
            ProjectConfig config = ProjectConfig.forProject("actbot", root.resolve(slug(model.name())));
            for (Map.Entry<Path, String> file : ScaffoldCorpus.render(model, config).entrySet()) {
                assertFalse(file.getValue().contains("STUDIO:"),
                        file.getKey().getFileName() + " (" + model.name() + ") still carries a fill marker:\n"
                                + file.getValue());
            }
        }
    }

    /** Sanity: a corpus that rendered nothing would satisfy every assertion above without checking one. */
    @Test
    void theCorpusRendersWholeProjects(@TempDir Path root) throws Exception {
        for (ScaffoldCorpus.Model model : ScaffoldCorpus.models()) {
            ProjectConfig config = ProjectConfig.forProject("actbot", root.resolve(slug(model.name())));
            Map<Path, String> rendered = ScaffoldCorpus.render(model, config);
            assertFalse(rendered.isEmpty(), model.name() + " rendered no files at all");
            assertTrue(rendered.values().stream().allMatch(s -> s.startsWith("package com.actbot")),
                    model.name() + " rendered a file that is not in the project's package: " + rendered.keySet());
        }
    }

    /**
     * Compiles {@code sources} against the real SDK jar and returns the directory the classes landed in,
     * failing with the compiler's own diagnostics when it will not compile.
     *
     * <p>The jar is located from a class Studio already holds rather than from a path: the SDK is on this
     * module's test classpath for type identity ({@code palette/SdkType}), so asking {@code Activity} where
     * it came from is exact, and there is no jar to build or find.
     */
    private static Path compile(ProjectConfig config, Iterable<Path> sources) throws Exception {
        Path classes = Files.createDirectories(config.projectPath().resolve("target-classes"));
        Path sdk = Path.of(Activity.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> args = new ArrayList<>(List.of("-classpath", sdk.toString(), "-d", classes.toString()));
        for (Path source : sources) args.add(source.toString());

        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc;
        try (PrintStream out = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            rc = compiler.run(null, null, out, args.toArray(String[]::new));
        }
        assertEquals(0, rc, () -> "the scaffold Studio writes does not compile:\n"
                + diagnostics.toString(StandardCharsets.UTF_8));
        return classes;
    }

    /** A model's name as a directory: each one gets its own project so the compiles cannot see each other. */
    private static String slug(String name) {
        return name.replaceAll("[^a-zA-Z0-9]+", "-");
    }
}
