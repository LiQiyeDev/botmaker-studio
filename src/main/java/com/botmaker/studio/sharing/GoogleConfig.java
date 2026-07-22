package com.botmaker.studio.sharing;

/**
 * Static configuration for "Sign in with Google" (OAuth 2.0 device flow).
 *
 * <p>{@link #OAUTH_CLIENT_ID} is maintainer-owned: register an OAuth client of type <em>TV and Limited Input
 * devices</em> in the Google Cloud console and paste its (public) client id here.
 *
 * <p><b>Nothing surfaces this today.</b> The client id is blank, so {@link GoogleAuth#isConfigured()} is false
 * and {@code ui/app/GoogleAccountBar} could only ever render itself invisible — it is therefore no longer
 * instantiated anywhere ({@code ProjectSelectionScreen} used to build one). The only Google affordance in the
 * UI is the editor's round <b>G</b> button, deliberately <em>disabled</em> with the reason as its tooltip
 * ("reserved for future Tailscale/Drive features"). The device-flow plumbing here and in {@link GoogleAuth} is
 * finished and correct; it is waiting on a registered client id <em>and</em> a backend that does something with
 * the identity. Wiring one means: fill in the two constants below and mount a {@code GoogleAccountBar}.
 */
public final class GoogleConfig {

    private GoogleConfig() {}

    /** Public OAuth client id (device-flow / limited-input). Blank until the maintainer registers one. */
    public static final String OAUTH_CLIENT_ID = "";

    /**
     * OAuth client secret. Google requires it in the device-flow token exchange even for limited-input clients
     * (where it is not truly secret). Blank until the maintainer registers a client; sent only when non-blank.
     */
    public static final String OAUTH_CLIENT_SECRET = "";

    public static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    public static final String ACCESS_TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    /** Minimal identity scopes — enough to greet the user by email; nothing is stored server-side. */
    public static final String SCOPE = "openid email profile";

    public static boolean isConfigured() {
        return !OAUTH_CLIENT_ID.isBlank();
    }
}
