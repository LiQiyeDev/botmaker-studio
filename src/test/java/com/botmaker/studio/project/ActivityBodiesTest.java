package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An activity's body is wherever its author put it, so it is found rather than computed.
 *
 * <p>The path it replaced was {@code activities/<name>.java} — true only while Studio wrote that file. These
 * hold the two answers that matter: the file holding the call, and {@code null} for an activity nobody has
 * written a body for, which is an ordinary state and not damage.
 */
class ActivityBodiesTest {

    private static ProjectConfig project(Path root, String name) throws IOException {
        ProjectConfig config = ProjectConfig.forProject(name, root);
        Files.createDirectories(config.mainSourceFile().getParent());
        return config;
    }

    private static void write(ProjectConfig config, String fileName, String body) throws IOException {
        Files.writeString(config.mainSourceFile().getParent().resolve(fileName),
                "package " + config.mainPackage() + ";\n\n" + body);
    }

    @Test
    void theBodyIsFoundInWhateverFileItsAuthorPutItIn(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, "MyFarmer");
        write(config, "MyFarmer.java", "public class MyFarmer { public static void main(String[] a) {} }\n");
        write(config, "Jobs.java", """
                final class Jobs {
                    static void wire() {
                        Activities.define("Mining", ctx -> ctx.done());
                    }
                }
                """);

        Path found = ActivityBodies.find(config, null, "Mining");
        assertNotNull(found, "the call is in Jobs.java, which no naming rule would have pointed at");
        assertEquals("Jobs.java", found.getFileName().toString());
    }

    /** Spacing is the user's, and a static import leaves only the method name behind. */
    @Test
    void theCallIsRecognisedHoweverItIsSpelled(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, "MyFarmer");
        write(config, "Jobs.java", """
                final class Jobs {
                    static void wire() {
                        define ( "Mining" , ctx -> ctx.done());
                    }
                }
                """);

        assertNotNull(ActivityBodies.find(config, null, "Mining"));
    }

    @Test
    void anActivityWithNoBodyYetIsNotAnError(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, "MyFarmer");
        write(config, "Jobs.java", """
                final class Jobs {
                    static void wire() {
                        Activities.define("Fishing", ctx -> ctx.done());
                    }
                }
                """);

        assertNull(ActivityBodies.find(config, null, "Mining"),
                "it takes its DISABLED wire and the flow runs without it — nothing to repair");
    }

    @Test
    void aNameThatIsAPrefixOfAnotherIsNotAMatch(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, "MyFarmer");
        write(config, "Jobs.java", """
                final class Jobs {
                    static void wire() {
                        Activities.define("MiningDeep", ctx -> ctx.done());
                    }
                }
                """);

        assertNull(ActivityBodies.find(config, null, "Mining"),
                "the literal is matched whole, closing quote included");
    }

    @Test
    void aBlankNameAsksNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, "MyFarmer");
        assertNull(ActivityBodies.find(config, null, "  "));
        assertNull(ActivityBodies.find(config, null, null));
    }
}
