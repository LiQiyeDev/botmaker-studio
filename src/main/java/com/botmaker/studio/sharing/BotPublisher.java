package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publish side of the federated gallery (requires authentication via {@link GitHubAuth}).
 *
 * <p>Publishing a bot: ensure the author's GitHub repo exists, push the project tree via the Git Data API
 * (no git binary), cut a release tag, then (best-effort) submit the bot to the curated index repo as a
 * fork + PR so a maintainer can review it. The repo + release are the source of truth for installs/updates;
 * gallery submission only adds the bot to discovery and never blocks the publish.
 *
 * <p>Blocking — intended to run off the FX thread (the dialog wraps it in a background task).
 */
public final class BotPublisher {

    /** Outcome of a publish: the repo, the release tag, and a human-readable gallery-submission status. */
    public record PublishResult(String repoUrl, String tag, String galleryStatus) {}

    private final GitHubClient client;
    private final GitHubAuth auth;

    public BotPublisher(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
    }

    public PublishResult publish(Path projectDir, String botName, String repoName,
                                 String description, String version, List<String> tags) throws IOException {
        if (!auth.isAuthenticated()) {
            throw new IOException("Not signed in to GitHub.");
        }
        String token = auth.token();
        String api = GitHubConfig.API_BASE;

        String login = auth.login(client).join();
        if (login.isBlank()) {
            throw new IOException("Could not read your GitHub account.");
        }
        String repoApi = api + "/repos/" + login + "/" + repoName;

        // 1. Ensure the repo exists (auto_init gives us a base branch/commit to build on).
        JsonNode repo = client.get(repoApi, token).join();
        String repoUrl;
        String branch;
        if (repo == null) {
            JsonNode created = client.post(api + "/user/repos", mapOf(
                    "name", repoName, "description", description, "private", false, "auto_init", true), token).join();
            repoUrl = created.path("html_url").asText("https://github.com/" + login + "/" + repoName);
            branch = created.path("default_branch").asText("main");
        } else {
            repoUrl = repo.path("html_url").asText("https://github.com/" + login + "/" + repoName);
            branch = repo.path("default_branch").asText("main");
        }

        // 2. Base commit sha (may be absent for a truly empty repo).
        String refUrl = repoApi + "/git/refs/heads/" + branch;
        JsonNode ref = client.get(refUrl, token).join();
        String baseSha = (ref == null) ? null : ref.path("object").path("sha").asText(null);

        // 3. Blobs for every publishable file, assembled into a full tree (no base_tree → exact replace).
        Map<String, byte[]> files = ProjectArchive.collect(projectDir);
        if (files.isEmpty()) {
            throw new IOException("Nothing to publish — the project has no files.");
        }
        List<Map<String, Object>> tree = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            JsonNode blob = client.post(repoApi + "/git/blobs", mapOf(
                    "content", Base64.getEncoder().encodeToString(e.getValue()),
                    "encoding", "base64"), token).join();
            tree.add(mapOf("path", e.getKey(), "mode", "100644", "type", "blob",
                    "sha", blob.get("sha").asText()));
        }
        String treeSha = client.post(repoApi + "/git/trees", mapOf("tree", tree), token).join()
                .get("sha").asText();

        // 4. Commit + move the branch ref onto it.
        Map<String, Object> commitBody = new LinkedHashMap<>();
        commitBody.put("message", "Publish " + botName + " " + version + " from BotMaker Studio");
        commitBody.put("tree", treeSha);
        if (baseSha != null) commitBody.put("parents", List.of(baseSha));
        String commitSha = client.post(repoApi + "/git/commits", commitBody, token).join().get("sha").asText();

        if (baseSha != null) {
            client.patch(refUrl, mapOf("sha", commitSha, "force", true), token).join();
        } else {
            client.post(repoApi + "/git/refs", mapOf("ref", "refs/heads/" + branch, "sha", commitSha), token).join();
        }

        // 5. Release (the version other users install / update to).
        client.post(repoApi + "/releases", mapOf(
                "tag_name", version, "name", version, "target_commitish", branch,
                "body", "Published from BotMaker Studio"), token).join();

        // 6. Discovery topic (best-effort secondary signal; the index repo is authoritative).
        try {
            client.put(repoApi + "/topics", mapOf("names", List.of(GitHubConfig.TOPIC)), token).join();
        } catch (Exception ignored) {
            // optional
        }

        // 7. Record provenance locally so the author also sees update prompts.
        new BotSource(login, repoName, version).write(projectDir);

        // 8. Submit to the curated gallery (best-effort; never fails the publish).
        String galleryStatus = submitToGallery(login, repoName, botName, description, tags, token);

