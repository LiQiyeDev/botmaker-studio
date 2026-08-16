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
 * What a person who did not write the bot is shown of an activity stub.
 *
 * <p>The shape being asserted is the whole point of the audience axis: everything BotMaker put in the file
 * disappears, and {@code run()} — the one thing its author actually wrote — stays. The editor keeps seeing all
 * of it, because hiding scaffolding from the person responsible for it would be a different (and wrong) feature.
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
    void anActivityStubShowsTheUserItsRunMethodAndNothingElse() {
        assertEquals(Visibility.EDITOR_ONLY, inStub("Outcome"));    // written from the flow dialog
        assertEquals(Visibility.EDITOR_ONLY, inStub("INSTANCE"));   // wiring the registry binds
        assertEquals(Visibility.EDITOR_ONLY, inStub("isEnabled"));  // MethodLock.FULL
        assertEquals(Visibility.EVERYONE, inStub("run"));           // the reason the file exists
    }

    @Test
    void anInstanceFieldIsTheAuthorsDataNotScaffolding() {
        // Only statics are wiring: a non-static field in a stub is ordinary state the author declared.
        assertEquals(Visibility.EVERYONE, inStub("oreCount"));
    }

    @Test
    void theEditorSeesEverythingTheUserDoesNot() {
        LockResolver resolver = resolver(STUB);
        for (String name : new String[] {"Outcome", "INSTANCE", "isEnabled", "run", "oreCount"}) {
            assertTrue(MemberVisibility.isVisible(resolver, member(name), Audience.EDITOR), name);
        }
        assertFalse(MemberVisibility.isVisible(resolver, member("Outcome"), Audience.USER));
    }

    @Test
    void aSuperviseHooksOutcomeIsHiddenEvenThoughNothingLocksIt() {
        // Popups/GoHome are called directly rather than routed on, so GeneratedMembers doesn't lock their
        // Outcome — it is still not something a reader of the bot has any use for.
        LockResolver hook = resolver(HOOK);
        assertTrue(hook.signatureEditable(member("Outcome")));
        assertEquals(Visibility.EDITOR_ONLY, MemberVisibility.of(hook, member("Outcome")));
        // ...and its static template list goes with it, for the same reason INSTANCE does.
        assertEquals(Visibility.EDITOR_ONLY, MemberVisibility.of(hook, member("INSTANCE")));
    }

    @Test
    void anOrdinaryFileHasNoScaffoldingToHide() {
        // Same source, different location. These rules are about the files the Studio manages, not about the
        // shape of a class — a user's own helper keeps every member whatever it is named.
        LockResolver plain = resolver(PLAIN);
        for (String name : new String[] {"Outcome", "INSTANCE", "isEnabled", "run", "oreCount"}) {
            assertEquals(Visibility.EVERYONE, MemberVisibility.of(plain, member(name)), name);
        }
    }

    @Test
    void noProjectShowsEverything() {
        assertEquals(Visibility.EVERYONE, MemberVisibility.of(null, member("Outcome")));
        assertEquals(Visibility.EVERYONE, MemberVisibility.of(resolver(STUB), null));
    }
}
