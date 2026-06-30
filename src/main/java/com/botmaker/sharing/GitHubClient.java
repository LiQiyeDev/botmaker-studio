package com.botmaker.sharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Thin async wrapper over the GitHub REST API (and arbitrary GETs) using the JDK {@link HttpClient} and
 * Jackson — no third-party GitHub SDK (none is official; see the project plan). Read calls are best-effort
 * (resolve to {@code null} on failure); write calls surface non-2xx responses as a failed future so the
 * publisher can report them.
 */
public final class GitHubClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** Raw GET returning the response body as text, or {@code null} on any non-200 / failure. */
    public CompletableFuture<String> getString(String url) {
        HttpRequest req = baseRequest(url).GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> resp.statusCode() == 200 ? resp.body() : null)
                .exceptionally(e -> {
                    System.err.println("GitHub GET failed (" + url + "): " + e.getMessage());
                    return null;
                });
    }

    /** GET parsed as JSON (best-effort: {@code null} on any non-200 / failure). */
    public CompletableFuture<JsonNode> get(String url, String token) {
        HttpRequest req = authed(baseRequest(url), token).GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    try {
                        return resp.statusCode() == 200 ? mapper.readTree(resp.body()) : null;
                    } catch (Exception e) {
                        return null;
                    }
                })
                .exceptionally(e -> {
                    System.err.println("GitHub GET failed (" + url + "): " + e.getMessage());
                    return null;
                });
    }

    /** Download raw bytes (e.g. a codeload zip), or fail the future on a non-200. */
    public CompletableFuture<byte[]> getBytes(String url) {
        HttpRequest req = baseRequest(url).GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Download failed (HTTP " + resp.statusCode() + "): " + url);
                    }
                    return resp.body();
                });
    }

    public CompletableFuture<JsonNode> post(String url, Object body, String token) {
        return send("POST", url, body, token);
    }

    public CompletableFuture<JsonNode> put(String url, Object body, String token) {
        return send("PUT", url, body, token);
    }

    public CompletableFuture<JsonNode> patch(String url, Object body, String token) {
        return send("PATCH", url, body, token);
    }

    /** Write request; fails the future (with the response body) on a non-2xx status. */
    private CompletableFuture<JsonNode> send(String method, String url, Object body, String token) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        HttpRequest req = authed(baseRequest(url), token)
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int sc = resp.statusCode();
                    if (sc < 200 || sc >= 300) {
                        throw new RuntimeException("GitHub " + method + " " + url
                                + " → HTTP " + sc + ": " + resp.body());
                    }
                    try {
                        return resp.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(resp.body());
                    } catch (Exception e) {
                        throw new RuntimeException("Bad JSON from " + url + ": " + e.getMessage(), e);
                    }
                });
    }

    private static HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private static HttpRequest.Builder authed(HttpRequest.Builder b, String token) {
        return (token == null || token.isBlank()) ? b : b.header("Authorization", "Bearer " + token);
    }
}
