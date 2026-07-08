package com.botmaker.studio.config;

/**
 * The running application's version. In a packaged build this comes from the jar manifest's
 * {@code Implementation-Version} (injected by the shade plugin, see pom.xml); during development runs
 * (e.g. {@code mvn javafx:run}, no shaded jar) that is {@code null}, so we fall back to {@link #FALLBACK}.
 */
public final class AppVersion {

    /** Kept in sync with the pom {@code app.version} property; used only when no manifest version is present. */
    public static final String FALLBACK = "1.0.5";

    private AppVersion() {}

    /** The current version string, without any {@code v} prefix (e.g. {@code "1.0.0"}). */
    public static String get() {
        String fromManifest = AppVersion.class.getPackage().getImplementationVersion();
        return (fromManifest != null && !fromManifest.isBlank()) ? fromManifest : FALLBACK;
    }

    /**
     * True when running from source (e.g. {@code mvn javafx:run}) rather than a packaged build. The shade
     * plugin injects {@code Implementation-Version} into the shaded jar / jpackage app-image manifest, so its
     * absence uniquely identifies a dev run. Used to gate developer-only affordances (e.g. listing locally
     * dev-installed SDK snapshots) out of released builds.
     */
    public static boolean isDevBuild() {
        String fromManifest = AppVersion.class.getPackage().getImplementationVersion();
        return fromManifest == null || fromManifest.isBlank();
    }
}
