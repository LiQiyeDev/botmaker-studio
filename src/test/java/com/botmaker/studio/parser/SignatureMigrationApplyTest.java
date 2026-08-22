package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.MethodReferences;
import com.botmaker.studio.parser.refactor.SignatureMigration;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.BeforeEach;
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
 * Changing a signature and having the project follow — the whole round trip, from the scan to the file on disk.
 *
 * <p>{@code MethodMigrationTest} covers what the plan <em>says</em>; this covers what is actually written. The
 * two assertions that matter here are the ones a single-file test cannot make: the other file changes too, and
 * it changes <b>on disk</b>, because that is where Studio's copy of a file it isn't showing comes from.
 */
class SignatureMigrationApplyTest {

    private static final String BOT = """
            package test;

            public class Bot {
                public boolean clickAt(int x, int tries) {
                    System.out.println(tries);
                    return x > 0;
                }

                public void run() {
                    clickAt(1, 3);
                    boolean ok = clickAt(2, 4);
                    System.out.println(ok);
                }

                public void hover(int spot) {
                }
            }
            """;

    private static final String GO_HOME = """
            package test;

            public class GoHome {
                public void go() {
                    Bot.clickAt(5, 6);
                }
            }
            """;

    @TempDir
    Path dir;

    private ProjectState state;
    private ProjectFile goHome;
    private CodeEditor editor;
    private String lastCode;
    private int updates;

