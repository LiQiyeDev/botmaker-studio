package com.botmaker.studio.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link GameLibraryProvider} for the Epic Games Launcher: discovers installed games by reading the
 * launcher's on-disk manifests — no login, no Web API, no network. Mirrors {@link SteamLibraryScanner}.
 *
 * <p>Epic writes one JSON manifest per installed game under
 * {@code %ProgramData%\Epic\EpicGamesLauncher\Data\Manifests\*.item}. Each manifest carries the game's
 * {@code AppName} (the launch token handed to {@code Game.launchEpic(...)} via the
 * {@code com.epicgames.launcher://apps/<AppName>?action=launch} URL) and {@code DisplayName} (the title).
 * Only genuinely-installed games have a manifest, so this is exactly the launchable set.
 *
 * <p>Every step is best-effort: any missing directory / unparseable manifest is skipped and an empty list
 * is the worst case — this never throws. Epic keeps no local portrait cover art in a stable path, so
 * {@link InstalledGame#artwork()} is always {@code null} here and the picker falls back to a placeholder
 * tile. Windows-only in practice (the launcher only ships on Windows/macOS); returns empty elsewhere.
 */
public final class EpicLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = "epic";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Epic Games"; }

    /** All locally-installed Epic games, deduplicated by AppName and sorted by title. Never throws. */
    @Override
    public List<InstalledGame> installedGames() {
        Map<String, InstalledGame> byId = new LinkedHashMap<>();
        try {
            Path manifests = manifestsDir();
            if (manifests == null) return List.of();
            try (Stream<Path> items = Files.list(manifests)) {
                items.filter(EpicLibraryScanner::isManifest)
                        .forEach(item -> parseManifest(item)
                                .ifPresent(g -> byId.putIfAbsent(g.id(), g)));
            }
        } catch (Exception ignored) {
            // never propagate: the picker degrades to free-text entry
        }
        List<InstalledGame> games = new ArrayList<>(byId.values());
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return games;
    }

    private static boolean isManifest(Path p) {
        return p.getFileName().toString().endsWith(".item");
    }

    /** The Epic manifests directory for this OS, or {@code null} if the launcher isn't installed. */
    private static Path manifestsDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String programData = System.getenv("PROGRAMDATA");
            if (programData != null && !programData.isBlank()) {
                candidates.add(Path.of(programData, "Epic", "EpicGamesLauncher", "Data", "Manifests"));
            }
            candidates.add(Path.of("C:\\ProgramData\\Epic\\EpicGamesLauncher\\Data\\Manifests"));
        } else if (os.contains("mac")) {
            candidates.add(Path.of(System.getProperty("user.home", ""),
                    "Library", "Application Support", "Epic", "EpicGamesLauncher", "Data", "Manifests"));
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c)) return c;
        }
        return null;
    }

    /** Reads {@code AppName} + {@code DisplayName} from one {@code .item} manifest. */
    private static java.util.Optional<InstalledGame> parseManifest(Path item) {
        try {
            JsonNode root = MAPPER.readTree(item.toFile());
            String appName = text(root, "AppName");
            if (appName == null || appName.isBlank()) return java.util.Optional.empty();
            String name = text(root, "DisplayName");
            if (name == null || name.isBlank()) name = appName;
            return java.util.Optional.of(new InstalledGame(PLATFORM, appName, name, null));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
