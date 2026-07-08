package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.UserLibrary;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Maven operations for generated user projects.
 *
 * <p>Replaces the old Gradle integration: the project descriptor (pom.xml) is built with the
 * Maven Model API (no hand-written build string), and dependencies are resolved transitively
 * in-process with Maven Resolver (Aether) — no system {@code mvn} binary is required.
 */
public final class MavenService {

    private MavenService() {}

    /** Default remote repositories used both in generated POMs and during resolution. */
    private static final Map<String, String> DEFAULT_REPOSITORIES = new LinkedHashMap<>();
    static {
        DEFAULT_REPOSITORIES.put("central", "https://repo.maven.apache.org/maven2/");
        DEFAULT_REPOSITORIES.put("jitpack", "https://jitpack.io");
        DEFAULT_REPOSITORIES.put("google", "https://dl.google.com/dl/android/maven2/");
    }

    /** Maven coordinate of the BotMaker SDK (published from GitHub tags via JitPack). */
    public static final String SDK_GROUP_ID = "com.github.LiQiyeDev";
    public static final String SDK_ARTIFACT_ID = "botmaker-sdk";
    /** Version used for the SDK when none is supplied / JitPack is unreachable. */
    public static final String SDK_FALLBACK_VERSION = "1.0.10";

    /**
     * Locally-installed SDK dev builds found in {@code ~/.m2} (e.g. {@code local-SNAPSHOT} produced by
     * {@code botmaker-sdk/dev-install.sh}), newest first. These never appear in JitPack's tag list, so the
     * version pickers surface them from here — a developer picks the local build instead of typing it. A bot
     * pinned to such a version resolves it from {@code ~/.m2} ahead of JitPack (see {@link #resolveClasspath}).
     *
     * <p>Best-effort: returns an empty list on any IO error or when nothing is installed (the common user case).
     */
    public static List<String> localSdkVersions() {
        Path sdkDir = Path.of(System.getProperty("user.home"), ".m2", "repository",
                SDK_GROUP_ID.replace('.', '/'), SDK_ARTIFACT_ID);
        if (!Files.isDirectory(sdkDir)) return List.of();
        try (var entries = Files.list(sdkDir)) {
            return entries
                    .filter(Files::isDirectory)
                    // Only dev builds (SNAPSHOTs), and only if the jar is actually present.
                    .filter(dir -> dir.getFileName().toString().contains("SNAPSHOT"))
                    .filter(dir -> Files.exists(dir.resolve(
                            SDK_ARTIFACT_ID + "-" + dir.getFileName() + ".jar")))
                    .sorted(Comparator.comparingLong(MavenService::lastModifiedMillis).reversed())
                    .map(dir -> dir.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long lastModifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Dependencies every generated project gets (mirrors the old build.gradle). */
    private record Dep(String groupId, String artifactId, String version, String scope) {}

    private static final List<Dep> DEFAULT_DEPENDENCIES = List.of(
            new Dep(SDK_GROUP_ID, SDK_ARTIFACT_ID, SDK_FALLBACK_VERSION, null),
            new Dep("net.java.dev.jna", "jna", "5.13.0", null),
            new Dep("net.java.dev.jna", "jna-platform", "5.13.0", null),
            new Dep("com.fasterxml.jackson.core", "jackson-databind", "2.15.2", null),
            new Dep("org.junit.jupiter", "junit-jupiter", "5.9.3", "test")
    );

    /** {@code groupId:artifactId} of the built-in dependencies — these are never treated as user libraries. */
    private static final Set<String> DEFAULT_GROUP_ARTIFACTS = DEFAULT_DEPENDENCIES.stream()
            .map(d -> d.groupId() + ":" + d.artifactId())
            .collect(Collectors.toUnmodifiableSet());

    private static boolean isDefaultDependency(Dependency d) {
        return DEFAULT_GROUP_ARTIFACTS.contains(d.getGroupId() + ":" + d.getArtifactId());
    }

    // =========================================================================
    // POM GENERATION (Maven Model API)
    // =========================================================================

    /**
     * Builds a {@code pom.xml} pinning the SDK to {@link #SDK_FALLBACK_VERSION}.
     *
     * @see #writePom(Path, ProjectConfig, String)
     */
    public static void writePom(Path projectDir, ProjectConfig cfg) throws IOException {
        writePom(projectDir, cfg, SDK_FALLBACK_VERSION);
    }

    /**
     * Builds a {@code pom.xml} for the given project using the Maven Model API and writes it to
     * {@code projectDir/pom.xml}. The model is assembled as an object graph and serialized with
     * {@link MavenXpp3Writer} — no XML string templating. The BotMaker SDK is pinned to {@code sdkVersion}
     * (blank → {@link #SDK_FALLBACK_VERSION}); all other defaults use their built-in versions.
     */
    public static void writePom(Path projectDir, ProjectConfig cfg, String sdkVersion) throws IOException {
        String resolvedSdkVersion = (sdkVersion == null || sdkVersion.isBlank())
                ? SDK_FALLBACK_VERSION : sdkVersion.trim();
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("com." + cfg.packageName());
        model.setArtifactId(cfg.projectName());
        model.setVersion("0.0.1-SNAPSHOT");
        model.setPackaging("jar");

        Properties props = new Properties();
        props.setProperty("maven.compiler.release", String.valueOf(Runtime.version().feature()));
        props.setProperty("project.build.sourceEncoding", "UTF-8");
        model.setProperties(props);

        DEFAULT_REPOSITORIES.forEach((id, url) -> {
            Repository repo = new Repository();
            repo.setId(id);
            repo.setUrl(url);
            model.addRepository(repo);
        });

        for (Dep d : DEFAULT_DEPENDENCIES) {
            Dependency dep = new Dependency();
            dep.setGroupId(d.groupId());
            dep.setArtifactId(d.artifactId());
            boolean isSdk = SDK_GROUP_ID.equals(d.groupId()) && SDK_ARTIFACT_ID.equals(d.artifactId());
            dep.setVersion(isSdk ? resolvedSdkVersion : d.version());
            if (d.scope() != null) dep.setScope(d.scope());
            model.addDependency(dep);
        }

        Files.createDirectories(projectDir);
        try (OutputStream out = Files.newOutputStream(projectDir.resolve("pom.xml"))) {
            new MavenXpp3Writer().write(out, model);
        }
    }

    // =========================================================================
    // DEPENDENCY RESOLUTION (Maven Resolver / Aether)
    // =========================================================================

    /**
     * Reads {@code projectDir/pom.xml} and resolves its (non-test) dependencies transitively,
     * returning the absolute paths of all resolved jars from the local {@code ~/.m2} repository.
     * Missing artifacts are downloaded from the POM's repositories (plus Maven Central).
     *
     * <p>Resolution is best-effort: if some artifacts fail, the ones that did resolve are still returned.
     */
    public static List<String> resolveClasspath(Path projectDir) {
        return resolveClasspath(projectDir, msg -> {});
    }

    /**
     * As {@link #resolveClasspath(Path)}, but reports per-artifact download progress via {@code progress}
     * (invoked with a short human-readable message, e.g. {@code "Downloading opencv-4.9.0.jar"}). The
     * consumer only fires for actual network transfers, so already-cached opens stay quiet. It may be
     * called from Aether's worker threads — callers that touch the UI must marshal onto the FX thread.
     */
    public static List<String> resolveClasspath(Path projectDir, Consumer<String> progress) {
        Path pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            System.err.println("No pom.xml found at " + pomPath);
            return List.of();
        }

        Model model;
        try (InputStream in = Files.newInputStream(pomPath)) {
            model = new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            System.err.println("Failed to read pom.xml: " + e.getMessage());
            return List.of();
        }

        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));
        // Expose the JVM's system properties (notably java.version) to the model builder so POMs whose
        // effective model depends on JDK-activated profiles resolve correctly. Without this, bytedeco's
        // javacpp-presets parent fails ("Failed to determine Java version for profile doclint-java8-disable"),
        // the descriptor read is silently ignored, and the whole opencv subtree — including the opencv main
        // jar that carries org.opencv.core.Mat — is dropped from the bot's runtime classpath.
        session.setSystemProperties(System.getProperties());
        session.setTransferListener(new AbstractTransferListener() {
            @Override
            public void transferInitiated(TransferEvent event) {
                progress.accept("Downloading " + fileName(event.getResource()));
            }
        });

        List<RemoteRepository> remoteRepos = buildRemoteRepositories(model);

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRepositories(remoteRepos);
        for (Dependency d : model.getDependencies()) {
            if ("test".equals(d.getScope())) continue;
            if ("sources".equals(d.getClassifier())) continue;
            String classifier = d.getClassifier() == null ? "" : d.getClassifier();
            Artifact artifact = new DefaultArtifact(
                    d.getGroupId(), d.getArtifactId(), classifier, "jar", d.getVersion());
            String scope = d.getScope() == null ? "compile" : d.getScope();
            collectRequest.addDependency(new org.eclipse.aether.graph.Dependency(artifact, scope));
        }

        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
        List<String> jars = new ArrayList<>();
        try {
            DependencyResult result = system.resolveDependencies(session, dependencyRequest);
            collectJars(result.getArtifactResults(), jars);
        } catch (DependencyResolutionException e) {
            System.err.println("Some dependencies failed to resolve: " + e.getMessage());
            if (e.getResult() != null) {
                collectJars(e.getResult().getArtifactResults(), jars);
            }
        }
        return jars;
    }