    @BeforeEach
    void setUp() throws IOException {
        state = new ProjectState();
        Path botPath = dir.resolve("Bot.java");
        Path goHomePath = dir.resolve("GoHome.java");
        Files.writeString(botPath, BOT);
        Files.writeString(goHomePath, GO_HOME);

        state.addFile(new ProjectFile(botPath, BOT));
        goHome = new ProjectFile(goHomePath, GO_HOME);
        state.addFile(goHome);
        state.setActiveFile(botPath);
        state.setCurrentCode(BOT);
        state.setSourcePath(dir);
        state.setResolvedClasspath(TestSupport.runtimeClassPath());
        state.setCompilationUnit(SourceParser.parse(BOT));

        EventBus bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> {
            lastCode = e.newCode();
            updates++;
        });
        editor = new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state));
    }

    // --- fixtures ------------------------------------------------------------------------------------------

    private TypeDeclaration bot() {
        return (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
    }

    private MethodDeclaration clickAt() {
        return bot().getMethods()[0];
    }

    private static FunctionDraft.Parameter param(String name, BotType type, int origin) {
        return new FunctionDraft.Parameter(name, BotType.Choice.of(type)).withOrigin(origin);
    }

    private static FunctionDraft.Parameter added(String name, BotType type) {
        return new FunctionDraft.Parameter(name, BotType.Choice.of(type));
    }

    private FunctionDraft before() {
        return new FunctionDraft("clickAt", BotType.Choice.of(BotType.YES_NO),
                List.of(param("x", BotType.WHOLE_NUMBER, 0), param("tries", BotType.WHOLE_NUMBER, 1)));
    }

    /** Runs the whole path the ✎ button runs, minus the preview the user would have approved. */
    private void migrate(FunctionDraft after) {
        MethodDeclaration method = clickAt();
        MethodReferences.Result references = MethodReferences.find(state, method);
        assertFalse(references.isRefusal(), references.refusal());
        SignatureMigration.Plan plan = SignatureMigration.of(before(), after, method, references.calls());
        editor.applyFunctionSignature(method, after, plan);
    }

    private String goHomeOnDisk() throws IOException {
        return Files.readString(goHome.getPath());
    }

    // --- tests ---------------------------------------------------------------------------------------------

    @Test
    void aRenameReachesTheCallsInBothFiles() throws IOException {
        migrate(new FunctionDraft("tapAt", BotType.Choice.of(BotType.YES_NO), before().parameters()));

        assertEquals(1, updates, "the file being edited is still written exactly once");
        assertTrue(lastCode.contains("public boolean tapAt(int x, int tries)"), lastCode);
        assertTrue(lastCode.contains("tapAt(1, 3);"), "the call in this file follows:\n" + lastCode);
        assertTrue(lastCode.contains("boolean ok = tapAt(2, 4);"), lastCode);
        assertTrue(goHomeOnDisk().contains("Bot.tapAt(5, 6);"),
                "the call in the file the user isn't looking at follows, on disk:\n" + goHomeOnDisk());
        assertEquals(goHomeOnDisk(), goHome.getContent(),
                "and the editor's copy of it agrees with the disk");
    }

    @Test
    void anAddedParameterIsFilledInAtEveryCall() throws IOException {
        migrate(new FunctionDraft("clickAt", BotType.Choice.of(BotType.YES_NO),
                List.of(param("x", BotType.WHOLE_NUMBER, 0), param("tries", BotType.WHOLE_NUMBER, 1),
                        added("label", BotType.TEXT))));

        assertTrue(lastCode.contains("clickAt(int x, int tries, String label)"), lastCode);
        assertTrue(lastCode.contains("clickAt(1, 3, \"\");"), lastCode);
        assertTrue(goHomeOnDisk().contains("Bot.clickAt(5, 6, \"\");"), goHomeOnDisk());
    }

    @Test
    void aRemovedParameterIsDroppedAtTheCallsAndBecomesALocalInTheBody() throws IOException {
        migrate(new FunctionDraft("clickAt", BotType.Choice.of(BotType.YES_NO),
                List.of(param("x", BotType.WHOLE_NUMBER, 0))));

        assertTrue(lastCode.contains("clickAt(int x)"), lastCode);
        assertTrue(lastCode.contains("int tries = 0;"),
                "the body still prints tries, so tries still has to exist:\n" + lastCode);
        assertTrue(lastCode.contains("clickAt(1);"), lastCode);
        assertTrue(goHomeOnDisk().contains("Bot.clickAt(5);"), goHomeOnDisk());
    }

    @Test
    void reorderingPermutesTheArgumentsAtEveryCall() throws IOException {
        migrate(new FunctionDraft("clickAt", BotType.Choice.of(BotType.YES_NO),
                List.of(param("tries", BotType.WHOLE_NUMBER, 1), param("x", BotType.WHOLE_NUMBER, 0))));

        assertTrue(lastCode.contains("clickAt(int tries, int x)"), lastCode);
        assertTrue(lastCode.contains("clickAt(3, 1);"), "the arguments moved with them:\n" + lastCode);
        assertTrue(goHomeOnDisk().contains("Bot.clickAt(6, 5);"), goHomeOnDisk());
    }

    @Test
    void aChangedReturnTypeReplacesTheUseAndLeavesTheStatementCallAlone() {
        migrate(new FunctionDraft("clickAt", BotType.Choice.of(BotType.WHOLE_NUMBER), before().parameters()));

        assertTrue(lastCode.contains("public int clickAt(int x, int tries)"), lastCode);
        assertTrue(lastCode.contains("boolean ok = false;"),
                "the slot keeps the type it was written with, and the call that no longer fits it goes:\n"
                        + lastCode);
        assertTrue(lastCode.contains("clickAt(1, 3);"),
                "a call standing as its own line consumed nothing, so nothing needed fixing:\n" + lastCode);
    }

    @Test
    void aFunctionNothingCallsIsJustSaved() throws IOException {
        MethodDeclaration hover = bot().getMethods()[2];
        MethodReferences.Result references = MethodReferences.find(state, hover);
        SignatureMigration.Plan plan = SignatureMigration.of(
                new FunctionDraft("hover", BotType.Choice.of(BotType.NOTHING),
                        List.of(param("spot", BotType.WHOLE_NUMBER, 0))),
                new FunctionDraft("hoverOver", BotType.Choice.of(BotType.NOTHING),
                        List.of(param("spot", BotType.WHOLE_NUMBER, 0))),
                hover, references.calls());

        assertTrue(plan.isEmpty(), "nothing calls hover, so there is nothing to preview");
        editor.applyFunctionSignature(hover, new FunctionDraft("hoverOver", BotType.Choice.of(BotType.NOTHING),
                List.of(param("spot", BotType.WHOLE_NUMBER, 0))), plan);

        assertTrue(lastCode.contains("public void hoverOver(int spot)"), lastCode);
        assertEquals(GO_HOME, goHomeOnDisk(), "no other file is touched for a change no other file can see");
    }

    // --- what the change leaves behind for the user --------------------------------------------------------

    /**
     * The same migrations, run through an editor that has a project to write a marker into.
     *
     * <p>The editor above deliberately has none ({@code config} is null), which is what makes every assertion
     * up to here about the repaired code alone. These are the same gestures with the bookkeeping switched on.
     */
    private void migrateWithProject(FunctionDraft after) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir.resolve("projects"));
        Files.createDirectories(config.mainPackageDir());
        EventBus bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode = e.newCode());
        CodeEditor marking = new CodeEditor(config, state, bus, new ProjectAnalyzer(null, state));

        MethodDeclaration method = clickAt();
        MethodReferences.Result references = MethodReferences.find(state, method);
        assertFalse(references.isRefusal(), references.refusal());
        marking.applyFunctionSignature(method,
                after, SignatureMigration.of(before(), after, method, references.calls()));
    }

    @Test
    void aRenameIsACompleteRepairAndIsMarkedNowhere() throws IOException {
        migrateWithProject(new FunctionDraft("tapAt", BotType.Choice.of(BotType.YES_NO), before().parameters()));

        assertFalse(lastCode.contains("@NeedsReview"),
                "every call afterwards does exactly what it did before:\n" + lastCode);
        assertFalse(goHomeOnDisk().contains("@NeedsReview"), goHomeOnDisk());
    }

    @Test
    void anAddedParameterMarksEveryFunctionItWasGuessedIn() throws IOException {
        migrateWithProject(new FunctionDraft("clickAt", BotType.Choice.of(BotType.YES_NO),
                List.of(param("x", BotType.WHOLE_NUMBER, 0), param("tries", BotType.WHOLE_NUMBER, 1),
                        added("label", BotType.TEXT))));

        assertTrue(lastCode.contains("@NeedsReview"), "run() calls it twice with a placeholder:\n" + lastCode);
        assertTrue(lastCode.contains("gained an input"), lastCode);
        assertTrue(goHomeOnDisk().contains("@NeedsReview"),
                "so does go(), in the file the user never opened:\n" + goHomeOnDisk());
        assertTrue(goHomeOnDisk().contains("import com.mybot.NeedsReview;"),
                "and it is imported there, since that file is not in the bot's own package:\n" + goHomeOnDisk());
    }

    @Test
    void aRemovedParameterMarksTheFunctionWhoseBodyNowReadsAZero() throws IOException {
        migrateWithProject(new FunctionDraft("clickAt", BotType.Choice.of(BotType.WHOLE_NUMBER),
                List.of(param("x", BotType.WHOLE_NUMBER, 0))));

        assertTrue(lastCode.contains("int tries = 0;"), lastCode);
        assertTrue(lastCode.contains("was removed, so this now starts by declaring it"),
                "the body goes on reading tries, and it is no longer the caller's:\n" + lastCode);
    }

    @Test
    void aCallThatLostAnArgumentThatDidSomethingIsMarkedAndOneThatLostALiteralIsNot() throws IOException {
        // GoHome calls clickAt(5, 6) — a bare literal, dropped in silence. Bot.run's calls are literals too,
        // so the whole migration should mark nothing at any call site.
        migrateWithProject(new FunctionDraft("clickAt", BotType.Choice.of(BotType.YES_NO),
                List.of(param("x", BotType.WHOLE_NUMBER, 0))));

        assertFalse(goHomeOnDisk().contains("@NeedsReview"),
                "dropping a 6 the user watched being dropped is not a review row:\n" + goHomeOnDisk());
    }

    @Test
    void aChangedReturnTypeMarksOnlyTheCallThatNoLongerFits() throws IOException {
        migrateWithProject(new FunctionDraft("clickAt", BotType.Choice.of(BotType.WHOLE_NUMBER),
                before().parameters()));

        assertTrue(lastCode.contains("boolean ok = false;"), lastCode);
        assertTrue(lastCode.contains("no longer fits here"), lastCode);
        assertFalse(goHomeOnDisk().contains("@NeedsReview"),
                "GoHome's call stands as its own line and consumed nothing:\n" + goHomeOnDisk());
    }

    @Test
    void theMarkerAnnotationIsWrittenIntoTheBotOnce() throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir.resolve("projects"));
        migrateWithProject(new FunctionDraft("clickAt", BotType.Choice.of(BotType.YES_NO),
                List.of(param("x", BotType.WHOLE_NUMBER, 0), param("tries", BotType.WHOLE_NUMBER, 1),
                        added("label", BotType.TEXT))));

        Path marker = config.mainPackageDir().resolve("NeedsReview.java");
        assertTrue(Files.exists(marker), "the marks reference an annotation that has to exist");
        assertTrue(Files.readString(marker).contains("RetentionPolicy.SOURCE"), Files.readString(marker));
    }
}
