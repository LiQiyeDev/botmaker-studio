package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The write layer refuses locked edits <b>no matter which UI path asks</b>.
 *
 * <p>This is the test that would have caught the bug it was written for. Read-only used to be enforced only by
 * not rendering a control, so every screen that forgot — the expression menu, the method-call dropdown, the
 * separator "+" — silently rewrote generated code and persisted it. No block or JavaFX node appears below: these
 * call {@link CodeEditor} directly, exactly as a forgetful UI path would, and the guard still has to hold.
 *
 * <p>The mirror-image case matters just as much and is easier to get wrong, so "this edit must land" is
 * asserted as carefully as "this edit must not".
 *
 * <p><b>What the refusals are about changed on 2026-08-29.</b> Most of them used to be about generated code —
 * a {@code FlowDriver} BotMaker wrote and rewrote, an activity stub's {@code isEnabled()} wiring, a signature
 * an {@code @Override} fixed. Nothing generates a project's Java, so those files and members are the user's
 * and every one of those edits now lands. The two locks left are the two that were never about generation:
 * <b>bundled library source</b>, and a <b>bot opened for reading</b> (an installed one, see
 * {@code ProjectMode}). They are asserted here through the same forgetful-UI paths.
 */
class CodeEditorLockTest {

    private static final Path PROJECTS = Paths.get("/tmp/projects");
    private static final ProjectConfig CONFIG = ProjectConfig.forProject("MyBot", PROJECTS);

