package com.botmaker.studio.sharing;

import com.botmaker.studio.config.BotMakerDirs;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * "Sign in with Google" via the OAuth 2.0 <b>device flow</b> — the user authorizes in their browser and never
 * pastes a token. Structurally the twin of {@link GitHubAuth} (no library implements device flow, so it is
 * hand-rolled over the JDK {@link HttpClient}); the two persist to the same {@code credentials.json} under the
 * BotMaker cache dir, under distinct keys.
 *
 * <p><b>No backend yet.</b> This is the sign-in plumbing plus a signed-in identity (the user's email); nothing
 * is gated on it. It stays inert until {@link GoogleConfig#OAUTH_CLIENT_ID} is set, at which point
 * {@link #isConfigured()} turns true and the UI offers the button.
 */
public final class GoogleAuth {

    /** Device-code response the UI shows the user. Google returns {@code verification_url}. */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             int intervalSeconds, int expiresInSeconds) {}

    private static final Path CRED_FILE = BotMakerDirs.getCacheDir().resolve("credentials.json");
    private static final String TOKEN_KEY = "google_token";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String token;
    private volatile String email;

    public GoogleAuth() {
        this.token = loadToken();
    }

    public boolean isConfigured() {
        return GoogleConfig.isConfigured();
    }

    public boolean isAuthenticated() {
        return token != null && !token.isBlank();
    }

    public String token() {
        return token;
    }

    /**
     * The signed-in user's email, resolved lazily from Google's userinfo endpoint and cached for the session.
     * Resolves to {@code ""} when not signed in or unreachable. Runs the call off the FX thread.
     */
    public CompletableFuture<String> email() {
        if (!isAuthenticated()) return CompletableFuture.completedFuture("");
        String cached = email;
        if (cached != null) return CompletableFuture.completedFuture(cached);
        HttpRequest req = HttpRequest.newBuilder(URI.create(GoogleConfig.USERINFO_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            try {
                JsonNode n = mapper.readTree(resp.body());
                String e = n.path("email").asText("");
                if (!e.isBlank()) email = e;
                return e;
            } catch (Exception ex) {
                return "";
            }
        });
    }

    public void signOut() {
        token = null;
        email = null;
        try {
            removeTokenKey();
        } catch (Exception e) {
            System.err.println("Failed to clear Google credentials: " + e.getMessage());
        }
    }

    /** Step 1: request a device + user code from Google. */
    public CompletableFuture<DeviceCode> requestDeviceCode() {
        String body = form(Map.of(
                "client_id", GoogleConfig.OAUTH_CLIENT_ID,
                "scope", GoogleConfig.SCOPE));
        HttpRequest req = HttpRequest.newBuilder(URI.create(GoogleConfig.DEVICE_CODE_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            try {
                JsonNode n = mapper.readTree(resp.body());
                if (n.hasNonNull("device_code")) {
                    // Google names it verification_url; accept verification_uri too for forward-compat.
                    String verify = n.path("verification_url").asText(n.path("verification_uri").asText(""));
                    return new DeviceCode(
                            n.get("device_code").asText(),
                            n.get("user_code").asText(),
                            verify,
                            n.path("interval").asInt(5),
                            n.path("expires_in").asInt(1800));
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
     * Step 2: poll Google until the user authorizes (or the code expires / is denied). On success the token is
     * stored and returned. Runs on a background thread — do not call on the FX thread.
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
                    this.email = null; // re-resolve for the (possibly new) account
                    return t;
                }
                String error = n.path("error").asText("");
                switch (error) {
                    case "authorization_pending" -> { /* keep waiting */ }
                    case "slow_down" -> interval += 5;
                    case "access_denied" -> throw new RuntimeException("Authorization was denied.");
                    case "expired_token" -> throw new RuntimeException("The code expired. Please try again.");
                    default -> { if (!error.isBlank()) throw new RuntimeException("Google sign-in failed: " + error); }
                }
            }
            throw new RuntimeException("Sign-in timed out. Please try again.");
        });
    }

    private JsonNode pollOnce(String deviceCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", GoogleConfig.OAUTH_CLIENT_ID);
        if (!GoogleConfig.OAUTH_CLIENT_SECRET.isBlank()) {
            params.put("client_secret", GoogleConfig.OAUTH_CLIENT_SECRET);
        }
        params.put("device_code", deviceCode);
        params.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        HttpRequest req = HttpRequest.newBuilder(URI.create(GoogleConfig.ACCESS_TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            return null; // transient; keep polling
        }
    }

    // -------------------------------------------------------------------------
    // Token persistence (shares credentials.json with GitHubAuth, distinct key)
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
            Map<String, String> all = readAll();
            all.put(TOKEN_KEY, t);
            mapper.writeValue(CRED_FILE.toFile(), all);
            restrictPermissions(CRED_FILE);
        } catch (Exception e) {
            System.err.println("Failed to store Google credentials: " + e.getMessage());
        }
    }

    private void removeTokenKey() throws Exception {
        if (!Files.exists(CRED_FILE)) return;
        Map<String, String> all = readAll();
        all.remove(TOKEN_KEY);
        if (all.isEmpty()) {
            Files.deleteIfExists(CRED_FILE);
        } else {
            mapper.writeValue(CRED_FILE.toFile(), all);
            restrictPermissions(CRED_FILE);
        }
    }

    /** Reads the whole credentials map so writing one provider's key preserves the other's. */
    private Map<String, String> readAll() {
        Map<String, String> all = new LinkedHashMap<>();
        try {
            if (Files.exists(CRED_FILE)) {
                JsonNode n = mapper.readTree(CRED_FILE.toFile());
                n.fields().forEachRemaining(e -> all.put(e.getKey(), e.getValue().asText("")));
            }
        } catch (Exception e) {
            System.err.println("Failed to read credentials: " + e.getMessage());
        }
        return all;
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
