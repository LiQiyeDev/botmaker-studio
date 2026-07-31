package com.botmaker.studio.index;

import com.botmaker.studio.config.BotMakerDirs;
import io.github.classgraph.ClassInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>Studio remainder MISSING 4 — index → JSON cache → re-hydrate round-trip.</b>
 *
 * <p>355 lines at 0.0%, and the cache is what makes Studio's startup tolerable: without it every launch
 * re-scans every jar on the project's classpath with ClassGraph before the first menu can open. The
 * serialize/deserialize step is ClassGraph's; what is Studio's — and what this covers — is the <em>policy</em>
 * around it: where a cache file goes, when it is considered fresh, what re-hydrates from it, and which
 * classes are surfaced to the user once it has.
 *
 * <p>The fixture is a real jar, compiled here with the platform compiler and scanned by the real ClassGraph.
 * There is no seam for the cache <em>directory</em> — {@code BotMakerDirs.getCacheDir()} is a static read of
 * the environment — so the suite redirects it into {@code target/} through Surefire's
 * {@code <environmentVariables>}; see the pom. The assumption below is what says so out loud rather than
 * writing into a developer's real cache when that redirect does not apply.
 */
class TypeSummaryManagerCacheTest {

    /** The package the fixture jar's classes live in, and the allow-list this manager is built with. */
    private static final String PKG = "com.example.fixture";

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(BotMakerDirs.getCacheDir().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    /** Compiles two classes into {@code dir} and jars them up. Returns the jar path. */
    private static Path fixtureJar(Path dir, String jarName) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jar cannot be built");

        Path src = dir.resolve("src");
        Path pkgDir = src.resolve(PKG.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Widget.java"), """
                package %s;
                public class Widget {
                    public static String describe(int n) { return "widget " + n; }
                    public int size;
                }
                """.formatted(PKG));
        Files.writeString(pkgDir.resolve("Mode.java"), """
                package %s;
                public enum Mode { FAST, SLOW }
                """.formatted(PKG));
        Files.writeString(pkgDir.resolve("Quiet.java"), """
                package %s;
                public class Quiet { public void nothingStaticHere() {} }
                """.formatted(PKG));

        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        int rc = compiler.run(null, null, null, "-d", classes.toString(),
                pkgDir.resolve("Widget.java").toString(),
                pkgDir.resolve("Mode.java").toString(),
                pkgDir.resolve("Quiet.java").toString());
        assertEquals(0, rc, "the fixture sources must compile");

        Path jar = dir.resolve(jarName);
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

    private static TypeSummaryManager managerFor(Path jar) {
        TypeSummaryManager manager = new TypeSummaryManager(Set.of(PKG));
        manager.refresh(List.of(jar.toString()));
        return manager;
    }

    private static List<String> simpleNames(TypeSummaryManager manager) {
        return manager.getAllTypes().stream().map(ClassInfo::getSimpleName).sorted().toList();
    }

    // --- Indexing ---

    @Test
    void afreshJarIsScannedAndItsTypesBecomeQueryable(@TempDir Path tmp) throws IOException {
        TypeSummaryManager manager = managerFor(fixtureJar(tmp, "fixture-1.0.jar"));

        assertEquals(List.of("Mode", "Quiet", "Widget"), simpleNames(manager));
        assertEquals(3, manager.totalTypes());
        assertTrue(manager.findBySimpleName("Widget").isPresent());
        assertTrue(manager.findByQualifiedName(PKG + ".Widget").isPresent());
        assertTrue(manager.findBySimpleName("NotHere").isEmpty());
    }

    @Test
    void anEnumIsRecognisedAsOneAndAStaticUtilityByItsValueReturningStaticMethod(@TempDir Path tmp)
            throws IOException {
        TypeSummaryManager manager = managerFor(fixtureJar(tmp, "fixture-1.0.jar"));

        assertEquals(List.of("Mode"), manager.findEnums().stream().map(ClassInfo::getSimpleName).toList(),
                "the expression menu offers enum constants off this list");
        assertTrue(manager.getStaticUtilityTypes().stream().anyMatch(c -> "Widget".equals(c.getSimpleName())),
                "Widget.describe is a public static returning a value — a Call-Function target");
        assertFalse(manager.getStaticUtilityTypes().stream().anyMatch(c -> "Quiet".equals(c.getSimpleName())),
                "a class with nothing static to call must not appear in the Call-Function menu");
    }

    /**
     * Everything in a jar is indexed, but only the allowed prefixes are surfaced. That split is what keeps
     * the block palette to the SDK's {@code api} package instead of listing OpenCV, Jackson and JDT.
     */
    @Test
    void classesOutsideTheAllowedPrefixesStayIndexedButNeverReachTheMenus(@TempDir Path tmp) throws IOException {
        Path jar = fixtureJar(tmp, "fixture-1.0.jar");

        TypeSummaryManager hidden = new TypeSummaryManager(Set.of("com.somewhere.else"));
        hidden.refresh(List.of(jar.toString()));

        assertEquals(List.of(), simpleNames(hidden), "nothing under the allow-list is surfaced");
        assertEquals(3, hidden.getTypesForJar(jar.toString()).size(),
                "but the jar is still fully indexed, so resolution can find those types");
    }

    // --- The cache round-trip ---

    @Test
    void indexingWritesAJsonCacheNextToTheJarsName(@TempDir Path tmp) throws IOException {
        Path jar = fixtureJar(tmp, "fixture-1.0.jar");
        managerFor(jar);

        Path cache = TypeSummaryManager.getCacheFileForJar(jar.toString());
        assertTrue(Files.exists(cache), "no cache written at " + cache);
        assertTrue(Files.size(cache) > 0);
        assertEquals("fixture-1.0.jar.json", cache.getFileName().toString());
    }

    /**
     * <b>The round-trip.</b> A second manager over the same jar must not re-scan — it re-hydrates
     * ClassGraph's JSON — and must answer identically. Everything a menu reads has to survive the trip:
     * the class list, the enum flag, the member info the static-utility filter runs on.
     */
    @Test
    void aSecondLoadComesFromTheCacheAndAnswersIdentically(@TempDir Path tmp) throws IOException {
        Path jar = fixtureJar(tmp, "fixture-1.0.jar");
        TypeSummaryManager first = managerFor(jar);

        Path cache = TypeSummaryManager.getCacheFileForJar(jar.toString());
        FileTime writtenAt = Files.getLastModifiedTime(cache);

        TypeSummaryManager second = managerFor(jar);

        assertEquals(simpleNames(first), simpleNames(second));
        assertEquals(first.totalTypes(), second.totalTypes());
        assertEquals(List.of("Mode"), second.findEnums().stream().map(ClassInfo::getSimpleName).toList(),
                "isEnum must survive JSON, or every enum picker empties on the second launch");
        assertTrue(second.getStaticUtilityTypes().stream().anyMatch(c -> "Widget".equals(c.getSimpleName())),
                "method info must survive too — the static-utility filter reads it");
        assertEquals(writtenAt, Files.getLastModifiedTime(cache),
                "a fresh cache must be read, not rewritten: rewriting it means the scan ran again");
    }

    /**
     * The staleness rule, and the case its javadoc names: a locally-rebuilt {@code 0.0.0-SNAPSHOT} jar keeps
     * its file name, so only the modification time can say the cache is out of date. Without this, a
     * dev-install of the SDK would show the previous build's API until the cache was deleted by hand.
     */
    @Test
    void aJarRebuiltUnderTheSameNameIsReIndexedRatherThanServedFromCache(@TempDir Path tmp) throws IOException {
        Path jar = fixtureJar(tmp, "fixture-0.0.0-SNAPSHOT.jar");
        TypeSummaryManager manager = managerFor(jar);
        assertEquals(3, manager.totalTypes());

        // Rebuild: same path and name, different contents, newer than the cache.
        Path rebuilt = fixtureJar(tmp.resolve("rebuild"), "fixture-0.0.0-SNAPSHOT.jar");
        Files.copy(rebuilt, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        TypeSummaryManager after = new TypeSummaryManager(Set.of(PKG));
        after.refresh(List.of(jar.toString()));

        assertTrue(Files.getLastModifiedTime(TypeSummaryManager.getCacheFileForJar(jar.toString()))
                        .toMillis() >= Files.getLastModifiedTime(jar).toMillis() - 5_000,
                "a jar newer than its cache must have been re-scanned and re-saved");
        assertEquals(3, after.totalTypes());
    }

    @Test
    void refreshingWithAJarAlreadyInMemoryIsANoOp(@TempDir Path tmp) throws IOException {
        Path jar = fixtureJar(tmp, "fixture-1.0.jar");
        TypeSummaryManager manager = managerFor(jar);

        manager.refresh(List.of(jar.toString()));
        manager.refresh(List.of(jar.toString()));

        assertEquals(3, manager.totalTypes(), "re-refreshing must not duplicate a jar's classes");
    }

    @Test
    void invalidatingAJarDeletesItsCacheSoTheNextLoadRescans(@TempDir Path tmp) throws IOException {
        Path jar = fixtureJar(tmp, "fixture-1.0.jar");
        managerFor(jar);
        Path cache = TypeSummaryManager.getCacheFileForJar(jar.toString());
        assertTrue(Files.exists(cache));

        TypeSummaryManager.invalidateJar(jar.toString());

        assertFalse(Files.exists(cache));
        assertEquals(3, managerFor(jar).totalTypes(), "and the next load rebuilds it from the jar");
    }

    @Test
    void aCorruptCacheFileIsReIndexedRatherThanCrashingTheOpen(@TempDir Path tmp) throws IOException {
        Path jar = fixtureJar(tmp, "fixture-1.0.jar");
        managerFor(jar);
        Path cache = TypeSummaryManager.getCacheFileForJar(jar.toString());
        Files.writeString(cache, "{ not the json ClassGraph wrote }");
        Files.setLastModifiedTime(cache, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        TypeSummaryManager manager = new TypeSummaryManager(Set.of(PKG));
        manager.refresh(List.of(jar.toString()));

        assertEquals(3, manager.totalTypes(),
                "a cache written by an older ClassGraph must degrade to a re-scan, not an unopenable project");
    }

    @Test
    void aJarThatDoesNotExistIsSkippedRatherThanThrowing(@TempDir Path tmp) {
        TypeSummaryManager manager = new TypeSummaryManager(Set.of(PKG));
        manager.refresh(List.of(tmp.resolve("gone.jar").toString()));

        assertEquals(0, manager.totalTypes());
    }

    /**
     * <b>B23 — the cache file is keyed by jar <em>file name</em>, not by path.</b> Two different jars that
     * happen to share a name share one cache file, and the freshness check only compares that file's
     * timestamp against the jar being asked for. A second jar older than the first one's cache is therefore
     * served the first one's classes.
     *
     * <p>Narrow in practice — Maven names are version-stamped, so it takes two builds of the same coordinate
     * in different places — but it is exactly the situation a developer with a locally-installed SNAPSHOT and
     * a project-local copy is in, and the symptom (a menu listing another jar's API) gives no hint of a cache.
     * Characterised, not fixed: keying on the absolute path is the fix, and it invalidates every existing
     * cache file, which is a decision for the phase that makes it.
     */
    @Test
    void twoJarsWithTheSameNameShareOneCacheEntry(@TempDir Path tmp) throws IOException {
        Path first = fixtureJar(tmp.resolve("a"), "lib.jar");
        Path second = fixtureJar(tmp.resolve("b"), "lib.jar");

        assertNotEquals(first, second, "two distinct jars on disk");
        assertEquals(TypeSummaryManager.getCacheFileForJar(first.toString()),
                TypeSummaryManager.getCacheFileForJar(second.toString()),
                "…and one cache file between them");

        managerFor(first);
        Files.setLastModifiedTime(second, FileTime.fromMillis(
                Files.getLastModifiedTime(TypeSummaryManager.getCacheFileForJar(first.toString())).toMillis()
                        - 60_000));

        TypeSummaryManager served = new TypeSummaryManager(Set.of(PKG));
        served.refresh(List.of(second.toString()));

        assertEquals(3, served.totalTypes(),
                "the second jar was answered out of the first jar's cache without ever being scanned");
    }
}
