package com.botmaker.studio.util;

import com.botmaker.shared.launch.UriLauncher;

/**
 * Opens a URL in the user's default browser.
 *
 * <p>The opening itself — {@code Desktop.browse} with a fall back to the platform's URL opener
 * ({@code xdg-open} / {@code open} / {@code rundll32}) — lives in shared's {@link UriLauncher}, because the SDK
 * needs exactly the same thing for {@code steam://} and friends and the two copies each carried a javadoc
 * naming the other. This wrapper adds only Studio's contract: best-effort, and a failure the user can act on
 * printed rather than thrown.
 */
public final class BrowserLauncher {

    private BrowserLauncher() {}

    public static void open(String url) {
        if (url == null || url.isBlank()) return;
        if (UriLauncher.open(url)) return;
        System.err.println("Could not open browser for " + url + " — open it manually.");
    }
}
