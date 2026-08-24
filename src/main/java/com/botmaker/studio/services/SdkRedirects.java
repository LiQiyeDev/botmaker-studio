package com.botmaker.studio.services;

import com.botmaker.studio.parser.refactor.SdkMigrationRunner;
import com.botmaker.studio.parser.refactor.SignatureMigration.ArgumentEdit;
import com.botmaker.studio.services.SdkApiModel.Advice;
import com.botmaker.studio.services.SdkApiModel.ApiClass;
import com.botmaker.studio.services.SdkApiModel.ApiMember;
import com.botmaker.studio.services.SdkUpgradeService.Call;
import com.botmaker.studio.services.SdkUpgradeService.Candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.botmaker.studio.services.SdkApiModel.CTOR;

/**
 * Where one call of the old jar could point in the new one, checked rather than hoped.
 *
 * <p>This is the <b>one</b> place that decides a redirect, and every reader goes through it —
 * {@link SdkUpgradeDiff} for the sentence the dialog shows and the menu it offers, {@link SdkUpgradeService}
 * for the edit. Two answers to one question would eventually be a dialog promising a rewrite the rewriter
 * does not make.
 */
final class SdkRedirects {

    private SdkRedirects() {
    }

    /**
     * Where this call could point instead, in the author's preference order — empty when nothing in the
     * target jar can take it.
     *
     * <p>The list has more than one element only for a <b>split</b>, and each element is a candidate that
     * survived the checks below on its own. Whether a candidate also <em>fits where the value is used</em> is
     * not decided here: that is a property of the call <em>site</em>, since a call standing as a statement
     * discards its result and any candidate fits — see {@link SdkUpgradeService.Choice} and
     * {@link #fittingAt}.
     *
     * <p>It answers empty for three quite different things, all of which end the same way (a default value
     * and a review mark): the call already resolves and needs nothing; nothing pairs with it; or something
     * does but the shapes cannot be reconciled. The last group is where the refusals live, and each is
     * deliberate:
     *
     * <ul>
     *   <li><b>a field paired with a method, or a constructor with a method</b> — the source shapes differ,
     *       and rewriting one into the other is not a redirect but a rewrite of the surrounding code;</li>
     *   <li><b>several candidate overloads and none of this call's arity</b> — which one the author meant is
     *       exactly what an arity was going to tell us, so a guess between two is a guess.</li>
     * </ul>
     *
     * <p>Where a single overload of another arity is the only candidate, the arguments this call already
     * passes are kept in order and the difference is made up: a {@link ArgumentEdit.Literal} per input the
     * target gained, and trailing arguments simply dropped for one it lost. That is
     * {@code SignatureMigration}'s own machinery, doing here what it does for a hand-edited signature.
     */
    static List<Candidate> redirectsFor(ApiClass then, ApiClass now, Call call,
                                        Map<String, ApiClass> after, SdkPairing pairing) {
        List<SdkPairing.Member> targets = pairing.targetOf(then, call.member());
        if (targets.isEmpty()) {
            SdkMigrationRunner.Redirect only = redirectTo(then, now, call, after, null);
            return only == null ? List.of() : List.of(new Candidate(only, ""));
        }
        List<Candidate> out = new ArrayList<>();
        for (SdkPairing.Member target : targets) {
            SdkMigrationRunner.Redirect redirect = redirectTo(then, now, call, after, target);
            if (redirect != null) out.add(new Candidate(redirect, target.when()));
        }
        return List.copyOf(out);
    }

    /**
     * The first candidate {@link #redirectsFor} offers, or null. The answer every reader that does not ask
     * the user wants — and the whole answer whenever the pointer named one target, which is nearly always.
     */
    static SdkMigrationRunner.Redirect redirectFor(ApiClass then, ApiClass now, Call call,
                                                   Map<String, ApiClass> after, SdkPairing pairing) {
        List<Candidate> all = redirectsFor(then, now, call, after, pairing);
        return all.isEmpty() ? null : all.getFirst().redirect();
    }

    /**
     * The candidates that fit at this call, in preference order — the menu one site is offered.
     *
     * <p>The whole of the site-dependent half of compile safety: a call standing as a <b>statement</b>
     * discards its result, so nothing a candidate returns can be wrong there; a call whose value is
     * <b>used</b> admits only the candidates whose return type still sits where the old one did. Empty is
     * not a new outcome — it is the default value and the review mark the site would have got anyway.
     */
    static List<Candidate> fittingAt(List<Candidate> candidates, Call call) {
        return candidates.stream()
                .filter(c -> call.statement() || c.redirect().expressionSafe())
                .toList();
    }

