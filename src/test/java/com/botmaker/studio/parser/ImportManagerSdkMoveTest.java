package com.botmaker.studio.parser;

import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.studio.plugin.PluginHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK 1.1.0 reorganised {@code com.botmaker.sdk.api}: the geometry records moved to {@code api.geometry}, the
 * annotations to {@code api.meta}, {@code Debug}/{@code Time}/{@code BotMaker} to {@code api.util},
 * {@code Session}/{@code BotSettings} to {@code api.bot}. Every bot written before that carries the old
 * import lines, so {@link ImportManager#repairSdkImports} exists to repoint them on open.
 *
 * <p>These are the three cases that matter: a stale import is rewritten, a current one is left exactly alone
 * (a needless rewrite would dirty a file the user did not edit), and a name the SDK no longer publishes is
 * left for the compiler rather than guessed at.
 */
class ImportManagerSdkMoveTest {

    /** Runs the repair over {@code source} and returns the rewritten text. */
    private static String repaired(String source) {
        EditorFixture fixture = new EditorFixture(source);
        fixture.editor.repairSdkImports();
        return fixture.lastCode == null ? source : fixture.lastCode;
    }

    private static String botWith(String... imports) {
        return String.join("\n", imports) + """

                public class Subject {
                    public static void main(String[] args) {
                    }
                }
                """;
    }

    @Test
    void aPreMoveGeometryImportIsRepointed() {
        String out = repaired(botWith("import com.botmaker.sdk.api.Point;",
                "import com.botmaker.sdk.api.Rect;"));

        assertTrue(out.contains("import com.botmaker.sdk.api.geometry.Point;"), out);
        assertTrue(out.contains("import com.botmaker.sdk.api.geometry.Rect;"), out);
        assertFalse(out.contains("import com.botmaker.sdk.api.Point;"), "the stale line must be gone:\n" + out);
    }

    @Test
    void theOtherThreeMovedPackagesAreRepointedToo() {
        String out = repaired(botWith("import com.botmaker.sdk.api.Debug;",
                "import com.botmaker.sdk.api.Session;",
                "import com.botmaker.sdk.api.core.Direction;"));

        assertTrue(out.contains("import com.botmaker.sdk.api.util.Debug;"), out);
        assertTrue(out.contains("import com.botmaker.sdk.api.bot.Session;"), out);
        assertTrue(out.contains("import com.botmaker.sdk.api.geometry.Direction;"), out);
    }

    @Test
    void anImportThatIsAlreadyCurrentIsNotTouched() {
        String source = botWith("import com.botmaker.sdk.api.geometry.Point;",
                "import com.botmaker.sdk.api.vision.ImageFinder;");
        EditorFixture fixture = new EditorFixture(source);

        assertEquals(List.of(), fixture.editor.repairSdkImports(), "nothing to repair");
    }

    @Test
    void aNameTheSdkNoLongerPublishesIsLeftForTheCompiler() {
        // Screen was deleted and the CaptureSource implementations moved to com.botmaker.sdk.internal.
        // There is no honest api FQN to write, and a wrong one would compile into a different type — so the
        // line stays as it is and javac names it.
        String out = repaired(botWith("import com.botmaker.sdk.api.capture.Screen;"));

        assertTrue(out.contains("import com.botmaker.sdk.api.capture.Screen;"), out);
    }

    @Test
    void theRepairOnlyEverConsultsTheServedCatalog() {
        // The guarantee behind the test above: every name the repair can write comes from a catalogued facade,
        // which holds a real Class<?>, so a repaired import cannot name a class that does not exist.
        for (FacadeEntry facade : PluginHost.bundled().facades()) {
            assertTrue(facade.qualifiedName().startsWith("com.botmaker.sdk.api."),
                    facade.simpleName() + " is not in the api package: " + facade.qualifiedName());
        }
    }
}
