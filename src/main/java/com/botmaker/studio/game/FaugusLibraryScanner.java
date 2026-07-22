package com.botmaker.studio.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link GameLibraryProvider} for <a href="https://faugus.github.io/">Faugus Launcher</a> — how non-Steam
 * Windows launchers and games (Battle.net, the EA App, HoYoPlay, …) run on Linux under umu/Proton. Discovers
 * entries by reading Faugus's own library file (no login, no Web API, no network), mirroring
 * {@link HeroicLibraryScanner}.
 *
 * <p>The library is {@code <configRoot>/games.json}: a JSON <em>array</em> whose entries carry {@code gameid}
 * (the launch token handed to {@code Game.launchFaugus(...)}, matched exactly by Faugus's runner),
 * {@code title}, {@code cover}, {@code icon} and {@code hidden}. Hidden entries are skipped — they are hidden
 * in Faugus's own grid. Both the native ({@code ~/.local/share/faugus-launcher}) and Flatpak
 * ({@code ~/.var/app/io.github.Faugus.faugus-launcher/data/faugus-launcher}) roots are checked.
 *
 * <p>{@link InstalledGame#artwork()} is the entry's {@code cover} when set, else its {@code icon}; both are
 * absolute paths Faugus already wrote to disk, so the picker gets a preview with no extra work.
 */
public final class FaugusLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = "faugus";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Faugus (Proton/Wine)"; }

    /** All non-hidden Faugus entries, deduplicated by game id and sorted by title. */
    @Override
    public List<InstalledGame> installedGames() {
        Path root = configRoot();
        if (root == null) return List.of();

        JsonNode games = readTree(root.resolve("games.json"));
        if (games == null || !games.isArray()) return List.of();

        Map<String, InstalledGame> byId = new LinkedHashMap<>();
        for (JsonNode game : games) {
            if (game.path("hidden").asBoolean(false)) continue;
            String gameId = text(game, "gameid", null);
            if (gameId == null || gameId.isBlank()) continue;
            String title = text(game, "title", gameId);
            byId.putIfAbsent(gameId, new InstalledGame(PLATFORM, gameId, title, artworkFor(game)));
        }

        List<InstalledGame> result = new ArrayList<>(byId.values());
        result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    /** The first existing Faugus config root (native, then Flatpak), or null if Faugus isn't installed. */
    private static Path configRoot() {
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) return null;
        List<Path> candidates = List.of(
                Path.of(home, ".local", "share", "faugus-launcher"),
                Path.of(home, ".var", "app", "io.github.Faugus.faugus-launcher", "data", "faugus-launcher"));
        for (Path c : candidates) {
            if (Files.isDirectory(c)) return c;
        }
        return null;
    }

    /** The entry's cover, else its icon — whichever absolute path actually exists; null when neither does. */
    private static Path artworkFor(JsonNode game) {
        for (String field : List.of("cover", "icon")) {
            String value = text(game, field, null);
            if (value == null || value.isBlank()) continue;
            Path path = Path.of(value);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    /** Reads and parses a JSON file, or null when it is missing/unreadable/unparseable. Never throws. */
    private static JsonNode readTree(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            return MAPPER.readTree(file.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }
}