    /** One candidate, checked. {@code target} is null for "no pointer led anywhere" — see the doc above. */
    private static SdkMigrationRunner.Redirect redirectTo(ApiClass then, ApiClass now, Call call,
                                                          Map<String, ApiClass> after,
                                                          SdkPairing.Member target) {
        ApiClass owner = target == null ? now : after.get(target.type());
        String name = target == null ? call.member() : target.name();
        if (owner == null) return null;
        // A constructor is not a method with a funny name: `new Point(…)` and `Point.of(…)` are different
        // source shapes, and one is not rewritten into the other by renaming anything.
        if (CTOR.equals(call.member()) != CTOR.equals(name)) return null;

        boolean moved = !owner.name().equals(now.name());
        String oldReturn = returnTypeOf(then, call);
        Advice advice = adviceFor(then, call, owner, name);

        if (call.isField()) {
            if (!owner.declaresField(name)) return null;
            if (!moved && name.equals(call.member())) return null;      // still there, still spelled the same
            String newReturn = typeOfField(owner, name);
            return new SdkMigrationRunner.Redirect(then.simpleName(), call.member(), call.argCount(),
                    moved ? owner.name() : null, name, List.of(), oldReturn, newReturn,
                    fits(oldReturn, newReturn, after), returnTypeFqn(oldReturn, after),
                    advice.note(), advice.behaviourChanged());
        }

        List<ApiMember> overloads = owner.byName().getOrDefault(name, List.of()).stream()
                .filter(m -> !m.field()).toList();
        if (overloads.isEmpty()) return null;

        ApiMember exact = overloads.stream()
                .filter(m -> m.params().size() == call.argCount()).findFirst().orElse(null);
        if (exact != null && !moved && name.equals(call.member())) return null;   // the call already compiles

        ApiMember chosen = exact;
        if (chosen == null) {
            if (overloads.size() != 1) return null;
            chosen = overloads.getFirst();
        }
        return new SdkMigrationRunner.Redirect(then.simpleName(), call.member(), call.argCount(),
                moved ? owner.name() : null, name, argumentsFor(call.argCount(), chosen),
                oldReturn, chosen.type(), fits(oldReturn, chosen.type(), after),
                returnTypeFqn(oldReturn, after), advice.note(), advice.behaviourChanged());
    }

    /**
     * What the SDK's own author said about this move, from whichever end of the pointer pair carries it.
     *
     * <p>Both ends are asked because only one of them need exist. The <b>forward</b> half is the
     * {@code @ReplacedBy} on the element in the <em>old</em> jar — the one the bot still calls — and it wins,
     * being the author speaking on the member the user actually wrote. The <b>backward</b> half is the
     * {@code @Replaces} claim on the survivor in the <em>new</em> jar, which is the only place the sentence
     * survives once the deprecated element is finally deleted, and so is the answer for a bot that skipped
     * the deprecation release entirely. See {@link Advice}.
     */
    private static Advice adviceFor(ApiClass then, Call call, ApiClass owner, String name) {
        Advice forward = pointerAdvice(overloadOf(then, call));
        String oldSpelling = then.name() + "#" + call.member();
        Advice backward = owner.byName().getOrDefault(name, List.of()).stream()
                .flatMap(m -> m.replaces().stream())
                .filter(c -> c.name().equals(oldSpelling) && c.covers(call.argCount()))
                .findFirst()
                .map(c -> new Advice(c.note(), c.behaviourChanged()))
                .orElse(Advice.NONE);
        return forward.over(backward);
    }

    /** The overload this call reaches, by arity, falling back to any of the name — see {@link #returnTypeOf}. */
    private static ApiMember overloadOf(ApiClass then, Call call) {
        List<ApiMember> named = then.byName().getOrDefault(call.member(), List.of());
        return named.stream()
                .filter(m -> call.isField() ? m.field() : !m.field() && m.params().size() == call.argCount())
                .findFirst()
                .orElseGet(() -> named.stream().findFirst().orElse(null));
    }

