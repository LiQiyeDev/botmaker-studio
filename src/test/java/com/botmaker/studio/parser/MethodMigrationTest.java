package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.MethodReferences;
import com.botmaker.studio.parser.refactor.SignatureMigration;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding a function's callers, and working out what a signature change does to each of them.
 *
 * <p>Both halves of the phase that has no visible effect: {@link MethodReferences} answers "where is this
 * called from", which nothing in Studio could answer before, and {@link SignatureMigration} turns a changed
 * signature into the per-call edit list the preview describes and the write applies. Neither of them writes
 * anything, which is why they can be pinned this precisely.
 */
class MethodMigrationTest {

    private static final String BOT = """
            package test;

            import com.botmaker.sdk.api.Point;

            public class Bot {
                public boolean clickAt(Point where, int tries) {
                    System.out.println(tries);
                    return where != null;
                }

                public void run() {
                    clickAt(null, 3);
                    boolean ok = clickAt(null, 4);
                }

                public void hover(Point spot) {
                }
            }
            """;

    private static final String GO_HOME = """
            package test;

            public class GoHome {
                public void go() {
                    Bot.clickAt(null, 1);
                }
            }
            """;

    private ProjectState state;
    private Path botPath;
    private Path goHomePath;

    @BeforeEach
    void setUp() {
        state = new ProjectState();
        botPath = Paths.get("Bot.java").toAbsolutePath();
        goHomePath = Paths.get("GoHome.java").toAbsolutePath();
        state.addFile(new ProjectFile(botPath, BOT));
        state.addFile(new ProjectFile(goHomePath, GO_HOME));
        state.setActiveFile(botPath);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());
        state.setCompilationUnit(SourceParser.parse(BOT));
    }

    /** Points GoHome at different source — the other file is the one every interesting case lives in. */
    private void otherFile(String source) {
        state.addFile(new ProjectFile(goHomePath, source));
    }

    private MethodDeclaration clickAt() {
        TypeDeclaration bot = (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
        return bot.getMethods()[0];
    }

    private FunctionDraft before() {
        return MethodSignatures.draftOf(clickAt()).orElseThrow();
    }

    private static FunctionDraft draft(String name, BotType returns, List<FunctionDraft.Parameter> params) {
        return new FunctionDraft(name, BotType.Choice.of(returns), params);
    }

    private static FunctionDraft.Parameter param(String name, BotType type, int origin) {
        return new FunctionDraft.Parameter(name, BotType.Choice.of(type)).withOrigin(origin);
    }

    private static FunctionDraft.Parameter added(String name, BotType type) {
        return new FunctionDraft.Parameter(name, BotType.Choice.of(type));
    }

    private SignatureMigration.Plan planFor(FunctionDraft after) {
        MethodReferences.Result found = MethodReferences.find(state, clickAt());
        assertFalse(found.isRefusal(), "this fixture's calls are all decidable: " + found.refusal());
        return SignatureMigration.of(before(), after, clickAt(), found.calls());
    }

    /** The argument plan of the {@code index}-th call, which is the shape every assertion below is about. */
    private static List<SignatureMigration.ArgumentEdit> argumentsOf(SignatureMigration.Plan plan, int index) {
        SignatureMigration.CallChange change = plan.calls().get(index);
        return assertInstanceOf(SignatureMigration.CallChange.Rewrite.class, change).arguments();
    }

    // --- finding the calls ---------------------------------------------------------------------------------

    @Test
    void everyCallInTheProjectIsFound() {
        MethodReferences.Result found = MethodReferences.find(state, clickAt());

        assertFalse(found.isRefusal(), "nothing here is ambiguous");
        assertEquals(3, found.calls().size(), "two in the declaring file, one in the other");
        assertEquals(List.of("Bot", "GoHome"), found.fileNames());
    }

    @Test
    void anOverloadOfADifferentShapeIsNotThisMethod() {
        // Java allows clickAt(Point) beside clickAt(Point, int); renaming one must not touch the other.
        otherFile(GO_HOME.replace("Bot.clickAt(null, 1);", "Bot.clickAt(null);"));

        MethodReferences.Result found = MethodReferences.find(state, clickAt());

        assertFalse(found.isRefusal());
        assertEquals(List.of("Bot"), found.fileNames(), "the one-argument call belongs to another method");
    }

    @Test
    void aFileThatDoesNotParseRefusesTheWholeChange() {
        otherFile(GO_HOME.replace("}\n", ""));

        MethodReferences.Result found = MethodReferences.find(state, clickAt());

        assertTrue(found.isRefusal(), "a file that cannot be read might contain calls");
        assertNotNull(found.refusal());
        assertTrue(found.refusal().contains("GoHome"), "the refusal names the file: " + found.refusal());
    }

    @Test
    void aCallThroughSomethingUnreadableRefusesAndNamesTheFile() {
        // `helper.clickAt(...)` where nothing in sight declares `helper`: it may or may not be a Bot, and a
        // migration that guesses is a migration that breaks one file in four.
        otherFile(GO_HOME.replace("Bot.clickAt(null, 1);", "helper.clickAt(null, 1);"));

        MethodReferences.Result found = MethodReferences.find(state, clickAt());

        assertTrue(found.isRefusal());
        assertTrue(found.refusal().contains("GoHome"), found.refusal());
    }

    @Test
    void aReceiverDeclaredAsAnotherTypeIsNotOurMethod() {
        otherFile("""
                package test;

                public class GoHome {
                    public void go() {
                        Popups popups = new Popups();
                        popups.clickAt(null, 1);
                    }
                }
                """);

        MethodReferences.Result found = MethodReferences.find(state, clickAt());

        assertFalse(found.isRefusal(), "the receiver's declared type answers it: " + found.refusal());
        assertEquals(List.of("Bot"), found.fileNames());
    }

    @Test
    void aClassCallingItsOwnSameNamedFunctionIsLeftAlone() {
        otherFile("""
                package test;

                import com.botmaker.sdk.api.Point;

                public class GoHome {
                    public boolean clickAt(Point where, int tries) {
                        return false;
                    }

                    public void go() {
                        clickAt(null, 1);
                    }
                }
                """);

        MethodReferences.Result found = MethodReferences.find(state, clickAt());

        assertFalse(found.isRefusal(), "a class that declares its own is calling its own: " + found.refusal());
        assertEquals(List.of("Bot"), found.fileNames());
    }

    // --- what the change does to them ----------------------------------------------------------------------

    @Test
    void aRenameIsCarriedToEveryCallAndNothingElseMoves() {
        SignatureMigration.Plan plan = planFor(draft("tapAt", BotType.YES_NO,
                List.of(param("where", BotType.POINT, 0), param("tries", BotType.WHOLE_NUMBER, 1))));

        assertEquals(3, plan.calls().size());
        for (SignatureMigration.CallChange change : plan.calls()) {
            SignatureMigration.CallChange.Rewrite rewrite =
                    assertInstanceOf(SignatureMigration.CallChange.Rewrite.class, change);
            assertEquals("tapAt", rewrite.newName());
            assertEquals(List.of(new SignatureMigration.ArgumentEdit.Keep(0),
                    new SignatureMigration.ArgumentEdit.Keep(1)), rewrite.arguments());
        }
        assertTrue(plan.rescued().isEmpty());
        assertEquals(List.of("Bot — 2 calls", "GoHome — 1 call"), plan.perFileLines());
    }

    @Test
    void anAddedParameterIsFilledInAtEveryCall() {
        SignatureMigration.Plan plan = planFor(draft("clickAt", BotType.YES_NO,
                List.of(param("where", BotType.POINT, 0), param("tries", BotType.WHOLE_NUMBER, 1),
                        added("area", BotType.RECT))));

        List<SignatureMigration.ArgumentEdit> arguments = argumentsOf(plan, 0);
        assertEquals(3, arguments.size());
        assertInstanceOf(SignatureMigration.ArgumentEdit.Fresh.class, arguments.get(2));
        assertTrue(plan.changes().stream().anyMatch(line -> line.contains("new input \"area\"")),
                "the preview says so in words: " + plan.changes());
    }

    @Test
    void aRemovedParameterIsDroppedAtTheCallsAndRescuedInTheBody() {
        // `tries` is printed in the body, so removing it would leave a name with nothing behind it.
        SignatureMigration.Plan plan = planFor(draft("clickAt", BotType.YES_NO,
                List.of(param("where", BotType.POINT, 0))));

        assertEquals(List.of(new SignatureMigration.ArgumentEdit.Keep(0)), argumentsOf(plan, 0));
        assertEquals(1, plan.rescued().size());
        assertEquals("tries", plan.rescued().getFirst().name());
        assertEquals("int", plan.rescued().getFirst().type().sourceName());
    }

    @Test
    void aRemovedParameterTheBodyNeverUsedIsNotRescued() {
        // `hover(Point spot)` never mentions `spot`, so dropping it owes the body nothing — a local declared
        // for a name nobody reads would be a leftover the user has to delete by hand.
        TypeDeclaration bot = (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
        MethodDeclaration hover = bot.getMethods()[2];

        SignatureMigration.Plan plan = SignatureMigration.of(MethodSignatures.draftOf(hover).orElseThrow(),
                draft("hover", BotType.NOTHING, List.of()), hover, List.of());

        assertTrue(plan.rescued().isEmpty());
        assertTrue(plan.isEmpty(), "nothing to preview: no caller, and nothing owed to the body");
    }

    @Test
    void reorderingPermutesTheArgumentsRatherThanRetypingThem() {
        SignatureMigration.Plan plan = planFor(draft("clickAt", BotType.YES_NO,
                List.of(param("tries", BotType.WHOLE_NUMBER, 1), param("where", BotType.POINT, 0))));

        assertEquals(List.of(new SignatureMigration.ArgumentEdit.Keep(1),
                new SignatureMigration.ArgumentEdit.Keep(0)), argumentsOf(plan, 0));
        assertTrue(plan.changes().contains("inputs reordered"), plan.changes().toString());
    }

    @Test
    void aRetypedParameterKeepsWhatStillFitsAndReplacesWhatDoesNot() {
        // `clickAt(null, 3)`: the 3 cannot be a Point and is replaced by that type's default; the null says
        // nothing about itself, and doubt keeps what the user wrote.
        SignatureMigration.Plan plan = planFor(draft("clickAt", BotType.YES_NO,
                List.of(param("where", BotType.RECT, 0), param("tries", BotType.POINT, 1))));

        List<SignatureMigration.ArgumentEdit> arguments = argumentsOf(plan, 0);
        assertEquals(new SignatureMigration.ArgumentEdit.Keep(0), arguments.getFirst());
        assertInstanceOf(SignatureMigration.ArgumentEdit.Fresh.class, arguments.get(1));
    }

    @Test
    void aChangedReturnTypeReplacesTheUseButLeavesALineOfItsOwnAlone() {
        SignatureMigration.Plan plan = planFor(draft("clickAt", BotType.NOTHING,
                List.of(param("where", BotType.POINT, 0), param("tries", BotType.WHOLE_NUMBER, 1))));

        List<SignatureMigration.CallChange> inBot = new ArrayList<>(plan.calls().stream()
                .filter(change -> change.site().className().equals("Bot")).toList());
        assertEquals(2, inBot.size());
        assertInstanceOf(SignatureMigration.CallChange.Rewrite.class, inBot.getFirst(),
                "`clickAt(null, 3);` is a line of its own — nothing consumed what it gave back");

        SignatureMigration.CallChange.ValueReplaced replaced = assertInstanceOf(
                SignatureMigration.CallChange.ValueReplaced.class, inBot.get(1),
                "`boolean ok = clickAt(null, 4);` no longer has a value to assign");
        assertEquals("boolean", replaced.expected().sourceName(),
                "the slot is filled with what it was written to expect, and `ok` is never retyped");
    }

    /**
     * The report that overturned the earlier rule: a call inside {@code print(…)} kept its value replaced by a
     * default even though the argument accepts anything at all. "Its value is used" was being read as "its
     * value no longer fits", and those are different questions.
     */
    @Test
    void aChangedReturnTypeLeavesACallInASlotThatStillAcceptsIt() {
        otherFile("""
                package test;

                public class GoHome {
                    public void go() {
                        System.out.println(Bot.clickAt(null, 1));
                    }
                }
                """);

        SignatureMigration.Plan plan = planFor(draft("clickAt", BotType.WHOLE_NUMBER,
                List.of(param("where", BotType.POINT, 0), param("tries", BotType.WHOLE_NUMBER, 1))));

        SignatureMigration.CallChange inGoHome = plan.calls().stream()
                .filter(change -> change.site().className().equals("GoHome")).findFirst().orElseThrow();
        assertInstanceOf(SignatureMigration.CallChange.Rewrite.class, inGoHome,
                "println takes anything, so there is nothing about this call that stopped working");
    }

    /** The other side of the same question: a condition needs a yes/no, and a number is not one. */
    @Test
    void aChangedReturnTypeReplacesACallInASlotThatRefusesIt() {
        otherFile("""
                package test;

                public class GoHome {
                    public void go() {
                        if (Bot.clickAt(null, 1)) {
                        }
                    }
                }
                """);

        SignatureMigration.Plan plan = planFor(draft("clickAt", BotType.WHOLE_NUMBER,
                List.of(param("where", BotType.POINT, 0), param("tries", BotType.WHOLE_NUMBER, 1))));

        SignatureMigration.CallChange inGoHome = plan.calls().stream()
                .filter(change -> change.site().className().equals("GoHome")).findFirst().orElseThrow();
        assertInstanceOf(SignatureMigration.CallChange.ValueReplaced.class, inGoHome,
                "`if (…)` cannot be given a number, so this one really does have to be replaced");
    }

    // --- constructors --------------------------------------------------------------------------------------

    /**
     * A constructor's calls are {@code new Bot(…)}. The scan visited {@link org.eclipse.jdt.core.dom.MethodInvocation}
     * only, so it reported <b>zero</b> for a class instantiated all over the project — and "nothing calls this"
     * is the answer that lets a delete through.
     */
    @Test
    void everyInstantiationOfAClassIsFoundForItsConstructor() {
        String withConstructor = """
                package test;

                public class Bot {
                    public Bot(int tries) {
                    }
                }
                """;
        state.addFile(new ProjectFile(botPath, withConstructor));
        state.setCompilationUnit(SourceParser.parse(withConstructor));
        otherFile("""
                package test;

                public class GoHome {
                    public void go() {
                        Bot first = new Bot(1);
                        Bot second = new Bot(2);
                    }
                }
                """);

        TypeDeclaration bot = (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
        MethodReferences.Result found = MethodReferences.find(state, bot.getMethods()[0]);

        assertFalse(found.isRefusal(), "both are plain `new Bot(…)`: " + found.refusal());
        assertEquals(2, found.calls().size(), "every instantiation counts as a call");
        assertEquals(List.of("GoHome"), found.fileNames());
    }

    /** A {@code new} of the same arity naming some other class is not this constructor. */
    @Test
    void anInstantiationOfAnotherClassIsNotThisConstructor() {
        String withConstructor = """
                package test;

                public class Bot {
                    public Bot(int tries) {
                    }
                }
                """;
        state.addFile(new ProjectFile(botPath, withConstructor));
        state.setCompilationUnit(SourceParser.parse(withConstructor));
        otherFile("""
                package test;

                public class GoHome {
                    public void go() {
                        Object other = new StringBuilder(16);
                    }
                }
                """);

        TypeDeclaration bot = (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
        MethodReferences.Result found = MethodReferences.find(state, bot.getMethods()[0]);

        assertFalse(found.isRefusal(), "a different class name is decidable, not doubtful");
        assertTrue(found.calls().isEmpty(), "nothing builds a Bot here");
    }
}
