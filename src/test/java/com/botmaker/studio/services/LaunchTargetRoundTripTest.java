package com.botmaker.studio.services;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.studio.project.ProjectCreator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 6 — the launch target survives a write / read / parse / describe round trip.</b>
 * Gates <b>SV10</b> (parse {@code launch.target} through {@link LaunchKind} instead of re-splitting the string)
 * and, through it, SU6.
 *
 * <p>The spec is the one value that crosses every boundary this project has: Studio writes it to
 * {@code botmaker-project.properties}, the file survives on disk between sessions, the SDK reads it at bot
 * startup, and Studio reads it back to seed the editor. Nothing tested any leg of that. The property that has
 * to hold is not "parse works" but <b>write(read(x)) == x for every kind</b> — because a spec that survives
 * three legs and is mangled on the fourth is a bot that launches the wrong thing, or nothing, with no error.
 *
 * <p>The unknown-kind case is the one worth stating out loud: a spec written by a newer Studio must still
 * round-trip through an older one unchanged, which is why {@link LaunchSpec#parse} keeps the original text for
 * {@link LaunchKind#UNKNOWN} rather than dropping it.
 */
class LaunchTargetRoundTripTest {

    /** One realistic token per kind — every value in the closed set, so a new kind fails here until listed. */
    private static List<String> everyKindsSpec() {
        return List.of(
                "steam:570",
                "epic:Fortnite",
                "heroic:Corvette",
                "faugus:3f2a11",
                "cli:/usr/bin/wine game.exe --windowed",
                "exe:/opt/game/game.x86_64",
                "emu-app:com.example.game@MuMuPlayer-12.0-1");
    }

    /** Writes {@code spec} into a fresh project resources dir and reads it straight back. */
    private static String writeThenRead(Path resources, String spec) throws IOException {
        ProjectCreator.writeLaunchTarget(resources, spec);
        return ProjectCreator.readLaunchTarget(resources);
    }

    // ---- The round trip ----

    @Test
    void everyKindSurvivesWriteReadParseAndReWrite(@TempDir Path dir) {
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (String spec : everyKindsSpec()) {
            checks.add(() -> {
                Path resources = dir.resolve(spec.substring(0, spec.indexOf(':')));
                String read = writeThenRead(resources, spec);
                assertEquals(spec, read, "the file did not give back what was written");

                LaunchSpec parsed = LaunchSpec.parse(read);
                assertNotNull(parsed, spec + " did not parse");
                assertEquals(spec, parsed.spec(),
                        "re-encoding a parsed spec must reproduce it exactly, or the next save corrupts it");
            });
        }
        assertAll(checks);
    }

    /** Every kind but {@code UNKNOWN} must be reachable from a written spec — the id is the wire format. */
    @Test
    void everyKindIsReachableFromItsWrittenId() {
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (LaunchKind kind : LaunchKind.values()) {
            if (kind == LaunchKind.UNKNOWN) continue;
            checks.add(() -> {
                LaunchSpec parsed = LaunchSpec.parse(kind.id() + ":token");
                assertNotNull(parsed, kind + " did not parse");
                assertEquals(kind, parsed.kind(),
                        kind + "'s persisted id no longer parses back to it");
            });
        }
        assertAll(checks);
    }

    // ---- Describing it ----

    /**
     * The label the dialogs and the run checklist print. It is asserted per kind rather than as "not blank"
     * because the failure this replaces was a kind that launched fine and described as nothing.
     */
    @Test
    void eachKindDescribesItself() {
        assertAll(
                () -> assertEquals("Steam game 570", LaunchSpec.describe("steam:570")),
                () -> assertEquals("Epic game Fortnite", LaunchSpec.describe("epic:Fortnite")),
                // exe: describes by file name, not by the whole path — a path is unreadable in a button.
                () -> assertEquals("Executable game.x86_64", LaunchSpec.describe("exe:/opt/game/game.x86_64")),
                () -> assertEquals("(none)", LaunchSpec.describe(null)),
                () -> assertEquals("(none)", LaunchSpec.describe("   ")));
    }

    // ---- Clearing it ----

    /**
     * A null spec removes the key rather than writing an empty one, and the other project properties survive
     * — the write is a read-modify-write of a shared file shared with the capturing plugin, so "clear the
     * target" must not clear a key it did not write.
     *
     * <p>It used to seed that other key here, with {@code ProjectCreator.writeCaptureProperties}, and assert
     * the capture resolution survived. Both of those went to the plugin on 2026-09-01; what is asserted now
     * is the property this file's own writer owns, which is the half this test was ever able to prove.
     */
    @Test
    void clearingTheTargetRemovesTheKeyAndLeavesTheOtherPropertiesAlone(@TempDir Path dir) throws IOException {
        Path resources = dir.resolve("resources");
        ProjectCreator.writeLaunchTarget(resources, "steam:570");
        assertEquals("steam:570", ProjectCreator.readLaunchTarget(resources));

        ProjectCreator.writeLaunchTarget(resources, null);

        assertNull(ProjectCreator.readLaunchTarget(resources), "the key must be gone, not blank");

        String file = Files.readString(resources.resolve(
                com.botmaker.shared.config.ProjectProperties.FILE_NAME));
        assertFalse(file.contains(com.botmaker.shared.config.ProjectProperties.KEY_LAUNCH_TARGET),
                "an empty key left behind reads as a configured-but-blank target: " + file);
    }

    /** No file yet is "no target configured", not a crash — {@code QuickLaunch} asks before anything exists. */
    @Test
    void readingBeforeAnythingIsWrittenIsNotAnError(@TempDir Path dir) {
        assertNull(ProjectCreator.readLaunchTarget(dir.resolve("never-created")));
    }

    // ---- The unreadable spec ----

    /**
     * A hand-edited or newer-Studio spec must survive untouched. If parsing dropped it, opening the project in
     * an older Studio and saving anything would silently delete a working launch target.
     */
    @Test
    void anUnknownKindRoundTripsRatherThanBeingDropped(@TempDir Path dir) throws IOException {
        String fromTheFuture = "flatpak:org.example.Game";

        String read = writeThenRead(dir, fromTheFuture);
        LaunchSpec parsed = LaunchSpec.parse(read);

        assertNotNull(parsed, "an unknown kind must still parse — dropping it deletes the user's setting");
        assertEquals(LaunchKind.UNKNOWN, parsed.kind());
        assertEquals(fromTheFuture, parsed.spec(), "re-writing it must not rewrite it");
        assertEquals(fromTheFuture, parsed.describe(), "and it must still be printable");
    }

    /** Genuinely unparseable text yields null rather than throwing: the file is user-editable. */
    @Test
    void nothingToParseIsNullAndNeverAnException() {
        assertAll(
                () -> assertNull(LaunchSpec.parse(null)),
                () -> assertNull(LaunchSpec.parse("")),
                () -> assertNull(LaunchSpec.parse("no-colon-at-all")),
                () -> assertNull(LaunchSpec.parse("steam:"), "an empty token is not a target"),
                () -> assertNull(LaunchSpec.parse(":570"), "a missing kind is not a target"));
    }

    // ---- What the launcher actually looks for ----

    /**
     * {@code runningToken()} is what the skip-if-running probe matches against a live process command line.
     * It is deliberately <em>not</em> {@link LaunchSpec#spec()}, and the Steam case says why: the bare appId
     * is a short number that matches an unrelated command line by accident.
     */
    @Test
    void theRunningTokenIsTheLaunchersIdentityNotOurs() {
        assertAll(
                () -> assertEquals("AppId=570", LaunchSpec.parse("steam:570").runningToken()),
                () -> assertEquals("Fortnite", LaunchSpec.parse("epic:Fortnite").runningToken()),
                () -> assertEquals("game.x86_64", LaunchSpec.parse("exe:/opt/game/game.x86_64").runningToken()),
                () -> assertEquals("wine", LaunchSpec.parse("cli:/usr/bin/wine game.exe").runningToken(),
                        "a command line is identified by its executable, not its arguments"),
                () -> assertNull(LaunchSpec.parse("emu-app:com.example.game@Inst").runningToken(),
                        "an app inside an emulator is on no host process table"));
    }

    /**
     * The capture source travels the same four legs as the launch target, and the pilot now routes on it — so
     * it needs the same round trip. The emulator form is the one with a consumer that parses it back
     * ({@link com.botmaker.shared.config.ProjectProperties#emulatorInstanceOf}); the rest are carried raw.
     */
    @Test
    void theCaptureSourceSurvivesAWriteReadRoundTrip(@TempDir Path dir) throws IOException {
        assertNull(ProjectCreator.readCaptureSource(dir), "no file yet is unset, not a failure");

        ProjectCreator.writeCaptureSource(dir, "emulator:Waydroid");
        assertEquals("emulator:Waydroid", ProjectCreator.readCaptureSource(dir));

        // Writing the launch target beside it must not disturb it — they share one file.
        ProjectCreator.writeLaunchTarget(dir, "emu-app:com.example.game@Waydroid");
        assertEquals("emulator:Waydroid", ProjectCreator.readCaptureSource(dir));
        assertEquals("emu-app:com.example.game@Waydroid", ProjectCreator.readLaunchTarget(dir));

        ProjectCreator.writeCaptureSource(dir, null);
        assertNull(ProjectCreator.readCaptureSource(dir), "a cleared source reads back as unset");
        assertNotNull(ProjectCreator.readLaunchTarget(dir), "clearing one key must not clear the other");
    }

    /** The {@code @} split keeps package dots and takes the <em>last</em> separator. */
    @Test
    void anEmulatorAppSplitsIntoPackageAndInstance() {
        LaunchSpec spec = LaunchSpec.parse("emu-app:com.example.game@MuMuPlayer-12.0-1");

        assertEquals("com.example.game", spec.emulatorPackage());
        assertEquals("MuMuPlayer-12.0-1", spec.emulatorInstance());
        assertTrue(LaunchSpec.parse("steam:570").emulatorPackage() == null,
                "only emu-app has these halves");
    }
}
