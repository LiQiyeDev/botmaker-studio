package com.botmaker.studio.game;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio remainder MISSING 3 — the {@code game/} scanners against fixture directories.</b> Gates
 * <b>SC6</b>.
 *
 * <p>510 lines at 0.0%: four parsers over four on-disk formats, feeding the Launch Target picker. Every one
 * of them is best-effort by design — a missing file, an unreadable directory or a malformed manifest is
 * skipped and an empty list is the worst case — so a parser that quietly stopped matching would look
 * exactly like "you have no games installed", on a machine full of them.
 *
 * <p>The three format-owning scanners each grew a package-private overload taking their root
 * ({@code gamesUnder} / {@code gamesIn}) so a fixture tree can stand in for an install; the public
 * {@code installedGames()} still locates the root from {@code user.home} and the registry, and delegates.
 * {@code HeroicLibraryScanner} is not here: its reading belongs to shared's {@code HeroicLibrary}, so that
 * this repo could not fork the parser was the point of putting it there.
 */
class GameLibraryScannerTest {

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    // =====================================================================
    // Steam — libraryfolders.vdf + appmanifest_<appid>.acf
    // =====================================================================

    /** A Steam root with one library folder and the given manifests already in {@code steamapps/}. */
    private static Path steamRoot(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("Steam");
        Files.createDirectories(root.resolve("steamapps"));
        return root;
    }

    private static String appManifest(String appId, String name) {
        return """
                "AppState"
                {
                    "appid"    "%s"
                    "name"     "%s"
                    "installdir"   "Whatever"
                }
                """.formatted(appId, name);
    }

    @Test
    void aSteamManifestYieldsItsAppIdAndTitle(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        write(root.resolve("steamapps/appmanifest_570.acf"), appManifest("570", "Dota 2"));

        List<InstalledGame> games = SteamLibraryScanner.gamesUnder(root);

        assertEquals(1, games.size());
        assertEquals("steam", games.getFirst().platform());
        assertEquals("570", games.getFirst().id(), "the appId is the launch token");
        assertEquals("Dota 2", games.getFirst().name());
    }

    @Test
    void gamesComeBackSortedByTitleRegardlessOfDirectoryOrder(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        write(root.resolve("steamapps/appmanifest_1.acf"), appManifest("1", "Zeta"));
        write(root.resolve("steamapps/appmanifest_2.acf"), appManifest("2", "alpha"));

        assertEquals(List.of("alpha", "Zeta"), SteamLibraryScanner.gamesUnder(root).stream()
                        .map(InstalledGame::name).toList(),
                "the picker is a list a human reads — and the sort is case-insensitive");
    }

    @Test
    void aSecondLibraryFolderIsFollowedOutOfTheVdf(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        Path elsewhere = tmp.resolve("BigDisk");
        Files.createDirectories(elsewhere.resolve("steamapps"));
        write(root.resolve("steamapps/appmanifest_1.acf"), appManifest("1", "On The Root"));
        write(elsewhere.resolve("steamapps/appmanifest_2.acf"), appManifest("2", "On The Big Disk"));
        write(root.resolve("steamapps/libraryfolders.vdf"), """
                "libraryfolders"
                {
                    "0" { "path"  "%s" }
                }
                """.formatted(elsewhere.toString().replace("\\", "\\\\")));

        assertEquals(List.of("On The Big Disk", "On The Root"),
                SteamLibraryScanner.gamesUnder(root).stream().map(InstalledGame::name).toList(),
                "a game on a second drive is installed too; missing it is the whole bug class here");
    }

    @Test
    void theSameAppIdInTwoLibrariesIsReportedOnce(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        Path elsewhere = tmp.resolve("BigDisk");
        Files.createDirectories(elsewhere.resolve("steamapps"));
        write(root.resolve("steamapps/appmanifest_570.acf"), appManifest("570", "Dota 2"));
        write(elsewhere.resolve("steamapps/appmanifest_570.acf"), appManifest("570", "Dota 2"));
        write(root.resolve("steamapps/libraryfolders.vdf"),
                "\"libraryfolders\" { \"0\" { \"path\" \"" + elsewhere + "\" } }");

        assertEquals(1, SteamLibraryScanner.gamesUnder(root).size());
    }

