package com.botmaker.studio.project.scaffold;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every hole Studio can fill, and which generations of each it knows how to produce.
 *
 * <h2>Why an enum and not a string</h2>
 *
 * <p>Filling a hole used to be {@code Map.of("MAX_STEPS", "1000", "STEP_DELAY_MS", "0")} in two classes at
 * five call sites. A misspelling there is not a compile error — it is a fragment that arrives at
 * {@link TemplateStore#render} naming a hole no template has, which is caught, but only at runtime and only
 * because {@code render} refuses rather than shrugs. As an enum the name is the compiler's problem, the set of
 * holes is enumerable (which is what lets a test check it against what the SDK actually ships), and
 * {@code FLOW} can only be spelled one way.
 *
 * <h2>The generation is the part that matters</h2>
 *
 * <p>Each constant carries the generations of its shape that <em>this</em> Studio can write. A template
 * declares one generation per hole; the match is <b>exact</b>. So:
 *
 * <ul>
 *   <li>A newer SDK that bumps {@code FLOW:1 → FLOW:2} meets a Studio that only produces {@code FLOW:1}, finds
 *       no match, and is <b>refused by name</b> — instead of the older arrangement being written into the
 *       newer frame, which every name-based check would have called fine.
 *   <li>A newer Studio keeps {@code 1} beside {@code 2} in the same constant and so still edits a bot pinned
 *       to the older SDK. That is why this is a set and not a number.
 *   <li>A hole a newer SDK <em>adds</em> needs nothing: the template's own default stands and the file
 *       compiles. Only a change of shape costs a generation.
 * </ul>
 *
 * <p>A producer, once written, is never dropped — {@code scaffold-holes.txt} is the committed ledger of every
 * {@code name:generation} that has ever shipped, and {@code ScaffoldHolesTest} refuses a removal. Deleting one
 * would silently un-support every SDK still declaring it, which is exactly the case no test could otherwise
 * see: those SDKs are published and cannot be resolved offline.
 *
 * <p><b>The text</b> for a generation is produced where the data is — {@code ActivityService} and
 * {@code ProjectCreator} — not here. What lives here is the closed set of names and the closed set of
 * generations, which is what has to be checkable in one place. When a second generation of a hole arrives, the
 * call site branches on the generation {@link TemplateStore.Template#generationOf} reports for the template it
 * is filling.
 */
public enum ScaffoldToken {

    /**
     * {@code Activities}: extra imports. <b>Filled with nothing, always</b> — {@code VariableWire.javaType}
     * names every type in full, which is the cheapest way to guarantee the file never wants an import that
     * was forgotten. Studio claims the hole so that a template declaring it is not an unknown, and produces
     * no text for it; see {@code ActivityService.generateSource}.
     */
    IMPORTS(Fill.RESERVED, 1),

    /** {@code Activities}: one {@code public static final} per stored value. */
    FIELDS(1),

    /** {@code Activities}: the static block that loads each of them through {@code Wire}. */
    INITS(1),

    /** {@code ActivityRegistry} / {@code FlowDriver}: the import of the bot's own {@code activities} package. */
    ACTIVITY_IMPORT(1),

    /** {@code ActivityRegistry}: one typed singleton per activity the flow can reach. */
    SINGLETONS(1),

    /** {@code ActivityRegistry}: those singletons again, as the flat {@code List<Activity<?>>}. */
    ALL(1),

    /** {@code FlowDriver}: the start node and the whole routing table. */
    FLOW(1),

    /** {@code FlowDriver}: how many hand-offs one run may make. */
    MAX_STEPS(1),

    /** {@code FlowDriver}: the pause between two activities, in milliseconds. */
    STEP_DELAY_MS(1),

    /** An activity stub: the constants of that activity's own {@code Outcome} enum. */
    OUTCOMES(1),

    /** An activity stub: the {@code Activities} flag its {@code isEnabled()} reads. */
    ENABLED(1);

    /**
     * Whether Studio ever has text to put in a hole, or claims it and deliberately leaves the SDK's default.
     *
     * <p>The distinction is only visible to {@code ScaffoldHolesTest}, which requires every hole to be
     * <em>compiled</em> by at least one corpus project and would otherwise demand a fixture that makes
     * {@link #IMPORTS} non-empty — a fixture that cannot exist, since nothing produces one. Saying it here
     * keeps that exception a property of the hole rather than a name hard-coded in a test.
     */
    enum Fill {
        /** Studio produces the text between the fences. */
        PRODUCED,
        /** Studio knows the hole and never fills it; the template's own default is what ships. */
        RESERVED
    }

    private final Fill fill;
    private final Set<Integer> generations;

    ScaffoldToken(int... generations) {
        this(Fill.PRODUCED, generations);
    }

    ScaffoldToken(Fill fill, int... generations) {
        this.fill = fill;
        Set<Integer> set = new TreeSet<>();
        for (int g : generations) set.add(g);
        this.generations = Set.copyOf(set);
    }

    /** Whether Studio claims this hole without ever producing text for it — see {@link Fill}. */
    public boolean isReserved() {
        return fill == Fill.RESERVED;
    }

    /** Whether this Studio can write the shape {@code generation} asks for. */
    public boolean canFill(int generation) {
        return generations.contains(generation);
    }

    /** {@code NAME:generation} — the spelling the manifest, the fences and the ledger all use. */
    public String key(int generation) {
        return name() + ":" + generation;
    }

    /** Every generation of this hole, as keys — {@link #key} over the whole set. */
    public Set<String> keys() {
        Set<String> out = new LinkedHashSet<>();
        for (int generation : new TreeSet<>(generations)) out.add(key(generation));
        return out;
    }

    /**
     * Every {@code name:generation} this Studio can produce, sorted — what {@code ScaffoldHolesTest} compares
     * against the SDK jar's manifest and against the committed ledger.
     */
    public static Set<String> allKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (ScaffoldToken token : values()) out.addAll(token.keys());
        return out;
    }
}
