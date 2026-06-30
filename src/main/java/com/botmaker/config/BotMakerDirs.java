package com.botmaker.config;

import java.nio.file.Path;

public class BotMakerDirs {

    public static Path getCacheDir() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return Path.of(System.getenv("LOCALAPPDATA"), "BotMaker", ".cache");
        } else if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Caches", "botmaker");
        } else {
            String xdgCache = System.getenv("XDG_CACHE_HOME");
            if (xdgCache != null && !xdgCache.isEmpty()) {
                return Path.of(xdgCache, "botmaker");
            }
            return Path.of(System.getProperty("user.home"), ".cache", "botmaker");
        }
    }
}