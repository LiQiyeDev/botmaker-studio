package com.botmaker.sharing;

import com.botmaker.config.BotMakerDirs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * "Sign in with GitHub" via the OAuth <b>device flow</b> — the user authorizes in their browser and never
 * pastes a personal access token. No third-party library implements device flow, so it is hand-rolled here
 * over the JDK {@link HttpClient}.
 *
 * <p>The obtained token is persisted (best-effort {@code 0600}) under the BotMaker cache dir — never inside
 * {@code ~/BotMakerProjects/}, which may itself be published.
 *
 * <p>Requires {@link GitHubConfig#OAUTH_CLIENT_ID} to be set; otherwise {@link #isConfigured()} is false and
 * the publish UI degrades gracefully.
 */
public final class GitHubAuth {

    /** Device-code response the UI shows the user. */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             int intervalSeconds, int expiresInSeconds) {}

    private static final Path CRED_FILE = BotMakerDirs.getCacheDir().resolve("credentials.json");
    private static final String TOKEN_KEY = "github_token";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String token;
    private volatile String login;

    public GitHubAuth() {
        this.token = loadToken();
    }

    public boolean isConfigured() {
        return GitHubConfig.isPublishingConfigured();
    }

    public boolean isAuthenticated() {
        return token != null && !token.isBlank();
    }

    public String token() {
        return token;
    }

    /**
     * The signed-in user's GitHub login, resolved lazily from {@code /user} and cached for the session.
     * Resolves to {@code ""} when not signed in or unreachable. Runs the call off the FX thread.
     */
    public CompletableFuture<String> login(GitHubClient client) {
        if (!isAuthenticated()) return CompletableFuture.completedFuture("");
        String cached = login;
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return client.get(GitHubConfig.API_BASE + "/user", token).thenApply(node -> {
            String l = node == null ? "" : node.path("login").asText("");
            if (!l.isBlank()) login = l;
            return l;
        });
    }

    public void signOut() {
        token = null;
        login = null;
        try {
            Files.deleteIfExists(CRED_FILE);
        } catch (Exception e) {
            System.err.println("Failed to clear credentials: " + e.getMessage());
        }
    }

    /** Step 1: request a device + user code from GitHub. */
    public CompletableFuture<DeviceCode> requestDeviceCode() {
        String body = form(Map.of(
                "client_id", GitHubConfig.OAUTH_CLIENT_ID,
                "scope", GitHubConfig.SCOPE));
        HttpRequest req = HttpRequest.newBuilder(URI.create(GitHubConfig.DEVICE_CODE_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            try {
                JsonNode n = mapper.readTree(resp.body());
                if (n.hasNonNull("device_code")) {
                    return new DeviceCode(
                            n.get("device_code").asText(),
                            n.get("user_code").asText(),
                            n.get("verification_uri").asText(),
                            n.path("interval").asInt(5),
                            n.path("expires_in").asInt(900));
                }
                throw new RuntimeException("Device-code request failed: " + resp.body());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Device-code request failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Step 2: poll GitHub until the user authorizes (or the code expires / is denied). On success the token
     * is stored and returned. Runs on a background thread — do not call on the FX thread.
     */
    public CompletableFuture<String> pollForToken(DeviceCode code) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + code.expiresInSeconds() * 1000L;
            int interval = Math.max(1, code.intervalSeconds());
            while (System.currentTimeMillis() < deadline) {
                sleep(interval);
                JsonNode n = pollOnce(code.deviceCode());
                if (n == null) continue;
                if (n.hasNonNull("access_token")) {
                    String t = n.get("access_token").asText();
                    storeToken(t);
                    this.token = t;
                    this.login = null; // re-resolve for the (possibly new) account
                    return t;
                }
                String error = n.path("error").asText("");
                switch (error) {
                    case "authorization_pending" -> { /* keep waiting */ }
                    case "slow_down" -> interval += 5;
                    case "access_denied" -> throw new RuntimeException("Authorization was denied.");
                    case "expired_token" -> throw new RuntimeException("The code expired. Please try again.");
                    default -> { if (!error.isBlank()) throw new RuntimeException("GitHub sign-in failed: " + error); }
                }
            }
            throw new RuntimeException("Sign-in timed out. Please try again.");
        });
    }

    private JsonNode pollOnce(String deviceCode) {
        String body = form(Map.of(
                "client_id", GitHubConfig.OAUTH_CLIENT_ID,
                "device_code", deviceCode,
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(GitHubConfig.ACCESS_TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            return null; // transient; keep polling
        }
    }

    // -------------------------------------------------------------------------
    // Token persistence
    // -------------------------------------------------------------------------

    private String loadToken() {
        try {
            if (Files.exists(CRED_FILE)) {
                JsonNode n = mapper.readTree(CRED_FILE.toFile());
                String t = n.path(TOKEN_KEY).asText("");
                return t.isBlank() ? null : t;
            }
        } catch (Exception e) {
            System.err.println("Failed to read credentials: " + e.getMessage());
        }
        return null;
    }

    private void storeToken(String t) {
        try {
            Files.createDirectories(CRED_FILE.getParent());
            mapper.writeValue(CRED_FILE.toFile(), Map.of(TOKEN_KEY, t));
            restrictPermissions(CRED_FILE);
        } catch (Exception e) {
            System.err.println("Failed to store credentials: " + e.getMessage());
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Non-POSIX filesystem (e.g. Windows) — best-effort only.
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(v));
        });
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