    /** The trailing file name of a transfer resource (e.g. {@code opencv-4.9.0.jar}), for progress text. */
    private static String fileName(TransferResource resource) {
        String name = resource.getResourceName();
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static void collectJars(List<ArtifactResult> results, List<String> out) {
        if (results == null) return;
        for (ArtifactResult ar : results) {
            if (ar.getArtifact() != null && ar.getArtifact().getFile() != null) {
                out.add(ar.getArtifact().getFile().getAbsolutePath());
            }
        }
    }

    private static List<RemoteRepository> buildRemoteRepositories(Model model) {
        Map<String, String> repos = new LinkedHashMap<>(DEFAULT_REPOSITORIES);
        for (Repository r : model.getRepositories()) {
            if (r.getUrl() != null) repos.put(r.getId(), r.getUrl());
        }
        List<RemoteRepository> result = new ArrayList<>();
        repos.forEach((id, url) ->
                result.add(new RemoteRepository.Builder(id, "default", url).build()));
        return result;
    }

    // =========================================================================
    // USER LIBRARIES (pom.xml is the source of truth)
    // =========================================================================

    /**
     * Reads the user-added libraries from {@code projectDir/pom.xml}: every dependency that is not one
     * of the built-in {@link #DEFAULT_DEPENDENCIES}. Returns an empty list if the pom is missing or
     * unreadable.
     */
    public static List<UserLibrary> readUserLibraries(Path projectDir) {
        Model model = readModel(projectDir);
        if (model == null) return List.of();
        return model.getDependencies().stream()
                .filter(d -> !isDefaultDependency(d))
                .map(d -> new UserLibrary(d.getGroupId(), d.getArtifactId(), d.getVersion()))
                .collect(Collectors.toList());
    }

    /**
     * Reads the BotMaker SDK version currently declared in {@code projectDir/pom.xml}, or
     * {@link #SDK_FALLBACK_VERSION} if the pom is missing or does not declare the SDK.
     */
    public static String readSdkVersion(Path projectDir) {
        Model model = readModel(projectDir);
        if (model == null) return SDK_FALLBACK_VERSION;
        return model.getDependencies().stream()
                .filter(d -> SDK_GROUP_ID.equals(d.getGroupId()) && SDK_ARTIFACT_ID.equals(d.getArtifactId()))
                .map(Dependency::getVersion)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(SDK_FALLBACK_VERSION);
    }

    /**
     * Resolves the {@code sources} classifier jar for the BotMaker SDK version declared in the project's
     * pom (downloading from JitPack if not already cached in {@code ~/.m2}), returning its local path.
     * The Studio does not compile against the SDK, but the sources jar carries the API Javadoc that
     * {@code index/SdkDocsParser} reads to describe methods/parameters (see {@code services/SdkDocsService}).
     * Best-effort: returns empty when the pom is missing, the artifact can't be resolved, or offline.
     * May block on the network — call off the FX thread.
     */
    public static Optional<Path> resolveSdkSourcesJar(Path projectDir) {
        Model model = readModel(projectDir);
        if (model == null) {
            return Optional.empty();
        }
        Artifact artifact = new DefaultArtifact(
                SDK_GROUP_ID, SDK_ARTIFACT_ID, "sources", "jar", readSdkVersion(projectDir));

        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));
        session.setSystemProperties(System.getProperties());

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(buildRemoteRepositories(model));
        try {
            ArtifactResult result = system.resolveArtifact(session, request);
            var file = result.getArtifact().getFile();
            return file != null ? Optional.of(file.toPath()) : Optional.empty();
        } catch (ArtifactResolutionException e) {
            System.err.println("Could not resolve SDK sources jar: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Replaces the user-added libraries with {@code libs}, leaving the SDK version unchanged.
     *
     * @see #writeUserLibraries(Path, List, String)
     */
    public static void writeUserLibraries(Path projectDir, List<UserLibrary> libs) throws IOException {
        writeUserLibraries(projectDir, libs, readSdkVersion(projectDir));
    }

    /**
     * Replaces the user-added libraries in {@code projectDir/pom.xml} with {@code libs} and pins the
     * BotMaker SDK to {@code sdkVersion}, leaving the other built-in dependencies, repositories and
     * properties untouched. The pom is read, mutated and written back in place (no regeneration).
     */
    public static void writeUserLibraries(Path projectDir, List<UserLibrary> libs, String sdkVersion)
            throws IOException {
        Model model = readModel(projectDir);
        if (model == null) {
            throw new IOException("No pom.xml found at " + projectDir.resolve("pom.xml"));
        }

        // Keep the built-in deps, drop the previous user deps, then append the new ones.
        List<Dependency> kept = model.getDependencies().stream()
                .filter(MavenService::isDefaultDependency)
                .collect(Collectors.toList());
        for (UserLibrary lib : libs) {
            Dependency dep = new Dependency();
            dep.setGroupId(lib.groupId());
            dep.setArtifactId(lib.artifactId());
            dep.setVersion(lib.version());
            kept.add(dep);
        }
        model.setDependencies(kept);

        if (sdkVersion != null && !sdkVersion.isBlank()) {
            setManagedDependencyVersion(model, SDK_GROUP_ID, SDK_ARTIFACT_ID, sdkVersion.trim());
        }

        try (OutputStream out = Files.newOutputStream(projectDir.resolve("pom.xml"))) {
            new MavenXpp3Writer().write(out, model);
        }
    }

    /** Sets the version of the matching dependency already present in the model (no-op if absent). */
    private static void setManagedDependencyVersion(Model model, String groupId, String artifactId,
                                                    String version) {
        for (Dependency d : model.getDependencies()) {
            if (groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId())) {
                d.setVersion(version);
            }
        }
    }

    private static Model readModel(Path projectDir) {
        Path pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) return null;
        try (InputStream in = Files.newInputStream(pomPath)) {
            return new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            System.err.println("Failed to read pom.xml: " + e.getMessage());
            return null;
        }
    }
}
