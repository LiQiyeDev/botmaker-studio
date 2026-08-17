package com.botmaker.studio.project;

import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.LockResolver.EditKind;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated members inside an activity stub — a file that is otherwise entirely the user's.
 *
 * <p>Three different kinds of rule, deliberately: the {@code Outcome} enum and the bound {@code INSTANCE} are
 * locked outright (the flow dialog writes one, the registry and entry point name the other), while the trailing
 * {@code return} stays editable and is only pinned in <em>place</em> — choosing which outcome to report is the
 * whole point of it.
 */
class GeneratedMembersTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));
    private static final Path STUB = CONFIG.activitiesPackageDir().resolve("Mining.java");
    private static final Path PLAIN = CONFIG.mainSourceFile().getParent().resolve("MyHelper.java");
    /** A supervise hook: an activity that lives beside the entry point rather than under activities/. */
    private static final Path HOOK = CONFIG.mainSourceFile().getParent().resolve("GoHome.java");

    private static final String STUB_SOURCE = """
            package com.mybot.activities;
            public class Mining extends Activity<Mining.Outcome> {
                public enum Outcome { NEXT, BAG_FULL }
                @Override
                public boolean isEnabled() { return Activities.Mining; }
                @Override
                public Outcome run() {
                    ImageClicker.click(ore);
                    return Outcome.NEXT;
                }
            }
            """;

    private static final CompilationUnit CU = SourceParser.parse(STUB_SOURCE);

    private static TypeDeclaration type() {
        return (TypeDeclaration) CU.types().getFirst();
    }

    private static EnumDeclaration outcomeEnum() {
        for (Object member : type().bodyDeclarations()) {
            if (member instanceof EnumDeclaration e) return e;
        }
        throw new AssertionError("the fixture has an Outcome enum");
    }

    private static MethodDeclaration run() {
        for (MethodDeclaration m : type().getMethods()) {
            if ("run".equals(m.getName().getIdentifier())) return m;
        }
        throw new AssertionError("the fixture has a run()");
    }

    private static Block runBody() {
        return run().getBody();
    }

    private static Statement lastStatement() {
        return (Statement) runBody().statements().getLast();
    }

    private static LockResolver resolver(Path file) {
        return new LockResolver(CONFIG, ProjectTemplate.GAME_BOT, file);
    }

    @Test
    void theOutcomeEnumIsLockedEvenThoughItsFileIsTheUsers() {
        LockResolver resolver = resolver(STUB);

        assertFalse(resolver.signatureEditable(outcomeEnum()));
        assertFalse(resolver.permits(outcomeEnum(), EditKind.SIGNATURE));
        // The rest of the file is untouched by this: run()'s body is exactly what the user came to write.
        assertTrue(resolver.bodyEditable(runBody()));
    }

    @Test
    void theReasonPointsAtTheFlowDialogRatherThanSayingItIsGenerated() {
        String reason = resolver(STUB).check(outcomeEnum(), EditKind.SIGNATURE).reason();
        assertNotNull(reason);
        assertTrue(reason.contains("Activity Flow"), reason);
    }

    @Test
    void theTrailingReturnIsPinnedButNotLocked() {
        LockResolver resolver = resolver(STUB);

        assertSame(lastStatement(), resolver.pinnedReturnOf(runBody()));
        assertTrue(resolver.isPinnedReturn(lastStatement()));
        // Still editable: swapping NEXT for BAG_FULL is the user's decision, and it goes through the body path.
        assertTrue(resolver.permits(lastStatement(), EditKind.BODY));
    }

    @Test
    void anEarlierStatementIsNotPinned() {
        Statement first = (Statement) runBody().statements().getFirst();
        assertFalse(resolver(STUB).isPinnedReturn(first));
    }

    @Test
    void theOutcomeEnumIsLockedOnlyInsideTheActivitiesPackage() {
        // Same source, different location. What locks the enum is that the flow canvas writes it, and only the
        // files under activities/ are on that canvas — GoHome and Popups carry an Outcome the canvas never
        // touches, so locking theirs would leave the user nowhere to edit it.
        assertTrue(resolver(PLAIN).signatureEditable(outcomeEnum()));
    }

    @Test
    void anActivityOutsideTheActivitiesPackageStillHasItsReturnPinned() {
        // GoHome.java and Popups.java sit beside the entry point and extend Activity. Their run() ends in the
        // return their caller routes on exactly as a stub's does, and it used to render as a generic orange
        // expression chip purely because of which directory the file was in.
        LockResolver hook = resolver(HOOK);

        assertSame(lastStatement(), hook.pinnedReturnOf(runBody()));
        assertTrue(hook.isPinnedReturn(lastStatement()));
    }

    @Test
    void aClassThatIsNotAnActivityHasNoPinnedReturn() {
        // The structural test is what carries a file outside activities/, so a plain helper there must fail it.
        CompilationUnit cu = SourceParser.parse("""
                package com.mybot;
                public class MyHelper {
                    public int run() { return 1; }
                }
                """);
        MethodDeclaration run = ((TypeDeclaration) cu.types().getFirst()).getMethods()[0];

        assertNull(resolver(PLAIN).pinnedReturnOf(run.getBody()));
    }

    @Test
    void aStubKeepsItsPinnedReturnEvenWithoutTheExtendsClause() {
        // The directory rule stays as a fallback: a file ensureStubs created is an activity whatever its header
        // says right now — mid-edit, or written by an older Studio that spelled the base class differently.
        CompilationUnit cu = SourceParser.parse("""
                package com.mybot.activities;
                public class Mining {
                    public Outcome run() { return Outcome.NEXT; }
                }
                """);
        MethodDeclaration run = ((TypeDeclaration) cu.types().getFirst()).getMethods()[0];

        assertNotNull(resolver(STUB).pinnedReturnOf(run.getBody()));
    }

    @Test
    void theBoundInstanceCannotBeDeletedFromEitherKindOfScaffoldFile() {
        // The reported symptom was a working delete cross on GoHome's INSTANCE. A field sits in no method, so
        // it inherited no MethodLock, and its file is EDITABLE — nothing refused it, and removing it breaks the
        // entry point that binds GoHome.INSTANCE::execute.
        CompilationUnit cu = SourceParser.parse("""
                package com.mybot;
                public class GoHome extends Activity<GoHome.Outcome> {
                    public static final GoHome INSTANCE = new GoHome();
                    public static final List<String> POPUPS = List.of();
                }
                """);
        TypeDeclaration hookType = (TypeDeclaration) cu.types().getFirst();
        FieldDeclaration instance = (FieldDeclaration) hookType.bodyDeclarations().get(0);
        FieldDeclaration popups = (FieldDeclaration) hookType.bodyDeclarations().get(1);

        assertFalse(resolver(HOOK).signatureEditable(instance));
        assertFalse(resolver(STUB).signatureEditable(instance), "an activity stub's INSTANCE is bound too");
        // Not every static: the author's own list in Popups.java is theirs to edit.
        assertTrue(resolver(HOOK).signatureEditable(popups));
        // And nothing at all is scaffolding in a file the Studio never wrote.
        assertTrue(resolver(PLAIN).signatureEditable(instance));
    }

    @Test
    void anotherMethodsTrailingReturnIsNotPinned() {
        // Only run() reports an outcome. A helper that happens to end in a return is ordinary code.
        CompilationUnit cu = SourceParser.parse("""
                package com.mybot.activities;
                public class Mining extends Activity<Mining.Outcome> {
                    private boolean ready() { return true; }
                }
                """);
        MethodDeclaration ready = ((TypeDeclaration) cu.types().getFirst()).getMethods()[0];

        assertNull(resolver(STUB).pinnedReturnOf(ready.getBody()));
    }
}
