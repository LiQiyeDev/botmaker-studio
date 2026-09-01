package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renaming and deleting a template are only safe if Studio can find where it is used. A template is named in
 * source two ways — the generated {@code Templates.NAME} constant, and the raw project-relative path for a
 * template whose name predates the lowercase rule — and the second one is the dangerous half: it survives a
 * rename with no compile error and fails at run time.
 */
class TemplateReferencesTest {

    @Test
    void bothSpellingsAreFound(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot;
                class Miner {
                    void run() {
                        find(new ImageTemplate(Templates.GOLD_ORE));
                        find(new ImageTemplate("src/main/resources/images/gold_ore.png"));
                        find(new ImageTemplate(Templates.IRON_ORE));
                    }
                }
                """);

        TemplateReferences.Scan scan = TemplateReferences.find(config, null, "gold_ore");

        assertEquals(2, scan.uses().size());
        assertEquals(1, scan.fileCount());
        assertEquals(List.of(4, 5), scan.uses().stream().map(TemplateReferences.Use::line).toList());
    }

    @Test
    void anUnusedTemplateHasNothingToFix(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", "class Miner { void run() {} }\n");

        assertTrue(TemplateReferences.find(config, null, "gold_ore").isEmpty());
    }

    @Test
    void retargetRewritesTheConstantAndTheLiteral(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                class Miner {
                    String a = Templates.GOLD_ORE;
                    String b = "src/main/resources/images/gold_ore.png";
                    String c = com.refbot.Templates.GOLD_ORE;
                    String keep = Templates.GOLD_ORE_SEAM;
                }
                """);

        List<Path> changed = TemplateReferences.retarget(config, null, "gold_ore", "gold_vein");

