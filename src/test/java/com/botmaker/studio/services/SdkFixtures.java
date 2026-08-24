package com.botmaker.studio.services;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two real SDK jars and a project to point them at — the fixture every upgrade test is built on.
 *
 * <p>The jars are <b>compiled here rather than mocked</b>, for the reason the whole feature exists: the
 * question is about bytecode, and the interesting cases — a method gone, an overload's arity changed, a
 * pointer split across two survivors — are exactly the ones no pair of published SDK versions exhibits yet.
 * A fixture that only ever encoded what already shipped would pass forever without proving the diff works.
 *
 * <p>Shared rather than copied because two harnesses that build jars slightly differently is a way to get
 * two tests disagreeing about a jar neither of them is really testing.
 */
final class SdkFixtures {

    static final String PKG = "com.botmaker.sdk.api";

    /**
     * The jars get unique names: {@link TypeSummaryManager}'s disk cache is keyed by jar <em>filename</em>
     * and settles ties by mtime, so two same-named fixtures written in the same millisecond would have the
     * second one read the first one's scan.
     */
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private SdkFixtures() {
    }

    /** Compiles {@code classes} into a jar, optionally carrying {@code resources} under {@code META-INF}. */
    static Path jarOf(Path dir, String label, Map<String, String> classes,
                      Map<String, String> resources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jars cannot be built");

        Path src = dir.resolve("src-" + label + "-" + UNIQUE.get()).resolve(PKG.replace('.', '/'));
        Files.createDirectories(src);
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, String> e : classes.entrySet()) {
            // A key may name a sub-package — "meta.Since" — because @Since and @Scaffolding only ever
            // existed under api.meta, and a fixture that put them in `api` would be testing a jar the SDK
            // has never published.
            String key = e.getKey();
            int dot = key.lastIndexOf('.');
            Path pkgDir = dot < 0 ? src : src.resolve(key.substring(0, dot).replace('.', '/'));
            Files.createDirectories(pkgDir);
            Path file = pkgDir.resolve(key.substring(dot + 1) + ".java");
            Files.writeString(file, e.getValue());
            paths.add(file.toString());
        }

        Path out = dir.resolve("classes-" + label + "-" + UNIQUE.get());
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-d", out.toString()));
        args.addAll(paths);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)),
                "the " + label + " fixture sources must compile");

        for (Map.Entry<String, String> e : resources.entrySet()) {
            Path file = out.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }

        Path jar = dir.resolve("botmaker-sdk-" + label + "-" + UNIQUE.incrementAndGet() + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(out)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
        }
        return jar;
    }

    /** A service over a throwaway project holding {@code sources} as {@code Subject.java}, {@code Subject1}… */
    static SdkUpgradeService serviceOver(Path tmp, String... sources) throws IOException {
        Path project = tmp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/com/mybot"));

        ProjectState state = new ProjectState();
        int n = 0;
        for (String source : sources) {
            Path file = project.resolve("src/main/java/com/mybot/Subject" + (n == 0 ? "" : n) + ".java");
            Files.writeString(file, source);
            state.addFile(new ProjectFile(file, source));
            n++;
        }

        ProjectConfig config = ProjectConfig.forProject("fixture", project);
        EventBus bus = new EventBus(false);
        LibraryService libraries = new LibraryService(config, state, new TypeSummaryManager(), bus);
        return new SdkUpgradeService(config, state, libraries, new JitPackSearch());
    }

    /**
     * The four meta annotations, compiled into whichever fixture jar wants them. CLASS retention is the
     * default, which is exactly what the real ones declare — and what makes them readable off a jar.
     *
     * <p>{@code ReplacedBy} and {@code Replaces} are declared under {@code api} rather than {@code api.meta}
     * on purpose: that is where a pre-1.1.0 jar has them, which is the jar an upgrade reads the forward
     * pointer out of. {@code Since} and {@code Scaffolding} have no such spelling and are declared where the
     * SDK actually keeps them.
     */
    static Map<String, String> withPointers(Map<String, String> base) {
        Map<String, String> out = new HashMap<>(base);
        out.put("ReplacedBy", """
                package %s;
                public @interface ReplacedBy {
                    String[] value() default {};
                    String[] whens() default {};
                    String note() default "";
                    boolean behaviourChanged() default false;
                }
                """.formatted(PKG));
        out.put("Replaces", """
                package %s;
                public @interface Replaces {
                    String[] value();
                    String note() default "";
                    boolean behaviourChanged() default false;
                }
                """.formatted(PKG));
        out.put("meta.Since", """
                package %s.meta;
                public @interface Since { String value(); }
                """.formatted(PKG));
        out.put("meta.Scaffolding", """
                package %s.meta;
                public @interface Scaffolding {}
                """.formatted(PKG));
        return out;
    }
}
