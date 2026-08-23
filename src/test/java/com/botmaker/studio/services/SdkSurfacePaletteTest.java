package com.botmaker.studio.services;

import com.botmaker.studio.config.BotMakerDirs;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The curation half of {@link SdkSurfaceService}: which overloads this bot's SDK says are worth
 * <em>offering</em>, as opposed to which merely exist (that is {@link SdkSurfaceServiceTest}).
 *
 * <p>Real jars again, for the reason that test gives and one more of its own: {@code @Palette} is
 * {@code CLASS}-retained, so unlike {@code @Deprecated} it is <b>not</b> visible to reflection at all. If it
 * failed to survive into the class file, or ClassGraph declined to read a {@code RuntimeInvisible} annotation,
 * every assertion below would read as "uncurated" and a mock would never have noticed.
 *
 * <p>Three jars, because strict mode has three states and conflating any two of them is the way this feature
 * breaks in the field: a jar that predates the annotation (offer everything), a curated class (offer the
 * annotated overloads), and an uncurated class inside a curated jar (offer everything — this is what lets the
 * annotation be rolled out one facade at a time).
 */
class SdkSurfacePaletteTest {

    private static final String PKG = "com.botmaker.sdk.api";
    private static final String META = PKG + ".meta";

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(BotMakerDirs.getCacheDir().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables)");
    }

    /**
     * A miniature curated SDK. {@code Finder} is in strict mode with three of its five overloads offered —
     * including a varargs one, whose key must be the <em>element</em> type; {@code Mouse} carries no
     * annotation at all and so stays uncurated inside the same jar.
     */
    private static Path curatedJar(Path dir) throws IOException {
        return jarOf(dir, "curated", true, """
                package %s;
                import %s.Palette;
                @Palette
                public class Finder {
                    @Palette public static boolean find(String template) { return false; }
                    public static boolean find(String template, double confidence) { return false; }
                    @Palette public static boolean find(String template, int[] source) { return false; }
                    @Palette public static boolean findAny(String... templates) { return false; }
                    public static void tune(double factor) {}
                }
                """.formatted(PKG, META), """
                package %s;
                public class Mouse {
                    public static void click(int x, int y) {}
                    public static void click(int x, int y, int button) {}
                }
                """.formatted(PKG));
    }

    /** The same two facades in a jar with no {@code Palette} class at all — every SDK released so far. */
    private static Path uncuratedJar(Path dir) throws IOException {
        return jarOf(dir, "uncurated", false, """
                package %s;
                public class Finder {
                    public static boolean find(String template) { return false; }
                    public static boolean find(String template, double confidence) { return false; }
                }
                """.formatted(PKG), """
                package %s;
                public class Mouse {
                    public static void click(int x, int y) {}
                }
                """.formatted(PKG));
    }

    private static Path jarOf(Path dir, String name, boolean withPalette, String... sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jar cannot be built");

        Path root = dir.resolve(name);
        Path pkgDir = root.resolve("src").resolve(PKG.replace('.', '/'));
        Files.createDirectories(pkgDir);
        List<String> files = new ArrayList<>();
        if (withPalette) {
            Path metaDir = root.resolve("src").resolve(META.replace('.', '/'));
            Files.createDirectories(metaDir);
            Path palette = metaDir.resolve("Palette.java");
            Files.writeString(palette, """
                    package %s;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.CLASS)
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    public @interface Palette {}
                    """.formatted(META));
            files.add(palette.toString());
        }
        for (int i = 0; i < sources.length; i++) {
            String simple = sources[i].split("public class ")[1].split("[ {]")[0];
            Path p = pkgDir.resolve(simple + ".java");
            Files.writeString(p, sources[i]);
            files.add(p.toString());
        }

        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)),
                "the fixture sources must compile");

        Path jar = root.resolve("botmaker-sdk-" + name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static SdkSurfaceService serviceOver(Path tmp, Path jar) {
        TypeSummaryManager index = new TypeSummaryManager(Set.of(PKG));
        if (jar != null) index.refresh(List.of(jar.toString()));
        return new SdkSurfaceService(ProjectConfig.forProject("fixture", tmp), index, new EventBus(false));
    }

    // --- The probe: an old jar is detected, not guessed ---

    @Test
    void anSdkWithoutTheAnnotationOffersEverything(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, uncuratedJar(tmp));

        assertFalse(surface.isPaletteAware(),
                "a jar with no Palette class must read as uncurated, not as curated-to-nothing");
        assertFalse(surface.isCurated("Finder"));
        assertNull(surface.offeredSignatures("Finder", "find"),
                "null is the 'all of them' answer and is what every SDK released so far gets");
        assertTrue(surface.isOffered("Finder", "find"));
        assertTrue(surface.isOffered("Mouse", "click"));
    }

    @Test
    void anUnindexedSdkOffersEverything(@TempDir Path tmp) {
        SdkSurfaceService surface = serviceOver(tmp, null);

        assertFalse(surface.isIndexed());
        assertFalse(surface.isPaletteAware());
        assertNull(surface.offeredSignatures("Finder", "find"));
        assertTrue(surface.isOffered("Finder", "anythingAtAll"),
                "the fail-open rule outranks strict mode: a degraded probe must not empty the menus");
    }

    // --- Strict mode ---

    @Test
    void aCuratedClassOffersExactlyTheAnnotatedOverloads(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, curatedJar(tmp));

        assertTrue(surface.isPaletteAware());
        assertTrue(surface.isCurated("Finder"));
        assertEquals(Set.of("String", "String,int[]"), surface.offeredSignatures("Finder", "find"),
                "the double-confidence overload is public and supported, but not proposed");
        assertTrue(surface.isOffered("Finder", "find"));
    }

    @Test
    void aVarargsOverloadIsKeyedByItsElementType(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, curatedJar(tmp));

        // The descriptor is String[]; every caller — the menu, the picker, the persisted favorite — reasons
        // about the element, so the key has to agree with them and not with the bytecode.
        assertEquals(Set.of("String"), surface.offeredSignatures("Finder", "findAny"));
    }

    @Test
    void aMethodWithNoAnnotatedOverloadIsNotOffered(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, curatedJar(tmp));

        assertEquals(Set.of(), surface.offeredSignatures("Finder", "tune"));
        assertFalse(surface.isOffered("Finder", "tune"),
                "an empty set is 'none of them' — the strict default for anything nobody annotated");
    }

    @Test
    void anUncuratedClassInACuratedJarIsUnchanged(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, curatedJar(tmp));

        // This is what makes the sweep safe to stop anywhere: a facade nobody has decided yet behaves
        // exactly as it did before the annotation existed, even though the jar around it is curated.
        assertFalse(surface.isCurated("Mouse"));
        assertNull(surface.offeredSignatures("Mouse", "click"));
        assertTrue(surface.isOffered("Mouse", "click"));
    }

    // --- Offered, never resolved ---

    @Test
    void theOverloadACallIsAlreadyOnSurvivesTheFilter(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, curatedJar(tmp));
        MethodSignature hidden = sig("find", "String", "double");
        List<MethodSignature> all = List.of(sig("find", "String"), hidden, sig("find", "String", "int[]"));

        assertEquals(2, surface.retainOffered("Finder", "find", all, null).size());
        List<MethodSignature> keeping = surface.retainOffered("Finder", "find", all, hidden);
        assertEquals(3, keeping.size(), "a block already on a hidden overload must still see where it is");
        assertTrue(keeping.contains(hidden));
    }

    @Test
    void filteringEverythingAwayFallsBackToTheWholeList(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, curatedJar(tmp));
        List<MethodSignature> all = List.of(sig("tune", "double"));

        assertEquals(all, surface.retainOffered("Finder", "tune", all, null),
                "an empty picker reads as a broken block; the menu already declined to offer this name");
    }

    private static MethodSignature sig(String name, String... paramTypes) {
        List<ResolvedType> types = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < paramTypes.length; i++) {
            types.add(ResolvedType.named(paramTypes[i]));
            names.add("arg" + i);
        }
        return new MethodSignature(name, types, names, ResolvedType.named("boolean"));
    }
}
