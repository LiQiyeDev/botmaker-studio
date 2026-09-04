package com.botmaker.studio.services;

import com.botmaker.studio.config.AppVersion;
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
import org.eclipse.aether.repository.RepositoryPolicy;
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
import java.io.StringWriter;
import java.io.UncheckedIOException;
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

    // The pom is Studio's again (2026-08-26), one day after phase 3 moved it to the SDK. The reversal is
    // recorded rather than tidied away because the argument that moved it was not wrong, only incomplete:
    // the pom is not a file *about* the SDK, it is the file that *declares which* SDK — and, once there is
    // ever a second plugin, which plugins. The SDK is the editor's default plugin, not the editor; a plugin
    // cannot enumerate its siblings, so a pom it wrote would silently omit theirs. Only the composer can
    // write the manifest of what it composed. Creation still lands in one pass: ProjectCreator hands this
    // text to Authoring.createProject as a caller file.

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
    /**
     * Version used for the SDK when none is supplied / JitPack is unreachable.
     *
     * <p><b>Hand-typed, and it must stay that way.</b> {@code release.sh} bumps it with a {@code sed} over
     * this string literal on every {@code --sdk} release; deriving it from {@link SdkVersion#latest()} would
     * make that bump silently stop working. What a <em>freshly created</em> pom pins is also a separate
     * question from what this build of the SDK is.
     */
    public static final String SDK_FALLBACK_VERSION = "1.1.3";

    // There is no MIN_SDK_VERSION any more, and its absence is deliberate (2026-08-25).
    //
    // The floor was a statement about generation: below it Studio could not render a generated file, because
    // the templates and the FlowGraph/Wire API they call arrived in 1.1.0. Studio does not generate at all
    // now — the templates left the SDK and its own emitters are not written yet (inversion phase 2) — so a
    // floor would be a comparison with nothing behind it: a banner and a refusal about a capability neither
    // side has. Every pinned SDK therefore opens with no warning, and an incompatibility surfaces where it
    // always could, at compile time, naming the element.
    //
    // Do not reinstate it as a palette floor either. The palette question is answered per element by
    // SdkSurfaceService against the project's own jar, which is strictly better than a version comparison.
    // release.sh's check_sdk_floor went with it.

    /**
     * Locally-installed SDK dev builds found in {@code ~/.m2} (typically {@code 0.0.0-SNAPSHOT}, produced by
     * {@code mvn -pl botmaker-sdk -am install} from the umbrella root), newest first. These never appear in
     * JitPack's tag list, so the version pickers surface them from here — a developer picks the local build
     * instead of typing it. A bot pinned to such a version resolves it from {@code ~/.m2} ahead of JitPack
     * (see {@link #resolveClasspath}).
     *
     * <p>Best-effort: returns an empty list on any IO error or when nothing is installed (the common user case).
     */
    public static List<String> localSdkVersions() {
        // Developer-only affordance: never surface local snapshots in a packaged/released build (a
        // maintainer running the shipped app would otherwise see their own ~/.m2 dev builds).
        if (!AppVersion.isDevBuild()) return List.of();
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
                    // One local build is enough — the newest wins (a plain `mvn install` writes
                    // 0.0.0-SNAPSHOT); this also hides a stale leftover like an old local-SNAPSHOT.
                    .limit(1)
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

    /** A plugin built into {@code ~/.m2} and not published anywhere — what a plugin author is working on. */
    public record LocalPluginBuild(String groupId, String artifactId, String version) {

        /** {@code group:artifact}, which is how a registry entry and a pom row both name a dependency. */
        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    /**
     * The plugins installed as dev builds in {@code ~/.m2}, newest first.
     *
     * <p>The counterpart of {@link #localSdkVersions()} for the <em>other</em> kind of local build: a plugin
     * author runs {@code mvn install} and wants Studio to offer what they just built, without publishing it,
     * tagging it or hand-editing a pom.
     *
     * <p><b>What makes a jar a plugin is the service file, and nothing else asks.</b> A candidate is
     * accepted when its jar carries {@code META-INF/services/com.botmaker.plugin.api.StudioPlugin} — the
     * very entry {@code ServiceLoader} reads — so this needs no registry, no naming convention and no list
     * to keep in step with anything. A local build of the SDK is therefore listed too, correctly: the SDK
     * <em>is</em> a plugin, and its version is Manage Libraries' business rather than this scan's.
     *
     * <p>Only {@code *SNAPSHOT} versions are considered, for the same reason {@link #localSdkVersions()}
     * considers only those: a released version in {@code ~/.m2} is simply a download, not something somebody
     * is working on. Best-effort throughout — an unreadable jar or directory yields fewer rows, never an
     * exception.
     */
    public static List<LocalPluginBuild> localPluginBuilds() {
        // Developer-only affordance, exactly like localSdkVersions(): a packaged Studio must never surface
        // whatever happens to be in the user's own ~/.m2.
        if (!AppVersion.isDevBuild()) return List.of();
        return localPluginBuilds(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    /** The scan itself, with the repository root given, so a test can point it at a tree it built. */
    static List<LocalPluginBuild> localPluginBuilds(Path repositoryRoot) {
        if (!Files.isDirectory(repositoryRoot)) return List.of();
        List<Path> versionDirs = new ArrayList<>();
        collectSnapshotDirs(repositoryRoot, versionDirs, 0);
        List<LocalPluginBuild> found = new ArrayList<>();
        versionDirs.sort(Comparator.comparingLong(MavenService::lastModifiedMillis).reversed());
        for (Path versionDir : versionDirs) {
            String version = versionDir.getFileName().toString();
            Path artifactDir = versionDir.getParent();
            if (artifactDir == null || artifactDir.getParent() == null) continue;
            String artifactId = artifactDir.getFileName().toString();
            Path jar = versionDir.resolve(artifactId + "-" + version + ".jar");
            if (!Files.isRegularFile(jar) || !declaresPlugin(jar)) continue;
            String groupId = repositoryRoot.relativize(artifactDir.getParent()).toString()
                    .replace('\\', '/').replace('/', '.');
            if (groupId.isBlank()) continue;
            found.add(new LocalPluginBuild(groupId, artifactId, version));
        }
        return List.copyOf(found);
    }

    /**
     * Every {@code *SNAPSHOT} directory under {@code dir}, without listing the files in any other.
     *
     * <p>A local repository is tens of thousands of files and a handful of snapshots, so this walks
     * directories only and stops descending the moment it finds one — a snapshot directory holds an
     * artifact's files, never another artifact.
     */
    private static void collectSnapshotDirs(Path dir, List<Path> out, int depth) {
        // A Maven coordinate is deep but not unbounded; the cap is what stops a symlink loop rather than a
        // real repository, which never approaches it.
        if (depth > 12) return;
        try (var children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : children) {
                if (child.getFileName().toString().contains("SNAPSHOT")) {
                    out.add(child);
                } else {
                    collectSnapshotDirs(child, out, depth + 1);
                }
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable directory is one fewer candidate, never a failed scan.
        }
    }

    /** Whether {@code jar} declares a {@code StudioPlugin} the way {@code ServiceLoader} finds one. */
    private static boolean declaresPlugin(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.getEntry("META-INF/services/com.botmaker.plugin.api.StudioPlugin") != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Dependencies every generated project gets (mirrors the old build.gradle). */
    private record Dep(String groupId, String artifactId, String version, String scope) {}

    /**
     * The version of {@code botmaker-plugin-toolkit} a generated pom declares.
     *
     * <p>Separate from {@link #SDK_FALLBACK_VERSION} and bumped by the same {@code release.sh} mechanism —
     * a {@code sed} on this string literal — because the toolkit versions on its own schedule. It is
     * deliberately <b>not</b> derived from anything: a computed value would make that sed silently stop
     * working, which is the same reason the SDK fallback is a literal.
     */
    public static final String TOOLKIT_FALLBACK_VERSION = "0.0.3";

    /**
     * The whole of a <b>blank</b> project's dependencies: a test framework, and nothing else.
     *
     * <p><b>A blank project names no plugin, and therefore has no bot API</b> (2026-09-04). Until then every
     * project Studio created pinned {@code botmaker-sdk} plus the eight entries below, so "blank" meant a bot
     * project with a {@code System.out.println} in it — and a user who wanted a plain Java project could not
     * have one. The platform's own rule is that the SDK is one plugin among any number; a starting point that
     * names it is a starting point that has chosen one, which is a choice belonging to the person starting.
     *
     * <p>So a blank project opens with <b>no palette, no plugin toolbar buttons, no pictures, no capture and
     * no pilot</b>. That is not a degraded state to be minimised — it is what a project with no plugins
     * installed looks like, and it is reachable in one step from **Project ▸ Manage Plugins…**, which
     * installs the SDK as an ordinary dependency exactly as it installs anybody else's plugin. The richer
     * starting point is a published bot carrying the {@code template} tag; see {@code TemplateProject}.
     *
     * <p>JUnit is here rather than in {@link #BOT_DEPENDENCIES} because it is the one entry that is not about
     * BotMaker at all: it is what a Maven project is expected to come with, and a user who writes a test on
     * day one should not have to add it. {@link #DEFAULT_REPOSITORIES} is unchanged for both shapes, so a
     * blank project can <em>add</em> a BotMaker library later without anybody hand-editing a repository in.
     */
    private static final List<Dep> BLANK_DEPENDENCIES = List.of(
            new Dep("org.junit.jupiter", "junit-jupiter", "5.9.3", "test")
    );

    /**
     * What a pom that names the SDK plugin has to carry — <b>the list, kept, for a project that wants one</b>.
     *
     * <p>Nothing Studio creates writes this any more: a blank project takes {@link #BLANK_DEPENDENCIES} and a
     * project made from a gallery template brings its author's own pom, versions and all. It stays because it
     * is the written statement of the paragraph below, which a template author needs and which is not
     * derivable from anywhere else, and because {@link #DEFAULT_GROUP_ARTIFACTS} must go on recognising every
     * one of these in the pom of a project that already exists.
     *
     * <h2>Why five of them are {@code provided}, and what breaks without them</h2>
     *
     * <p>Since 2026-09-02 <b>Studio bundles no plugin</b>: every plugin, the SDK included, is loaded by
     * {@code PluginHost.bind} from the classpath this project's own pom resolves. That moved a cost nobody
     * had priced. The SDK is a library <em>and</em> a plugin in one jar, and its plugin half needs a widget
     * kit, JavaFX, a web server and a QR encoder — all of which the SDK's pom marks {@code optional} so a
     * headless bot never links them. <b>{@code optional} is not transitive</b>, so with Studio no longer
     * supplying them from its own classpath they reached nothing at all: {@code SdkPlugin} could not
     * resolve its own superclass, and the pilot's toolbar button failed with a missing class.
     *
     * <p>{@code provided} is the scope that says exactly what is true here — <i>present while the code is
     * being edited, absent when the bot runs</i>. {@link #resolveClasspath} filters out only {@code test},
     * so these are on the classpath Studio binds plugins from; {@code mvn package} and the run/debug
     * launchers exclude {@code provided} by definition, so the bot's own runtime is unchanged and the
     * headless case the {@code optional} flags protect is still protected.
     *
     * <p><b>The generalisation, for the second plugin:</b> a plugin's dependencies are declared by whoever
     * puts that plugin on a classpath. Here that is this pom, because this pom is what names the plugin.
     * A plugin generated by {@code botmaker-plugin-archetype} declares its own toolkit at {@code compile}
     * scope and needs nothing from this list — it is the SDK's double life as library-and-plugin that makes
     * it the exception.
     */
    private static final List<Dep> BOT_DEPENDENCIES = List.of(
            new Dep(SDK_GROUP_ID, SDK_ARTIFACT_ID, SDK_FALLBACK_VERSION, null),
            new Dep("net.java.dev.jna", "jna", "5.13.0", null),
            new Dep("net.java.dev.jna", "jna-platform", "5.13.0", null),
            // Jackson stays even though nothing generated needs it any more: a java-model project's settings
            // are Java literals, not a JSON read at startup. It is on the list for what the user might write,
            // and taking it away would break a bot that imports it for a gain nobody would notice.
            new Dep("com.fasterxml.jackson.core", "jackson-databind", "2.15.2", null),

            // ---- the SDK plugin's own needs; see the javadoc above ------------------------------------
            // SdkPlugin extends the toolkit's AbstractStudioPlugin, and its slot editors are Editors/Pills.
            new Dep("com.github.LiQiyeDev", "botmaker-plugin-toolkit", TOOLKIT_FALLBACK_VERSION, "provided"),
            // The slot editors and every dialog the plugin's toolbar items open are JavaFX. The two
            // artifacts move together — javafx-controls is compiled against one exact javafx-graphics.
            new Dep("org.openjfx", "javafx-controls", "21", "provided"),
            new Dep("org.openjfx", "javafx-graphics", "21", "provided"),
            // The Remote Pilot's HTTP + WebSocket server.
            new Dep("io.javalin", "javalin", "6.7.0", "provided"),
            // The pairing QR code. `core` only: the BitMatrix is written straight into a JavaFX
            // WritableImage, so no zxing-javase and no AWT image path.
            new Dep("com.google.zxing", "core", "3.5.3", "provided"),

            new Dep("org.junit.jupiter", "junit-jupiter", "5.9.3", "test")
    );

    /**
     * {@code groupId:artifactId} of every built-in dependency — these are never treated as user libraries.
     *
     * <p>A "user library" is anything in the pom that is not on this list, so this set and the pom writers
     * must stay one list or the dialog offers to delete a dependency it cannot re-add. That is an argument
     * for one owner, and the owner is whoever writes the pom — Studio, again.
     *
     * <p><b>It is the union of both lists, and narrowing it to the blank one would be a data-loss bug.</b>
     * This classifies the dependencies of <em>any</em> project's pom, not only of one Studio wrote today: a
     * bot created before 2026-09-04, and every project unpacked from a gallery template, carries the
     * {@link #BOT_DEPENDENCIES} set. Left out of here, the SDK, the toolkit and JavaFX would read as user
     * libraries — offered for deletion in Manage Libraries, and then genuinely dropped by
     * {@link #writeUserLibraries}, which keeps what {@link #isDefaultDependency} recognises and discards the
     * rest.
     */
    private static final Set<String> DEFAULT_GROUP_ARTIFACTS =
            java.util.stream.Stream.concat(BLANK_DEPENDENCIES.stream(), BOT_DEPENDENCIES.stream())
                    .map(d -> d.groupId() + ":" + d.artifactId())
                    .collect(Collectors.toUnmodifiableSet());

    private static boolean isDefaultDependency(Dependency d) {
        return DEFAULT_GROUP_ARTIFACTS.contains(d.getGroupId() + ":" + d.getArtifactId());
    }

    // =========================================================================
    // POM GENERATION (Maven Model API)
    // =========================================================================

    /**
     * A <b>blank</b> project's {@code pom.xml} as text — {@link #BLANK_DEPENDENCIES}, and no plugin named.
     *
     * <p>This is what project creation writes. See {@link #botPomXml} for the shape a project that wants the
     * SDK carries, and {@link #BLANK_DEPENDENCIES} for why creation no longer writes that one.
     */
    public static String blankPomXml(ProjectConfig cfg) {
        return pomXml(cfg, null);
    }

    /**
     * A project's {@code pom.xml} as text, <b>naming the SDK plugin</b> and everything its plugin half needs
     * — {@link #BOT_DEPENDENCIES}, with the SDK pinned to {@code sdkVersion} (blank →
     * {@link #SDK_FALLBACK_VERSION}).
     *
     * <p>Nothing creates a project this way since 2026-09-04. It is what {@code ProjectRepair} rebuilds a
     * missing pom as for a project that already had one, and what a test builds a bot-shaped fixture with.
     */
    public static String botPomXml(ProjectConfig cfg, String sdkVersion) {
        return pomXml(cfg, sdkVersion == null || sdkVersion.isBlank()
                ? SDK_FALLBACK_VERSION : sdkVersion.trim());
    }

    /**
     * Builds a {@code pom.xml} for the given project using the Maven Model API and returns it as text. The
     * model is assembled as an object graph and serialized with {@link MavenXpp3Writer} — no XML string
     * templating, so a project name with an {@code &} in it cannot produce a pom that does not parse.
     *
     * <p><b>{@code sdkVersion} is {@code null} for a blank project</b>, which is the one thing this method
     * branches on: it selects {@link #BLANK_DEPENDENCIES} over {@link #BOT_DEPENDENCIES}. Null rather than
     * blank, because blank already means "pin the fallback" to every caller that ever passed a user's typed
     * version through, and a project with no SDK at all is a different statement from one whose version was
     * left empty. Both public spellings above say which they mean in their names.
     *
     * <p>Text rather than a write, because project <em>creation</em> does not write this file itself: it
     * hands the text to {@code Authoring.createProject}, which commits it in the same all-or-none pass as
     * the files the SDK owns. Composing it here and committing it there is what keeps both halves — Studio
     * knows the whole dependency set (the SDK is only one plugin), and a failed creation still leaves
     * nothing behind.
     */
    private static String pomXml(ProjectConfig cfg, String resolvedSdkVersion) {
        List<Dep> dependencies = resolvedSdkVersion == null ? BLANK_DEPENDENCIES : BOT_DEPENDENCIES;
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

        for (Dep d : dependencies) {
            Dependency dep = new Dependency();
            dep.setGroupId(d.groupId());
            dep.setArtifactId(d.artifactId());
            boolean isSdk = SDK_GROUP_ID.equals(d.groupId()) && SDK_ARTIFACT_ID.equals(d.artifactId());
            dep.setVersion(isSdk ? resolvedSdkVersion : d.version());
            if (d.scope() != null) dep.setScope(d.scope());
            model.addDependency(dep);
        }

        StringWriter out = new StringWriter();
        try {
            new MavenXpp3Writer().write(out, model);
        } catch (IOException impossible) {
            // A StringWriter does not fail. Wrapping rather than declaring keeps every caller's throws
            // clause about the filesystem, which is the only IO any of them can do anything about.
            throw new UncheckedIOException(impossible);
        }
        return out.toString();
    }

    /**
     * Writes a <b>bot-shaped</b> {@code pom.xml} — {@link #botPomXml} — to {@code projectDir/pom.xml}.
     *
     * <p>This is the <em>repair</em> path — restoring a build file somebody deleted out of an otherwise
     * intact project, where re-creating the project would (correctly) refuse. Creation goes through
     * {@link #blankPomXml} instead, so that its write is part of one atomic commit.
     *
     * <p><b>The caller chooses the shape, and must.</b> A repair that always wrote this one would hand an
     * SDK to a blank project that never had one — see {@link #writeBlankPom} and {@code ProjectRepair},
     * which reads {@code settings.json}'s recorded template to decide.
     */
    public static void writePom(Path projectDir, ProjectConfig cfg, String sdkVersion) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), botPomXml(cfg, sdkVersion));
    }

    /** Writes a <b>blank</b> {@code pom.xml} — {@link #blankPomXml} — to {@code projectDir/pom.xml}. */
    public static void writeBlankPom(Path projectDir, ProjectConfig cfg) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), blankPomXml(cfg));
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
        return resolveClasspath(projectDir, ProgressReporter.NONE);
    }

    /**
     * As {@link #resolveClasspath(Path)}, but reports download progress via {@code progress}: a real
     * fraction (aggregated across all concurrent transfers by bytes) plus a short message, e.g.
     * {@code "Downloading opencv-4.9.0.jar"}. It only fires for actual network transfers, so already-cached
     * opens stay quiet. It may be called from Aether's worker threads — callers that touch the UI must
     * marshal onto the FX thread.
     */
    public static List<String> resolveClasspath(Path projectDir, ProgressReporter progress) {
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
        DownloadAggregator downloads = new DownloadAggregator();
        session.setTransferListener(new AbstractTransferListener() {
            @Override
            public void transferInitiated(TransferEvent event) {
                progress.report(downloads.fraction(), "Downloading " + fileName(event.getResource()));
            }
            @Override
            public void transferProgressed(TransferEvent event) {
                downloads.progressed(event.getResource(), event.getTransferredBytes());
                progress.report(downloads.fraction(), "Downloading " + fileName(event.getResource()));
            }
            @Override
            public void transferSucceeded(TransferEvent event) {
                downloads.finished(event.getResource(), event.getTransferredBytes());
                progress.report(downloads.fraction(), "Downloaded " + fileName(event.getResource()));
            }
            @Override
            public void transferFailed(TransferEvent event) {
                downloads.finished(event.getResource(), event.getTransferredBytes());
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

    /**
     * Aggregates bytes across all concurrent Aether transfers into a single overall fraction. New artifacts
     * are discovered mid-resolve, so the denominator grows as downloads start — the fraction is honest (real
     * bytes) but may briefly step back when a new large jar appears. Thread-safe: Aether fires transfer
     * callbacks from worker threads.
     */
    private static final class DownloadAggregator {
        /** resource identity → {transferredBytes, contentLength (-1 if unknown)} for in-flight transfers. */
        private final Map<TransferResource, long[]> active = new java.util.IdentityHashMap<>();
        private long completedBytes = 0;

        synchronized void progressed(TransferResource resource, long transferred) {
            active.put(resource, new long[]{transferred, resource.getContentLength()});
        }

        synchronized void finished(TransferResource resource, long transferred) {
            long[] prev = active.remove(resource);
            long bytes = transferred > 0 ? transferred : (prev != null ? prev[0] : 0);
            completedBytes += Math.max(0, bytes);
        }

        /** Overall completed-fraction in [0,1], or -1 when nothing with a known size is in flight yet. */
        synchronized double fraction() {
            long transferred = completedBytes;
            long total = completedBytes;
            for (long[] v : active.values()) {
                transferred += v[0];
                total += Math.max(v[1], v[0]); // unknown length → count its own transferred bytes as the total
            }
            return total > 0 ? Math.min(1.0, (double) transferred / total) : -1;
        }
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
        // Disable snapshot fetching on every remote: in this project SNAPSHOT coordinates are always
        // local-only dev builds (botmaker-sdk / botmaker-shared at 0.0.0-SNAPSHOT, installed to ~/.m2 by
        // the umbrella reactor). Letting a remote (notably jitpack) answer for a SNAPSHOT could shadow the
        // freshly reinstalled local jar. Releases are non-SNAPSHOT, so user libraries are unaffected.
        RepositoryPolicy noSnapshots = new RepositoryPolicy(
                false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_WARN);
        List<RemoteRepository> result = new ArrayList<>();
        repos.forEach((id, url) ->
                result.add(new RemoteRepository.Builder(id, "default", url)
                        .setSnapshotPolicy(noSnapshots)
                        .build()));
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
     * The BotMaker SDK version {@code projectDir/pom.xml} declares — <b>empty when it declares none</b>.
     *
     * <p>It answered {@link #SDK_FALLBACK_VERSION} for a pom naming no SDK until 2026-09-04, which was
     * harmless while every pom Studio wrote named one and became a lie the moment a blank project could
     * exist. The readers of this answer resolve jars with it, offer upgrades against it and print it in an
     * about box; every one of them would have been describing a dependency the project does not have.
     *
     * <p><b>Empty is not an error.</b> A project with no SDK is an ordinary project — it is what New Project
     * now creates — so each caller degrades rather than refusing: no docs, no surface index, no upgrade
     * offered, no version reported.
     */
    public static Optional<String> readSdkVersion(Path projectDir) {
        Model model = readModel(projectDir);
        if (model == null) return Optional.empty();
        return model.getDependencies().stream()
                .filter(d -> SDK_GROUP_ID.equals(d.getGroupId()) && SDK_ARTIFACT_ID.equals(d.getArtifactId()))
                .map(Dependency::getVersion)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /**
     * Resolves the {@code sources} classifier jar for the BotMaker SDK version declared in the project's
     * pom (downloading from JitPack if not already cached in {@code ~/.m2}), returning its local path.
     * The Studio does not compile against the SDK, but the sources jar carries the API Javadoc that
     * {@code index/SdkDocsParser} reads to describe methods/parameters (see {@code services/SdkDocsService}).
     * Best-effort: returns empty when the pom is missing, <b>declares no SDK</b>, the artifact can't be
     * resolved, or offline. May block on the network — call off the FX thread.
     */
    public static Optional<Path> resolveSdkSourcesJar(Path projectDir) {
        return resolveSdkArtifact(projectDir, readSdkVersion(projectDir).orElse(""), "sources");
    }

    /**
     * Resolves the SDK's own (classifier-less) jar for an <em>arbitrary</em> version — not necessarily the
     * one this project pins. That is what {@link SdkUpgradeService} compares against: answering "what breaks
     * if I move to v2.0.0" means reading v2.0.0's bytecode, which nothing else in Studio ever needs.
     *
     * <p>The project's own pom is still consulted, for its {@code <repositories>} — JitPack is declared
     * there, so a version that has never been resolved on this machine downloads on demand.
     */
    public static Optional<Path> resolveSdkJar(Path projectDir, String version) {
        return resolveSdkArtifact(projectDir, version, "");
    }

    /**
     * The same, for a caller that has <b>no project</b> — {@code ProjectCreator}, which must know what the
     * chosen SDK contains <em>before</em> a pom exists to read repositories from.
     *
     * <p>Resolving against an empty model is not a compromise here: {@link #buildRemoteRepositories} starts
     * from {@link #DEFAULT_REPOSITORIES}, and those are exactly the repositories {@link #blankPomXml} is about to
     * declare. The only thing a project pom adds is a repository the <em>user</em> put there, which by
     * definition a project that does not exist yet has none of.
     */
    public static Optional<Path> resolveSdkJar(String version) {
        return resolveSdkArtifact(new Model(), version, "");
    }

    /**
     * Resolves one BotMaker SDK artifact from the local repo, downloading it via the project pom's
     * repositories if absent. {@code classifier} is {@code ""} for the jar itself, {@code "sources"} for the
     * sources jar. Best-effort: empty when the pom is missing, the artifact cannot be resolved, or offline.
     * May block on the network — call off the FX thread.
     */
    public static Optional<Path> resolveSdkArtifact(Path projectDir, String version, String classifier) {
        Model model = readModel(projectDir);
        if (model == null) return Optional.empty();
        return resolveSdkArtifact(model, version, classifier);
    }

    private static Optional<Path> resolveSdkArtifact(Model model, String version, String classifier) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        Artifact artifact = new DefaultArtifact(
                SDK_GROUP_ID, SDK_ARTIFACT_ID, classifier, "jar", version.trim());

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
            System.err.println("Could not resolve SDK artifact " + artifact + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Replaces the user-added libraries with {@code libs}, leaving the SDK version unchanged — and adding
     * none to a project that declares no SDK.
     *
     * @see #writeUserLibraries(Path, List, String)
     */
    public static void writeUserLibraries(Path projectDir, List<UserLibrary> libs) throws IOException {
        writeUserLibraries(projectDir, libs, readSdkVersion(projectDir).orElse(""));
    }

    /**
     * Replaces the user-added libraries in {@code projectDir/pom.xml} with {@code libs} and pins the
     * BotMaker SDK to {@code sdkVersion}, leaving the other built-in dependencies, repositories and
     * properties untouched. The pom is read, mutated and written back in place (no regeneration).
     *
     * <p><b>A blank {@code sdkVersion} pins nothing, and does not add the SDK.</b>
     * {@link #setManagedDependencyVersion} only ever edits a dependency that is already declared, so a blank
     * project keeps naming no plugin however often its libraries are edited — which is what makes
     * {@code LibraryService} usable on one at all.
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
