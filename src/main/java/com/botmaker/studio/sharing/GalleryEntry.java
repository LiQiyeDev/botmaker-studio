package com.botmaker.studio.sharing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One entry in the curated gallery {@code index.json}: a pointer to a bot's own GitHub repo. Version /
 * release information is intentionally NOT stored here — it is fetched live from the author's repo
 * ({@link GitHubGallery#latestReleaseTag}) so version bumps never require editing the index.
 *
 * @param name        the bot's project name (PascalCase, as created in Studio) — also the install dir name
 * @param owner       GitHub repo owner (login)
 * @param repo        GitHub repo name
 * @param description short human description
 * @param tags        optional free-form tags for filtering
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GalleryEntry(String name, String owner, String repo, String description, List<String> tags) {

    public GalleryEntry {
        name = name == null ? "" : name.trim();
        owner = owner == null ? "" : owner.trim();
        repo = repo == null ? "" : repo.trim();
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public String htmlUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** {@code owner/repo}, the stable identity of a bot. */
    public String slug() {
        return owner + "/" + repo;
    }

    /** True if the entry matches a free-text query against name / description / owner / tags. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        if (name.toLowerCase().contains(q)) return true;
        if (description.toLowerCase().contains(q)) return true;
        if (owner.toLowerCase().contains(q)) return true;
        return tags.stream().anyMatch(t -> t.toLowerCase().contains(q));
    }
}
