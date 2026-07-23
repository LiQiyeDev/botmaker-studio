package com.botmaker.studio.game;

import com.botmaker.shared.launch.HeroicLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link GameLibraryProvider} for the <a href="https://heroicgameslauncher.com/">Heroic Games Launcher</a> —
 * the practical way to run Epic, GOG and sideloaded games on Linux, where the native store clients don't run.
 *
 * <p>The reading is {@link HeroicLibrary}'s, in shared: the launch stack needs the very same records to decide
 * whether a {@code heroic:} target is already running (its app name appears nowhere in a running game's
 * command line — the executable and title do), and a second parser here would drift from that one. What stays
 * Studio's is the mapping onto {@link InstalledGame} and the artwork lookup, which only a picker cares about.
 *
 * <p>The {@link InstalledGame#id() id} is Heroic's <em>app name</em> — the launch token handed to
 * {@code Game.launchHeroic(...)} via the {@code heroic://launch/<appName>} URL.
 *
 * <p>{@link InstalledGame#artwork()} comes from {@code <configRoot>/icons/<appName>.<ext>}, which Heroic
 * populates when it caches a game's art (e.g. {@code icons/43d4ef20fcb94eb39a864d13164fe3ca.jpg}). An earlier
 * version of this javadoc claimed Heroic keeps no stable local art path and hard-coded {@code null}, which is
 * why every Heroic tile rendered as a bare placeholder.
 */
public final class HeroicLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = "heroic";

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Heroic (Epic/GOG)"; }

    /** All Heroic-installed games (Epic + GOG + sideloaded), deduplicated by app name and sorted by title. */
    @Override
    public List<InstalledGame> installedGames() {
        List<InstalledGame> games = new ArrayList<>();
        for (HeroicLibrary.Game game : HeroicLibrary.games().values()) {
            games.add(new InstalledGame(PLATFORM, game.appName(), game.title(), artworkFor(game.appName())));
        }
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(games);
    }

    /**
     * The cached cover Heroic wrote for {@code appName} under {@code <root>/icons/}, or null if it hasn't
     * cached one. The extension varies with what the store served, so each known one is probed in turn — in
     * every config root {@link HeroicLibrary} read, so a Flatpak install's art is found too.
     */
    private static Path artworkFor(String appName) {
        for (Path root : HeroicLibrary.configRoots()) {
            for (String ext : List.of("jpg", "jpeg", "png", "webp", "ico")) {
                Path candidate = root.resolve("icons").resolve(appName + '.' + ext);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }
}
