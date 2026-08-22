package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.MethodReferences.CallSite;
import com.botmaker.studio.parser.refactor.SignatureMigration.ArgumentEdit;
import com.botmaker.studio.parser.refactor.SignatureMigration.CallChange;
import com.botmaker.studio.parser.refactor.SignatureMigration.Plan;
import com.botmaker.studio.parser.refactor.SignatureMigration.ReturnFate;
import com.botmaker.studio.project.ProjectFile;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edit primitives an SDK migration is built out of, exercised on their own.
 *
 * <p>Nothing here reads a {@code migrations.json} or upgrades anything: this is the layer below that, where a
 * "rename this constant" or "this member lives on another class now" becomes characters in a file. Testing it
 * here rather than through the upgrade means a failure says which edit is wrong instead of which upgrade is.
 *
 * <p>Every test asserts the rewritten source <b>parses</b>. That is not ceremony — an edit that produces text
 * no compiler accepts is the one failure mode this whole feature exists to prevent, and it is invisible to an
 * assertion that only looks for a substring.
 */
class MigrationEditsTest {

    // --- literal arguments ---------------------------------------------------------------------------------

    private static final String CALLER = """
            package test;

            public class Bot {
                public void run() {
                    Vision.find("target", 3);
                }
            }
            """;

    @Test
    void aLiteralArgumentCanBeWrittenFirstMiddleOrLast() {
        for (int position = 0; position <= 2; position++) {
            List<ArgumentEdit> edits = new ArrayList<>(List.of(new ArgumentEdit.Keep(0),
                    new ArgumentEdit.Keep(1)));
            edits.add(position, new ArgumentEdit.Literal("Precision.TIGHT",
                    "com.botmaker.sdk.api.vision.Precision"));

            String source = rewrite(CALLER, unit -> {
                CallSite site = callTo(unit, "find");
                return new CallChange.Rewrite(site, "find", List.copyOf(edits));
            });

            assertNotNull(source, "position " + position);
            assertTrue(source.contains("Precision.TIGHT"), source);
            assertTrue(source.contains("import com.botmaker.sdk.api.vision.Precision;"), source);
            assertParses(source);
        }
        // And in the order asked for, not merely present.
        String first = rewrite(CALLER, unit -> new CallChange.Rewrite(callTo(unit, "find"), "find",
                List.of(new ArgumentEdit.Literal("Precision.TIGHT", null), new ArgumentEdit.Keep(0))));
        assertTrue(first.contains("find(Precision.TIGHT, \"target\")"), first);
    }

    @Test
    void aLiteralNeedingNoImportBringsNoneIn() {
        String source = rewrite(CALLER, unit -> new CallChange.Rewrite(callTo(unit, "find"), "find",
                List.of(new ArgumentEdit.Keep(0), new ArgumentEdit.Literal("0", null))));

        assertTrue(source.contains("find(\"target\", 0)"), source);
        assertFalse(source.contains("import"), source);
        assertParses(source);
    }

    @Test
    void aLiteralThatIsNotAnExpressionRefusesTheWholeMigration() {
        // The text comes out of a jar somebody else built, so this is input, not an invariant.
        assertNull(rewrite(CALLER, unit -> new CallChange.Rewrite(callTo(unit, "find"), "find",
                List.of(new ArgumentEdit.Literal("class Nope {}", null), new ArgumentEdit.Keep(1)))));
    }

    // --- a renamed type ------------------------------------------------------------------------------------

    private static final String TYPE_USER = """
            package test;

            import com.botmaker.sdk.api.vision.Tolerance;
            import static com.botmaker.sdk.api.vision.Tolerance.TIGHT;

            public class Bot {
                private Tolerance kept = Tolerance.LOOSE;

                public void run() {
                    Tolerance here = Tolerance.of(3);
                    System.out.println(TIGHT);
                }
            }
            """;

    @Test
    void aRenamedTypeTakesItsUsesAndItsImportsWithIt() {
        CompilationUnit unit = SourceParser.parse(TYPE_USER);
        EditContext ctx = EditContext.of(unit, null, null);
        CallMigrator.renameTypeIn(ctx, "com.botmaker.sdk.api.vision.Tolerance",
                "com.botmaker.sdk.api.vision.Precision");
        String source = ctx.applyTo(TYPE_USER);

        assertParses(source);
        assertFalse(source.contains("Tolerance"), source);
        assertTrue(source.contains("import com.botmaker.sdk.api.vision.Precision;"), source);
        assertTrue(source.contains("private Precision kept = Precision.LOOSE;"), source);
        assertTrue(source.contains("Precision here = Precision.of(3);"), source);
        // The static import names the type as its qualifier, and nothing else in the file reaches it.
        assertTrue(source.contains("import static com.botmaker.sdk.api.vision.Precision.TIGHT;"), source);
    }

