package com.botmaker.studio.core.component;

import com.botmaker.studio.core.component.BlockComponent.Visibility;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectTemplate;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each audience is shown of an activity stub.
 *
 * <p>The shape being asserted is the whole point of the audience axis: everything BotMaker put in the file
 * disappears, and {@code run()} — the one thing its author actually wrote — stays. That now holds for the
 * <em>author</em> too. The three generated members are written from elsewhere ({@code ActivityStubSync}, the
 * flow dialog's checkbox) or from nowhere, so an edit made to them in the editor is reverted on the next save;
 * showing the author a control that cannot work is not the same as showing them what they are responsible for.
 */
class MemberVisibilityTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));
    private static final Path STUB = CONFIG.activitiesPackageDir().resolve("Mining.java");
    private static final Path HOOK = CONFIG.mainSourceFile().getParent().resolve("Popups.java");
    private static final Path PLAIN = CONFIG.mainSourceFile().getParent().resolve("MyHelper.java");

    private static final String STUB_SOURCE = """
            package com.mybot.activities;
            public class Mining extends Activity<Mining.Outcome> {
                public static final Mining INSTANCE = new Mining();
                public static final String[] POPUPS = {};
                private int oreCount;
                public enum Outcome { NEXT, BAG_FULL }
                @Override
                public boolean isEnabled() { return Activities.Mining; }
                @Override
                public Outcome run() { return Outcome.NEXT; }
            }
            """;

    private static final CompilationUnit CU = SourceParser.parse(STUB_SOURCE);

    private static LockResolver resolver(Path file) {
        return new LockResolver(CONFIG, ProjectTemplate.GAME_BOT, file);
    }

    private static BodyDeclaration member(String name) {
        for (Object obj : ((TypeDeclaration) CU.types().getFirst()).bodyDeclarations()) {
            if (obj instanceof MethodDeclaration m && name.equals(m.getName().getIdentifier())) return m;
            if (obj instanceof EnumDeclaration e && name.equals(e.getName().getIdentifier())) return e;
            if (obj instanceof FieldDeclaration f
                    && name.equals(((VariableDeclarationFragment) f.fragments().getFirst())
                            .getName().getIdentifier())) {
                return f;
            }
        }
        throw new AssertionError("the fixture has a member called " + name);
    }

    private static Visibility inStub(String name) {
        return MemberVisibility.of(resolver(STUB), member(name));
    }

    @Test
    void anActivityStubShowsItsRunMethodAndNothingElse() {
        assertEquals(Visibility.NOBODY, inStub("Outcome"));         // written from the flow dialog
        assertEquals(Visibility.NOBODY, inStub("INSTANCE"));        // wiring the registry binds
        assertEquals(Visibility.NOBODY, inStub("isEnabled"));       // MethodLock.FULL
        assertEquals(Visibility.EVERYONE, inStub("run"));           // the reason the file exists
    }

    @Test
    void aStaticTheAuthorDeclaredIsHiddenFromAReaderButNotFromThem() {
        // The rule is not "statics are scaffolding". Popups.POPUPS is the author's own template list, edited
        // right there in the file; only INSTANCE, which nothing can edit, is nobody's.
        assertEquals(Visibility.EDITOR_ONLY, inStub("POPUPS"));
    }

    @Test
    void anInstanceFieldIsTheAuthorsDataNotScaffolding() {
        // Only statics are wiring: a non-static field in a stub is ordinary state the author declared.
        assertEquals(Visibility.EVERYONE, inStub("oreCount"));
    }

    @Test
    void generatedMembersAreHiddenFromBothAudiences() {
        LockResolver resolver = resolver(STUB);
        for (String name : new String[] {"Outcome", "INSTANCE", "isEnabled"}) {
            assertFalse(MemberVisibility.isVisible(resolver, member(name), Audience.EDITOR), name);
            assertFalse(MemberVisibility.isVisible(resolver, member(name), Audience.USER), name);
        }
        // What the author keeps: their own code, and the static a reader is spared.
        for (String name : new String[] {"run", "oreCount", "POPUPS"}) {
            assertTrue(MemberVisibility.isVisible(resolver, member(name), Audience.EDITOR), name);
        }
        assertFalse(MemberVisibility.isVisible(resolver, member("POPUPS"), Audience.USER));
    }

    @Test
    void aSuperviseHooksOutcomeIsHiddenEvenThoughNothingLocksIt() {
        // Popups/GoHome are called directly rather than routed on, so GeneratedMembers doesn't lock their
        // Outcome — ActivityStubSync still writes it, so an edit here would not survive the next save.
        LockResolver hook = resolver(HOOK);
        assertTrue(hook.signatureEditable(member("Outcome")));
        assertEquals(Visibility.NOBODY, MemberVisibility.of(hook, member("Outcome")));
        assertEquals(Visibility.NOBODY, MemberVisibility.of(hook, member("INSTANCE")));
    }

    @Test
    void anOrdinaryFileHasNoScaffoldingToHide() {
        // Same source, different location. These rules are about the files the Studio manages, not about the
        // shape of a class — a user's own helper keeps every member whatever it is named.
        LockResolver plain = resolver(PLAIN);
        for (String name : new String[] {"Outcome", "INSTANCE", "isEnabled", "run", "oreCount", "POPUPS"}) {
            assertEquals(Visibility.EVERYONE, MemberVisibility.of(plain, member(name)), name);
        }
    }

    @Test
    void noProjectShowsEverything() {
        assertEquals(Visibility.EVERYONE, MemberVisibility.of(null, member("Outcome")));
        assertEquals(Visibility.EVERYONE, MemberVisibility.of(resolver(STUB), null));
    }
}