    @Test
    void aLocalCoverImageIsFoundInSteamsOwnLibraryCache(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        write(root.resolve("steamapps/appmanifest_570.acf"), appManifest("570", "Dota 2"));
        Path cover = root.resolve("appcache/librarycache/570/library_600x900.jpg");
        write(cover, "not really a jpeg");

        assertEquals(cover, SteamLibraryScanner.gamesUnder(root).getFirst().artwork());
    }

    @Test
    void aGameWithNoCachedArtHasNoArtworkRatherThanAMissingPath(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        write(root.resolve("steamapps/appmanifest_570.acf"), appManifest("570", "Dota 2"));

        assertNull(SteamLibraryScanner.gamesUnder(root).getFirst().artwork(),
                "the picker draws a placeholder for null; a path that doesn't exist would draw broken");
    }

    /** The appId is recoverable from the file name, so a truncated manifest still gives a launchable entry. */
    @Test
    void aManifestWithNoAppIdKeyFallsBackToItsFileName(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        write(root.resolve("steamapps/appmanifest_570.acf"), "\"AppState\" { \"name\" \"Dota 2\" }");

        assertEquals("570", SteamLibraryScanner.gamesUnder(root).getFirst().id());
    }

    @Test
    void aManifestWithNoNameIsListedUnderItsAppId(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        write(root.resolve("steamapps/appmanifest_570.acf"), "\"AppState\" { \"appid\" \"570\" }");

        assertEquals("570", SteamLibraryScanner.gamesUnder(root).getFirst().name(),
                "a nameless tile is still launchable; dropping it would not be");
    }

    @Test
    void filesThatAreNotAppManifestsAreIgnored(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        write(root.resolve("steamapps/appmanifest_570.acf"), appManifest("570", "Dota 2"));
        write(root.resolve("steamapps/appworkshop_570.acf"), appManifest("999", "Workshop Content"));
        write(root.resolve("steamapps/readme.txt"), "hello");

        assertEquals(List.of("Dota 2"),
                SteamLibraryScanner.gamesUnder(root).stream().map(InstalledGame::name).toList());
    }

    @Test
    void aMissingOrEmptySteamRootIsAnEmptyListRatherThanAFailure(@TempDir Path tmp) throws IOException {
        assertEquals(List.of(), SteamLibraryScanner.gamesUnder(null));
        assertEquals(List.of(), SteamLibraryScanner.gamesUnder(tmp.resolve("nope")));
        assertEquals(List.of(), SteamLibraryScanner.gamesUnder(steamRoot(tmp)));
    }

    // =====================================================================
    // Epic — one JSON .item manifest per game
    // =====================================================================

    @Test
    void anEpicManifestYieldsItsAppNameAndDisplayName(@TempDir Path tmp) throws IOException {
        Path manifests = tmp.resolve("Manifests");
        write(manifests.resolve("abc.item"),
                "{\"AppName\":\"Fortnite\",\"DisplayName\":\"Fortnite\",\"InstallLocation\":\"C:\\\\Games\"}");

        List<InstalledGame> games = EpicLibraryScanner.gamesIn(manifests);

        assertEquals(1, games.size());
        assertEquals("epic", games.getFirst().platform());
        assertEquals("Fortnite", games.getFirst().id());
        assertNull(games.getFirst().artwork(), "Epic keeps no local art at a stable path");
    }

    @Test
    void anEpicManifestWithNoDisplayNameIsListedUnderItsAppName(@TempDir Path tmp) throws IOException {
        Path manifests = tmp.resolve("Manifests");
        write(manifests.resolve("abc.item"), "{\"AppName\":\"0a2d9f6a\"}");

        assertEquals("0a2d9f6a", EpicLibraryScanner.gamesIn(manifests).getFirst().name());
    }

    @Test
    void anUnparseableEpicManifestIsSkippedAndItsNeighboursSurvive(@TempDir Path tmp) throws IOException {
        Path manifests = tmp.resolve("Manifests");
        write(manifests.resolve("broken.item"), "{ this is not json");
        write(manifests.resolve("good.item"), "{\"AppName\":\"Good\",\"DisplayName\":\"Good Game\"}");

        assertEquals(List.of("Good Game"),
                EpicLibraryScanner.gamesIn(manifests).stream().map(InstalledGame::name).toList(),
                "one corrupt manifest must not empty the whole picker");
    }

    @Test
    void anEpicManifestWithoutAnAppNameIsNotLaunchableAndIsDropped(@TempDir Path tmp) throws IOException {
        Path manifests = tmp.resolve("Manifests");
        write(manifests.resolve("a.item"), "{\"DisplayName\":\"No Token\"}");

        assertEquals(List.of(), EpicLibraryScanner.gamesIn(manifests),
                "without an AppName there is nothing to launch — a tile that does nothing is worse than none");
    }

