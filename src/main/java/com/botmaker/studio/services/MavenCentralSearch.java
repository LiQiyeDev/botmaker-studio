package com.botmaker.studio.services;

import com.botmaker.studio.project.UserLibrary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Queries the public Maven Central search (Solr) API for IntelliJ-style autocomplete of library
 * coordinates. All calls are asynchronous and best-effort: any network/parse failure resolves to an
 * empty result rather than throwing, mirroring {@link MavenService#resolveClasspath}.
 *
 * <p>Endpoints (see <a href="https://central.sonatype.org/search/rest-api-guide/">REST API guide</a>):
 * <ul>
 *   <li>artifact search — {@code select?q=<query>&rows=N&wt=json}</li>
 *   <li>versions for a g:a — {@code select?q=g:"g"+AND+a:"a"&core=gav&rows=N&wt=json}</li>
 * </ul>
 */
public final class MavenCentralSearch {

    private static final String BASE = "https://search.maven.org/solrsearch/select";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MavenCentralSearch() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Suggests up to {@code limit} artifacts matching the typed query. Each result carries the latest
     * version Maven Central knows about.
     */
    public CompletableFuture<List<UserLibrary>> searchArtifacts(String query, int limit) {
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String url = BASE + "?q=" + enc(query) + "&rows=" + limit + "&wt=json";
        return getJson(url).thenApply(root -> {
            List<UserLibrary> out = new ArrayList<>();
            if (root == null) return out;
            for (JsonNode doc : root.path("response").path("docs")) {
                String g = doc.path("g").asText("");
                String a = doc.path("a").asText("");
                String v = doc.path("latestVersion").asText("");
                if (!g.isBlank() && !a.isBlank()) {
                    out.add(new UserLibrary(g, a, v));
                }
            }
            return out;
        });
    }

    /**
     * Lists the available versions for a {@code groupId:artifactId}, newest first (as returned by the
     * API), up to {@code limit}.
     */
    public CompletableFuture<List<String>> fetchVersions(String groupId, String artifactId, int limit) {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String q = "g:\"" + groupId + "\" AND a:\"" + artifactId + "\"";
        String url = BASE + "?q=" + enc(q) + "&core=gav&rows=" + limit + "&wt=json";
        return getJson(url).thenApply(root -> {
            List<String> out = new ArrayList<>();
            if (root == null) return out;
            for (JsonNode doc : root.path("response").path("docs")) {
                String v = doc.path("v").asText("");
                if (!v.isBlank()) out.add(v);
            }
            return out;
        });
    }

    private CompletableFuture<JsonNode> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    try {
                        if (resp.statusCode() != 200) return null;
                        return mapper.readTree(resp.body());
                    } catch (Exception e) {
                        return null;
                    }
                })
                .exceptionally(e -> {
                    System.err.println("Maven Central search failed: " + e.getMessage());
                    return null;
                });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
