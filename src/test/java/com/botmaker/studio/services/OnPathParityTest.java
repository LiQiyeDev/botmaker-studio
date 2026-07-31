package com.botmaker.studio.services;

import com.botmaker.shared.Executables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 5 — the three private {@code which} helpers agree with shared's
 * {@link Executables#onPath}.</b> Gates <b>SV4</b>, which deletes them.
 *
 * <p>Studio spawns a {@code which} subprocess to answer "is this program installed" in three places —
 * {@code DesktopGrab.toolExists}, {@code SessionEnvironment.onPath} and {@code UpdateService.commandExists} —
 * all three private, all three byte-for-byte the same {@code ProcessBuilder("which", name)}. shared already
 * owns the answer, in pure Java, and its javadoc records that this exact duplication is why it exists.
 *
 * <p>Two things must be true before the three copies can go, and only one of them is "same answer":
 *
 * <ol>
 *   <li><b>They agree</b> on the inputs the call sites actually pass. Asserted below by running the copies'
 *       own implementation — the literal {@code which} spawn — against shared's.</li>
 *   <li><b>The one input they <em>disagree</em> on is never passed.</b> {@code which /bin/sh} succeeds;
 *       {@code Executables.onPath("/bin/sh")} is false by design, because it searches {@code PATH} for a
 *       directory entry named {@code "/bin/sh"}. shared splits that case out into {@link Executables#exists}
 *       on purpose. So SV4 is only safe while every call site passes a bare name — which the last test
 *       asserts against the sources, so adding a path-shaped call site fails here rather than in the field.</li>
 * </ol>
 *
 * <p>Windows has no {@code which}; the parity half is Linux/macOS only, and the call-site half is not.
 */
class OnPathParityTest {

    /** The three helpers' implementation, verbatim — this is what is being replaced. */
    private static boolean viaWhich(String name) {
        try {
            return new ProcessBuilder("which", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The bare names the three call sites really ask about, plus two that certainly aren't installed. */
    private static final List<String> REAL_QUERIES = List.of(
            "sh", "ls", "env",                                  // certainly present
            "grim", "gnome-screenshot", "spectacle",            // DesktopGrab
            "dnf", "apt-get", "pacman", "zypper",               // SessionEnvironment
            "dpkg", "rpm",                                      // UpdateService
            "botmaker-no-such-tool", "definitely-not-installed-xyz");

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sharedAgreesWithTheWhichSubprocessOnEveryNameTheCallSitesAsk() {
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (String name : REAL_QUERIES) {
            checks.add(() -> assertEquals(viaWhich(name), Executables.onPath(name),
                    "shared and `which " + name + "` disagree — SV4 would change behaviour"));
        }
        assertAll(checks);
    }

    /**
     * The copies swallow everything and answer false; shared answers false without spawning anything. Same
     * answer, and the degenerate inputs are worth stating because a {@code which} with no argument exits
     * non-zero rather than listing the whole PATH.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bothAnswerFalseForNothingAtAll() {
        assertAll(
                () -> assertFalse(Executables.onPath(null)),
                () -> assertFalse(Executables.onPath("")),
                () -> assertFalse(Executables.onPath("   ")),
                () -> assertEquals(viaWhich(""), Executables.onPath("")));
    }

    /**
     * The documented divergence, asserted so it is a known boundary rather than a surprise: a path is not a
     * PATH lookup. SV4 is safe only because no Studio call site passes one — see the next test.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aPathShapedArgumentIsWhereTheTwoDeliberatelyDiffer() {
        assertTrue(Files.isExecutable(Path.of("/bin/sh")) || Files.isExecutable(Path.of("/usr/bin/sh")),
                "fixture assumption: a POSIX shell exists at a known absolute path");

        assertFalse(Executables.onPath("/bin/sh"),
                "onPath searches PATH for an entry literally named '/bin/sh' — by design");
        assertTrue(Executables.exists("/bin/sh"),
                "exists() is the method for an argv[0] that may be a path; that is the split SV4 must respect");
    }

    /**
     * Reads the three call sites and asserts every argument they pass is a bare program name. This is the
     * precondition for SV4, and it is checked against the source rather than assumed, because the divergence
     * above is invisible at the call site: {@code toolExists("/usr/bin/grim")} would compile, run, and start
     * answering "not installed" the day the helper is swapped.
     */
    @Test
    void everyCallSitePassesABareNameAndNotAPath() throws IOException {
        List<Path> sources = List.of(
                Path.of("src/main/java/com/botmaker/studio/services/capture/DesktopGrab.java"),
                Path.of("src/main/java/com/botmaker/studio/services/platform/SessionEnvironment.java"),
                Path.of("src/main/java/com/botmaker/studio/services/UpdateService.java"));

        List<String> pathShaped = new ArrayList<>();
        for (Path source : sources) {
            for (String literal : stringLiteralsPassedToOnPathLikeCalls(Files.readString(source))) {
                if (literal.indexOf('/') >= 0 || literal.indexOf('\\') >= 0) {
                    pathShaped.add(source.getFileName() + ": " + literal);
                }
            }
        }

        assertTrue(pathShaped.isEmpty(),
                "these arguments are paths, so Executables.onPath would answer 'no' for them: " + pathShaped);
    }

    /**
     * The string literals handed to the three helpers. Deliberately crude — a regex over the source, matching
     * the call spellings the three files use — because the alternative is making three private methods public
     * just to be able to look at them.
     */
    private static List<String> stringLiteralsPassedToOnPathLikeCalls(String source) {
        List<String> found = new ArrayList<>();
        var matcher = java.util.regex.Pattern
                .compile("(?:toolExists|onPath|commandExists)\\(\"([^\"]*)\"\\)")
                .matcher(source);
        while (matcher.find()) found.add(matcher.group(1));

        // The array-driven site: `for (String pm : new String[]{"dnf", "apt-get", …})`.
        var array = java.util.regex.Pattern
                .compile("new String\\[\\]\\{([^}]*)}")
                .matcher(source);
        while (array.find()) {
            for (String part : array.group(1).split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
                    found.add(trimmed.substring(1, trimmed.length() - 1));
                }
            }
        }
        return found;
    }
}
