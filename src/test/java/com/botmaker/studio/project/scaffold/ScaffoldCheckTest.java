package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.scaffold.ScaffoldSurface.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forward direction: a project pinned to an SDK <em>newer</em> than this Studio.
 *
 * <p>{@link ScaffoldSurfaceTest} covers everything up to Studio's own SDK, by construction — it looks the
 * declared elements up in the jar Studio compiles against. What it cannot cover is a jar that did not exist
 * when it ran, and that is what {@link ScaffoldCheck} exists for. So the SDK here is a <b>stub</b>, not a
 * scratch jar: the question is what the check and the repair do with a given set of facts, and building a jar
 * to state those facts would only add a build step between the statement and the assertion.
 *
 * <p>The stub starts as a faithful copy of {@link ScaffoldSurface} itself — an SDK that has everything — and
 * each test takes one thing away, with or without a pointer. That way a test says exactly what changed about
 * the world, and the "nothing changed" case is the same code path as the others rather than a special one.
 */
class ScaffoldCheckTest {

    private static final String POPUP_GUARD = "com.botmaker.sdk.api.bot.PopupGuard";
    private static final String ACTIVITY = "com.botmaker.sdk.api.bot.Activity";
    /**
     * The stand-in for "a member the generators call". It has to be one the surface actually declares — the
     * stub is a copy of {@link ScaffoldSurface}, so removing something absent from it changes nothing and
     * every assertion below would read SATISFIED. It was {@code Wait#milliseconds} until the walk moved into
     * the SDK and Studio stopped writing it.
     */
    private static final String WIRE = "com.botmaker.sdk.api.config.Wire";

    // ------------------------------------------------------------------
    // the check
    // ------------------------------------------------------------------

    @Test
    void anSdkThatHasEverythingIsSatisfied() {
        ScaffoldCheck.Result result = ScaffoldCheck.of(new Stub());
        assertEquals(ScaffoldCheck.Status.SATISFIED, result.status());
        assertTrue(result.substitutions().isEmpty());
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void anUnscannedJarFailsOpen() {
        // The rule SdkSurfaceService already applies to every presence query: a probe that could not run must
        // never be the reason a project cannot be created.
        ScaffoldCheck.Result result = ScaffoldCheck.of(new Stub().unindexed());
        assertEquals(ScaffoldCheck.Status.SATISFIED, result.status());
    }

    @Test
    void aRemovedMemberWithNoPointerIsUnsatisfiableAndNamed() {
        ScaffoldCheck.Result result = ScaffoldCheck.of(new Stub().remove(POPUP_GUARD + "#install"));
        assertEquals(ScaffoldCheck.Status.UNSATISFIABLE, result.status());
        assertEquals(List.of(POPUP_GUARD + "#install(1)"), result.missing());
        assertTrue(result.refusal().contains("PopupGuard#install"),
                "the refusal must name the element: " + result.refusal());
    }

    @Test
    void aRenamedMemberWithABackEdgeIsRepairable() {
        ScaffoldCheck.Result result = ScaffoldCheck.of(new Stub()
                .remove(WIRE + "#duration")
                .add(WIRE, "howLong", 1)
                .claims(WIRE + "#duration", WIRE + "#howLong"));

        assertEquals(ScaffoldCheck.Status.REPAIRABLE, result.status());
        assertEquals(1, result.substitutions().size());
        ScaffoldCheck.Substitution only = result.substitutions().getFirst();
        assertEquals(WIRE + "#howLong", only.ref());
        assertTrue(only.memberMoved());
        assertFalse(only.typeMoved());
    }

    @Test
    void aTypeThatMovedCarriesItsMembersWithoutAPointerEach() {
        // The case that makes annotating a package move bearable: the SDK author writes one @Replaces on the
        // type, and every member the scaffold calls on it resolves through that one edge.
        String moved = "com.botmaker.sdk.api.settings.Wire";
        Stub sdk = new Stub().removeType(WIRE).claims(WIRE, moved);
        // The move takes every member with it, unrenamed — that is what a package move is. Read off the
        // surface rather than listed, so a Wire reader added later is part of this case automatically.
        for (Element e : ScaffoldSurface.all()) {
            if (e.type().equals(WIRE) && !e.isType()) sdk.add(moved, e.member(), e.arity());
        }
        ScaffoldCheck.Result result = ScaffoldCheck.of(sdk);

        assertEquals(ScaffoldCheck.Status.REPAIRABLE, result.status());
        ScaffoldCheck.Substitution member = substitutionFor(result, WIRE + "#duration(1)");
        assertEquals(moved + "#duration", member.ref());
        assertTrue(member.typeMoved());
        assertFalse(member.memberMoved());
    }

    @Test
    void aPointerToSomethingTheJarDoesNotHaveIsNotAnAnswer() {
        // A dangling claim is worse than none, so it must not be mistaken for a repair.
        ScaffoldCheck.Result result = ScaffoldCheck.of(new Stub()
                .remove(POPUP_GUARD + "#install")
                .claims(POPUP_GUARD + "#install", POPUP_GUARD + "#arm"));
        assertEquals(ScaffoldCheck.Status.UNSATISFIABLE, result.status());
    }

    @Test
    void anOverloadOfTheWrongArityIsNotTheMemberTheScaffoldCalls() {
        // install(1) survives only as install(2): the generators write a fixed argument list, so this is a
        // removal as far as the scaffold is concerned, and reporting it as present would emit code that does
        // not compile.
        ScaffoldCheck.Result result = ScaffoldCheck.of(new Stub()
                .remove(POPUP_GUARD + "#install")
                .add(POPUP_GUARD, "install", 2));
        assertEquals(ScaffoldCheck.Status.UNSATISFIABLE, result.status());
    }

    @Test
    void aChainOfClaimsComposes() {
        // duration → howLong in one release, howLong → span in the next. Only the second claim survives on
        // an element this jar still has, so the walk has to go through the spelling that is gone.
        ScaffoldCheck.Result result = ScaffoldCheck.of(new Stub()
                .remove(WIRE + "#duration")
                .add(WIRE, "span", 1)
                .claims(WIRE + "#duration", WIRE + "#howLong")
                .claims(WIRE + "#howLong", WIRE + "#span"));

        assertEquals(ScaffoldCheck.Status.REPAIRABLE, result.status());
        assertEquals(WIRE + "#span", result.substitutions().getFirst().ref());
    }

    // ------------------------------------------------------------------
    // the repair
    // ------------------------------------------------------------------

    @Test
    void aStaticCallIsRetargetedInTheEmittedText() {
        Map<String, String> rendered = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "Actbot", "actbot");
        ScaffoldCheck.Result check = ScaffoldCheck.of(new Stub()
                .remove(POPUP_GUARD + "#install")
                .add(POPUP_GUARD, "arm", 1)
                .claims(POPUP_GUARD + "#install", POPUP_GUARD + "#arm"));

        ScaffoldRepair.Outcome repaired = ScaffoldRepair.apply(rendered, check.substitutions());
        assertTrue(repaired.canEmit(), "unexpressed: " + repaired.unexpressed());
        String entryPoint = repaired.sources().get("Actbot.java");
        assertTrue(entryPoint.contains("PopupGuard.arm("), entryPoint);
        assertFalse(entryPoint.contains("PopupGuard.install("), entryPoint);
        assertEquals(rendered.keySet(), repaired.sources().keySet(), "every file comes back, rewritten or not");
    }

