package com.botmaker.studio.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link GameLibraryProvider} for the <a href="https://heroicgameslauncher.com/">Heroic Games Launcher</a> —
 * the practical way to run Epic, GOG and sideloaded games on Linux, where the native store clients don't run.
 * Discovers installed games by reading Heroic's on-disk config (no login, no Web API, no network), mirroring
 * {@link EpicLibraryScanner}.
 *
 * <p>The {@link InstalledGame#id() id} is Heroic's <em>app name</em> — the launch token handed to
 * {@code Game.launchHeroic(...)} via the {@code heroic://launch/<appName>} URL. Sources read (best-effort, any
 * missing/unparseable file simply skipped):
 * <ul>
 *   <li>Epic — {@code legendaryConfig/legendary/installed.json} (an object keyed by app name).</li>
 *   <li>GOG — {@code gog_store/installed.json} (installed list) with titles from
 *       {@code store_cache/gog_library.json}.</li>
 *   <li>Sideloaded — {@code sideload_apps/library.json}.</li>
 * </ul>
 * Both the native ({@code ~/.config/heroic}) and Flatpak
 * ({@code ~/.var/app/com.heroicgameslauncher.hgl/config/heroic}) config roots are checked. Linux-only in
 * practice; returns empty elsewhere. Heroic keeps no stable local portrait art path, so
 * {@link InstalledGame#artwork()} is always {@code null} and the picker falls back to a placeholder tile.
 */
public final class HeroicLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = "heroic";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Heroic (Epic/GOG)"; }

    /** All Heroic-installed games (Epic + GOG + sideloaded), deduplicated by app name and sorted by title. */
    @Override
    public List<InstalledGame> installedGames() {
        Path root = configRoot();
        if (root == null) return List.of();

        Map<String, InstalledGame> byId = new LinkedHashMap<>();
        addEpic(root, byId);
        addGog(root, byId);
        addSideloaded(root, byId);

        List<InstalledGame> games = new ArrayList<>(byId.values());
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return games;
    }

    /** The first existing Heroic config root (native, then Flatpak), or null if Heroic isn't installed. */
    private static Path configRoot() {
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) return null;
        List<Path> candidates = List.of(
                Path.of(home, ".config", "heroic"),
                Path.of(home, ".var", "app", "com.heroicgameslauncher.hgl", "config", "heroic"));
        for (Path c : candidates) {
            if (Files.isDirectory(c)) return c;
        }
        return null;
    }

    /** Epic games: {@code legendaryConfig/legendary/installed.json} is an object keyed by app name. */
    private static void addEpic(Path root, Map<String, InstalledGame> byId) {
        JsonNode installed = readTree(root.resolve("legendaryConfig/legendary/installed.json"));
        if (installed == null || !installed.isObject()) return;
        installed.fields().forEachRemaining(entry -> {
            JsonNode game = entry.getValue();
            String appName = text(game, "app_name", entry.getKey());
            if (appName == null || appName.isBlank()) return;
            String title = text(game, "title", appName);
            byId.putIfAbsent(appName, new InstalledGame(PLATFORM, appName, title, null));
        });
    }

    /** GOG games: an installed list keyed by appName, with titles resolved from the library cache. */
    private static void addGog(Path root, Map<String, InstalledGame> byId) {
        JsonNode installedDoc = readTree(root.resolve("gog_store/installed.json"));
        if (installedDoc == null) return;
        JsonNode installed = installedDoc.path("installed");
        if (!installed.isArray()) return;

        Map<String, String> titles = gogTitles(root);
        for (JsonNode game : installed) {
            String appName = text(game, "appName", null);
            if (appName == null || appName.isBlank()) continue;
            String title = titles.getOrDefault(appName, appName);
            byId.putIfAbsent(appName, new InstalledGame(PLATFORM, appName, title, null));
        }
    }

    /** appName → title from {@code store_cache/gog_library.json} (best-effort; empty map if unavailable). */
    private static Map<String, String> gogTitles(Path root) {
        Map<String, String> titles = new HashMap<>();
        JsonNode lib = readTree(root.resolve("store_cache/gog_library.json"));
        if (lib == null) return titles;
        JsonNode games = lib.isArray() ? lib : lib.path("games");
        if (!games.isArray()) return titles;
        for (JsonNode game : games) {
            String appName = text(game, "app_name", text(game, "appName", null));
            String title = text(game, "title", null);
            if (appName != null && title != null) titles.put(appName, title);
        }
        return titles;
    }

    /** Sideloaded apps: {@code sideload_apps/library.json} carries a {@code games} array. */
    private static void addSideloaded(Path root, Map<String, InstalledGame> byId) {
        JsonNode doc = readTree(root.resolve("sideload_apps/library.json"));
        if (doc == null) return;
        JsonNode games = doc.path("games");
        if (!games.isArray()) return;
        for (JsonNode game : games) {
            String appName = text(game, "app_name", text(game, "appName", null));
            if (appName == null || appName.isBlank()) continue;
            String title = text(game, "title", appName);
            byId.putIfAbsent(appName, new InstalledGame(PLATFORM, appName, title, null));
        }
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
