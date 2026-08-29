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
 * The truth table for {@link LockResolver} — which is now two rows long, and it is worth recording what the
 * other rows were.
 *
 * <p>This used to be {@link FileRole} × {@code MethodLock} × {@link EditKind}: a generated file whose one
 * granted method kept an editable body, an activity's {@code isEnabled()} locked inside a file the user
 * otherwise owned, a flow driver locked wholesale. All of it described code BotMaker wrote and rewrote, and
 * it writes none — so the two verdicts that remain are the two that were never about generation at all: a
 * bot opened for <em>reading</em>, and bundled library source.
 */
class LockResolverTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static Path inMainPackage(String fileName) {
        return CONFIG.mainSourceFile().getParent().resolve(fileName);
    }

    private static final Path HELPER = inMainPackage("MyHelper.java");
    private static final Path FLOW_DRIVER = inMainPackage("FlowDriver.java");
    private static final Path LIBRARY_FILE =
            Paths.get("/tmp/projects/MyBot/src/main/java/com/botmaker/library/Helper.java");

    private static final String SOURCE = """
            package com.mybot;
            public class FlowDriver {
                private int field = 1;
                public static void run() { System.out.println("hi"); }
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
        return new LockResolver(CONFIG, file);
    }

    // --- reader mode: outranks the file's own verdict -----------------------------------------------------

    @Test
    void readerModeDeniesEvenAUsersOwnHelperInAnEditableFile() {
        // A helper file is EDITABLE and its body is normally the user's…
        LockResolver editable = new LockResolver(CONFIG, HELPER, false);
        assertTrue(editable.permits(statementIn("helper"), EditKind.BODY), "precondition: editable when editing");

        // …but opened for reading, the same edit is refused with the reader message.
        LockResolver reader = new LockResolver(CONFIG, HELPER, true);
        LockResolver.Verdict v = reader.check(statementIn("helper"), EditKind.BODY);
        assertFalse(v.allowed());
        assertEquals(LockResolver.READER_MODE_REASON, v.reason());
        assertTrue(reader.suppressesInteraction(), "reader mode suppresses interaction across the file");
    }

    // --- the project's own files: all of them the user's ---------------------------------------------------

    /**
     * {@code FlowDriver.java} is the strongest case: BotMaker wrote it in every game bot for a year, and every
     * line of it was locked. In a project that still has one it is now ordinary code the user may change or
     * delete, because nothing will write over their change.
     */
    @Test
    void aFileBotMakerUsedToGenerateIsFullyEditable() {
        LockResolver r = resolver(FLOW_DRIVER);
        assertEquals(FileRole.EDITABLE, r.role());
        assertTrue(r.permits(statementIn("run"), EditKind.BODY));
        assertTrue(r.permits(method("run"), EditKind.SIGNATURE));
        assertTrue(r.permits(type(), EditKind.SIGNATURE), "the class header too");
    }

    @Test
    void anOrdinaryUserFileIsFullyEditable() {
        LockResolver r = resolver(HELPER);
        assertTrue(r.permits(statementIn("helper"), EditKind.BODY));
        assertTrue(r.permits(method("helper"), EditKind.SIGNATURE));
        assertFalse(r.suppressesInteraction());
    }

    // --- library source: not the project's at all ----------------------------------------------------------

    @Test
    void libraryCodeRejectsEverything() {
        LockResolver r = resolver(LIBRARY_FILE);
        assertEquals(FileRole.LIBRARY, r.role());
        assertFalse(r.permits(statementIn("helper"), EditKind.BODY));
        assertFalse(r.permits(method("helper"), EditKind.SIGNATURE));
        assertTrue(r.suppressesInteraction());
        assertTrue(r.check(method("helper"), EditKind.SIGNATURE).reason().contains("library"));
    }

    // --- the two escape hatches ---------------------------------------------------------------------------

    @Test
    void noConfigMeansEverythingIsEditable() {
        LockResolver none = new LockResolver(null, null);
        assertTrue(none.permits(statementIn("run"), EditKind.BODY));
        assertTrue(none.permits(method("run"), EditKind.SIGNATURE));
    }

    @Test
    void aMissingTargetIsDeniedNotWavedThrough() {
        LockResolver.Verdict v = resolver(HELPER).check(null, EditKind.BODY);
        assertFalse(v.allowed(), "a caller that forgot to say what it was editing fails loudly");
        assertNotNull(v.reason());
    }

    @Test
    void aRefusalAlwaysCarriesAReasonToShowTheUser() {
        LockResolver.Verdict v = resolver(LIBRARY_FILE).check(statementIn("helper"), EditKind.BODY);
        assertFalse(v.allowed());
        assertNotNull(v.reason());
        assertFalse(v.reason().isBlank());
    }
}
