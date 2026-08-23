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
 * Curation reached the whole menu system on 2026-08-24, and with it a vocabulary problem
 * {@link SdkSurfacePaletteTest} never had to face: that test asks about {@code "Finder"}, but the surfaces
 * added here ask about whatever the analyzer called the scope — {@code com.botmaker.sdk.api.vision.Finder}
 * for a variable, an in-scope type or a library class, and a bare simple name only for a facade.
 *
 * <p>Both spellings must reach the same verdict, and — the half that actually protects someone —
 * a class of the user's own that merely <em>shares</em> an SDK simple name must reach none at all. A bot with
 * its own {@code Window} is not far-fetched; silently filtering its methods by the SDK's opinion of a
 * different class would be a menu that lies with no symptom to report.
 *
 * <p>The name-level filter is tested here too, because it is the one place that deliberately does <em>not</em>
 * inherit {@code retainOffered}'s never-hand-back-nothing guard, and the reason is a judgement rather than a
 * mechanism (see {@link SdkSurfaceService#retainOfferedNames}).
 */
class PaletteKeyResolutionTest {

    private static final String PKG = "com.botmaker.sdk.api";
    private static final String META = PKG + ".meta";
    private static final String USER_PKG = "com.mybot";

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(BotMakerDirs.getCacheDir().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables)");
    }

    /**
     * A curated {@code Window} in the SDK's package, and a same-named class of the user's own beside it. Both
     * go into one jar so the collision is real rather than staged: the index restricts itself to the SDK's
     * package, so only the first is ever indexed — which is exactly why keying on the last segment alone
     * would answer for the second.
     */
    private static Path jarWithACollision(Path dir) throws IOException {
        return jarOf(dir, """
                package %s;
                import %s.Palette;
                @Palette
                public class Window {
                    @Palette public static int width() { return 0; }
                    public static Object capture() { return null; }
                }
                """.formatted(PKG, META), """
                package %s;
                public class Window {
                    public static int width() { return 0; }
                    public static Object capture() { return null; }
                }
                """.formatted(USER_PKG));
    }

    private static Path jarOf(Path dir, String... sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jar cannot be built");

        Path root = dir.resolve("collision");
        List<String> files = new ArrayList<>();

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

        for (String source : sources) {
            String pkg = source.split("package ")[1].split(";")[0];
            String simple = source.split("public class ")[1].split("[ {]")[0];
            Path pkgDir = root.resolve("src").resolve(pkg.replace('.', '/'));
            Files.createDirectories(pkgDir);
            Path p = pkgDir.resolve(simple + ".java");
            Files.writeString(p, source);
            files.add(p.toString());
        }

        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)),
                "the fixture sources must compile");

        Path jar = root.resolve("botmaker-sdk-collision.jar");
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
        index.refresh(List.of(jar.toString()));
        return new SdkSurfaceService(ProjectConfig.forProject("fixture", tmp), index, new EventBus(false));
    }

    // --- The two spellings agree ---

    @Test
    void aQualifiedSdkNameAnswersAsItsSimpleNameDoes(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, jarWithACollision(tmp));

        assertTrue(surface.isCurated("Window"), "the facade menus' spelling");
        assertTrue(surface.isCurated(PKG + ".Window"), "a variable scope's spelling");
        assertEquals(surface.offeredSignatures("Window", "width"),
                surface.offeredSignatures(PKG + ".Window", "width"));
        assertFalse(surface.isOffered(PKG + ".Window", "capture"),
                "an unannotated member is hidden however the caller spells its type");
    }

    // --- A user's class of the same name is not the SDK's ---

    @Test
    void aUserClassSharingAnSdkSimpleNameIsNotCurated(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, jarWithACollision(tmp));

        assertFalse(surface.isCurated(USER_PKG + ".Window"));
        assertNull(surface.offeredSignatures(USER_PKG + ".Window", "capture"),
                "null is the 'all of them' answer — the user's own class is nobody's to curate");
        assertTrue(surface.isOffered(USER_PKG + ".Window", "capture"));
    }

    @Test
    void anUnrelatedTypeIsUntouched(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, jarWithACollision(tmp));

        assertFalse(surface.isCurated("java.util.List"));
        assertFalse(surface.isCurated("SomethingNobodyHasHeardOf"));
        assertNull(surface.offeredSignatures("java.util.List", "add"));
    }

    // --- The name-level filter ---

    @Test
    void hiddenNamesDropOutAndTheCurrentOneStays(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, jarWithACollision(tmp));
        List<String> names = List.of("capture", "width");

        assertEquals(List.of("width"), surface.retainOfferedNames(PKG + ".Window", names, null));
        assertEquals(names, surface.retainOfferedNames(PKG + ".Window", names, "capture"),
                "a block already on a hidden member must still see its own name in the dropdown");
    }

    @Test
    void anEmptyResultIsAnAnswerNotAFallback(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, jarWithACollision(tmp));

        // Deliberately unlike retainOffered: the caller drops the whole submenu, which is the same thing it
        // already does for a type with nothing compatible with the slot. An empty ⚙ picker reads as breakage;
        // an absent submenu does not.
        assertEquals(List.of(), surface.retainOfferedNames(PKG + ".Window", List.of("capture"), null));
    }

    @Test
    void anUncuratedTypeIsTheIdentityFunction(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, jarWithACollision(tmp));
        List<String> names = List.of("capture", "width");

        assertEquals(names, surface.retainOfferedNames(USER_PKG + ".Window", names, null));
        assertEquals(names, surface.retainOfferedNames("java.util.List", names, null));
    }

    @Test
    void overloadFilteringAcceptsAQualifiedNameToo(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, jarWithACollision(tmp));
        List<MethodSignature> all = List.of(sig("width"), sig("width", "int"));

        assertEquals(List.of(all.getFirst()), surface.retainOffered(PKG + ".Window", "width", all, null));
        assertEquals(all, surface.retainOffered(USER_PKG + ".Window", "width", all, null));
    }

    private static MethodSignature sig(String name, String... paramTypes) {
        List<ResolvedType> types = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < paramTypes.length; i++) {
            types.add(ResolvedType.named(paramTypes[i]));
            names.add("arg" + i);
        }
        return new MethodSignature(name, types, names, ResolvedType.named("int"));
    }
}
