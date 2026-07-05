package com.botmaker.studio.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Queries JitPack for the versions of a GitHub-hosted artifact (notably the BotMaker SDK,
 * {@code com.github.LiQiyeDev:BotMaker-sdk}). JitPack builds artifacts on demand from git tags and
 * exposes a standard Maven {@code maven-metadata.xml}; there is no Solr-style fuzzy search (that is why the
 * Maven Central search cannot find {@code com.github.*} coordinates), so this only resolves versions for a
 * known {@code groupId:artifactId}.
 *
 * <p>All calls are asynchronous and best-effort: any network/parse failure resolves to an empty result
 * rather than throwing, mirroring {@link MavenCentralSearch} and {@link MavenService#resolveClasspath}.
 */
public final class JitPackSearch {

    private static final String BASE = "https://jitpack.io";

    private static final Pattern VERSION_PATTERN = Pattern.compile("<version>(.*?)</version>");
    private static final Pattern RELEASE_PATTERN = Pattern.compile("<release>(.*?)</release>");

    private final HttpClient http;

    public JitPackSearch() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Lists the available versions for {@code groupId:artifactId}, newest first. JitPack lists versions
     * oldest-first in {@code maven-metadata.xml}, so the document order is reversed here.
     */
    public CompletableFuture<List<String>> fetchVersions(String groupId, String artifactId) {
        if (isBlank(groupId) || isBlank(artifactId)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return getMetadata(groupId, artifactId).thenApply(JitPackSearch::parseVersions);
    }

    /**
     * Resolves the latest released version for {@code groupId:artifactId} (the metadata {@code <release>},
     * falling back to the newest {@code <version>}), or {@code ""} if none could be determined.
     */
    public CompletableFuture<String> fetchLatestVersion(String groupId, String artifactId) {
        if (isBlank(groupId) || isBlank(artifactId)) {
            return CompletableFuture.completedFuture("");
        }
        return getMetadata(groupId, artifactId).thenApply(JitPackSearch::parseLatest);
    }

    private CompletableFuture<String> getMetadata(String groupId, String artifactId) {
        String path = groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/" + path))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> resp.statusCode() == 200 ? resp.body() : null)
                .exceptionally(e -> {
                    System.err.println("JitPack metadata fetch failed: " + e.getMessage());
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // Parsing (package-private + static for unit testing)
    // -------------------------------------------------------------------------

    /** Parses the {@code <version>} entries from a {@code maven-metadata.xml} body, newest first. */
    static List<String> parseVersions(String metadataXml) {
        if (metadataXml == null) return List.of();
        List<String> versions = new ArrayList<>();
        Matcher m = VERSION_PATTERN.matcher(metadataXml);
        while (m.find()) {
            String v = m.group(1).trim();
            if (!v.isEmpty()) versions.add(v);
        }
        Collections.reverse(versions);
        return versions;
    }

    /** Parses the latest version: the {@code <release>} value, else the newest {@code <version>}, else "". */
    static String parseLatest(String metadataXml) {
        if (metadataXml == null) return "";
        Matcher m = RELEASE_PATTERN.matcher(metadataXml);
        if (m.find()) {
            String release = m.group(1).trim();
            if (!release.isEmpty()) return release;
        }
        List<String> versions = parseVersions(metadataXml);
        return versions.isEmpty() ? "" : versions.get(0);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
