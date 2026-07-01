package com.botmaker.services;

import com.botmaker.config.AppVersion;
import com.botmaker.sharing.GitHubClient;
import com.botmaker.sharing.GitHubConfig;
import com.botmaker.sharing.SemVer;
import com.fasterxml.jackson.databind.JsonNode;

import java.awt.Desktop;
import java.io.IOException;
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

    /** Downloads the installer to a temp file and returns its path. Fails the future on any download error. */
    public CompletableFuture<Path> downloadInstaller(AvailableUpdate update) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(update.downloadUrl()))
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream")
                .GET().build();
        return downloader.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Download failed (HTTP " + resp.statusCode() + ")");
                    }
                    try {
                        Path dir = Files.createTempDirectory("botmaker-update");
                        Path file = dir.resolve(update.assetName());
                        Files.write(file, resp.body());
                        return file;
                    } catch (IOException e) {
                        throw new RuntimeException("Could not save installer: " + e.getMessage(), e);
                    }
                });
    }

    /** Hands {@code installer} to the OS so its native installer UI runs. Best-effort across platforms. */
    public void launchInstaller(Path installer) throws IOException {
        String file = installer.toAbsolutePath().toString();
        if (isWindows()) {
            new ProcessBuilder("msiexec", "/i", file).start();
            return;
        }
        // Linux/mac: let the desktop's default handler (software installer / archive tool) take it, with an
        // xdg-open fallback for headless AWT.
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(installer.toFile());
        } else {
            new ProcessBuilder("xdg-open", file).start();
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

    /** Installer extensions acceptable for this OS, in order of preference. */
    private static String[] preferredExtensions() {
        if (isWindows()) return new String[]{".msi", ".exe"};
        // Linux: prefer the format whose tooling is installed; fall back to the other.
        if (commandExists("dpkg")) return new String[]{".deb", ".rpm"};
        if (commandExists("rpm")) return new String[]{".rpm", ".deb"};
        return new String[]{".deb", ".rpm"};
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