        return new PublishResult(repoUrl, version, galleryStatus);
    }

    // -------------------------------------------------------------------------
    // Gallery submission (fork + edit index.json + PR)
    // -------------------------------------------------------------------------

    private String submitToGallery(String login, String repoName, String botName, String description,
                                   List<String> tags, String token) {
        if (!GitHubConfig.isGalleryConfigured()) {
            return "Gallery index not configured — your bot is published, but not yet listed.";
        }
        String slug = login + "/" + repoName;
        Map<String, Object> entry = mapOf("name", botName, "owner", login, "repo", repoName,
                "description", description, "tags", tags == null ? List.of() : List.copyOf(tags));

        try {
            // The maintainer can't fork their own index repo (and a self-PR is a no-op): commit directly.
            if (login.equalsIgnoreCase(GitHubConfig.INDEX_OWNER)) {
                editIndex(GitHubConfig.INDEX_OWNER, token, entry, slug);
                return "Added to the gallery.";
            }

            // Other users: fork the index repo, edit their fork, and open a PR for review.
            if (alreadyListed(slug)) {
                return "Already listed in the gallery (new release will be picked up automatically).";
            }
            String indexRepoApi = GitHubConfig.API_BASE
                    + "/repos/" + GitHubConfig.INDEX_OWNER + "/" + GitHubConfig.INDEX_REPO;

            client.post(indexRepoApi + "/forks", Map.of(), token).join();
            if (!awaitFork(login, token)) {
                return "Published. Couldn't reach your gallery fork to submit — try again from your fork.";
            }

            editIndex(login, token, entry, slug);

            JsonNode pr = client.post(indexRepoApi + "/pulls", mapOf(
                    "title", "Add " + slug + " to the gallery",
                    "head", login + ":" + GitHubConfig.INDEX_BRANCH,
                    "base", GitHubConfig.INDEX_BRANCH,
                    "body", "Submitted from BotMaker Studio: " + botName + " — " + description), token).join();

            String prUrl = pr.path("html_url").asText("");
            return prUrl.isBlank()
                    ? "Submitted to the gallery for review."
                    : "Submitted to the gallery for review: " + prUrl;
        } catch (Exception e) {
            return "Published. Auto-submit to the gallery failed (" + rootMessage(e)
                    + "); you can open a PR manually.";
        }
    }

    /**
     * Reads {@code index.json} from {@code repoOwner}'s copy of the index repo, replaces the entry with the
     * new one (deduping by {@code owner/repo} so re-publishing is idempotent), and commits it back.
     */
    private void editIndex(String repoOwner, String token, Map<String, Object> entry, String slug)
            throws Exception {
        String contentsUrl = GitHubConfig.API_BASE + "/repos/" + repoOwner + "/" + GitHubConfig.INDEX_REPO
                + "/contents/" + GitHubConfig.INDEX_PATH;

        JsonNode contents = client.get(contentsUrl + "?ref=" + GitHubConfig.INDEX_BRANCH, token).join();
        if (contents == null || !contents.hasNonNull("content")) {
            throw new IOException("Could not read " + GitHubConfig.INDEX_PATH + " in " + repoOwner + "/"
                    + GitHubConfig.INDEX_REPO);
        }
        String sha = contents.path("sha").asText();
        byte[] decoded = Base64.getMimeDecoder().decode(contents.path("content").asText());

        String newContent = Base64.getEncoder().encodeToString(mergeEntry(client.mapper(), decoded, entry, slug));

        client.put(contentsUrl, mapOf(
                "message", "Add " + slug + " to the gallery",
                "content", newContent,
                "sha", sha,
                "branch", GitHubConfig.INDEX_BRANCH), token).join();
    }

    /**
     * Pure JSON merge: parse an {@code index.json} body, drop any existing entry with the same
     * {@code owner/repo} as {@code slug}, append {@code entry}, and return the pretty-printed bytes.
     */
    static byte[] mergeEntry(ObjectMapper mapper, byte[] indexJson, Map<String, Object> entry, String slug)
            throws IOException {
        List<Object> entries = new ArrayList<>(List.of(mapper.readValue(indexJson, Object[].class)));
        entries.removeIf(o -> o instanceof Map<?, ?> e
                && slug.equalsIgnoreCase(e.get("owner") + "/" + e.get("repo")));
        entries.add(entry);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries);
    }

    private boolean alreadyListed(String slug) {
        try {
            String body = client.getString(GitHubConfig.indexRawUrl()).join();
            if (body == null || body.isBlank()) return false;
            for (Map<?, ?> e : client.mapper().readValue(body, Map[].class)) {
                String s = String.valueOf(e.get("owner")) + "/" + e.get("repo");
                if (s.equalsIgnoreCase(slug)) return true;
            }
        } catch (Exception ignored) {
            // treat as not-listed; worst case the maintainer closes a duplicate PR
        }
        return false;
    }

    /** Polls until the signed-in user's fork of the index repo has a readable {@code index.json}. */
    private boolean awaitFork(String login, String token) {
        String url = GitHubConfig.API_BASE + "/repos/" + login + "/" + GitHubConfig.INDEX_REPO
                + "/contents/" + GitHubConfig.INDEX_PATH + "?ref=" + GitHubConfig.INDEX_BRANCH;
        for (int attempt = 0; attempt < 10; attempt++) {
            JsonNode n = client.get(url, token).join();
            if (n != null && n.hasNonNull("content")) return true;
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
