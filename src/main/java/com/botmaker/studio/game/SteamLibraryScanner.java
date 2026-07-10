package com.botmaker.studio.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link GameLibraryProvider} for Steam: discovers installed games by reading Steam's on-disk metadata —
 * no login, no Web API key, no network. This is exactly the set of games that can be launched (a
 * non-installed appId would only make Steam offer to install it), so it is the right source for the
 * "Launch Steam Game" picker.
 *
 * <p>Reads {@code steamapps/libraryfolders.vdf} to find every library folder, then each folder's
 * {@code appmanifest_<appid>.acf} for the game's {@code appid} + {@code name}, and resolves each game's
 * cover art from the local library cache ({@code appcache/librarycache/<appid>/library_600x900.jpg}).
 * Every step is best-effort: any missing file / parse failure is skipped and an empty list is the worst
 * case — this never throws.
 */
public final class SteamLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = "steam";

    // "key"   "value"  — VDF is a flat sequence of quoted key/value pairs; we only need specific keys.
    private static final Pattern PATH_ENTRY = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
    private static final Pattern APPID_ENTRY = Pattern.compile("\"appid\"\\s+\"(\\d+)\"");
    private static final Pattern NAME_ENTRY = Pattern.compile("\"name\"\\s+\"([^\"]*)\"");

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Steam"; }

    /** All locally-installed Steam games, deduplicated by appId and sorted by name. Never throws. */
    @Override
    public List<InstalledGame> installedGames() {
        Map<String, InstalledGame> byId = new LinkedHashMap<>();
        try {
            Path root = steamRoot();
            if (root == null) return List.of();
            for (Path library : libraryFolders(root)) {
                Path steamapps = library.resolve("steamapps");
                if (!Files.isDirectory(steamapps)) continue;
                try (Stream<Path> manifests = Files.list(steamapps)) {
                    manifests.filter(SteamLibraryScanner::isAppManifest)
                            .forEach(acf -> parseManifest(acf, root)
                                    .ifPresent(g -> byId.putIfAbsent(g.id(), g)));
                } catch (IOException ignored) {
                    // unreadable library folder — skip it
                }
            }
        } catch (Exception ignored) {
            // never propagate: the picker degrades to free-text entry
        }
        List<InstalledGame> games = new ArrayList<>(byId.values());
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return games;
    }

    /** The installed game with this appId (name + local cover art), or empty if it isn't installed. */
    public java.util.Optional<InstalledGame> findById(String appId) {
        if (appId == null || appId.isBlank()) return java.util.Optional.empty();
        String id = appId.trim();
        return installedGames().stream().filter(g -> id.equals(g.id())).findFirst();
    }

    private static boolean isAppManifest(Path p) {
        String n = p.getFileName().toString();
        return n.startsWith("appmanifest_") && n.endsWith(".acf");
    }

    /** Every Steam library folder (the root install plus any extra libraries from libraryfolders.vdf). */
    private static List<Path> libraryFolders(Path root) {
        List<Path> folders = new ArrayList<>();
        folders.add(root);
        for (String vdfName : List.of("steamapps/libraryfolders.vdf", "config/libraryfolders.vdf")) {
            Path vdf = root.resolve(vdfName);
            if (!Files.isRegularFile(vdf)) continue;
            String text = readString(vdf);
            if (text == null) continue;
            Matcher m = PATH_ENTRY.matcher(text);
            while (m.find()) {
                Path p = Path.of(m.group(1));
                if (Files.isDirectory(p) && !folders.contains(p)) folders.add(p);
            }
        }
        return folders;
    }

    /** Locates the Steam install root for the current OS, or {@code null} if none is found. */
    private static Path steamRoot() {
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase();
        List<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String reg = queryWindowsSteamPath();
            if (reg != null) candidates.add(Path.of(reg));
            candidates.add(Path.of("C:\\Program Files (x86)\\Steam"));
            candidates.add(Path.of("C:\\Program Files\\Steam"));
        } else if (os.contains("mac")) {
            candidates.add(Path.of(home, "Library", "Application Support", "Steam"));
        } else {
            candidates.add(Path.of(home, ".local", "share", "Steam"));
            candidates.add(Path.of(home, ".steam", "steam"));
            candidates.add(Path.of(home, ".steam", "root"));
            candidates.add(Path.of(home, ".var", "app", "com.valvesoftware.Steam", "data", "Steam")); // Flatpak
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c)) return c;
        }
        return null;
    }

    /** Best-effort {@code reg query} for the Steam install path on Windows; {@code null} on any failure. */
    private static String queryWindowsSteamPath() {
        try {
            Process p = new ProcessBuilder(
                    "reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {
            // registry unavailable — fall back to default install locations
        }
        return null;
    }

    private static java.util.Optional<InstalledGame> parseManifest(Path acf, Path steamRoot) {
        String text = readString(acf);
        if (text == null) return java.util.Optional.empty();
        Matcher idM = APPID_ENTRY.matcher(text);
        String appId = idM.find() ? idM.group(1) : appIdFromFileName(acf);
        if (appId == null) return java.util.Optional.empty();
        Matcher nameM = NAME_ENTRY.matcher(text);
        String name = nameM.find() ? nameM.group(1) : appId;
        if (name.isBlank()) name = appId;
        return java.util.Optional.of(new InstalledGame(PLATFORM, appId, name, coverArt(steamRoot, appId)));
    }

    /**
     * Local cover image for {@code appId} from Steam's library cache, or {@code null}. Prefers the portrait
     * {@code library_600x900.jpg}, falling back to the landscape {@code header.jpg}. No network fetch.
     */
    private static Path coverArt(Path steamRoot, String appId) {
        Path dir = steamRoot.resolve("appcache").resolve("librarycache").resolve(appId);
        for (String file : List.of("library_600x900.jpg", "header.jpg", "library_header.jpg")) {
            Path img = dir.resolve(file);
            if (Files.isRegularFile(img)) return img;
        }
        return null;
    }

    /** Extracts the appId from an {@code appmanifest_<appid>.acf} file name as a fallback. */
    private static String appIdFromFileName(Path acf) {
        String n = acf.getFileName().toString();
        Matcher m = Pattern.compile("appmanifest_(\\d+)\\.acf").matcher(n);
        return m.find() ? m.group(1) : null;
    }

    private static String readString(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return null;
        }
    }
}
