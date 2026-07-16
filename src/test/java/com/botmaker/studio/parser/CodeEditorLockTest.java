package com.botmaker.studio.parser;

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
 * <p>The mirror-image case matters just as much and is easier to get wrong: {@code GameLoop.run} lives in a
 * generated file but exists <em>for</em> the user to fill in. Locking its body is the regression that made the
 * game loop unwritable, so "this edit must land" is asserted as carefully as "this edit must not".
 */
class CodeEditorLockTest {

    private static final Path PROJECTS = Paths.get("/tmp/projects");
    private static final ProjectConfig CONFIG = ProjectConfig.forProject("MyBot", PROJECTS);

    private static final List<String> RUNTIME_CLASSPATH =
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));

    private static final String GAME_LOOP = """
            package com.mybot;
            public class GameLoop {
                private static int ticks = 0;
                public static void run() {
                    int x = 1;
                }
                public static void helper() {
                    int y = 2;
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
            state = new ProjectState();
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
            BlockConverter.ConvertResult result = converter.convert(
                    source, state.getMutableNodeToBlockMap(), new BlockDragAndDropManager(bus), false, false);
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

    private static Fixture gameLoop() {
        return new Fixture(CONFIG.mainSourceFile().getParent().resolve("GameLoop.java"), GAME_LOOP);
    }

    private static Fixture activity() {
        return new Fixture(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY);
    }

    private static Fixture library() {
        return new Fixture(
                CONFIG.sourceRoot().resolve("com/botmaker/library/Helper.java"), GAME_LOOP);
    }

    // --- the edit that must LAND (bug: "I can't add any statement, even in run methods") ------------------

    @Test
    void aStatementCanBeAddedToAGeneratedFilesEditableRunBody() {
        Fixture f = gameLoop();
        f.editor.addStatement(f.body("run"), BlockCatalog.PRINT, 0);

        assertNotNull(f.lastCode, "GameLoop.run's body is the user's — the whole point of the file");
        assertTrue(f.lastCode.contains("BotMaker.print("), "the print should be in run():\n" + f.lastCode);
    }

    @Test
    void anActivitysRunBodyAcceptsEdits() {
        Fixture f = activity();
        f.editor.addStatement(f.body("run"), BlockCatalog.PRINT, 0);
        assertNotNull(f.lastCode, "this is what the user came to write");
    }

    // --- the edits that must NOT land ---------------------------------------------------------------------

    @Test
    void aLockedMethodsSignatureCannotBeRenamedEvenWithoutTheUi() {
        Fixture f = gameLoop();
        f.editor.renameMethod(f.method("run"), "tick");
        f.assertRefused("renaming a Bot.supervise hook");
    }

    @Test
    void aLockedMethodCannotBeDeletedOrReParameterised() {
        Fixture f = gameLoop();
        f.editor.deleteMethod(f.method("run"));
        f.assertRefused("deleting a supervise hook");

        Fixture g = gameLoop();
        g.editor.addParameterToMethod(g.method("run"), com.botmaker.studio.types.ResolvedType.named("int"), "n");
        g.assertRefused("adding a parameter to a supervise hook");
    }

    @Test
    void aStatementCannotBeAddedToAGeneratedFilesOtherMethods() {
        // MethodLock.NONE defers to FileRole, and the file is scaffolding.
        Fixture f = gameLoop();
        f.editor.addStatement(f.body("helper"), BlockCatalog.PRINT, 0);
        f.assertRefused("editing a generated file's own helper");
    }

    @Test
    void aGeneratedFilesClassStructureIsLocked() {
        Fixture f = gameLoop();
        f.editor.addMethodToClass(f.type(), "sneaky", "void", 0);
        f.assertRefused("adding a method to a generated class");
    }

    @Test
    void anActivitysIsEnabledBodyRejectsAnExpressionReplacement() {
        // The exact shape of the reported bug: the expression menu edits a FULL-locked body and it sticks.
        Fixture f = activity();
        ReturnStatement ret = (ReturnStatement) f.method("isEnabled").getBody().statements().getFirst();
        f.editor.replaceExpression(ret.getExpression(), ExpressionCatalog.TEXT);
        f.assertRefused("replacing the expression in generated isEnabled() wiring");
    }

    @Test
    void anActivitysRunSignatureIsLockedBecauseItIsAnOverride() {
        Fixture f = activity();
        f.editor.renameMethod(f.method("run"), "execute");
        f.assertRefused("renaming an @Override of Activity.run");
    }

    @Test
    void aLibraryFileRejectsEveryEdit() {
        Fixture f = library();
        f.editor.addStatement(f.body("run"), BlockCatalog.PRINT, 0);
        f.assertRefused("editing bundled library code");
    }

    @Test
    void aVendoredGameLoopDoesNotGetItsBodyUnlocked() {
        // SIGNATURE *grants* a body. If the hook were matched on file name alone, this library file's run()
        // would become writable — the one place nothing may be touched.
        Fixture f = library();
        f.editor.addStatement(f.body("run"), BlockCatalog.PRINT, 0);
        assertNull(f.lastCode);
    }

    // --- moves are two edits ------------------------------------------------------------------------------

    @Test
    void aStatementCannotBeDraggedOutOfALockedBody() {
        // Checking only the destination would let a drag empty out a generated method.
        Fixture f = gameLoop();
        f.editor.moveStatement(f.body("helper").getStatements().getFirst(),
                f.body("helper"), f.body("run"), 0);
        f.assertRefused("dragging a statement out of locked scaffolding");
    }

    // --- the menu path, driven end-to-end -----------------------------------------------------------------

    @Test
    void theExpressionMenuPathIsRejectedToo() {
        // AbstractCodeBlock.applyExpressionSelection dispatches menu picks to these same calls. Whatever the
        // menu decides to render, the write layer is what actually holds.
        Fixture f = activity();
        ReturnStatement ret = (ReturnStatement) f.method("isEnabled").getBody().statements().getFirst();
        Expression original = ret.getExpression();

        f.editor.replaceWithVariable(original, "somethingElse");
        f.editor.replaceLiteralValue(original, "false");
        f.editor.replaceWithRawExpression(original, "1 + 1");

        assertNull(f.lastCode, "no menu pick may rewrite generated wiring");
    }

    // --- the escape hatch ---------------------------------------------------------------------------------

    @Test
    void withNoProjectEverythingIsStillEditable() {
        // Tests and no-project editor paths construct CodeEditor with a null config; the guard must not turn
        // that into a locked editor.
        ProjectState state = new ProjectState();
        Path file = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(file, GAME_LOOP));
        state.setActiveFile(file);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(List.of());
        state.setCurrentCode(GAME_LOOP);

        EventBus bus = new EventBus(false);
        String[] last = new String[1];
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> last[0] = e.newCode());

        BlockConverter converter = new BlockConverter(null, state);
        BlockConverter.ConvertResult result = converter.convert(
                GAME_LOOP, state.getMutableNodeToBlockMap(), new BlockDragAndDropManager(bus), false, false);
        state.setCompilationUnit(result.cu());

        CodeEditor editor = new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state));
        TypeDeclaration type = (TypeDeclaration) result.root().getAstNode();
        editor.renameMethod(type.getMethods()[0], "renamed");

        assertNotNull(last[0], "a null config means no project to protect, not a locked one");
    }
}