    private static Advice pointerAdvice(ApiMember member) {
        if (member == null || member.replacedBy() == null) return Advice.NONE;
        return new Advice(member.replacedBy().note(), member.replacedBy().behaviourChanged());
    }

    /**
     * How the call's arguments become the target's: kept in order for as far as both go, then filled or
     * dropped. Filled with a <em>literal</em> default rather than the palette's, since the parameter's type
     * is whatever the target jar calls it and may be something this file cannot name.
     */
    private static List<ArgumentEdit> argumentsFor(int argCount, ApiMember target) {
        List<ArgumentEdit> edits = new ArrayList<>();
        for (int i = 0; i < target.params().size(); i++) {
            edits.add(i < argCount
                    ? new ArgumentEdit.Keep(i)
                    : new ArgumentEdit.Literal(target.params().get(i)));
        }
        return List.copyOf(edits);
    }

    /** What one field gives back — its own declared type, which is what a read of it is worth. */
    private static String typeOfField(ApiClass klass, String name) {
        return klass.byName().getOrDefault(name, List.of()).stream()
                .filter(ApiMember::field).map(ApiMember::type).findFirst().orElse("");
    }

    /** Every primitive a value of this type may stand in for without a cast. */
    private static final Map<String, Set<String>> WIDENS = Map.of(
            "byte", Set.of("short", "int", "long", "float", "double"),
            "short", Set.of("int", "long", "float", "double"),
            "char", Set.of("int", "long", "float", "double"),
            "int", Set.of("long", "float", "double"),
            "long", Set.of("float", "double"),
            "float", Set.of("double"));

    /**
     * Whether a value of {@code now} may stand where one of {@code was} was expected — the check that makes a
     * redirect in expression position safe rather than hopeful.
     *
     * <p>Four ways it can: the same type; a subtype of it in the target jar's own hierarchy; a widening
     * primitive conversion, which the compiler does silently; or {@code Object}, which takes anything. A
     * {@code void} <em>old</em> type accepts anything because nothing consumed it in the first place, and a
     * {@code void} new one fits nowhere, since there is no value to write.
     *
     * <p>Everything outside that list answers no — including a type the target jar does not declare at all,
     * which is where a {@code java.util.List} or a JDK type ends up. That falls the safe way: the site gets a
     * default and a review row instead of source that may not compile, and the user is told where the member
     * went in the same sentence.
     */
    private static boolean fits(String was, String now, Map<String, ApiClass> after) {
        if (was == null || now == null) return false;
        if (was.equals(now)) return true;
        if ("void".equals(was)) return true;
        if ("void".equals(now)) return false;
        if ("Object".equals(was)) return true;
        if (WIDENS.getOrDefault(now, Set.of()).contains(was)) return true;
        ApiClass target = after.get(now);
        return target != null && target.supertypes().contains(was);
    }

    /**
     * The type the old jar said this call gives back — the value the code around it was written for, and so
     * the type whose default stands in when the member is gone. {@code void} for a call made for its effect,
     * which is deleted rather than defaulted.
     */
    static String returnTypeOf(ApiClass then, Call call) {
        return then.byName().getOrDefault(call.member(), List.of()).stream()
                .filter(m -> call.isField() ? m.field() : !m.field() && m.params().size() == call.argCount())
                .map(ApiMember::type)
                .findFirst()
                // An arity nothing matched is a SIGNATURE_CHANGED break: any overload's type is a better
                // guess than none, and they are usually the same.
                .orElseGet(() -> then.byName().getOrDefault(call.member(), List.of()).stream()
                        .map(ApiMember::type).findFirst().orElse("void"));
    }

    /**
     * That same type spelled fully, <em>as the target jar has it</em> — null when the target has no such
     * class, which includes every primitive, {@code void} and {@code String}.
     *
     * <p>It is asked of the target and not of the old jar on purpose: this is what a cast in the repaired
     * source will name, and naming a class the release just dropped would trade one compile error for
     * another. Where it answers null the repair writes the bare literal, exactly as it did before — and the
     * one case that would leave uncompilable ({@code ImageTemplate t;} against a jar without it) is a
     * {@link SdkUpgradeService.BreakKind#TYPE_REMOVED} break, which has already refused the upgrade.
     */
    static String returnTypeFqn(String returnType, Map<String, ApiClass> after) {
        ApiClass klass = after.get(returnType);
        return klass == null ? null : klass.name();
    }
}
