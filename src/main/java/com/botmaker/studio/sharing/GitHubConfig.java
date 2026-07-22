package com.botmaker.studio.sharing;

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

    /** Umbrella repo that receives in-app bug reports (Help ▸ Report Issue…). */
    public static final String ISSUE_OWNER = "LiQiyeDev";
    public static final String ISSUE_REPO = "botmaker";
    public static final String INDEX_BRANCH = "main";
    public static final String INDEX_PATH = "index.json";

    /** Discovery topic also applied to published repos (secondary signal; the index repo is authoritative). */
    public static final String TOPIC = "botmaker-bot";

    public static final String API_BASE = "https://api.github.com";
    public static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    public static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    /**
     * Full {@code repo} rather than {@code public_repo}: the VCS panel's Push button creates and pushes to a
     * <em>private</em> backup repo, which {@code public_repo} cannot do. Tokens issued before this change keep
     * their narrower scope, so the private-repo calls translate the resulting 403/404 into a "sign out and back
     * in" hint rather than a raw API error.
     */
    public static final String SCOPE = "repo";

    /** Raw (CDN) URL of the curated catalog — no API rate limit, no auth. */
    public static String indexRawUrl() {
        return "https://raw.githubusercontent.com/"
                + INDEX_OWNER + "/" + INDEX_REPO + "/" + INDEX_BRANCH + "/" + INDEX_PATH;
    }

    /** {@code https://codeload.github.com/{owner}/{repo}/zip/refs/tags/{tag}} (public, unauthenticated). */
    public static String archiveUrl(String owner, String repo, String tag) {
        return "https://codeload.github.com/" + owner + "/" + repo + "/zip/refs/tags/" + tag;
    }

    /** REST endpoint for creating an issue on the umbrella repo (used with an authenticated token). */
    public static String issuesApiUrl() {
        return API_BASE + "/repos/" + ISSUE_OWNER + "/" + ISSUE_REPO + "/issues";
    }

    /** Browser URL of the umbrella repo's "New Issue" page (no auth handled by the app; the browser session is). */
    public static String newIssueBrowserUrl() {
        return "https://github.com/" + ISSUE_OWNER + "/" + ISSUE_REPO + "/issues/new";
    }

    public static boolean isPublishingConfigured() {
        return !OAUTH_CLIENT_ID.isBlank();
    }

    public static boolean isGalleryConfigured() {
        return !INDEX_OWNER.isBlank() && !INDEX_REPO.isBlank();
    }
}
