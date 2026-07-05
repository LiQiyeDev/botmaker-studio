package com.botmaker.studio.index;

import com.botmaker.studio.config.BotMakerDirs;
import com.botmaker.studio.util.ClassPathManager;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Per-jar index of external library types, backed directly by ClassGraph.
 *
 * <p>Each jar is scanned once into a {@link ScanResult}; the scan is cached to disk using
 * ClassGraph's own JSON serialization ({@link ScanResult#toJSON()} / {@link ScanResult#fromJSON(String)}),
 * one {@code .json} file per jar. The in-memory index hands out raw {@link ClassInfo} objects, which
 * {@link com.botmaker.studio.suggestions.ProjectAnalyzer} consumes directly — there is no intermediate DTO.
 *
 * <p>Cache <em>policy</em> (where/when/invalidate) lives here; only the serialize/deserialize step is
 * delegated to ClassGraph.
 */
public class TypeSummaryManager {

    /**
     * Package prefixes whose classes are exposed to the user as "allowed" library types. Everything indexed
     * outside these prefixes (the SDK's {@code internal} package and all transitive dependencies — opencv,
     * jackson, eclipse, ddmlib…) is still scanned for resolution-completeness but hidden from the type and
     * expression menus, keeping the bot-builder surface curated. Future work ("import other users' bots")
     * extends this set rather than special-casing it.
     */
    public static final Set<String> DEFAULT_ALLOWED_PACKAGE_PREFIXES = Set.of("com.botmaker.sdk.api");

    private final Set<String> allowedPackagePrefixes;

    /**
     * jar path → its (non-anonymous) classes. The scans are intentionally not closed: the
     * {@link ClassInfo} objects are read for the lifetime of the manager.
     */
    private final Map<String, List<ClassInfo>> index = new HashMap<>();

    /** jar path → owning ScanResult, retained so the whole index can be re-serialized to JSON. */
    private final Map<String, ScanResult> scans = new HashMap<>();

    // Derived caches so reads are O(1) instead of re-flattening/scanning every jar on each lookup (the hot
    // path for menu population). Marked dirty by store() and rebuilt lazily on first read, so loading N jars
    // rebuilds once, not N times.
    private boolean cachesDirty = true;
    private List<ClassInfo> allTypesCache = List.of();
    private final Map<String, ClassInfo> bySimpleName = new HashMap<>();
    private final Map<String, ClassInfo> byQualifiedName = new HashMap<>();
    private List<ClassInfo> staticUtilityCache = List.of();

    public TypeSummaryManager() {
        this(DEFAULT_ALLOWED_PACKAGE_PREFIXES);
    }

    public TypeSummaryManager(Set<String> allowedPackagePrefixes) {
        this.allowedPackagePrefixes = Set.copyOf(allowedPackagePrefixes);
    }

