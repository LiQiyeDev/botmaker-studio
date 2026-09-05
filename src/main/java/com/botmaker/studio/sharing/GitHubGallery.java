package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read side of the federated gallery (no GitHub account required).
 *
 * <p>The catalog is the curated {@code index.json} fetched from the index repo's raw CDN URL — a single
 * request with no API rate limit. Per-bot version / update information is fetched live from each author's
 * own repo via the Releases API, so the index only ever needs one entry per bot.
 *
 * <p>Those per-bot calls are sent <em>with</em> the signed-in token when there is one. Anonymously they are
 * capped at 60 requests/hour across the whole process, and browse costs one call per listed bot — so an
 * unauthenticated gallery would silently read back as "no release, ★ 0" for every entry past the cap. The
 * token also makes the user's own not-yet-public repos readable.
 */
public final class GitHubGallery {

    private final GitHubClient client;
    private final GitHubAuth auth;

    public GitHubGallery(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
    }

    /** The signed-in token, or {@code null} when browsing anonymously. */
    String token() {
        return (auth != null && auth.isAuthenticated()) ? auth.token() : null;
    }

    /** Fetches the full curated catalog (empty if the index repo is unset/unreachable or malformed). */
    public CompletableFuture<List<GalleryEntry>> browse() {
        if (!GitHubConfig.isGalleryConfigured()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return client.getString(GitHubConfig.indexRawUrl()).thenApply(body -> {
            if (body == null || body.isBlank()) return List.<GalleryEntry>of();
            try {
                return List.of(client.mapper().readValue(body, GalleryEntry[].class));
            } catch (Exception e) {
                System.err.println("Failed to parse gallery index.json: " + e.getMessage());
                return List.<GalleryEntry>of();
            }
        });
    }

    /**
     * Resolves the latest release tag for {@code owner/repo} (the author's newest published version), or
     * {@code ""} if the repo has no releases / is unreachable. Used for "Update available" checks.
     */
    public CompletableFuture<String> latestReleaseTag(String owner, String repo) {
        String url = GitHubConfig.API_BASE + "/repos/" + owner + "/" + repo + "/releases/latest";
        return client.get(url, token()).thenApply(node -> {
            if (node == null) return "";
            JsonNode tag = node.get("tag_name");
            return tag == null ? "" : tag.asText("");
        });
    }

    /**
     * Live per-repo signals used for gallery sorting/badges: star count and last-push time (epoch seconds).
     * {@link #UNKNOWN} is the <em>display</em> placeholder for "not fetched yet" only — a failed fetch
     * resolves to {@code null}, so callers can tell "we don't know" from "genuinely zero stars" and never
     * overwrite a count they already have.
     */
    public record RepoMeta(int stars, long pushedAt) {
        public static final RepoMeta UNKNOWN = new RepoMeta(0, 0);
    }

    /**
     * Fetches {@code owner/repo}'s live star count and last-push time from the repo object (the star count is
     * GitHub's own, so github.com stars count too). {@code null} when the repo is unreachable.
     */
    public CompletableFuture<RepoMeta> repoMeta(String owner, String repo) {
        String url = GitHubConfig.API_BASE + "/repos/" + owner + "/" + repo;
        return client.get(url, token()).thenApply(node -> {
            if (node == null) return null;
            int stars = node.path("stargazers_count").asInt(0);
            long pushed = parseInstant(node.path("pushed_at").asText(null));
            return new RepoMeta(stars, pushed);
        });
    }

    /** True when the signed-in user (via {@code token}) has starred {@code owner/repo}. */
    public CompletableFuture<Boolean> isStarred(String owner, String repo, String token) {
        return client.isNoContent(GitHubConfig.API_BASE + "/user/starred/" + owner + "/" + repo, token);
    }

    /** Stars or unstars {@code owner/repo} for the signed-in user. Requires a token. */
    public CompletableFuture<Void> setStarred(String owner, String repo, boolean starred, String token) {
        String url = GitHubConfig.API_BASE + "/user/starred/" + owner + "/" + repo;
        return (starred ? client.put(url, java.util.Map.of(), token) : client.delete(url, token))
                .thenApply(n -> null);
    }

    private static long parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return java.time.Instant.parse(iso).getEpochSecond();
        } catch (Exception e) {
            return 0;
        }
    }
}
