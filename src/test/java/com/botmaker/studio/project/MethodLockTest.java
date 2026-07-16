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
 * {@code Bot.supervise(GameLoop::run, GoHome::run, Startup::run)} binds each as a {@code Runnable}, so those
 * signatures are not the user's to rename or re-parameterise. The three hooks differ on the body:
 * GoHome/Startup are shipped as TODO stubs whose body is the whole point, while {@code GameLoop.run} is the
 * complete generated dispatch loop — all of it BotMaker's.
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
            public class GoHome {
                public static void run() {}
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
    void superviseHooksHaveALockedSignatureButAnEditableBody() {
        MethodLock lock = MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("GoHome.java"),
                methodNamed(GO_HOME, "run"));
        assertEquals(MethodLock.SIGNATURE, lock);
        assertTrue(lock.locksSignature(), "Bot.supervise binds GoHome::run as a Runnable");
        assertFalse(lock.locksBody(), "the body is exactly what the user is meant to write");
    }

    @Test
    void everySuperviseHookIsCovered() {
        for (String file : java.util.List.of("GoHome.java", "Startup.java")) {
            assertEquals(MethodLock.SIGNATURE,
                    MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage(file),
                            methodNamed(GO_HOME, "run")),
                    file + ".run is bound as a Runnable and must keep its signature");
        }
    }

    @Test
    void theGameLoopsRunIsFullyLocked() {
        // Unlike GoHome/Startup, GameLoop.run is not a stub: the generator ships the complete activity
        // dispatch loop, and an edited one is damage for ProjectRepair to restore, not user code.
        MethodLock lock = MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage("GameLoop.java"),
                methodNamed(GO_HOME, "run"));
        assertEquals(MethodLock.FULL, lock);
        assertTrue(lock.locksBody(), "the dispatch loop is generated wiring — the user's code goes in activities");
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
