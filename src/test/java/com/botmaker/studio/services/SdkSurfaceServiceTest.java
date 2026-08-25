package com.botmaker.studio.services;

import com.botmaker.studio.config.BotMakerDirs;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The palette gate: "does <em>this bot's</em> SDK have this?", as opposed to Studio's compile-time
 * {@code palette/SdkType}.
 *
 * <p>The fixture is a real jar built here by the platform compiler and scanned by the real ClassGraph,
 * because the two things most likely to break silently are exactly the two that a mock would paper over:
 * that {@code @Deprecated} survives into the class file at all (it is {@code RUNTIME}-retained, so it does —
 * but only if {@code TypeSummaryManager} asks ClassGraph for annotation info), and that the fail-open path
 * really is reached when no SDK is indexed.
 */
class SdkSurfaceServiceTest {

    /** The fixture's package — the one prefix TypeSummaryManager surfaces to the user. */
    private static final String PKG = "com.botmaker.sdk.api";

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(BotMakerDirs.getCacheDir().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    /**
     * A miniature SDK: one live facade, one facade with a deprecated method beside a live one, one method
     * with two overloads of which only one is deprecated, and one wholly deprecated class.
     */
    private static Path fixtureJar(Path dir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jar cannot be built");

        Path pkgDir = dir.resolve("src").resolve(PKG.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Mouse.java"), """
                package %s;
                public class Mouse {
                    public static void click(int x, int y) {}
                }
                """.formatted(PKG));
        Files.writeString(pkgDir.resolve("Wait.java"), """
                package %s;
                public class Wait {
                    public static void time(long ms) {}
                    /** @deprecated use {@link #time(long)} instead */
                    @Deprecated(since = "1.1.0", forRemoval = true)
                    public static void seconds(int s) {}
                    public static void mixed(int a) {}
                    @Deprecated
                    public static void mixed(String a) {}
                }
                """.formatted(PKG));
        Files.writeString(pkgDir.resolve("Legacy.java"), """
                package %s;
                @Deprecated
                public class Legacy {
                    public static void anything() {}
                }
                """.formatted(PKG));

        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        int rc = compiler.run(null, null, null, "-d", classes.toString(),
                pkgDir.resolve("Mouse.java").toString(),
                pkgDir.resolve("Wait.java").toString(),
                pkgDir.resolve("Legacy.java").toString());
        assertEquals(0, rc, "the fixture sources must compile");

        Path jar = dir.resolve("botmaker-sdk-fixture.jar");
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
        // No pom in the temp dir, so readSdkVersion falls back — which is itself the behaviour asserted in
        // theVersionFloorIgnoresWhatItCannotParse below.
        ProjectConfig config = ProjectConfig.forProject("fixture", tmp);
        return new SdkSurfaceService(config, index, new EventBus(false));
    }

    // --- Presence ---

    @Test
    void aClassInTheBotsJarIsPresentAndOneThatIsNotIsAbsent(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, fixtureJar(tmp));

        assertTrue(surface.isIndexed());
        assertTrue(surface.hasType("Mouse"));
        assertTrue(surface.hasMember("Mouse", "click"));
        // Real SDK classes Studio knows about but this fixture jar does not ship.
        assertFalse(surface.hasType("ImageFinder"), "a class this bot's SDK lacks must not read as present");
        assertFalse(surface.hasMember("Mouse", "doubleClick"));
        assertFalse(surface.hasMember("ImageFinder", "find"), "no class means no members");
    }

    @Test
    void withNothingIndexedEveryPresenceQuestionAnswersYes(@TempDir Path tmp) {
        SdkSurfaceService surface = serviceOver(tmp, null);

        assertFalse(surface.isIndexed());
        // Fail-open. A degraded probe hiding blocks the user legitimately has would read as a Studio bug and
        // give them nothing to diagnose; offering one that will not compile at least says so.
        assertTrue(surface.hasType("AnythingAtAll"));
        assertTrue(surface.hasMember("AnythingAtAll", "whatever"));
        assertEquals(com.botmaker.studio.palette.SdkType.MENU_FACADES.size(), surface.menuFacades().size(),
                "an unavailable index must not filter the palette at all");
        assertTrue(surface.missingFacades().isEmpty(), "and must not invent a list of missing classes either");
    }

    // --- Deprecation ---

    @Test
    void deprecationIsReadFromBytecodeNotJavadoc(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, fixtureJar(tmp));

        assertTrue(surface.isMemberDeprecated("Wait", "seconds"));
        assertFalse(surface.isMemberDeprecated("Wait", "time"));
    }

    @Test
    void aNameIsOnlyDeprecatedWhenEveryOverloadIs(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, fixtureJar(tmp));

        // The menus collapse overloads to one entry; striking that entry through while one overload is still
        // perfectly good would be a lie about the code the user is looking at.
        assertFalse(surface.isMemberDeprecated("Wait", "mixed"));
    }

    @Test
    void aDeprecatedClassDeprecatesEverythingOnIt(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, fixtureJar(tmp));

        assertTrue(surface.isTypeDeprecated("Legacy"));
        assertTrue(surface.isMemberDeprecated("Legacy", "anything"));
        assertFalse(surface.isTypeDeprecated("Mouse"));
    }

    @Test
    void anUnknownMemberIsNeverReportedDeprecated(@TempDir Path tmp) {
        SdkSurfaceService surface = serviceOver(tmp, null);

        // The mirror image of the presence rule: with no index, strike nothing through rather than everything.
        assertFalse(surface.isMemberDeprecated("Wait", "seconds"));
        assertFalse(surface.isTypeDeprecated("Legacy"));
    }

    // --- The palette intersection ---

    @Test
    void thePaletteIsIntersectedWithWhatTheBotActuallyHas(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceOver(tmp, fixtureJar(tmp));

        assertEquals(List.of("Mouse", "Wait"), surface.facadeNames(),
                "only the facades in the bot's own jar, in SdkType declaration order");
        assertTrue(surface.missingFacades().contains("ImageFinder"));
        assertFalse(surface.missingFacades().contains("Mouse"));
    }

    // --- The version floor ---

    @Test
    void anUnreadablePomAnswersTheFallbackVersion(@TempDir Path tmp) {
        SdkSurfaceService surface = serviceOver(tmp, null);

        // No pom → the fallback version, never null and never blank. This test used to close on
        // isBelowMinimum(); the version floor went on 2026-08-25 with Studio's generation, so what is left to
        // assert is the answer every other reader of this class depends on.
        assertEquals(MavenService.SDK_FALLBACK_VERSION, surface.sdkVersion());
    }
}
