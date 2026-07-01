package com.botmaker.config;

/**
 * The running application's version. In a packaged build this comes from the jar manifest's
 * {@code Implementation-Version} (injected by the shade plugin, see pom.xml); during development runs
 * (e.g. {@code mvn javafx:run}, no shaded jar) that is {@code null}, so we fall back to {@link #FALLBACK}.
 */
public final class AppVersion {

    /** Kept in sync with the pom {@code app.version} property; used only when no manifest version is present. */
    public static final String FALLBACK = "1.0.0";

    private AppVersion() {}

    /** The current version string, without any {@code v} prefix (e.g. {@code "1.0.0"}). */
    public static String get() {
        String fromManifest = AppVersion.class.getPackage().getImplementationVersion();
        return (fromManifest != null && !fromManifest.isBlank()) ? fromManifest : FALLBACK;
    }
}