        assertEquals(1, changed.size());
        String source = Files.readString(config.sourceRoot().resolve("Miner.java"));
        assertEquals(0, count(source, "gold_ore.png"));
        assertTrue(source.contains("String a = Templates.GOLD_VEIN;"));
        assertTrue(source.contains("String b = \"src/main/resources/images/gold_vein.png\";"));
        // A qualified use keeps its qualifier: only the Templates.NAME part is matched.
        assertTrue(source.contains("String c = com.refbot.Templates.GOLD_VEIN;"));
        // A longer constant that merely starts with the old one is not a use of it.
        assertTrue(source.contains("String keep = Templates.GOLD_ORE_SEAM;"));
    }

    /**
     * A name that isn't a lowercase identifier gets no constant, so its uses are path literals only — the case
     * that used to rename silently. Renaming it to a proper name rewrites those literals to the new path;
     * regenerating {@code Templates.java} is what then gives it a constant.
     */
    @Test
    void aTemplateWithNoConstantIsStillRewritten(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java",
                "class Miner { String a = \"src/main/resources/images/Gold-Ore.png\"; }\n");

        TemplateReferences.retarget(config, null, "Gold-Ore", "gold_ore");

        String source = Files.readString(config.sourceRoot().resolve("Miner.java"));
        assertTrue(source.contains("\"src/main/resources/images/gold_ore.png\""));
    }

    /**
     * The other direction: pointing blocks at a template whose <em>new</em> name has no constant. The constant
     * reference has to become the path literal — there is no constant to name it with, and leaving
     * {@code Templates.GOLD_ORE} behind would name a template that no longer exists.
     */
    @Test
    void aConstantBecomesALiteralWhenTheNewNameCannotHaveOne(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", "class Miner { String a = Templates.GOLD_ORE; }\n");

        TemplateReferences.retarget(config, null, "gold_ore", "Imported-1");

        String source = Files.readString(config.sourceRoot().resolve("Miner.java"));
        assertTrue(source.contains("String a = \"src/main/resources/images/Imported-1.png\";"));
        assertFalse(source.contains("Templates.GOLD_ORE"));
    }

    /** The generated class declares the constant; it is rewritten from the images folder, never from here. */
    @Test
    void theGeneratedTemplatesClassIsLeftAlone(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        // ProjectConfig.templatesSourceFile() named this file until 2026-09-01. It is spelled out here for
        // the same reason BotSources spells it out: the file is generated by the SDK, so what Studio holds
        // is only the name it must skip, and one accessor for that name was more ceremony than the name.
        Path templates = config.mainPackageDir().resolve("Templates.java");
        Files.createDirectories(templates.getParent());
        Files.writeString(templates,
                "package com.refbot;\npublic final class Templates {\n"
                        + "    public static final String GOLD_ORE = \"src/main/resources/images/gold_ore.png\";\n}\n");

        assertTrue(TemplateReferences.find(config, null, "gold_ore").isEmpty());
        assertTrue(TemplateReferences.retarget(config, null, "gold_ore", "gold_vein").isEmpty());
    }

    /**
     * The editor holds open files in memory and writes them out on run, so a rewrite that only touched the
     * disk would be silently undone by the next save. Both copies move together.
     */
    @Test
    void theOpenBufferIsRewrittenTogetherWithTheFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Path file = write(config, "Miner.java", "class Miner { String a = Templates.GOLD_ORE; }\n");
        ProjectState state = new ProjectState();
        // The buffer holds an unsaved edit — a second use the disk has never seen.
        ProjectFile open = new ProjectFile(file,
                "class Miner { String a = Templates.GOLD_ORE; String b = Templates.GOLD_ORE; }\n");
        state.addFile(open);

        TemplateReferences.retarget(config, state, "gold_ore", "gold_vein");

        assertEquals(2, count(open.getContent(), "Templates.GOLD_VEIN"));
        assertEquals(0, count(open.getContent(), "Templates.GOLD_ORE"));
        // The rewrite is of the buffer, which is the truth: the disk gets the same text, edit included.
        assertEquals(open.getContent(), Files.readString(file));
    }

    // -------------------------------------------------------------------------
    // Pointing blocks at a different picture, and saying so
    // -------------------------------------------------------------------------

    @Test
    void aRepointMarksTheFunctionTheRewrittenLineIsIn(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot;
                class Miner {
                    void mine() {
                        find(new ImageTemplate(Templates.GOLD_ORE));
                    }

                    void rest() {
                        sleep(1);
                    }
                }
                """);

        TemplateReferences.retarget(config, null, "gold_ore", "gold_vein", "com.refbot",
                "this looked for \"gold_ore\", which is gone.");

        String source = Files.readString(config.sourceRoot().resolve("Miner.java"));
        assertTrue(source.contains("Templates.GOLD_VEIN"), source);
        assertTrue(source.contains("@NeedsReview"), source);
        // Only mine() — rest() names no template, so nothing about it changed.
        assertEquals(1, count(source, "@NeedsReview"), source);
        assertTrue(source.indexOf("@NeedsReview") < source.indexOf("void mine()"), source);
    }

    /** A rename points every block at the same picture, so there is nothing to review. */
    @Test
    void aRetargetWithNoEntryMarksNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot;
                class Miner {
                    void mine() {
                        find(new ImageTemplate(Templates.GOLD_ORE));
                    }
                }
                """);

        TemplateReferences.retarget(config, null, "gold_ore", "gold_vein");

        assertFalse(Files.readString(config.sourceRoot().resolve("Miner.java")).contains("@NeedsReview"));
    }

    /**
     * A file that does not parse is still retargeted — that is the whole reason this works in text — and
     * simply goes unmarked, rather than being left half-rewritten or refused.
     */
    @Test
    void aFileThatDoesNotParseIsStillRetargetedJustNotMarked(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Broken.java", "class Broken { String a = Templates.GOLD_ORE; void oops( }\n");

        TemplateReferences.retarget(config, null, "gold_ore", "gold_vein", "com.refbot", "look at this");

        String source = Files.readString(config.sourceRoot().resolve("Broken.java"));
        assertTrue(source.contains("Templates.GOLD_VEIN"), source);
        assertFalse(source.contains("@NeedsReview"), source);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static Path write(ProjectConfig config, String name, String source) throws IOException {
        Path file = config.sourceRoot().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file.toAbsolutePath().normalize();
    }

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("refbot", root);
        Files.createDirectories(config.imagesRoot());
        Files.createDirectories(config.sourceRoot());
        return config;
    }
}
