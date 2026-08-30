package com.botmaker.studio.config;

import com.botmaker.shared.config.CacheDirs;

import java.nio.file.Path;

/**
 * Studio's caches — shared's cache root, with Studio's own subdirectories under it.
 *
 * <p>The three-branch platform switch that used to be here moved to {@link CacheDirs} on 2026-08-30, when
 * {@code EmulatorAppCache} became shared's: a cache written by a plugin and one written by Studio have to
 * agree about where the cache <em>is</em>, and two copies of that switch is how a user ends up with two of
 * them.
 */
public class BotMakerDirs {

    public static Path getCacheDir() {
        return CacheDirs.cacheRoot();
    }
}
