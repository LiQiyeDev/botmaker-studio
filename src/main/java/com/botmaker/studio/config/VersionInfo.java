package com.botmaker.studio.config;

import com.botmaker.studio.services.MavenService;

import java.nio.file.Path;

/**
 * Human-readable summary of which local builds are actually running — Studio, {@code shared}, and the open
 * project's SDK — for the About dialog and a startup banner. This is deliberately distinct from the GitHub
 * "check for updates" flow: it reports the versions <em>in use right now</em> (including dev/source runs and
 * locally dev-installed snapshots), not what the latest published release is.
 */
public final class VersionInfo {

    private VersionInfo() {}

    /** Studio's own version: {@code dev build (source run)} for an unpackaged run, else {@code vX.Y.Z}. */
    public static String studio() {
        return AppVersion.isDevBuild() ? "dev build (source run)" : "v" + AppVersion.get();
    }

    /**
     * The {@code shared} version Studio is running against (its only BotMaker dependency). Read from the
     * {@code shared} jar manifest's {@code Implementation-Version}; {@code null} in a reactor/dev-install build
     * (no manifest version) → reported as {@code 0.0.0-SNAPSHOT (local)}.
     */
    public static String shared() {
        String v = com.botmaker.shared.capture.NativeControllerFactory.class.getPackage().getImplementationVersion();
        return (v != null && !v.isBlank()) ? "v" + v : "0.0.0-SNAPSHOT (local)";
    }

    /**
     * The SDK version the open project's pom pins, flagged {@code (local build)} when it matches a locally
     * dev-installed snapshot ({@link MavenService#localSdkVersions()}). {@code null} projectDir → {@code —}.
     */
    public static String sdkForProject(Path projectDir) {
        if (projectDir == null) return "—";
        String v = MavenService.readSdkVersion(projectDir);
        return MavenService.localSdkVersions().contains(v) ? v + " (local build)" : v;
    }

    /** One-line banner for stdout. {@code projectDir} may be {@code null} when no project is open yet. */
    public static String banner(Path projectDir) {
        String line = "BotMaker Studio " + studio() + " | shared " + shared();
        if (projectDir != null) line += " | SDK(project) " + sdkForProject(projectDir);
        return line;
    }
}
