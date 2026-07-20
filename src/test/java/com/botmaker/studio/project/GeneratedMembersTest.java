package com.botmaker.studio.project;

import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.LockResolver.EditKind;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
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
 * <p>Two different kinds of rule, deliberately: the {@code Outcome} enum is locked outright (it is written from
 * the flow dialog), while the trailing {@code return} stays editable and is only pinned in <em>place</em> —
 * choosing which outcome to report is the whole point of it.
 */
class GeneratedMembersTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));
    private static final Path STUB = CONFIG.activitiesPackageDir().resolve("Mining.java");
    private static final Path PLAIN = CONFIG.mainSourceFile().getParent().resolve("MyHelper.java");

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
    void anOrdinaryFileHasNoGeneratedMembers() {
        // Same source, different location: the rules are about activity stubs, not about the shape of a class.
        LockResolver resolver = resolver(PLAIN);

        assertTrue(resolver.signatureEditable(outcomeEnum()));
        assertNull(resolver.pinnedReturnOf(runBody()));
        assertFalse(resolver.isPinnedReturn(lastStatement()));
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
