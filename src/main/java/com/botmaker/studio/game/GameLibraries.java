package com.botmaker.studio.game;

import java.util.List;
import java.util.Optional;

/**
 * The platform-key → {@link GameLibraryProvider} lookup, so a caller holding only a persisted launch spec
 * ({@code "heroic:43d4…"}) can resolve it back to the installed game — its title and its cover art — without
 * knowing which scanner owns that key. Scanning reads JSON/VDF off disk, so call {@link #findGame} off the FX
 * thread.
 */
public final class GameLibraries {

    private GameLibraries() {}

    /** One instance of each scanner, in picker order. Scanners are stateless and cheap to construct. */
    public static List<GameLibraryProvider> all() {
        return List.of(new SteamLibraryScanner(), new EpicLibraryScanner(), new HeroicLibraryScanner(),
                new FaugusLibraryScanner());
    }

    /** The provider whose {@link GameLibraryProvider#platform()} equals {@code platform}, if any. */
    public static Optional<GameLibraryProvider> forPlatform(String platform) {
        if (platform == null || platform.isBlank()) return Optional.empty();
        return all().stream().filter(p -> platform.equals(p.platform())).findFirst();
    }

    /** The installed game a {@code <platform>:<id>} pair refers to, or empty. Blocking; never throws. */
    public static Optional<InstalledGame> findGame(String platform, String id) {
        try {
            return forPlatform(platform).flatMap(p -> p.findById(id));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