    @Test
    void aTypeThatMovedIsRenamedImportsIncluded() {
        String moved = "com.botmaker.sdk.api.bot.Guard";
        Map<String, String> rendered = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "Actbot", "actbot");
        ScaffoldCheck.Result check = ScaffoldCheck.of(new Stub()
                .removeType(POPUP_GUARD)
                .add(moved, "install", 1)
                .add(moved, "enabled", 1)
                .claims(POPUP_GUARD, moved));

        ScaffoldRepair.Outcome repaired = ScaffoldRepair.apply(rendered, check.substitutions());
        assertTrue(repaired.canEmit(), "unexpressed: " + repaired.unexpressed());
        String entryPoint = repaired.sources().get("Actbot.java");
        assertTrue(entryPoint.contains("import " + moved + ";"), entryPoint);
        assertFalse(entryPoint.contains("import " + POPUP_GUARD + ";"), entryPoint);
        assertTrue(entryPoint.contains("Guard.install("), entryPoint);
    }

    @Test
    void nothingToDoLeavesEveryFileByteIdentical() {
        Map<String, String> rendered = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "Actbot", "actbot");
        ScaffoldRepair.Outcome repaired = ScaffoldRepair.apply(rendered, List.of());
        assertTrue(repaired.canEmit());
        rendered.forEach((name, source) -> assertSame(source, repaired.sources().get(name),
                name + " was rewritten although there was nothing to rewrite"));
    }

    @Test
    void anOverriddenMemberIsRefusedRatherThanHalfRewritten() {
        // `GoHome extends Activity` and overrides `run()`. Renaming a call cannot rename a declaration, so the
        // repair must come back empty-handed rather than emit a class that no longer overrides anything.
        Map<String, String> rendered = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "Actbot", "actbot");
        ScaffoldCheck.Result check = ScaffoldCheck.of(new Stub()
                .remove(ACTIVITY + "#run")
                .add(ACTIVITY, "perform", 0)
                .claims(ACTIVITY + "#run", ACTIVITY + "#perform"));

        assertEquals(ScaffoldCheck.Status.REPAIRABLE, check.status(), "the pointer does name a survivor");
        ScaffoldRepair.Outcome repaired = ScaffoldRepair.apply(rendered, check.substitutions());
        assertFalse(repaired.canEmit(), "the rewriter cannot express this and must say so");
        assertTrue(repaired.sources().isEmpty(), "nothing is emitted when one file cannot be repaired");
        assertTrue(repaired.unexpressed().getFirst().contains("run"), repaired.unexpressed().toString());
    }

    // ------------------------------------------------------------------
    // creation
    // ------------------------------------------------------------------

    @Test
    void creationRefusesBeforeItWritesAnything() {
        Map<String, String> rendered = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "Actbot", "actbot");
        ScaffoldUnsupported refusal = assertThrows(ScaffoldUnsupported.class,
                () -> ProjectCreator.scaffold("9.9.9", rendered, new Stub().remove(POPUP_GUARD + "#install")));
        assertTrue(refusal.getMessage().contains("PopupGuard#install"), refusal.getMessage());
    }

    @Test
    void creationEmitsTheRepairedSourcesWhenThePointersCover() throws Exception {
        Map<String, String> rendered = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "Actbot", "actbot");
        Map<String, String> emitted = ProjectCreator.scaffold("9.9.9", rendered, new Stub()
                .remove(POPUP_GUARD + "#install")
                .add(POPUP_GUARD, "arm", 1)
                .claims(POPUP_GUARD + "#install", POPUP_GUARD + "#arm"));
        assertTrue(emitted.get("Actbot.java").contains("PopupGuard.arm("));
    }

    @Test
    void creationAgainstAnSdkThatHasEverythingHandsBackWhatItWasGiven() {
        Map<String, String> rendered = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "Actbot", "actbot");
        assertDoesNotRewrite(rendered, new Stub());
        // …and a jar nobody could scan takes the same path, which is what fail-open has to mean here.
        assertDoesNotRewrite(rendered, new Stub().unindexed());
    }

    private static void assertDoesNotRewrite(Map<String, String> rendered, ScaffoldCheck.SdkFacts facts) {
        try {
            assertSame(rendered, ProjectCreator.scaffold("9.9.9", rendered, facts));
        } catch (ScaffoldUnsupported e) {
            throw new AssertionError("refused an SDK that has everything: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ScaffoldCheck.Substitution substitutionFor(ScaffoldCheck.Result result, String line) {
        ScaffoldCheck.Substitution found = result.substitutions().stream()
                .filter(s -> s.element().line().equals(line))
                .findFirst().orElse(null);
        assertNotNull(found, "no substitution for " + line + " in " + result.substitutions());
        return found;
    }

    /**
     * An SDK that has exactly what {@link ScaffoldSurface} declares, and whatever a test adds, removes or
     * points elsewhere. Mutable and chainable because every test is one sentence about a difference from that
     * baseline, and a builder would put the difference further from the assertion.
     */
    private static final class Stub implements ScaffoldCheck.SdkFacts {

        private final Set<String> types = new LinkedHashSet<>();
        /** {@code fqn#member} → the parameter counts it is declared with. */
        private final Map<String, Set<Integer>> members = new LinkedHashMap<>();
        private final Map<String, List<String>> claims = new LinkedHashMap<>();
        private boolean indexed = true;

        Stub() {
            for (Element element : ScaffoldSurface.all()) {
                types.add(element.type());
                if (element.isType()) continue;
                members.computeIfAbsent(element.ref(), k -> new LinkedHashSet<>()).add(element.arity());
            }
        }

        Stub unindexed() {
            indexed = false;
            return this;
        }

        Stub remove(String ref) {
            members.remove(ref);
            return this;
        }

        Stub removeType(String fqn) {
            types.remove(fqn);
            members.keySet().removeIf(ref -> ref.startsWith(fqn + "#"));
            return this;
        }

        Stub add(String fqn, String member, int arity) {
            types.add(fqn);
            members.computeIfAbsent(fqn + "#" + member, k -> new LinkedHashSet<>()).add(arity);
            return this;
        }

        /** {@code survivor} carries {@code @Replaces("old")} — the back edge, the only half one jar holds. */
        Stub claims(String old, String survivor) {
            claims.computeIfAbsent(old, k -> new ArrayList<>()).add(survivor);
            return this;
        }

        @Override
        public boolean indexed() {
            return indexed;
        }

        @Override
        public boolean hasType(String fqn) {
            return types.contains(fqn);
        }

        @Override
        public boolean hasMember(String fqn, String member, int arity) {
            return members.getOrDefault(fqn + "#" + member, Set.of()).contains(arity);
        }

        @Override
        public List<String> survivorsOf(String ref) {
            // The same transitive walk ScaffoldFacts does over a real jar, in the four lines a stub needs it in.
            List<String> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>(List.of(ref));
            List<String> pending = new ArrayList<>(claims.getOrDefault(ref, List.of()));
            while (!pending.isEmpty()) {
                String at = pending.removeFirst();
                if (!seen.add(at)) continue;
                out.add(at);
                pending.addAll(claims.getOrDefault(at, List.of()));
            }
            return out;
        }
    }
}
