package com.botmaker.studio.project;

import com.botmaker.studio.project.LockResolver.EditKind;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The truth table for {@link FileRole} × {@link MethodLock} × {@link EditKind}.
 *
 * <p>The two cases that matter most are the ones where the method's verdict must beat the file's: an activity's
 * {@code isEnabled()} ({@link MethodLock#FULL} inside an {@link FileRole#EDITABLE} file — locks), and an
 * activity's / GoHome's {@code run()} ({@link MethodLock#SIGNATURE} — keeps its body editable however the
 * signature is bound). {@code GameLoop.run} is deliberately <em>not</em> such a grant: it is the generated
 * dispatch loop, {@link MethodLock#FULL}, locked with the rest of its file.
 */
class LockResolverTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static Path inMainPackage(String fileName) {
        return CONFIG.mainSourceFile().getParent().resolve(fileName);
    }

    private static final Path GAME_LOOP = inMainPackage("GameLoop.java");
    private static final Path GO_HOME = inMainPackage("GoHome.java");
    private static final Path HELPER = inMainPackage("MyHelper.java");
    private static final Path ACTIVITY_STUB = CONFIG.activitiesPackageDir().resolve("Mining.java");
    private static final Path LIBRARY_FILE =
            Paths.get("/tmp/projects/MyBot/src/main/java/com/botmaker/library/Helper.java");

    private static final String SOURCE = """
            package com.mybot;
            public class GameLoop {
                private int field = 1;
                public static void run() { System.out.println("hi"); }
                public boolean isEnabled() { return true; }
                public void helper() { System.out.println("mine"); }
            }
            """;

    private static CompilationUnit parse() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(SOURCE.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static final CompilationUnit CU = parse();

    private static TypeDeclaration type() {
        return (TypeDeclaration) CU.types().getFirst();
    }

    private static MethodDeclaration method(String name) {
        for (MethodDeclaration m : type().getMethods()) {
            if (m.getName().getIdentifier().equals(name)) return m;
        }
        throw new AssertionError("no method " + name);
    }

    /** The statement inside {@code name}'s body — a node deep enough to exercise the ancestor walk. */
    private static Statement statementIn(String name) {
        return (Statement) method(name).getBody().statements().getFirst();
    }

    private static LockResolver resolver(Path file) {
        return new LockResolver(CONFIG, ProjectTemplate.GAME_BOT, file);
    }

    // --- reader mode: outranks every file/method verdict --------------------------------------------------

    @Test
    void readerModeDeniesEvenAUsersOwnHelperInAnEditableFile() {
        // A helper file is EDITABLE and its body is normally the user's…
        LockResolver editable = new LockResolver(CONFIG, ProjectTemplate.GAME_BOT, HELPER, false);
        assertTrue(editable.permits(statementIn("helper"), EditKind.BODY), "precondition: editable when editing");

        // …but opened for reading, the same edit is refused with the reader message.
        LockResolver reader = new LockResolver(CONFIG, ProjectTemplate.GAME_BOT, HELPER, true);
        LockResolver.Verdict v = reader.check(statementIn("helper"), EditKind.BODY);
        assertFalse(v.allowed());
        assertEquals(LockResolver.READER_MODE_REASON, v.reason());
        assertTrue(reader.suppressesInteraction(), "reader mode suppresses interaction across the file");
    }

    // --- the game loop: fully generated, fully locked ------------------------------------------------------

    @Test
    void theGameLoopsRunIsLockedBodyAndAll() {
        LockResolver r = resolver(GAME_LOOP);
        assertEquals(FileRole.GENERATED, r.role(), "precondition: the file really is scaffolding");
        assertFalse(r.bodyEditable(statementIn("run")), "the dispatch loop is generated wiring");
        assertFalse(r.permits(statementIn("run"), EditKind.BODY));
        assertFalse(r.permits(method("run"), EditKind.SIGNATURE), "Bot.supervise binds GameLoop::run");
    }

    @Test
    void theGameLoopsOwnScaffoldingIsLocked() {
        LockResolver r = resolver(GAME_LOOP);
        // Everything in the file that isn't the granted run() body: the class itself, and any other method.
        assertFalse(r.permits(type(), EditKind.SIGNATURE), "no adding members to a generated class");
        assertFalse(r.permits(statementIn("helper"), EditKind.BODY),
                "MethodLock.NONE defers to the file, and the file is generated");
    }

    // --- an editable file ---------------------------------------------------------------------------------

    @Test
    void aSuperviseHookInAnEditableFileKeepsItsBodyAndLosesItsSignature() {
        LockResolver r = resolver(GO_HOME);
        assertEquals(FileRole.EDITABLE, r.role());
        assertTrue(r.permits(statementIn("run"), EditKind.BODY));
        assertFalse(r.permits(method("run"), EditKind.SIGNATURE));
    }

    @Test
    void anOrdinaryUserFileIsFullyEditable() {
        LockResolver r = resolver(HELPER);
        assertTrue(r.permits(statementIn("run"), EditKind.BODY));
        assertTrue(r.permits(method("run"), EditKind.SIGNATURE), "a user's own run() is not a supervise hook");
        assertTrue(r.permits(type(), EditKind.SIGNATURE));
    }

    // --- activity stubs -----------------------------------------------------------------------------------

    @Test
    void anActivitysIsEnabledIsLockedBodyAndAll() {
        LockResolver r = resolver(ACTIVITY_STUB);
        assertEquals(FileRole.EDITABLE, r.role(), "the stub file itself is the user's");
        assertFalse(r.permits(statementIn("isEnabled"), EditKind.BODY), "generated wiring to the Activities flag");
        assertFalse(r.permits(method("isEnabled"), EditKind.SIGNATURE));
    }

    @Test
    void anActivitysRunBodyIsEditableButItsSignatureIsNot() {
        LockResolver r = resolver(ACTIVITY_STUB);
        assertTrue(r.permits(statementIn("run"), EditKind.BODY), "this is what the user came to write");
        assertFalse(r.permits(method("run"), EditKind.SIGNATURE), "it is an @Override of Activity.run");
    }

    // --- library ------------------------------------------------------------------------------------------

    @Test
    void libraryCodeRejectsEverything() {
        LockResolver r = resolver(LIBRARY_FILE);
        assertEquals(FileRole.LIBRARY, r.role());
        assertFalse(r.permits(statementIn("run"), EditKind.BODY));
        assertFalse(r.permits(statementIn("helper"), EditKind.BODY));
        assertFalse(r.permits(method("run"), EditKind.SIGNATURE));
        assertFalse(r.permits(type(), EditKind.SIGNATURE));
    }

    // --- nodes outside any method -------------------------------------------------------------------------

    @Test
    void aNodeOutsideAnyMethodIsJudgedByItsFileAlone() {
        assertFalse(resolver(GAME_LOOP).permits(type().getFields()[0], EditKind.SIGNATURE));
        assertTrue(resolver(HELPER).permits(type().getFields()[0], EditKind.SIGNATURE));
    }

    // --- the escape hatches -------------------------------------------------------------------------------

    @Test
    void noConfigMeansEverythingIsEditable() {
        // Tests and editor paths with no project open construct a permissive resolver rather than a null one.
        LockResolver none = new LockResolver(null, null, null);
        assertTrue(none.permits(method("run"), EditKind.SIGNATURE));
        assertTrue(none.permits(statementIn("isEnabled"), EditKind.BODY));
        assertTrue(none.check(null, EditKind.BODY).allowed(), "no project: nothing to protect");
    }

    @Test
    void aMissingTargetIsDeniedNotWavedThrough() {
        // The "we don't know the project" hatch belongs on config. A caller that forgot to say what it is
        // editing must fail loudly here, not silently rewrite locked code.
        LockResolver.Verdict v = resolver(GAME_LOOP).check(null, EditKind.BODY);
        assertFalse(v.allowed());
        assertNotNull(v.reason());
    }

    @Test
    void aRefusalAlwaysCarriesAReasonToShowTheUser() {
        for (Path file : java.util.List.of(GAME_LOOP, ACTIVITY_STUB, LIBRARY_FILE)) {
            for (EditKind kind : EditKind.values()) {
                for (var node : java.util.List.of(method("isEnabled"), statementIn("isEnabled"), type())) {
                    LockResolver.Verdict v = resolver(file).check(node, kind);
                    if (!v.allowed()) {
                        assertNotNull(v.reason(), file + "/" + kind);
                        assertFalse(v.reason().isBlank(), file + "/" + kind);
                    }
                }
            }
        }
    }
}
