package com.botmaker.studio.services;

import com.botmaker.studio.config.AppVersion;
import com.botmaker.studio.sharing.GitHubClient;
import com.botmaker.studio.sharing.GitHubConfig;
import com.botmaker.studio.sharing.SemVer;
import com.fasterxml.jackson.databind.JsonNode;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

/**
 * Checks GitHub Releases for a newer BotMaker Studio and, when the user opts in, downloads the installer that
 * matches the host OS ({@code .msi} on Windows, {@code .deb}/{@code .rpm} on Linux) and hands it to the OS
 * installer. All network work is async and best-effort; failures resolve to "no update" rather than throwing.
 *
 * <p>Reuses {@link GitHubClient} for the release JSON and {@link SemVer#isGreater} for the version comparison.
 * The binary download uses a redirect-following {@link HttpClient} because release-asset URLs 302 to a CDN.
 */
public final class UpdateService {

    /** A newer release plus the installer asset chosen for the current OS. */
    public record AvailableUpdate(String tag, String assetName, String downloadUrl) {}

    private final GitHubClient client;
    private final HttpClient downloader = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UpdateService() {
        this(new GitHubClient());
    }

    public UpdateService(GitHubClient client) {
        this.client = client;
    }

    /**
     * Resolves to the newer release (with a matching installer for this OS) if one exists, else empty.
     * Empty also covers "up to date", "no installer for this OS", and any network/parse failure.
     */
    public CompletableFuture<Optional<AvailableUpdate>> checkForUpdate() {
        String url = GitHubConfig.API_BASE + "/repos/"
                + GitHubConfig.STUDIO_OWNER + "/" + GitHubConfig.STUDIO_REPO + "/releases/latest";
        return client.get(url, null).thenApply(node -> {
            if (node == null) return Optional.<AvailableUpdate>empty();
            String tag = node.path("tag_name").asText("");
            if (!SemVer.isGreater(tag, AppVersion.get())) return Optional.<AvailableUpdate>empty();
            return pickAsset(node).map(a -> new AvailableUpdate(tag, a.name, a.url));
        }).exceptionally(e -> Optional.empty());
    }