    @Test
    void aTypeRenameIsAPackageMoveToo() {
        String before = """
                package test;

                import com.botmaker.sdk.api.Key;

                public class Bot {
                    public void run() {
                        Key k = Key.ENTER;
                    }
                }
                """;
        CompilationUnit unit = SourceParser.parse(before);
        EditContext ctx = EditContext.of(unit, null, null);
        CallMigrator.renameTypeIn(ctx, "com.botmaker.sdk.api.Key",
                "com.botmaker.sdk.api.interaction.Key");
        String source = ctx.applyTo(before);

        assertParses(source);
        assertTrue(source.contains("import com.botmaker.sdk.api.interaction.Key;"), source);
        assertFalse(source.contains("import com.botmaker.sdk.api.Key;"), source);
        // Same simple name on both sides: the uses are correct as written and must not be churned.
        assertTrue(source.contains("Key k = Key.ENTER;"), source);
    }

    // --- a renamed constant, in all three shapes -----------------------------------------------------------

    private static final String CONSTANT_USER = """
            package test;

            import static com.botmaker.sdk.api.Key.ESCAPE;

            public class Bot {
                public void run(Direction d) {
                    Object enter = Key.ENTER;
                    Object escape = ESCAPE;
                    switch (d) {
                        case UP -> {}
                        default -> {}
                    }
                }
            }
            """;

    @Test
    void aConstantIsRenamedWhereItIsQualified() {
        String source = rewrite(CONSTANT_USER,
                unit -> new CallChange.Rewrite(fieldAt(unit, "ENTER"), "RETURN", List.of()));

        assertParses(source);
        assertTrue(source.contains("Key.RETURN"), source);
    }

    @Test
    void aRenamedConstantIsFixedInTheStaticImportAsWellAsTheUse() {
        String source = rewrite(CONSTANT_USER,
                unit -> new CallChange.Rewrite(fieldAt(unit, "ESCAPE"), "ESC", List.of()));

        assertParses(source);
        // Both halves, or the file stops compiling — renaming only the use is the trap here.
        assertTrue(source.contains("import static com.botmaker.sdk.api.Key.ESC;"), source);
        assertTrue(source.contains("Object escape = ESC;"), source);
    }

    @Test
    void aRenamedConstantIsFixedInACaseLabelWithNoImportInvented() {
        String source = rewrite(CONSTANT_USER,
                unit -> new CallChange.Rewrite(caseLabel(unit, "UP"), "NORTH", List.of()));

        assertParses(source);
        assertTrue(source.contains("case NORTH ->"), source);
        // A label has no import behind it, so none is touched and certainly none is written.
        assertTrue(source.contains("import static com.botmaker.sdk.api.Key.ESCAPE;"), source);
        assertEquals(1, source.lines().filter(line -> line.startsWith("import")).count(), source);
    }

    // --- a member that moved -------------------------------------------------------------------------------

    @Test
    void aConstantMovesToAnotherClassAndBringsItsImport() {
        String source = rewrite(CONSTANT_USER, unit ->
                new CallChange.MemberMoved(fieldAt(unit, "ENTER"), "com.botmaker.sdk.api.Button", null));

        assertParses(source);
        assertTrue(source.contains("Button.ENTER"), source);
        assertTrue(source.contains("import com.botmaker.sdk.api.Button;"), source);
    }

    @Test
    void aMoveMayRenameTheMemberAtTheSameTime() {
        String source = rewrite(CONSTANT_USER, unit ->
                new CallChange.MemberMoved(fieldAt(unit, "ENTER"), "com.botmaker.sdk.api.Button", "RETURN"));

        assertParses(source);
        assertTrue(source.contains("Button.RETURN"), source);
    }

    @Test
    void aStaticallyImportedConstantMovesByRetargetingTheImport() {
        String source = rewrite(CONSTANT_USER, unit ->
                new CallChange.MemberMoved(fieldAt(unit, "ESCAPE"), "com.botmaker.sdk.api.Button", null));

        assertParses(source);
        assertTrue(source.contains("import static com.botmaker.sdk.api.Button.ESCAPE;"), source);
        // The use is a bare name and stays one; the import is what carried the type.
        assertTrue(source.contains("Object escape = ESCAPE;"), source);
    }