    @Test
    void aMissingEpicManifestsDirectoryIsAnEmptyList(@TempDir Path tmp) {
        assertEquals(List.of(), EpicLibraryScanner.gamesIn(null));
        assertEquals(List.of(), EpicLibraryScanner.gamesIn(tmp.resolve("nope")));
    }

    // =====================================================================
    // Faugus — a single games.json array
    // =====================================================================

    @Test
    void aFaugusEntryYieldsItsGameIdAndTitle(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("games.json"),
                "[{\"gameid\":\"abc123\",\"title\":\"Battle.net\",\"hidden\":false}]");

        List<InstalledGame> games = FaugusLibraryScanner.gamesUnder(tmp);

        assertEquals(1, games.size());
        assertEquals("faugus", games.getFirst().platform());
        assertEquals("abc123", games.getFirst().id());
        assertEquals("Battle.net", games.getFirst().name());
    }

    @Test
    void anEntryHiddenInFaugusOwnGridIsHiddenHereToo(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("games.json"), """
                [{"gameid":"a","title":"Shown"},
                 {"gameid":"b","title":"Hidden","hidden":true}]
                """);

        assertEquals(List.of("Shown"),
                FaugusLibraryScanner.gamesUnder(tmp).stream().map(InstalledGame::name).toList());
    }

    @Test
    void theCoverIsPreferredOverTheIconAndBothMustExistOnDisk(@TempDir Path tmp) throws IOException {
        Path cover = tmp.resolve("art/cover.png");
        Path icon = tmp.resolve("art/icon.png");
        write(cover, "x");
        write(icon, "x");
        write(tmp.resolve("games.json"), """
                [{"gameid":"a","title":"Both","cover":"%s","icon":"%s"},
                 {"gameid":"b","title":"Icon only","icon":"%s"},
                 {"gameid":"c","title":"Cover gone","cover":"%s"}]
                """.formatted(cover, icon, icon, tmp.resolve("art/missing.png")));

        List<InstalledGame> games = FaugusLibraryScanner.gamesUnder(tmp);
        assertEquals(cover, games.stream().filter(g -> g.id().equals("a")).findFirst().orElseThrow().artwork());
        assertEquals(icon, games.stream().filter(g -> g.id().equals("b")).findFirst().orElseThrow().artwork());
        assertNull(games.stream().filter(g -> g.id().equals("c")).findFirst().orElseThrow().artwork(),
                "a path Faugus recorded but no longer has on disk must not reach the ImageView");
    }

    @Test
    void aFaugusEntryWithNoTitleIsListedUnderItsGameId(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("games.json"), "[{\"gameid\":\"abc123\"}]");

        assertEquals("abc123", FaugusLibraryScanner.gamesUnder(tmp).getFirst().name());
    }

    @Test
    void aMissingCorruptOrNonArrayGamesFileIsAnEmptyList(@TempDir Path tmp) throws IOException {
        assertEquals(List.of(), FaugusLibraryScanner.gamesUnder(null));
        assertEquals(List.of(), FaugusLibraryScanner.gamesUnder(tmp), "no games.json at all");

        write(tmp.resolve("games.json"), "{ not an array }");
        assertEquals(List.of(), FaugusLibraryScanner.gamesUnder(tmp));

        Files.writeString(tmp.resolve("games.json"), "{\"games\":[]}");
        assertEquals(List.of(), FaugusLibraryScanner.gamesUnder(tmp),
                "a JSON object where an array is expected is a format change, not a crash");
    }

    // =====================================================================
    // The registry
    // =====================================================================

    /**
     * {@code GameLibraries} is what the Launch Target picker and {@code ToolbarManager}'s artwork lookup go
     * through, and it keys on the platform string. Those strings are the {@code LaunchKind} ids — SU6/SC6
     * replace the four {@code PLATFORM} constants with the enum, and this is the pairing that has to survive.
     */
    @Test
    void everyRegisteredProviderIsReachableByItsPlatformKey() {
        List<GameLibraryProvider> all = GameLibraries.all();
        assertTrue(all.size() >= 4, "steam, epic, heroic and faugus are all registered: " + all.size());

        for (GameLibraryProvider provider : all) {
            String key = provider.platform();
            assertEquals(provider.getClass(), GameLibraries.forPlatform(key).orElseThrow().getClass(),
                    key + " does not resolve back to its own provider");
            assertNotNull(provider.displayName(), key + " has no display name for the picker");
        }
    }

    @Test
    void anUnknownPlatformResolvesToNothingRatherThanTheFirstProvider() {
        assertTrue(GameLibraries.forPlatform("gog-galaxy").isEmpty());
        assertTrue(GameLibraries.findGame("gog-galaxy", "123").isEmpty());
    }

    // =====================================================================
    // ui MISSING 7 — LaunchKind round-trips through the picker path
    // =====================================================================

    /**
     * <b>The coupling SU6 and SC6 depend on.</b> {@code LaunchKind} is the repo's own worked example of a
     * closed set with a stable wire id — and this package spells four of those ids as its own
     * {@code PLATFORM} string constants, which the Launch Target dialog and {@code LaunchTargetArgPicker}
     * then repeat as literals at their button handlers. The two sets are identical today; nothing checks
     * that, which is what makes this the "MuMu" vs "MuMu Player" drift waiting to happen.
     *
     * <p>Every scanner's platform key must be a real kind, and must round-trip to itself. SU6/SC6 replace the
     * constants with the enum, at which point this becomes a tautology — which is the point of writing it now.
     */
    @Test
    void everyScannersPlatformKeyIsALaunchKindIdAndRoundTripsToIt() {
        for (GameLibraryProvider provider : GameLibraries.all()) {
            String key = provider.platform();
            LaunchKind kind = LaunchKind.fromId(key);

            assertNotEquals(LaunchKind.UNKNOWN, kind,
                    "provider key '" + key + "' is not a LaunchKind — a game the picker can find but the "
                            + "bot cannot launch");
            assertEquals(key, kind.id(), "the round-trip must land on the same wire id");
        }
    }

    @Test
    void eachScannerConstantIsSpelledExactlyAsItsKind() {
        assertEquals(LaunchKind.STEAM.id(), SteamLibraryScanner.PLATFORM);
        assertEquals(LaunchKind.EPIC.id(), EpicLibraryScanner.PLATFORM);
        assertEquals(LaunchKind.HEROIC.id(), HeroicLibraryScanner.PLATFORM);
        assertEquals(LaunchKind.FAUGUS.id(), FaugusLibraryScanner.PLATFORM);
    }

    /**
     * The whole path in one assertion: a game discovered on disk carries a platform key; the picker writes
     * {@code <key>:<id>} into {@code botmaker-project.properties}; the SDK parses it back. A key that is not
     * a kind breaks silently at the last step, in the bot, at run time.
     */
    @Test
    void aDiscoveredGameBuildsASpecThatParsesBackToTheSameKindAndToken(@TempDir Path tmp) throws IOException {
        Path root = steamRoot(tmp);
        write(root.resolve("steamapps/appmanifest_570.acf"), appManifest("570", "Dota 2"));
        InstalledGame game = SteamLibraryScanner.gamesUnder(root).getFirst();

        LaunchSpec parsed = LaunchSpec.parse(game.platform() + ":" + game.id());

        assertNotNull(parsed);
        assertEquals(LaunchKind.STEAM, parsed.kind());
        assertEquals("570", parsed.token());
        assertEquals("steam:570", parsed.spec(), "and it re-writes to exactly what was written");
    }

    /**
     * <b>The drift the audit named, measured.</b> Each provider carries a second display name for the same
     * product — {@code "Steam"} here against {@code LaunchKind}'s {@code "Steam game"}, and similarly for the
     * other three. Both reach the user: the provider's name labels the picker, the kind's labels the
     * configured target. Characterised rather than fixed, because collapsing them into one is SU6, and this
     * is the assertion SU6 flips.
     */
    @Test
    void eachProviderCarriesASecondDisplayNameForTheSameProduct() {
        assertEquals("Steam", new SteamLibraryScanner().displayName());
        assertEquals("Steam game", LaunchKind.STEAM.displayName());

        assertEquals("Epic Games", new EpicLibraryScanner().displayName());
        assertEquals("Epic game", LaunchKind.EPIC.displayName());

        for (GameLibraryProvider provider : GameLibraries.all()) {
            assertNotEquals(LaunchKind.fromId(provider.platform()).displayName(), provider.displayName(),
                    provider.platform() + " now agrees with its kind — delete this test with SU6 rather "
                            + "than loosening it");
        }
    }
}