    private static final List<String> RUNTIME_CLASSPATH =
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));

    private static final String FLOW_DRIVER = """
            package com.mybot;
            public class FlowDriver {
                private static int ticks = 0;
                public static void run() {
                    int x = 1;
                }
                public static void helper() {
                    int y = 2;
                }
            }
            """;

    /** The shape a new project starts with: a locked main(), and the user's own methods beside it. */
    private static final String ENTRY_POINT = """
            package com.mybot;
            public class MyBot {
                public static void main(String[] args) {
                    int x = 1;
                }
                static void goHome() {
                    int y = 2;
                }
            }
            """;

    private static final String ACTIVITY_WITH_COMMENT = """
            package com.mybot.activities;
            public class Mining extends Activity {
                @Override public boolean isEnabled() { return Activities.Mining; }
                @Override public void run() {
                    // your code goes here
                }
            }
            """;

    private static final String ACTIVITY_WITH_FOREACH = """
            package com.mybot.activities;
            import java.util.List;
            public class Mining extends Activity {
                @Override public boolean isEnabled() { return Activities.Mining; }
                @Override public void run() {
                    for (String item : List.of("a", "b")) {
                        String copy = item;
                    }
                }
            }
            """;

    private static final String ACTIVITY = """
            package com.mybot.activities;
            public class Mining extends Activity {
                @Override public boolean isEnabled() { return Activities.Mining; }
                @Override public void run() {
                    int x = 1;
                }
            }
            """;

    /** A CodeEditor over {@code source} at {@code file}, plus the block tree and the code it last published. */
    private static final class Fixture {
        final CodeEditor editor;
        final ProjectState state;
        final AbstractCodeBlock root;
        String lastCode;
        final List<String> statusMessages = new ArrayList<>();

        Fixture(Path file, String source) {
            this(file, source, false);
        }

        Fixture(Path file, String source, boolean readerMode) {
            state = new ProjectState();
            state.setReaderMode(readerMode);
            state.addFile(new ProjectFile(file, source));
            state.setActiveFile(file);
            // The project lives at a notional /tmp path (FileRole only compares paths, it never reads them),
            // but the parser needs a real source root and classpath to resolve against.
            state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
            state.setResolvedClasspath(RUNTIME_CLASSPATH);
            state.setTemplate(ProjectTemplate.GAME_BOT);
            state.setCurrentCode(source);

            EventBus bus = new EventBus(false);
            bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode = e.newCode());
            bus.subscribe(CoreApplicationEvents.StatusMessageEvent.class, e -> statusMessages.add(e.message()));

            BlockConverter converter = new BlockConverter(CONFIG, state);
            BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                    converter, state, source, new BlockDragAndDropManager(bus), false, false);
            state.setCompilationUnit(result.cu());
            root = result.root();
            assertNotNull(root, "converter should produce a root block");

            editor = new CodeEditor(CONFIG, state, bus, new ProjectAnalyzer(null, state));
        }

        TypeDeclaration type() {
            return (TypeDeclaration) root.getAstNode();
        }

        MethodDeclaration method(String name) {
            for (MethodDeclaration m : type().getMethods()) {
                if (m.getName().getIdentifier().equals(name)) return m;
            }
            throw new AssertionError("no method " + name);
        }

        /** The {@link BodyBlock} for {@code name}'s body, found the way CodeEditorService finds it: by AST node. */
        BodyBlock body(String name) {
            var target = method(name).getBody();
            for (CodeBlock b : all(root)) {
                if (b instanceof BodyBlock bb && bb.getAstNode() == target) return bb;
            }
            throw new AssertionError("no body block for " + name);
        }

        private static List<CodeBlock> all(CodeBlock from) {
            List<CodeBlock> out = new ArrayList<>();
            out.add(from);
            if (from instanceof BlockWithChildren p) {
                for (CodeBlock c : p.getChildren()) out.addAll(all(c));
            }
            return out;
        }

        void assertRefused(String what) {
            assertNull(lastCode, what + " must not reach the source");
            assertFalse(statusMessages.isEmpty(), what + " must tell the user why, not fail silently");
        }
    }

    private static Fixture flowDriver() {
        return new Fixture(CONFIG.flowDriverSourceFile(), FLOW_DRIVER);
    }

    /** The same project, opened for reading — someone else's installed bot. */
    private static Fixture reading() {
        return new Fixture(CONFIG.flowDriverSourceFile(), FLOW_DRIVER, true);
    }

    private static Fixture activity() {
        return new Fixture(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY);
    }

    private static Fixture library() {
        return new Fixture(
                CONFIG.sourceRoot().resolve("com/botmaker/library/Helper.java"), FLOW_DRIVER);
    }

    // --- the edit that must LAND (bug: "I can't add any statement, even in run methods") ------------------

    @Test
    void anActivitysRunBodyAcceptsEdits() {
        Fixture f = activity();
        f.editor.addStatement(f.body("run"), BlockCatalog.PRINT, 0);
        assertNotNull(f.lastCode, "this is what the user came to write");
        assertTrue(f.lastCode.contains("System.out.println("), "the print should be in run():\n" + f.lastCode);
    }

    @Test
    void aStatementCanBeAddedBelowACommentOnlyBody() {
        // CommentBlock is a StatementBlock, so a body whose only child is a comment reports the "+" below it at
        // child-index 1 — but a Comment isn't in JDT Block.statements(), so inserting at statements() index 1 of
        // an empty list threw IndexOutOfBounds. This is why stub run() bodies with a comment placeholder
        // refused every statement.
        Fixture f = new Fixture(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY_WITH_COMMENT);
        f.editor.addStatement(f.body("run"), BlockCatalog.PRINT, 1);

        assertNotNull(f.lastCode, "adding a statement below a comment must not crash");
        assertTrue(f.lastCode.contains("System.out.println("), "the print should land in run():\n" + f.lastCode);
    }

    @Test
    void renamingAForEachVariableRewritesItsReferencesToo() {
        // Renaming only the declaration left the body on the old name, so the file stopped compiling.
        Fixture f = new Fixture(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY_WITH_FOREACH);
        var loop = (org.eclipse.jdt.core.dom.EnhancedForStatement) f.method("run").getBody().statements().getFirst();
        f.editor.renameForEachVariable(loop.getParameter().getName(), "element");

        assertNotNull(f.lastCode, "renaming a loop variable in an editable run() body is allowed");
        assertTrue(f.lastCode.contains("String element :"), "declaration should be renamed:\n" + f.lastCode);
        assertTrue(f.lastCode.contains("copy = element"), "the reference should be renamed too:\n" + f.lastCode);
        assertFalse(f.lastCode.contains("item"), "no stale reference to the old name may remain:\n" + f.lastCode);
    }

    // --- the edits that used to be refused and now land ---------------------------------------------------

    /**
     * {@code FlowDriver.java} is the strongest case in the file: BotMaker wrote it in every game bot and every
     * line of it was locked, body, signature and class header alike. In a project that still has one, all
     * three edits land — nothing will write over them.
     */
    @Test
    void aFileBotMakerUsedToGenerateAcceptsEveryEdit() {
        Fixture body = flowDriver();
        body.editor.addStatement(body.body("run"), BlockCatalog.PRINT, 0);
        assertNotNull(body.lastCode, "the body is the user's now");

        Fixture rename = flowDriver();
        rename.editor.renameMethod(rename.method("run"), "tick");
        assertNotNull(rename.lastCode, "so is the signature");

        Fixture structure = flowDriver();
        structure.editor.addMethodToClass(structure.type(), "mine", "void", 0);
        assertNotNull(structure.lastCode, "and the class header");
    }

    /** An activity's {@code run()} is an ordinary method in an ordinary class — nothing overrides anything. */
    @Test
    void anActivitysRunCanBeRenamedNow() {
        Fixture f = activity();
        f.editor.renameMethod(f.method("run"), "execute");
        assertNotNull(f.lastCode, "an activity's body is reached by name from Activities.define, not by class");
    }

    // --- the edits that must NOT land ---------------------------------------------------------------------

    @Test
    void aLibraryFileRejectsEveryEdit() {
        Fixture f = library();
        f.editor.addStatement(f.body("run"), BlockCatalog.PRINT, 0);
        f.assertRefused("editing bundled library code");
    }

    @Test
    void aBotOpenedForReadingRejectsEveryEdit() {
        Fixture f = reading();
        f.editor.addStatement(f.body("run"), BlockCatalog.PRINT, 0);
        f.assertRefused("editing someone else's installed bot");
    }

    @Test
    void readingRefusesTheSignatureAndTheClassHeaderToo() {
        Fixture rename = reading();
        rename.editor.renameMethod(rename.method("run"), "tick");
        rename.assertRefused("renaming a method in a bot open for reading");

        Fixture structure = reading();
        structure.editor.addMethodToClass(structure.type(), "sneaky", "void", 0);
        structure.assertRefused("adding a method to a class in a bot open for reading");
    }

    // --- moves are two edits ------------------------------------------------------------------------------

    @Test
    void aStatementCannotBeDraggedOutOfALockedBody() {
        // Checking only the destination would let a drag empty out a body nothing may change.
        Fixture f = reading();
        f.editor.moveStatement(f.body("helper").getStatements().getFirst(),
                f.body("helper"), f.body("run"), 0);
        f.assertRefused("dragging a statement out of a bot open for reading");
    }

    // --- the menu path, driven end-to-end -----------------------------------------------------------------

    @Test
    void theExpressionMenuPathIsRejectedToo() {
        // AbstractCodeBlock.applyExpressionSelection dispatches menu picks to these same calls. Whatever the
        // menu decides to render, the write layer is what actually holds.
        Fixture f = new Fixture(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY, true);
        ReturnStatement ret = (ReturnStatement) f.method("isEnabled").getBody().statements().getFirst();
        Expression original = ret.getExpression();

        f.editor.replaceWithVariable(original, "somethingElse");
        f.editor.replaceLiteralValue(original, "false");
        f.editor.replaceWithRawExpression(original, "1 + 1");

        assertNull(f.lastCode, "no menu pick may rewrite a bot open for reading");
    }

    // --- the one member-level rule left: main() -----------------------------------------------------------

    /**
     * {@code main(String[])} is the method Java itself looks for. Renaming it, deleting it or changing its
     * parameter is the one edit whose consequence the user cannot read off the screen — the project stops
     * launching, or stops compiling, with nothing pointing at what changed.
     */
    @Test
    void theEntryPointsMainSignatureIsRefused() {
        Fixture rename = new Fixture(CONFIG.mainSourceFile(), ENTRY_POINT);
        rename.editor.renameMethod(rename.method("main"), "start");
        rename.assertRefused("renaming main()");

        Fixture delete = new Fixture(CONFIG.mainSourceFile(), ENTRY_POINT);
        delete.editor.deleteMethod(delete.method("main"));
        delete.assertRefused("deleting main()");

        Fixture parameter = new Fixture(CONFIG.mainSourceFile(), ENTRY_POINT);
        parameter.editor.renameMethodParameter(parameter.method("main"), 0, "argv");
        parameter.assertRefused("renaming main()'s parameter");
    }

    /**
     * And its <b>body</b> is the user's, which is the whole point of the file. It is where the bot is put
     * together, one static call at a time — {@code PopupGuard.install}, {@code Bot.start},
     * {@code FlowGraph.run} are ordinary API methods a user calls or does not.
     */
    @Test
    void theEntryPointsMainBodyIsTheUsers() {
        Fixture f = new Fixture(CONFIG.mainSourceFile(), ENTRY_POINT);
        f.editor.addStatement(f.body("main"), BlockCatalog.PRINT, 0);
        assertNotNull(f.lastCode, "main()'s body is where the bot is assembled:\n" + f.lastCode);
        assertTrue(f.lastCode.contains("System.out.println("), "the print should land in main():\n" + f.lastCode);
    }

    /** The rest of the entry point file is ordinary user code too. */
    @Test
    void everythingElseInTheEntryPointFileIsTheUsers() {
        Fixture f = new Fixture(CONFIG.mainSourceFile(), ENTRY_POINT);
        f.editor.addStatement(f.body("goHome"), BlockCatalog.PRINT, 0);
        assertNotNull(f.lastCode, "goHome is the user's to fill in:\n" + f.lastCode);

        Fixture g = new Fixture(CONFIG.mainSourceFile(), ENTRY_POINT);
        g.editor.addMethodToClass(g.type(), "mine", "void", 0);
        assertNotNull(g.lastCode, "and they may add methods beside it");
    }

    /**
     * The rule is matched on the method's shape, not on the file's path: the entry point is the user's file to
     * rename, move or split, and a path-keyed rule would stop holding the moment they did.
     */
    @Test
    void mainIsProtectedWhereverTheUserPutsIt() {
        Fixture f = new Fixture(CONFIG.mainSourceFile().getParent().resolve("Renamed.java"), ENTRY_POINT);
        f.editor.renameMethod(f.method("main"), "start");
        f.assertRefused("renaming main() in a renamed entry point");
    }

    // --- the escape hatch ---------------------------------------------------------------------------------

    @Test
    void withNoProjectEverythingIsStillEditable() {
        // Tests and no-project editor paths construct CodeEditor with a null config; the guard must not turn
        // that into a locked editor.
        ProjectState state = new ProjectState();
        Path file = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(file, FLOW_DRIVER));
        state.setActiveFile(file);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(List.of());
        state.setCurrentCode(FLOW_DRIVER);

        EventBus bus = new EventBus(false);
        String[] last = new String[1];
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> last[0] = e.newCode());

        BlockConverter converter = new BlockConverter(null, state);
        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                converter, state, FLOW_DRIVER, new BlockDragAndDropManager(bus), false, false);
        state.setCompilationUnit(result.cu());

        CodeEditor editor = new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state));
        TypeDeclaration type = (TypeDeclaration) result.root().getAstNode();
        editor.renameMethod(type.getMethods()[0], "renamed");

        assertNotNull(last[0], "a null config means no project to protect, not a locked one");
    }
}
