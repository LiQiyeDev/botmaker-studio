package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.Sources;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's half of {@link Sources} — the token matcher and the two-copy rewrite.
 *
 * <p>Every assertion here is about a needle written the way a user writes Java, because that is the whole
 * contract: a plugin says {@code Templates.ORE} and gets the matches a compiler would agree with. Nothing in
 * this file names a picture, a template or any other plugin concept, which is the point of the capability.
 */
class HostSourcesTest {

    @AfterEach
    void clearInstalledProject() {
        HostSources.clear();
    }

    /** A project on disk with {@code files} under its main package, and its sources installed. */
    private static ProjectConfig project(Path root, Map<String, String> files) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("RefBot", root);
        Files.createDirectories(config.mainPackageDir());
        for (Map.Entry<String, String> e : files.entrySet()) {
            Files.writeString(config.mainPackageDir().resolve(e.getKey()), e.getValue());
        }
        HostSources.install(config, new ProjectState(), null);
        return config;
    }

    /**
     * A parseable subject with {@code body} as the whole of {@code run()}.
     *
     * <p>The statements are real calls rather than bare expressions, which matters for one assertion here:
     * {@link com.botmaker.studio.parser.refactor.ReviewMarker#markLines} refuses to mark a file it cannot
     * parse, on the rule that a mark on a file the user can no longer open is worth less than no mark. A
     * fixture holding {@code Templates.ORE;} is not a statement, so it would test the refusal rather than
     * the marking.
     */
    private static String source(String body) {
        return "package com.refbot;\npublic class Subject {\n    void run() {\n" + body + "\n    }\n}\n";
    }

    /** {@code use(x);} — one line, a real statement, and the needle sits inside it. */
    private static String use(String expression) {
        return "        System.out.println(" + expression + ");";
    }

    // ---- the matcher -----------------------------------------------------------------------------------

    @Test
    void aNeedleMatchesAcrossWhitespace(@TempDir Path root) throws IOException {
        project(root, Map.of("Subject.java", source(use("Templates . ORE"))));

        assertEquals(1, HostSources.live().find(List.of("Templates.ORE")).size(),
                "a needle is tokens, so the spacing the user typed is irrelevant");
    }

    @Test
    void aNeedleDoesNotMatchInsideALongerIdentifier(@TempDir Path root) throws IOException {
        project(root, Map.of("Subject.java",
                source(use("Templates.OREX") + "\n" + use("MyTemplates.ORE"))));

        assertTrue(HostSources.live().find(List.of("Templates.ORE")).isEmpty(),
                "both ends are guarded: neither a longer name nor a longer qualifier is this needle");
    }

    @Test
    void aStringLiteralMatchesWholeAndNotWithinALongerOne(@TempDir Path root) throws IOException {
        project(root, Map.of("Subject.java", source("""
                        String a = "images/ore.png";
                        String b = "assets/images/ore.png";""")));

        List<Sources.Use> found = HostSources.live().find(List.of("\"images/ore.png\""));

        assertAll(
                () -> assertEquals(1, found.size(), "the leading quote is part of the needle"),
                () -> assertTrue(found.getFirst().text().contains("String a")));
    }

    @Test
    void aUseCarriesItsFileLineAndText(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, Map.of("Subject.java", source(use("Templates.ORE"))));

        Sources.Use use = HostSources.live().find(List.of("Templates.ORE")).getFirst();

        assertAll(
                () -> assertEquals(config.mainPackageDir().resolve("Subject.java"), use.file()),
                () -> assertEquals(4, use.line(), "1-based, and the body starts on line 4"),
                () -> assertEquals("System.out.println(Templates.ORE);", use.text(),
                        "the whole line, trimmed, so a refusal can print what it is about to break"));
    }

    // ---- rewriting -------------------------------------------------------------------------------------

    @Test
    void replaceWritesTheFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, Map.of("Subject.java", source(use("Templates.ORE"))));

        List<Path> changed = HostSources.live()
                .replace(Map.of("Templates.ORE", "Templates.GOLD"), null, null);

        assertAll(
                () -> assertEquals(1, changed.size()),
                () -> assertTrue(Files.readString(config.mainPackageDir().resolve("Subject.java"))
                        .contains("Templates.GOLD")));
    }

    /**
     * The case an AST rewrite could not serve, and the reason the matcher is text.
     *
     * <p>A file the user has open and half-edited does not parse, and it is exactly the file a rename has to
     * reach — leaving it behind means the rename silently half-happened. The buffer is also rewritten rather
     * than only the disk, because the editor writes buffers out later and would otherwise undo this.
     */
    @Test
    void anOpenBufferIsRewrittenEvenWhenItDoesNotParse(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("RefBot", root);
        Files.createDirectories(config.mainPackageDir());
        Path file = config.mainPackageDir().resolve("Subject.java");
        Files.writeString(file, source(use("Templates.ORE")));

        ProjectState state = new ProjectState();
        String unparseable = "package com.refbot;\npublic class Subject {\n    void run() {\n"
                + "        Templates.ORE;\n    // the user is mid-edit and this file has no closing brace\n";
        ProjectFile buffer = new ProjectFile(file, unparseable);
        state.addFile(buffer);
        HostSources.install(config, state, null);

        HostSources.live().replace(Map.of("Templates.ORE", "Templates.GOLD"), null, null);

        assertAll(
                () -> assertTrue(buffer.getContent().contains("Templates.GOLD"),
                        "the buffer is what the editor will save, so it must be rewritten"),
                () -> assertTrue(Files.readString(file).contains("Templates.GOLD"),
                        "and the disk, so a run before the next save sees it"),
                () -> assertFalse(buffer.getContent().contains("Templates.ORE")));
    }

    @Test
    void aReviewNoteMarksTheEnclosingFunctionAndNullDoesNot(@TempDir Path root) throws IOException {
        ProjectConfig marked = project(root.resolve("a"), Map.of("Subject.java", source(use("Templates.ORE"))));
        HostSources.live().replace(Map.of("Templates.ORE", "Templates.GOLD"), null, "points somewhere else now");
        String withNote = Files.readString(marked.mainPackageDir().resolve("Subject.java"));

        ProjectConfig plain = project(root.resolve("b"), Map.of("Subject.java", source(use("Templates.ORE"))));
        HostSources.live().replace(Map.of("Templates.ORE", "Templates.GOLD"), null, null);
        String withoutNote = Files.readString(plain.mainPackageDir().resolve("Subject.java"));

        assertAll(
                () -> assertTrue(withNote.contains("NeedsReview"),
                        "a rewrite that changes what the bot does says so: " + withNote),
                () -> assertTrue(Files.exists(marked.mainPackageDir().resolve("NeedsReview.java")),
                        "and the annotation it names is declared, or the mark is a compile error"),
                () -> assertFalse(withoutNote.contains("NeedsReview"),
                        "a rename is lossless and must not cry wolf: " + withoutNote),
                () -> assertFalse(Files.exists(plain.mainPackageDir().resolve("NeedsReview.java")),
                        "and an unmarked rewrite adds no file to the project"));
    }

    /**
     * Order is the caller's, so a needle that is a prefix of another is theirs to sequence.
     *
     * <p>Applied in iteration order and first-one-wins, which is why the contract tells a plugin to pass a
     * {@code LinkedHashMap}: with {@code Templates.ORE} ahead of {@code Templates.OR}, a line holding the
     * longer name is rewritten by the longer needle and the shorter one then finds nothing left to match.
     */
    @Test
    void replacementsApplyInIterationOrder(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, Map.of("Subject.java", source(use("Templates.ORE"))));
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("Templates.ORE", "Templates.GOLD");
        ordered.put("Templates.OR", "Templates.WRONG");

        HostSources.live().replace(ordered, null, null);

        String out = Files.readString(config.mainPackageDir().resolve("Subject.java"));
        assertAll(
                () -> assertTrue(out.contains("Templates.GOLD")),
                () -> assertFalse(out.contains("WRONG")));
    }

    @Test
    void nothingMatchingChangesNoFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, Map.of("Subject.java", source(use("Templates.ORE"))));
        String before = Files.readString(config.mainPackageDir().resolve("Subject.java"));

        List<Path> changed = HostSources.live()
                .replace(Map.of("Templates.NOTHING", "Templates.GOLD"), null, null);

        assertAll(
                () -> assertTrue(changed.isEmpty()),
                () -> assertEquals(before, Files.readString(config.mainPackageDir().resolve("Subject.java"))));
    }

    // ---- no project ------------------------------------------------------------------------------------

    /**
     * Between projects a plugin gets the total no-op, never the project the user just left.
     *
     * <p>The reason it is total rather than null is the one {@code Runs.NONE} gives: a plugin asks what
     * refers to the thing it is renaming, is told nothing does, and proceeds — which is the honest answer
     * for a host holding no user code.
     */
    @Test
    void withNoProjectInstalledEverythingIsTheNoOp() {
        HostSources.clear();

        assertAll(
                () -> assertSame(Sources.NONE, HostSources.live()),
                () -> assertTrue(HostSources.live().find(List.of("Templates.ORE")).isEmpty()),
                () -> assertTrue(HostSources.live()
                        .replace(Map.of("Templates.ORE", "Templates.GOLD"), null, null).isEmpty()));
    }
}
