package com.botmaker.sharing;

/**
 * Static configuration for the federated bot-sharing gallery.
 *
 * <p>Two values are maintainer-owned and must be filled in before publishing / the gallery work
 * end-to-end (see the project plan):
 * <ul>
 *   <li>{@link #OAUTH_CLIENT_ID} — register a GitHub OAuth App with device flow enabled and paste its
 *       (public) client id here. While blank, publishing degrades gracefully ("publishing not configured").</li>
 *   <li>{@link #INDEX_OWNER}/{@link #INDEX_REPO} — the curated index repo holding {@code index.json}. While
 *       it doesn't exist, {@link GitHubGallery#browse()} simply returns an empty catalog.</li>
 * </ul>
 * Browsing / installing public bots needs no GitHub account; only publishing does.
 */
public final class GitHubConfig {

    private GitHubConfig() {}

    public static final String OAUTH_CLIENT_ID = "Ov23lizugeHUiWl7WZhQ";

    public static final String INDEX_OWNER = "LiQiyeDev";
    public static final String INDEX_REPO = "botmaker-gallery";

    /** The Studio's own repo, whose GitHub Releases host the app installers (used by the in-app updater). */
    public static final String STUDIO_OWNER = "LiQiyeDev";
    public static final String STUDIO_REPO = "BotMaker-Studio";
    public static final String INDEX_BRANCH = "main";
    public static final String INDEX_PATH = "index.json";

    /** Discovery topic also applied to published repos (secondary signal; the index repo is authoritative). */
    public static final String TOPIC = "botmaker-bot";

    public static final String API_BASE = "https://api.github.com";
    public static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    public static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    public static final String SCOPE = "public_repo";

    /** Raw (CDN) URL of the curated catalog — no API rate limit, no auth. */
    public static String indexRawUrl() {
        return "https://raw.githubusercontent.com/"
                + INDEX_OWNER + "/" + INDEX_REPO + "/" + INDEX_BRANCH + "/" + INDEX_PATH;
    }

    /** {@code https://codeload.github.com/{owner}/{repo}/zip/refs/tags/{tag}} (public, unauthenticated). */
    public static String archiveUrl(String owner, String repo, String tag) {
        return "https://codeload.github.com/" + owner + "/" + repo + "/zip/refs/tags/" + tag;
    }

    public static boolean isPublishingConfigured() {
        return !OAUTH_CLIENT_ID.isBlank();
    }

    public static boolean isGalleryConfigured() {
        return !INDEX_OWNER.isBlank() && !INDEX_REPO.isBlank();
    }
}
