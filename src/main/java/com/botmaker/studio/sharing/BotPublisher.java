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

    /**
     * Publishes a release of the project to the author's GitHub repo. When {@code listInGallery} is true the
     * bot is also submitted to the curated public gallery (topic signal + index PR); when false the release is
     * private-in-effect — the repo + release are created but the bot is left out of gallery discovery.
     */
    public PublishResult publish(Path projectDir, String botName, String repoName, String description,
                                 String version, List<String> tags, boolean listInGallery) throws IOException {
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

        // 3–4. Blobs → tree → commit for the whole project snapshot (no base_tree → exact replace).
        Map<String, byte[]> files = ProjectArchive.collect(projectDir);
        if (files.isEmpty()) {
            throw new IOException("Nothing to publish — the project has no files.");
        }
        String commitSha = buildTreeCommit(repoApi, files, baseSha,
                "Publish " + botName + " " + version + " from BotMaker Studio", token);

        if (baseSha != null) {
            client.patch(refUrl, mapOf("sha", commitSha, "force", true), token).join();
        } else {
            client.post(repoApi + "/git/refs", mapOf("ref", "refs/heads/" + branch, "sha", commitSha), token).join();
        }

        // 5. Release (the version other users install / update to).
        client.post(repoApi + "/releases", mapOf(
                "tag_name", version, "name", version, "target_commitish", branch,
                "body", "Published from BotMaker Studio"), token).join();

        // 6. Record provenance locally so the author also sees update prompts.
        new BotSource(login, repoName, version).write(projectDir);

        // 7. Gallery discovery is opt-in: only a public (listed) release adds the topic signal + index entry.
        String galleryStatus;
        if (listInGallery) {
            // Discovery topic (best-effort secondary signal; the index repo is authoritative).
            try {
                client.put(repoApi + "/topics", mapOf("names", List.of(GitHubConfig.TOPIC)), token).join();
            } catch (Exception ignored) {
                // optional
            }
            // Submit to the curated gallery (best-effort; never fails the publish).
            galleryStatus = submitToGallery(login, repoName, botName, description, tags, token);
        } else {
            galleryStatus = "Published privately — not listed in the public gallery.";
        }

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

    // -------------------------------------------------------------------------
    // Community patching (fork the origin bot repo, push a snapshot branch, open a PR upstream)
    // -------------------------------------------------------------------------

    /** Outcome of a patch submission: the opened PR's URL (may be blank if GitHub didn't return one). */
    public record PatchResult(String pullRequestUrl) {}

    /**
     * Proposes the user's local changes back to the bot they installed: forks {@code origin.slug()}, pushes the
     * current project snapshot onto a fresh branch in the fork, and opens a pull request against the origin's
     * default branch. Reuses the same Git Data API tree-push as {@link #publish}. Blocking; run off the FX thread.
     *
     * @param origin the installed bot's provenance (from {@link BotSource}) — the PR target
     */
    public PatchResult submitPatch(Path projectDir, BotSource origin, String title, String body)
            throws IOException {
        if (!auth.isAuthenticated()) {
            throw new IOException("Not signed in to GitHub.");
        }
        if (origin == null) {
            throw new IOException("This project has no upstream bot to patch.");
        }
        String token = auth.token();
        String api = GitHubConfig.API_BASE;
        String login = auth.login(client).join();
        if (login.isBlank()) {
            throw new IOException("Could not read your GitHub account.");
        }
        String originApi = api + "/repos/" + origin.owner() + "/" + origin.repo();

        // Origin's default branch is the PR base.
        JsonNode originRepo = client.get(originApi, token).join();
        if (originRepo == null) {
            throw new IOException("Upstream repo " + origin.slug() + " is unavailable.");
        }
        String baseBranch = originRepo.path("default_branch").asText("main");

        // Fork it under the signed-in account (idempotent) and wait for the fork's tree to be readable.
        client.post(originApi + "/forks", Map.of(), token).join();
        String forkApi = api + "/repos/" + login + "/" + origin.repo();
        if (!awaitForkRepo(forkApi, baseBranch, token)) {
            throw new IOException("Couldn't reach your fork of " + origin.slug() + " — try again shortly.");
        }

        // Base the patch branch on the fork's current tip of the default branch.
        String forkRefUrl = forkApi + "/git/refs/heads/" + baseBranch;
        JsonNode forkRef = client.get(forkRefUrl, token).join();
        String baseSha = (forkRef == null) ? null : forkRef.path("object").path("sha").asText(null);

        Map<String, byte[]> files = ProjectArchive.collect(projectDir);
        if (files.isEmpty()) {
            throw new IOException("Nothing to submit — the project has no files.");
        }
        String message = (title == null || title.isBlank()) ? "Patch from BotMaker Studio" : title;
        String commitSha = buildTreeCommit(forkApi, files, baseSha, message, token);

        String branch = "botmaker-patch-" + System.currentTimeMillis();
        client.post(forkApi + "/git/refs",
                mapOf("ref", "refs/heads/" + branch, "sha", commitSha), token).join();

        JsonNode pr = client.post(originApi + "/pulls", mapOf(
                "title", message,
                "head", login + ":" + branch,
                "base", baseBranch,
                "body", body == null ? "Submitted from BotMaker Studio." : body), token).join();

        return new PatchResult(pr.path("html_url").asText(""));
    }

    /** Blobs → tree (full snapshot, no {@code base_tree}) → commit; returns the new commit's SHA. */
    private String buildTreeCommit(String repoApi, Map<String, byte[]> files, String baseSha,
                                   String message, String token) {
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

        Map<String, Object> commitBody = new LinkedHashMap<>();
        commitBody.put("message", message);
        commitBody.put("tree", treeSha);
        if (baseSha != null) commitBody.put("parents", List.of(baseSha));
        return client.post(repoApi + "/git/commits", commitBody, token).join().get("sha").asText();
    }

    /** Polls until a fork repo's default-branch tree is readable (fork creation is asynchronous). */
    private boolean awaitForkRepo(String forkApi, String branch, String token) {
        String url = forkApi + "/git/refs/heads/" + branch;
        for (int attempt = 0; attempt < 10; attempt++) {
            JsonNode n = client.get(url, token).join();
            if (n != null && n.path("object").hasNonNull("sha")) return true;
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
    // Unpublish (remove from the gallery index — reverse of submitToGallery)
    // -------------------------------------------------------------------------

    /**
     * Delists a bot from the curated gallery index (reverse of the publish-time submission). For the index
     * maintainer this commits the removal directly; for everyone else it edits their fork of the index repo
     * and opens a PR. The author's own repo + releases are left intact — this only removes the bot from
     * discovery. Blocking; run off the FX thread.
     */
    public String unpublish(String repoName) throws IOException {
        if (!auth.isAuthenticated()) {
            throw new IOException("Not signed in to GitHub.");
        }
        String token = auth.token();
        String login = auth.login(client).join();
        if (login.isBlank()) {
            throw new IOException("Could not read your GitHub account.");
        }
        if (!GitHubConfig.isGalleryConfigured()) {
            return "Gallery index not configured — nothing to unpublish.";
        }
        String slug = login + "/" + repoName;
        try {
            // The maintainer can commit the removal directly (can't fork / self-PR their own index repo).
            if (login.equalsIgnoreCase(GitHubConfig.INDEX_OWNER)) {
                editIndexRemove(GitHubConfig.INDEX_OWNER, token, slug);
                return "Removed from the gallery.";
            }

            if (!alreadyListed(slug)) {
                return "Not listed in the gallery — nothing to remove.";
            }
            String indexRepoApi = GitHubConfig.API_BASE
                    + "/repos/" + GitHubConfig.INDEX_OWNER + "/" + GitHubConfig.INDEX_REPO;

            client.post(indexRepoApi + "/forks", Map.of(), token).join();
            if (!awaitFork(login, token)) {
                return "Couldn't reach your gallery fork to submit the removal — try again from your fork.";
            }

            editIndexRemove(login, token, slug);

            JsonNode pr = client.post(indexRepoApi + "/pulls", mapOf(
                    "title", "Remove " + slug + " from the gallery",
                    "head", login + ":" + GitHubConfig.INDEX_BRANCH,
                    "base", GitHubConfig.INDEX_BRANCH,
                    "body", "Unpublished from BotMaker Studio."), token).join();

            String prUrl = pr.path("html_url").asText("");
            return prUrl.isBlank()
                    ? "Submitted a gallery removal for review."
                    : "Submitted a gallery removal for review: " + prUrl;
        } catch (Exception e) {
            return "Unpublish failed (" + rootMessage(e) + "); you can edit the gallery index manually.";
        }
    }

    /** Reads {@code index.json} from {@code repoOwner}'s copy of the index repo, drops the entry for
     * {@code slug}, and commits it back. */
    private void editIndexRemove(String repoOwner, String token, String slug) throws Exception {
        String contentsUrl = GitHubConfig.API_BASE + "/repos/" + repoOwner + "/" + GitHubConfig.INDEX_REPO
                + "/contents/" + GitHubConfig.INDEX_PATH;

        JsonNode contents = client.get(contentsUrl + "?ref=" + GitHubConfig.INDEX_BRANCH, token).join();
        if (contents == null || !contents.hasNonNull("content")) {
            throw new IOException("Could not read " + GitHubConfig.INDEX_PATH + " in " + repoOwner + "/"
                    + GitHubConfig.INDEX_REPO);
        }
        String sha = contents.path("sha").asText();
        byte[] decoded = Base64.getMimeDecoder().decode(contents.path("content").asText());

        String newContent = Base64.getEncoder().encodeToString(removeEntry(client.mapper(), decoded, slug));

        client.put(contentsUrl, mapOf(
                "message", "Remove " + slug + " from the gallery",
                "content", newContent,
                "sha", sha,
                "branch", GitHubConfig.INDEX_BRANCH), token).join();
    }

    /**
     * Pure JSON transform: parse an {@code index.json} body and return it with any entry whose
     * {@code owner/repo} equals {@code slug} removed, pretty-printed.
     */
    static byte[] removeEntry(ObjectMapper mapper, byte[] indexJson, String slug) throws IOException {
        List<Object> entries = new ArrayList<>(List.of(mapper.readValue(indexJson, Object[].class)));
        entries.removeIf(o -> o instanceof Map<?, ?> e
                && slug.equalsIgnoreCase(e.get("owner") + "/" + e.get("repo")));
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries);
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
