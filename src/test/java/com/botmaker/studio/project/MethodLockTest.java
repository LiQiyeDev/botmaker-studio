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
 * {@code Bot.start(FlowDriver::run, GoHome.INSTANCE::execute)} binds the hook, so its signature is not the
 * user's to rename or re-parameterise. {@code GoHome} is an {@code Activity} subclass shipped as a TODO stub
 * whose {@code run()} body is the whole point, while its {@code isEnabled()} is generated wiring. It is the
 * only file this class claims now: {@code GameLoop.java} and {@code Startup.java} were the other two hooks and
 * both have been retired.
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

    private static final String POPUPS = """
            package com.mybot;
            import com.botmaker.sdk.api.bot.Activity;
            public class Popups extends Activity<Popups.Outcome> {
                public static final Popups INSTANCE = new Popups();
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
        // body is the whole point.
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

    /**
     * {@code Popups} is the second hook of this shape: the entry point binds {@code Popups.INSTANCE::execute}
     * as the popup guard, so its signature is BotMaker's for exactly the reason GoHome's is — and its body,
     * the logic that decides which combination of templates means "a popup is up", is the user's.
     */
    @Test
    void popupsIsLockedLikeGoHome() {
        Path file = inMainPackage("Popups.java");
        assertEquals(MethodLock.SIGNATURE,
                MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, file, methodNamed(POPUPS, "run")));
        assertEquals(MethodLock.FULL,
                MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, file, methodNamed(POPUPS, "isEnabled")));
    }

    @Test
    void theRetiredScaffoldFilesAreNoLongerClaimed() {
        // GameLoop.run and Startup.run were both FULL here. The files are no longer generated — the entry point
        // binds FlowDriver::run and the SDK supplies the launch step — so a copy still sitting in a project is
        // one the user chose to keep, and claiming it would lock a file BotMaker no longer writes or repairs.
        for (String file : java.util.List.of("GameLoop.java", "Startup.java")) {
            assertEquals(MethodLock.NONE,
                    MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage(file),
                            methodNamed(GO_HOME, "run")),
                    file + " is not scaffolding any more");
            assertEquals(FileRole.EDITABLE, FileRole.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage(file)),
                    file + " is the user's now, so nothing may lock it");
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
        Path vendored = Paths.get("/tmp/projects/MyBot/src/main/java/com/botmaker/library/GoHome.java");
        Path userSubpackage = CONFIG.mainSourceFile().getParent().resolve("util").resolve("GoHome.java");
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