    /** True when {@code ci}'s package is under one of the {@link #allowedPackagePrefixes}. */
    private boolean isAllowed(ClassInfo ci) {
        String pkg = ci.getPackageName();
        for (String prefix : allowedPackagePrefixes) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix + ".")) return true;
        }
        return false;
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Loads cached classes for each jar (one .json file per jar). Indexes any jars that don't have a
     * cache file yet, then saves them.
     *
     * @param allJars list of jar paths to ensure are indexed
     */
    public static TypeSummaryManager buildOrLoad(List<String> allJars) {
        TypeSummaryManager manager = new TypeSummaryManager();

        List<String> missingJars = new ArrayList<>();

        for (String jar : allJars) {
            if (ClassPathManager.isSystemJar(jar)) continue;

            Path cacheFile = getCacheFileForJar(jar);
            if (Files.exists(cacheFile)) {
                ScanResult cached = loadFromFile(cacheFile);
                if (cached != null) {
                    manager.store(jar, cached);
                    continue;
                }
            }
            missingJars.add(jar);
        }

        System.out.println("Index loaded from cache — " + manager.totalTypes() + " types across "
                + manager.index.size() + " jars");

        if (missingJars.isEmpty()) {
            return manager;
        }

        System.out.println("Indexing " + missingJars.size() + " new jars...");
        for (String jar : missingJars) {
            manager.indexJar(jar);
            manager.saveJar(jar); // save immediately after indexing each jar
        }

        return manager;
    }

    /**
     * Loads cached classes for a specific list of jars only.
     * Jars not present in cache are indexed on demand and saved.
     *
     * @param jars subset of jars to load/index
     */
    public static TypeSummaryManager load(List<String> jars) {
        return buildOrLoad(jars);
    }

    /**
     * Incrementally indexes any of {@code jars} not already present in this manager (skipping system
     * jars), saving each new jar's scan to its cache file. Already-indexed and system jars are ignored,
     * so this is cheap to call after a classpath change.
     */
    public void refresh(List<String> jars) {
        for (String jar : jars) {
            if (ClassPathManager.isSystemJar(jar)) continue;
            if (index.containsKey(jar)) continue;

            Path cacheFile = getCacheFileForJar(jar);
            if (Files.exists(cacheFile)) {
                ScanResult cached = loadFromFile(cacheFile);
                if (cached != null) {
                    store(jar, cached);
                    continue;
                }
            }
            indexJar(jar);
            saveJar(jar);
        }
    }

    public List<ClassInfo> getAllTypes() {
        ensureCaches();
        return allTypesCache;
    }

    public List<ClassInfo> getTypesForJar(String jarPath) {
        return index.getOrDefault(jarPath, List.of());
    }

    public Optional<ClassInfo> findBySimpleName(String simpleName) {
        ensureCaches();
        return Optional.ofNullable(bySimpleName.get(simpleName));
    }

    public Optional<ClassInfo> findByQualifiedName(String qualifiedName) {
        ensureCaches();
        return Optional.ofNullable(byQualifiedName.get(qualifiedName));
    }

    public List<ClassInfo> findEnums() {
        return getAllTypes().stream()
                .filter(ClassInfo::isEnum)
                .toList();
    }

    /** Library classes exposing at least one public static method returning a value (Call-Function targets). */
    public List<ClassInfo> getStaticUtilityTypes() {
        ensureCaches();
        return staticUtilityCache;
    }

    public int totalTypes() {
        return getAllTypes().size();
    }

    /**
     * Eagerly builds the derived caches (name maps + static-utility list) so the first menu interaction
     * doesn't pay the cost on the UI thread. Safe to call from a background thread — {@link #ensureCaches()}
     * is synchronized and idempotent. Intended to be invoked once right after the index is loaded.
     */
    public void warmCaches() {
        ensureCaches();
    }

    // =========================================================================
    // INDEXING
    // =========================================================================

    private void indexJar(String jar) {
        try {
            // Scan is intentionally not closed: ClassInfo objects are read for the manager's lifetime.
            ScanResult scan = new ClassGraph()
                    .overrideClasspath(jar)
                    .enableMethodInfo()
                    .enableFieldInfo()
                    .scan();

            store(jar, scan);
            System.out.println("Indexed: " + Path.of(jar).getFileName()
                    + " → " + getTypesForJar(jar).size() + " types");
        } catch (Exception e) {
            System.err.println("Failed to index " + jar + ": " + e.getMessage());
        }
    }

    /** Records a scan and its non-anonymous classes under a jar path, then rebuilds the derived caches. */
    private void store(String jar, ScanResult scan) {
        scans.put(jar, scan);
        index.put(jar, scan.getAllClasses().stream()
                .filter(ci -> !ci.isAnonymousInnerClass())
                .toList());
        cachesDirty = true;
    }

    private synchronized void ensureCaches() {
        if (!cachesDirty) return;
        cachesDirty = false;
        // Only allowed-package classes are surfaced to the user; transitive deps and the SDK's internal
        // package stay indexed per-jar (for any future resolution use) but never reach the menus.
        allTypesCache = index.values().stream().flatMap(List::stream).filter(this::isAllowed).toList();
        bySimpleName.clear();
        byQualifiedName.clear();
        for (ClassInfo ci : allTypesCache) {
            bySimpleName.putIfAbsent(ci.getSimpleName(), ci); // first wins, matching old findFirst()
            byQualifiedName.putIfAbsent(ci.getName(), ci);
        }
        staticUtilityCache = allTypesCache.stream()
                .filter(ci -> ci.getMethodInfo().stream().anyMatch(m ->
                        m.isPublic() && m.isStatic()
                                && !m.getTypeSignatureOrTypeDescriptor().getResultType().toString().equals("void")))
                .toList();
    }

    // =========================================================================
    // SERIALIZATION — per-jar files (ClassGraph JSON)
    // =========================================================================

    /**
     * Derives a stable cache file path for a given jar.
     * e.g. ~/.cache/botmaker/jars/somelib-1.0.0.jar.json
     */
    public static Path getCacheFileForJar(String jarPath) {
        String jarFileName = Path.of(jarPath).getFileName().toString();
        return BotMakerDirs.getCacheDir().resolve("jars").resolve(jarFileName + ".json");
    }

    /**
     * Saves the indexed scan for a single jar to its dedicated .json file.
     */
    public void saveJar(String jarPath) {
        ScanResult scan = scans.get(jarPath);
        if (scan == null) return;

        Path cacheFile = getCacheFileForJar(jarPath);
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, scan.toJSON());
            System.out.println("Saved cache: " + cacheFile.getFileName()
                    + " (" + getTypesForJar(jarPath).size() + " types)");
        } catch (IOException e) {
            System.err.println("Failed to save cache for " + jarPath + ": " + e.getMessage());
        }
    }

    /**
     * Saves all currently indexed jars to their individual cache files.
     */
    public void saveAll() {
        for (String jarPath : index.keySet()) {
            saveJar(jarPath);
        }
    }

    /**
     * Loads classes from a single cache file by re-hydrating ClassGraph's JSON.
     * Returns null if the file is missing, corrupt, or incompatible.
     */
    private static ScanResult loadFromFile(Path cacheFile) {
        try {
            // ScanResult is intentionally not closed: it is read for the manager's lifetime.
            return ScanResult.fromJSON(Files.readString(cacheFile));
        } catch (Exception e) {
            System.err.println("Failed to load cache " + cacheFile.getFileName()
                    + ", will re-index: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deletes the cache file for a specific jar (forces re-indexing on next load).
     */
    public static void invalidateJar(String jarPath) {
        Path cacheFile = getCacheFileForJar(jarPath);
        try {
            Files.deleteIfExists(cacheFile);
            System.out.println("Invalidated cache for: " + Path.of(jarPath).getFileName());
        } catch (IOException e) {
            System.err.println("Failed to invalidate cache for " + jarPath + ": " + e.getMessage());
        }
    }

    /**
     * Deletes all per-jar cache files under the jars/ subdirectory.
     */
    public static void clearAllCaches() {
        Path jarsDir = BotMakerDirs.getCacheDir().resolve("jars");
        if (!Files.exists(jarsDir)) return;
        try (var stream = Files.walk(jarsDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) { /* best effort */ }
                    });
            System.out.println("Cleared all jar caches.");
        } catch (IOException e) {
            System.err.println("Failed to clear caches: " + e.getMessage());
        }
    }
}
