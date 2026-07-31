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
 * <b>Studio services MISSING 5 — Studio's "is this program installed" answer is shared's
 * {@link Executables#onPath}, and it still agrees with the {@code which} subprocess it replaced.</b>
 *
 * <p>Studio used to spawn {@code which} in three places — {@code DesktopGrab.toolExists},
 * {@code SessionEnvironment.onPath} and {@code UpdateService.commandExists} — all three private, all three
 * essentially the same {@code ProcessBuilder("which", name)}, and one of them ({@code UpdateService}'s)
 * redirecting nothing at all, which is B7's shape. <b>SV4 (2026-08-01) deleted all three</b> in favour of
 * shared, which answers the same question in pure Java and whose javadoc records that this exact duplication
 * is why it exists.
 *
 * <p>Two things had to be true before the copies could go, and only one of them is "same answer":
 *
 * <ol>
 *   <li><b>They agree</b> on the inputs the call sites actually pass. Asserted below by running the deleted
 *       copies' implementation — the literal {@code which} spawn, kept here as {@link #viaWhich} — against
 *       shared's. It stays after the fix as the thing that would catch a divergence, since nothing else in
 *       the tree compares the two any more.</li>
 *   <li><b>The one input they <em>disagree</em> on is never passed.</b> {@code which /bin/sh} succeeds;
 *       {@code Executables.onPath("/bin/sh")} is false by design, because it searches {@code PATH} for a
 *       directory entry named {@code "/bin/sh"}. shared splits that case out into {@link Executables#exists}
 *       on purpose. So the swap is only sound while every call site passes a bare name — which the last test
 *       asserts against the sources, so adding a path-shaped call site fails here rather than in the field.</li>
 * </ol>
 *
 * <p>Windows has no {@code which}; the parity half is Linux/macOS only, and the call-site half is not.
 */
class OnPathParityTest {

    /** The three deleted helpers' implementation, verbatim — this is what shared replaced. */
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
            "dpkg", "rpm", "pkexec",                            // UpdateService
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
     * PATH lookup. The swap was safe only because no Studio call site passes one — see the next test.
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

    /** The three files SV4 touched; the ones whose arguments have to stay bare names. */
    private static final List<Path> CALL_SITES = List.of(
            Path.of("src/main/java/com/botmaker/studio/services/capture/DesktopGrab.java"),
            Path.of("src/main/java/com/botmaker/studio/services/platform/SessionEnvironment.java"),
            Path.of("src/main/java/com/botmaker/studio/services/UpdateService.java"));

    /**
     * <b>SV4's gate.</b> No Studio source spawns {@code which} any more — the question is answered in-process.
     *
     * <p>This is what was red before the fix, and it is worth keeping as more than a monument: the
     * {@code UpdateService} copy redirected neither stream, so it was also a live instance of B7 (an
     * undrained child blocks in {@code write()} once the pipe buffer fills). {@code which} is terse enough
     * that it never filled one in practice, but the shape is the bug, and the shape is what this forbids
     * coming back.
     */
    @Test
    void noStudioSourceAsksTheQuestionBySpawningWhich() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path source : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(source).contains("ProcessBuilder(\"which\"")) {
                    offenders.add(source.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these spawn `which` instead of calling Executables.onPath: " + offenders);
    }

    /**
     * Reads the three call sites and asserts every argument they pass is a bare program name. This was the
     * precondition for SV4 and remains the guard against undoing it, and it is checked against the source
     * rather than assumed, because the divergence above is invisible at the call site:
     * {@code Executables.onPath("/usr/bin/grim")} compiles, runs, and quietly answers "not installed".
     */
    @Test
    void everyCallSitePassesABareNameAndNotAPath() throws IOException {
        List<String> pathShaped = new ArrayList<>();
        for (Path source : CALL_SITES) {
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
     * The string literals that reach {@code Executables.onPath}. Deliberately crude — regexes over the source
     * — because two of the three sites don't pass a literal directly and the alternative is a real parser.
     * Over-matching is harmless here: every extra literal is simply one more string asserted not to be a path.
     */
    private static List<String> stringLiteralsPassedToOnPathLikeCalls(String source) {
        List<String> found = new ArrayList<>();
        // The direct site: `Executables.onPath("dpkg")`.
        var direct = java.util.regex.Pattern
                .compile("Executables\\.onPath\\(\"([^\"]*)\"\\)")
                .matcher(source);
        while (direct.find()) found.add(direct.group(1));

        // Every array initializer — which covers both indirect sites: the package-manager loop
        // `for (String pm : new String[]{"dnf", …})` and DesktopGrab's `String[][] tools = {{"grim"}, …}`,
        // whose `tool[0]` is what onPath is handed. An initializer is recognised as an innermost brace group
        // whose parts are *all* string literals, which is what keeps a method body from matching.
        var braced = java.util.regex.Pattern.compile("\\{([^{}]*)}").matcher(source);
        while (braced.find()) {
            List<String> parts = new ArrayList<>();
            for (String part : braced.group(1).split(",")) {
                String trimmed = part.trim();
                if (trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    parts.add(trimmed.substring(1, trimmed.length() - 1));
                } else if (!trimmed.isEmpty()) {
                    parts.clear();
                    break;
                }
            }
            found.addAll(parts);
        }
        return found;
    }
}
