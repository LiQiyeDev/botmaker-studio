package com.botmaker.studio.util;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;

/**
 * Opens a URL in the user's default browser, robustly across platforms.
 *
 * <p>{@link Desktop#browse(URI)} is tried first but is a silent no-op under many Linux desktop
 * environments (and is unsupported headless), so this falls back to the platform's URL opener
 * ({@code xdg-open} / {@code open} / {@code rundll32}). Best-effort: a failure is logged, never thrown.
 */
public final class BrowserLauncher {

    private BrowserLauncher() {}

    public static void open(String url) {
        if (url == null || url.isBlank()) return;
        if (tryDesktop(url)) return;
        if (tryNativeOpener(url)) return;
        System.err.println("Could not open browser for " + url + " — open it manually.");
    }

    private static boolean tryDesktop(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception e) {
            System.err.println("Desktop.browse failed for " + url + ": " + e.getMessage());
        }
        return false;
    }

    private static boolean tryNativeOpener(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> command;
        if (os.contains("win")) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            command = List.of("open", url);
        } else {
            command = List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(command).inheritIO().start();
            return true;
        } catch (Exception e) {
            System.err.println("Native browser opener failed for " + url + ": " + e.getMessage());
            return false;
        }
    }
}
