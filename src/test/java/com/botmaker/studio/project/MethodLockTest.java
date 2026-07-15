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
 * signatures are not the user's to rename or re-parameterise — even though the bodies are the whole point.
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
        for (String file : java.util.List.of("GoHome.java", "Startup.java", "GameLoop.java")) {
            assertEquals(MethodLock.SIGNATURE,
                    MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, inMainPackage(file),
                            methodNamed(GO_HOME, "run")),
                    file + ".run is bound as a Runnable and must keep its signature");
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
    void anActivitysRunIsTheUsers() {
        Path stub = CONFIG.activitiesPackageDir().resolve("Mining.java");
        assertEquals(MethodLock.NONE,
                MethodLock.of(CONFIG, ProjectTemplate.GAME_BOT, stub, methodNamed(ACTIVITY, "run")));
        assertTrue(MethodLock.isUsersEntryPoint(CONFIG, ProjectTemplate.GAME_BOT, stub,
                methodNamed(ACTIVITY, "run")));
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
