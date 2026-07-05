package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read side of the federated gallery (no GitHub account required).
 *
 * <p>The catalog is the curated {@code index.json} fetched from the index repo's raw CDN URL — a single
 * request with no API rate limit. Per-bot version / update information is fetched live from each author's
 * own repo via the Releases API, so the index only ever needs one entry per bot.
 */
public final class GitHubGallery {

    private final GitHubClient client;

    public GitHubGallery(GitHubClient client) {
        this.client = client;
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
        return client.get(url, null).thenApply(node -> {
            if (node == null) return "";
            JsonNode tag = node.get("tag_name");
            return tag == null ? "" : tag.asText("");
        });
    }
}