    @Test
    void aMethodMovesToAnotherClass() {
        String source = rewrite(CALLER, unit -> new CallChange.MemberMoved(callTo(unit, "find"),
                "com.botmaker.sdk.api.vision.ImageFinder", "locate"));

        assertParses(source);
        assertTrue(source.contains("ImageFinder.locate(\"target\", 3)"), source);
        assertTrue(source.contains("import com.botmaker.sdk.api.vision.ImageFinder;"), source);
    }

    @Test
    void aCaseLabelCannotBeMovedToAnotherTypeAndSaysSoByRefusing() {
        // The label's type is the switch expression's, written nowhere near the label — so there is no
        // qualifier to retarget, and inventing one would compile against the wrong enum or not at all.
        assertNull(rewrite(CONSTANT_USER, unit ->
                new CallChange.MemberMoved(caseLabel(unit, "UP"), "com.botmaker.sdk.api.Axis", null)));
    }

    @Test
    void aCallOnThisHasNoTypeToRetargetSoAMoveIsRefused() {
        String bareCall = """
                package test;

                public class Bot {
                    public void run() {
                        find("target");
                    }
                }
                """;
        assertNull(rewrite(bareCall, unit ->
                new CallChange.MemberMoved(callTo(unit, "find"), "com.botmaker.sdk.api.vision.ImageFinder",
                        null)));
    }

    // --- harness -------------------------------------------------------------------------------------------

    /**
     * The file the sites being built belong to. A field rather than a parameter so each test's lambda stays
     * {@code unit -> change}; {@code CallSite} needs the file the rewrite will be applied to, and it must hold
     * the <em>original</em> text, since that is what the recorded edits have offsets into.
     */
    private static ProjectFile current;

    /** Applies one change to {@code source}, returning the new text — or null when the edit was refused. */
    private static String rewrite(String source, java.util.function.Function<CompilationUnit, CallChange> of) {
        current = new ProjectFile(Path.of("Bot.java"), source);
        CompilationUnit unit = SourceParser.parse(source);
        Plan plan = new Plan(List.of(of.apply(unit)), List.of(), List.of(), ReturnFate.UNCHANGED);
        List<CallMigrator.Rewritten> rewritten = CallMigrator.rewriteOthers(plan, null, null, null);
        if (rewritten == null) return null;
        assertEquals(1, rewritten.size(), "expected exactly one file to change");
        return rewritten.getFirst().newSource();
    }

    private static void assertParses(String source) {
        assertNotNull(source, "the edit was refused, so there is nothing to parse");
        assertFalse(SourceParser.hasSyntaxErrors(SourceParser.parse(source)),
                () -> "rewritten source does not parse:\n" + source);
    }

    private static CallSite callTo(CompilationUnit unit, String name) {
        return siteOf(unit, found(unit, MethodInvocation.class,
                node -> node.getName().getIdentifier().equals(name)));
    }

    private static CallSite fieldAt(CompilationUnit unit, String name) {
        // `import static …Key.ESCAPE;` is itself a QualifiedName ending in the member's name, and it is not a
        // use of it — the scanner skips imports for the same reason.
        QualifiedName qualified = found(unit, QualifiedName.class,
                node -> node.getName().getIdentifier().equals(name) && !withinImport(node));
        if (qualified != null) return siteOf(unit, qualified);
        // A statically-imported constant: the use is a bare name, and the import is not it.
        return siteOf(unit, found(unit, SimpleName.class, node -> node.getIdentifier().equals(name)
                && !(node.getParent() instanceof QualifiedName)
                && node.getLocationInParent() != SwitchCase.EXPRESSIONS2_PROPERTY
                && !withinImport(node)));
    }

    private static CallSite caseLabel(CompilationUnit unit, String name) {
        return siteOf(unit, found(unit, SimpleName.class, node -> node.getIdentifier().equals(name)
                && node.getLocationInParent() == SwitchCase.EXPRESSIONS2_PROPERTY));
    }

    private static CallSite siteOf(CompilationUnit unit, Expression node) {
        assertNotNull(node, "the fixture does not contain the node this test is about");
        return new CallSite(current, unit, node);
    }

    private static boolean withinImport(org.eclipse.jdt.core.dom.ASTNode node) {
        for (org.eclipse.jdt.core.dom.ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof org.eclipse.jdt.core.dom.ImportDeclaration) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Expression> T found(CompilationUnit unit, Class<T> type,
                                                  java.util.function.Predicate<T> matches) {
        Object[] hit = new Object[1];
        unit.accept(new ASTVisitor() {
            @Override
            public void preVisit(org.eclipse.jdt.core.dom.ASTNode node) {
                if (hit[0] == null && type.isInstance(node) && matches.test((T) node)) hit[0] = node;
            }
        });
        return (T) hit[0];
    }
}
