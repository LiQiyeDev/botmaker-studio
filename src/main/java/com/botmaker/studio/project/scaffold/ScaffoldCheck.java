package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.project.scaffold.ScaffoldSurface.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the SDK a project actually pins can carry the files Studio generates — and, when it cannot, whether
 * its own pointers say what to write instead.
 *
 * <h2>The direction this answers</h2>
 *
 * <p>{@code ScaffoldSurfaceTest} guarantees the <b>backward</b> direction: every element of
 * {@link ScaffoldSurface} exists in the SDK <em>Studio was compiled against</em>, so every SDK up to Studio's
 * own is safe by construction and this check will find nothing to do. The direction it cannot guarantee is
 * <b>forward</b>: a project may pin an SDK newer than this Studio, which did not exist when the test ran. That
 * jar cannot be tested, but it can be <em>asked</em> — it is on disk, it carries {@code @Replaces} on every
 * survivor, and that is exactly the question here.
 *
 * <p>So the three outcomes are the three honest answers: {@link Status#SATISFIED} — write as always;
 * {@link Status#REPAIRABLE} — write, then apply these substitutions; {@link Status#UNSATISFIABLE} — write
 * nothing at all and say which element is missing.
 *
 * <h2>Fail-open</h2>
 *
 * <p>{@link SdkFacts#indexed()} false — the jar has not resolved, the user is offline, the scan failed —
 * answers {@link Status#SATISFIED} with nothing to do, the same rule {@code SdkSurfaceService} already
 * applies to every presence query. A probe that could not run must never be the reason a project cannot be
 * created: the failure mode it would prevent (a generated file that does not compile) is visible and
 * recoverable, whereas a refusal with no diagnosis is neither.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It says what <em>would</em> have to change, not that the change can be written. Expressing a substitution
 * in the emitted text is {@link ScaffoldRepair}'s job and it can refuse — an SDK method a generated class
 * <em>overrides</em> cannot be retargeted by rewriting calls. A caller therefore runs both and treats either
 * refusal the same way.
 */
public final class ScaffoldCheck {

    private ScaffoldCheck() {}

    /**
     * One SDK jar, asked only the three questions this check has. Narrow on purpose: it is implemented over a
     * bytecode scan in {@code services} (where the pointer reader lives) and over a hand-built stub in the
     * tests, and neither has to know about the other.
     */
    public interface SdkFacts {

        /** False when nothing was scanned — see the fail-open rule in the class Javadoc. */
        boolean indexed();

        /** Whether the jar declares this fully-qualified type. */
        boolean hasType(String fqn);

        /**
         * Whether {@code fqn} declares {@code member} with exactly {@code arity} parameters —
         * {@link ScaffoldSurface#CTOR} naming a constructor. The arity is checked because the generators write
         * a fixed argument list: an overload that survived with a different one is not a member the scaffold
         * can call.
         */
        boolean hasMember(String fqn, String member, int arity);

        /**
         * Where {@code ref} — {@code fqn} or {@code fqn#member} — went, according to the {@code @Replaces}
         * claims the jar's own survivors carry, nearest first. Empty when nothing claims it.
         */
        List<String> survivorsOf(String ref);
    }

    /** What the jar can carry. */
    public enum Status {
        /** Every element is present. Emit as always. */
        SATISFIED,
        /** Some are missing but the jar's pointers name survivors. Emit, then substitute. */
        REPAIRABLE,
        /** At least one element is missing with nothing taking its place. Write nothing. */
        UNSATISFIABLE
    }

    /**
     * One element the jar does not have, and what it should be written as instead.
     *
     * @param element the declared element, as Studio would have emitted it
     * @param type    the fully-qualified type to write instead
     * @param member  the member name to write instead, {@link ScaffoldSurface#TYPE_ONLY} when the element is a
     *                type. Equal to {@code element.member()} when only the owning type moved.
     */
    public record Substitution(Element element, String type, String member) {

        /** Whether the owning type is written differently — the file-wide half of the repair. */
        public boolean typeMoved() {
            return !type.equals(element.type());
        }

        /** Whether the member is written differently — the per-call half. */
        public boolean memberMoved() {
            return !member.equals(element.member());
        }

        /** {@code fqn} or {@code fqn#member}, the spelling the pointer annotations use. */
        public String ref() {
            return ScaffoldSurface.TYPE_ONLY.equals(member) ? type : type + "#" + member;
        }
    }

    /**
     * The verdict.
     *
     * @param substitutions what to write instead, in {@link ScaffoldSurface} declaration order — empty unless
     *                      {@link Status#REPAIRABLE}
     * @param missing       the {@link Element#line()} of every element with nowhere to go — empty unless
     *                      {@link Status#UNSATISFIABLE}
     */
    public record Result(Status status, List<Substitution> substitutions, List<String> missing) {

        static final Result SATISFIED = new Result(Status.SATISFIED, List.of(), List.of());

        /** Whether anything may be written at all. */
        public boolean canEmit() {
            return status != Status.UNSATISFIABLE;
        }

        /**
         * The sentence a refusal is reported with — it names the elements, because "the SDK is too new" is not
         * something a user can act on and "{@code PopupGuard.install} is gone" is.
         */
        public String refusal() {
            return "This project's SDK no longer has " + String.join(", ", missing)
                    + ", which Studio writes into the files it generates, and it does not say what replaced "
                    + (missing.size() == 1 ? "it" : "them") + ". Update Studio (Help ▸ Check for updates), or "
                    + "pick an SDK version this Studio knows.";
        }
    }

    /**
     * Resolves every declared element against {@code facts}.
     *
     * <p>A missing element is chased in two ways, in this order. First its own back edge: {@code A#m} may be
     * claimed by {@code B#n}, which is the general case and the only one that can express a rename of the
     * member itself. Failing that, its <em>type</em>'s back edge: a type that moved carries its members with
     * it, so {@code A#m} where {@code A} became {@code B} resolves to {@code B#m} without the SDK author
     * having had to write a pointer per member — which is what makes annotating a package move bearable.
     */
    public static Result of(SdkFacts facts) {
        return of(facts, ScaffoldSurface.all());
    }

    /**
     * The same over the elements one generator writes.
     *
     * <p>Which matters because the two kinds of generated file are written at different moments by different
     * code. Project creation writes the lot and asks about the lot; a regeneration writes
     * {@code Activities}, {@code ActivityRegistry}, {@code FlowDriver} and {@code Templates} and must ask
     * about <em>those</em> — refusing to let a user redraw their flow because the SDK moved
     * {@code ImageFinder.whileFindAny}, which only the once-written {@code Popups} seed names, would be a
     * block with no cause the user could act on. A {@link Origin#SEED} element that has gone is the
     * <em>upgrade's</em> business, and the upgrade reports it up front.
     */
    public static Result of(SdkFacts facts, ScaffoldSurface.Origin origin) {
        return of(facts, ScaffoldSurface.of(origin));
    }

    private static Result of(SdkFacts facts, List<Element> elements) {
        if (!facts.indexed()) return Result.SATISFIED;

        List<Substitution> substitutions = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Element element : elements) {
            if (present(facts, element)) continue;
            Substitution repair = repairFor(facts, element);
            if (repair != null) {
                substitutions.add(repair);
            } else {
                missing.add(element.line());
            }
        }
        if (!missing.isEmpty()) {
            return new Result(Status.UNSATISFIABLE, List.of(), List.copyOf(missing));
        }
        return substitutions.isEmpty()
                ? Result.SATISFIED
                : new Result(Status.REPAIRABLE, List.copyOf(substitutions), List.of());
    }

    private static boolean present(SdkFacts facts, Element element) {
        if (element.isType()) return facts.hasType(element.type());
        return facts.hasType(element.type())
                && facts.hasMember(element.type(), element.member(), element.arity());
    }

    private static Substitution repairFor(SdkFacts facts, Element element) {
        if (element.isType()) {
            for (String survivor : facts.survivorsOf(element.type())) {
                // A type's claimant must itself be a type: a member claiming to replace a whole class is a
                // pointer this cannot act on, since there is no member to write in a `extends` clause.
                if (survivor.contains("#") || !facts.hasType(survivor)) continue;
                return new Substitution(element, survivor, ScaffoldSurface.TYPE_ONLY);
            }
            return null;
        }
        for (String survivor : facts.survivorsOf(element.ref())) {
            if (!survivor.contains("#")) continue;
            String type = survivor.substring(0, survivor.indexOf('#'));
            String member = survivor.substring(survivor.indexOf('#') + 1);
            if (facts.hasType(type) && facts.hasMember(type, member, element.arity())) {
                return new Substitution(element, type, member);
            }
        }
        for (String survivor : facts.survivorsOf(element.type())) {
            if (survivor.contains("#") || !facts.hasType(survivor)) continue;
            if (facts.hasMember(survivor, element.member(), element.arity())) {
                return new Substitution(element, survivor, element.member());
            }
        }
        return null;
    }
}