    /**
     * Downloads the installer to a temp file and returns its path, reporting fractional progress in
     * {@code [0.0, 1.0]} to {@code progress} as bytes arrive (or {@code -1.0} when the server sends no
     * {@code Content-Length}, i.e. progress is indeterminate). {@code progress} is invoked on the
     * HTTP client's executor thread — the caller is responsible for marshalling to the UI thread.
     * Fails the future on any download error.
     */
    public CompletableFuture<Path> downloadInstaller(AvailableUpdate update, DoubleConsumer progress) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(update.downloadUrl()))
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream")
                .GET().build();
        return downloader.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Download failed (HTTP " + resp.statusCode() + ")");
                    }
                    long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);
                    try {
                        Path dir = Files.createTempDirectory("botmaker-update");
                        Path file = dir.resolve(update.assetName());
                        streamToFile(resp.body(), file, total, progress);
                        return file;
                    } catch (IOException e) {
                        throw new RuntimeException("Could not save installer: " + e.getMessage(), e);
                    }
                });
    }

    /** Copies {@code in} to {@code file}, reporting {@code read/total} (or {@code -1.0} when {@code total<=0}). */
    private static void streamToFile(InputStream in, Path file, long total, DoubleConsumer progress)
            throws IOException {
        try (InputStream src = in;
             OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            progress.accept(total > 0 ? 0.0 : -1.0);
            while ((n = src.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0) progress.accept((double) read / total);
            }
            if (total > 0) progress.accept(1.0);
        }
    }

    /**
     * Installs (not merely opens) {@code installer}, best-effort across platforms:
     * <ul>
     *   <li><b>Windows</b> — launches the {@code .msi} via {@code msiexec}.</li>
     *   <li><b>Linux AppImage</b> — when the app is itself running as an AppImage ({@code $APPIMAGE} set),
     *       the freshly downloaded {@code .AppImage} replaces the running file in place (no root, no
     *       package DB, no stale desktop entry). The user restarts to run the new version.</li>
     *   <li><b>Linux .rpm/.deb</b> — installs through the native package manager under a single {@code pkexec}
     *       (polkit) prompt, so the user no longer has to finish the install manually in the software store.</li>
     *   <li>Otherwise falls back to handing the file to the desktop's default handler.</li>
     * </ul>
     */
    public void launchInstaller(Path installer) throws IOException {
        String file = installer.toAbsolutePath().toString();
        if (isWindows()) {
            new ProcessBuilder("msiexec", "/i", file).start();
            return;
        }
        String lower = file.toLowerCase(Locale.ROOT);
        String runningAppImage = System.getenv("APPIMAGE");
        if (lower.endsWith(".appimage") && runningAppImage != null && !runningAppImage.isBlank()) {
            applyAppImageUpdate(installer, Path.of(runningAppImage));
            return;
        }
        if ((lower.endsWith(".rpm") || lower.endsWith(".deb")) && commandExists("pkexec")) {
            installWithPackageManager(installer);
            return;
        }
        // Fallback: let the desktop's default handler (software installer / archive tool) take it, with an
        // xdg-open fallback for headless AWT.
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(installer.toFile());
        } else {
            new ProcessBuilder("xdg-open", file).start();
        }
    }

    /** Replaces the running AppImage file in place; the OS keeps the already-mapped copy running until restart. */
    private static void applyAppImageUpdate(Path downloaded, Path current) throws IOException {
        Files.copy(downloaded, current, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        current.toFile().setExecutable(true, false);
    }

    /**
     * Installs a {@code .rpm}/{@code .deb} through the native package manager under one polkit prompt, falling
     * back to the low-level tool. This runs the actual system install (previously the user had to complete it
     * by hand in the software store). Blocks until the install finishes.
     */
    private static void installWithPackageManager(Path installer) throws IOException {
        String file = installer.toAbsolutePath().toString();
        boolean rpm = file.toLowerCase(Locale.ROOT).endsWith(".rpm");
        String cmd = rpm
                ? "dnf install -y '" + file + "' || rpm -U --replacepkgs '" + file + "'"
                : "apt-get install -y '" + file + "' || dpkg -i '" + file + "'";
        try {
            Process p = new ProcessBuilder("pkexec", "sh", "-c", cmd).inheritIO().start();
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("Package install exited with code " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Install was interrupted", e);
        }
    }

    private record Asset(String name, String url) {}

    /** Picks the release asset whose extension matches this OS, preferring the native package manager on Linux. */
    private Optional<Asset> pickAsset(JsonNode releaseNode) {
        JsonNode assets = releaseNode.get("assets");
        if (assets == null || !assets.isArray()) return Optional.empty();

        String[] preferred = preferredExtensions();
        for (String ext : preferred) {
            for (JsonNode a : assets) {
                String name = a.path("name").asText("");
                if (name.toLowerCase(Locale.ROOT).endsWith(ext)) {
                    return Optional.of(new Asset(name, a.path("browser_download_url").asText("")));
                }
            }
        }
        return Optional.empty();
    }

    /** Installer extensions acceptable for this OS, in order of preference (lower-case; matched case-insensitively). */
    private static String[] preferredExtensions() {
        if (isWindows()) return new String[]{".msi", ".exe"};
        // Running as an AppImage → self-update by swapping the AppImage file (no root, no package DB).
        String appImage = System.getenv("APPIMAGE");
        if (appImage != null && !appImage.isBlank()) return new String[]{".appimage"};
        // Otherwise prefer the format whose tooling is installed; fall back to the other, then AppImage.
        if (commandExists("dpkg")) return new String[]{".deb", ".rpm", ".appimage"};
        if (commandExists("rpm")) return new String[]{".rpm", ".deb", ".appimage"};
        return new String[]{".appimage", ".deb", ".rpm"};
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean commandExists(String cmd) {
        try {
            return new ProcessBuilder("which", cmd).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
