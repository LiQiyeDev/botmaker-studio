package com.botmaker.studio.sharing;

import com.botmaker.studio.project.launch.SupportedTargets;
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
 * @param launchTargets the launch kinds the author declares the bot works on — so a browser can tell before
 *                      installing whether their platform is one of them. Absent in every entry written before
 *                      this field existed, which reads as {@link SupportedTargets#any()}: "the author never
 *                      said", never "works on nothing".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                           SupportedTargets launchTargets) {

    /**
     * The one reserved tag: an entry carrying it is a <b>starting template</b> rather than a bot to install.
     *
     * <p>A template is a published bot — same repo, same release, same install path — and that is the whole
     * design. Studio composes one starting point of its own (a blank project: a pom, an empty {@code src}
     * tree and a {@code main} that prints a line), and every richer one is somebody's published project that
     * New Project downloads and renames. So a new kind of starting point needs no Studio release, and the
     * people who write bots are the people who write the templates.
     *
     * <p>It is a value in the existing free-form {@code tags} list rather than a field of its own, which
     * costs one thing worth stating: an author is free to tag an ordinary bot {@code "template"} and it will
     * be offered as one. That is a curation problem in a curated index, not a correctness one — the entry is
     * still a real project that unpacks and compiles.
     */
    public static final String TEMPLATE_TAG = "template";

    public GalleryEntry {
        name = name == null ? "" : name.trim();
        owner = owner == null ? "" : owner.trim();
        repo = repo == null ? "" : repo.trim();
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        launchTargets = launchTargets == null ? SupportedTargets.any() : launchTargets;
    }

    public String htmlUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** {@code owner/repo}, the stable identity of a bot. */
    public String slug() {
        return owner + "/" + repo;
    }

    /** True when this entry is a starting template — see {@link #TEMPLATE_TAG}. */
    public boolean isTemplate() {
        return tags.stream().anyMatch(TEMPLATE_TAG::equalsIgnoreCase);
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
