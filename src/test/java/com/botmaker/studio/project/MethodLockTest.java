package com.botmaker.studio.project;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in which methods the user may change. The contract being protected is the SDK's:
 * {@code Bot.start(GameLoop::run, GoHome.INSTANCE::execute, Startup::run)} binds each hook, so those signatures
 * are not the user's to rename or re-parameterise. The three hooks differ on the body: {@code GoHome} is now an
 * {@code Activity} subclass shipped as a TODO stub whose {@code run()} body is the whole point (its
 * {@code isEnabled()} is generated wiring), while {@code GameLoop.run} (the dispatch loop) and
 * {@code Startup.run} ({@code Target.start()} over the configured launch target) are complete generated wiring —
 * all of it BotMaker's.
 *
 * <p>Note the asymmetry these tests pin down: {@link MethodLock#NONE} <b>defers</b> to {@link FileRole}, while
 * {@link MethodLock#SIGNATURE} <b>grants</b> the body regardless of the file — which is why a {@code SIGNATURE}
 * verdict must never be handed out on file name alone.
 */
class MethodLockTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static Path inMainPackage(String fileName) {
        return CONFIG.mainSourceFile().getParent().resolve(fileName);
    }

    /** Parses {@code source} and returns its first type's method named {@code name}. */
    private static MethodDeclaration methodNamed(String source, String name) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        TypeDeclaration type = (TypeDeclaration) cu.types().getFirst();
        for (MethodDeclaration m : type.getMethods()) {
            if (m.getName().getIdentifier().equals(name)) return m;
        }
        throw new AssertionError("no method " + name);
    }

    private static final String GO_HOME = """
            package com.mybot;
            import com.botmaker.sdk.api.bot.Activity;
            public class GoHome extends Activity<GoHome.Outcome> {
                public static final GoHome INSTANCE = new GoHome();
                public enum Outcome { NEXT }
                @Override public boolean isEnabled() { return true; }
                @Override public Outcome run() { return Outcome.NEXT; }
            }
            """;

    private static final String ACTIVITY = """
            package com.mybot.activities;
            public class Mining extends Activity {
                @Override public boolean isEnabled() { return Activities.Mining; }
                @Override public void run() {}
            }
            """;

    private static final String HELPER = """
            package com.mybot;
            public class MyHelper {
                public static void run() {}
                public boolean isEnabled() { return true; }
            }
            """;

    @Test
    void goHomeRunHasALockedSignatureButAnEditableBody() {
        // GoHome is now an Activity subclass shipped as a TODO stub: run() is an @Override of Activity.run, so
        // its signature is BotMaker's (the entry point / driver call it via GoHome.INSTANCE.execute()), but its
        // body is the whole point. (GameLoop and Startup are generated wiring — FULL, tested below.)
        MethodLock lock = MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("GoHome.java"),
                methodNamed(GO_HOME, "run"));
        assertEquals(MethodLock.SIGNATURE, lock);
        assertTrue(lock.locksSignature(), "run() is an @Override of Activity.run");
        assertFalse(lock.locksBody(), "the body is exactly what the user is meant to write");
    }

    @Test
    void goHomeIsEnabledIsFullyLocked() {
        // Like any activity's isEnabled(), GoHome's is generated wiring (it simply returns true) — not a thing
        // to hand-edit.
        MethodLock lock = MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("GoHome.java"),
                methodNamed(GO_HOME, "isEnabled"));
        assertEquals(MethodLock.FULL, lock);
        assertTrue(lock.locksBody(), "isEnabled is generated wiring");
    }

    @Test
    void theGameLoopAndStartupRunsAreFullyLocked() {
        // Unlike GoHome, GameLoop.run and Startup.run are not stubs: GameLoop ships the complete activity
        // dispatch loop and Startup ships `Target.start()` over the configured launch target. An edited body is
        // damage for ProjectRepair to restore, not user code.
        for (String file : java.util.List.of("GameLoop.java", "Startup.java")) {
            MethodLock lock = MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage(file),
                    methodNamed(GO_HOME, "run"));
            assertEquals(MethodLock.FULL, lock, file + ".run is generated wiring");
            assertTrue(lock.locksBody(), file + " is generated — the user's code goes elsewhere");
        }
    }

    @Test
    void anActivitysIsEnabledIsFullyLocked() {
        Path stub = CONFIG.activitiesPackageDir().resolve("Mining.java");
        MethodLock lock = MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, stub, methodNamed(ACTIVITY, "isEnabled"));
        assertEquals(MethodLock.FULL, lock);
        assertTrue(lock.locksSignature());
        assertTrue(lock.locksBody(), "isEnabled is wiring to the generated Activities flag");
    }

    @Test
    void anActivitysRunHasALockedSignatureButAnEditableBody() {
        // The body is the whole reason the file exists, but the signature is not the user's: the stub is
        // generated as `@Override public void run()`, so a rename or an added parameter silently stops
        // overriding Activity.run and the activity never executes.
        Path stub = CONFIG.activitiesPackageDir().resolve("Mining.java");
        MethodLock lock = MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, stub, methodNamed(ACTIVITY, "run"));
        assertEquals(MethodLock.SIGNATURE, lock);
        assertTrue(lock.locksSignature(), "run() is an @Override of Activity.run");
        assertFalse(lock.locksBody(), "the body is what the user came to write");
        assertTrue(MethodLock.isUsersEntryPoint(CONFIG, ProjectTemplate.GAME_BOT, stub,
                methodNamed(ACTIVITY, "run")));
    }

    @Test
    void aSuperviseHookNameOutsideTheMainPackageIsNotAHook() {
        // SIGNATURE *grants* body edits, so matching on the bare file name would unlock a run() body inside a
        // bundled library file — the one place nothing may be touched.
        Path vendored = Paths.get("/tmp/projects/MyBot/src/main/java/com/botmaker/library/GameLoop.java");
        Path userSubpackage = CONFIG.mainSourceFile().getParent().resolve("util").resolve("GameLoop.java");
        assertEquals(MethodLock.NONE,
                MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, vendored, methodNamed(GO_HOME, "run")));
        assertEquals(MethodLock.NONE,
                MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, userSubpackage, methodNamed(GO_HOME, "run")));
    }

    @Test
    void theNamesOnlyMatterInTheScaffoldFiles() {
        // A user class of their own that happens to have a run()/isEnabled() is not scaffolding.
        Path helper = inMainPackage("MyHelper.java");
        assertEquals(MethodLock.NONE,
                MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, helper, methodNamed(HELPER, "run")));
        assertEquals(MethodLock.NONE,
                MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, helper, methodNamed(HELPER, "isEnabled")));
    }

    @Test
    void anEmptyProjectHasNoSuperviseContractToProtect() {
        assertEquals(MethodLock.NONE,
                MethodLock.of(CONFIG, ProjectTemplate.EMPTY, inMainPackage("GoHome.java"),
                        methodNamed(GO_HOME, "run")));
    }

    @Test
    void unknownInputsBelongToTheUser() {
        assertEquals(MethodLock.NONE, MethodLock.of(null, ProjectTemplate.GAME_BOT,
                inMainPackage("GoHome.java"), methodNamed(GO_HOME, "run")));
        assertEquals(MethodLock.NONE, MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, null,
                methodNamed(GO_HOME, "run")));
        assertEquals(MethodLock.NONE, MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT,
                inMainPackage("GoHome.java"), null));
    }

    @Test
    void badgesTellTheUserWhichMethodIsTheirs() {
        assertNull(MethodLock.NONE.badge());
        assertEquals("Name and parameters required by BotMaker", MethodLock.SIGNATURE.badge());
        assertEquals("Generated - Read Only", MethodLock.FULL.badge());
    }
}
