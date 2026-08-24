package com.botmaker.studio.project.scaffold;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Every SDK element <b>Studio injects</b> into a generated file — declared once, as data.
 *
 * <h2>What this is a list of, and what it stopped being one of</h2>
 *
 * <p>It used to be every SDK element a generated file <em>named</em>, because Studio wrote the whole file
 * out of text blocks and a text block cannot be asked what it names. Keeping the list honest took a 484-line
 * JDT visitor over the generators' output ({@code ScaffoldScan}), a committed {@code scaffolding-surface.txt}
 * carried to a repository that cannot read this one, and a rule in the SDK's {@code ApiPointersTest} to read
 * it back — all of it reconstructing, by parsing, an answer a compiler gives away.
 *
 * <p>Since 2026-08-24 the file's <b>frame</b> is the SDK's own: compiling templates in
 * {@code botmaker-sdk/src/templates/java}, checked by that module's compiler and its
 * {@code ScaffoldTemplatesTest}, extracted from the jar a project actually pins. A frame element cannot go
 * missing relative to its own jar, so there is nothing here to declare about it. What is left is the far
 * smaller set Studio drops <em>between the fences</em>: the flow table's {@code node}/{@code route}, the
 * field types and {@code Wire} readers of {@code Activities}, and {@code BotMaker.print} — the "Empty"
 * template being the one generator still written as Java in this repository.
 *
 * <p>So the three things that once needed three mechanisms now need one each. {@code ScaffoldCompileTest}
 * compiles the assembled output of several activity models against the real SDK jar — a stronger answer to
 * "does what we emit work?" than any declaration was. {@code ScaffoldSurfaceTest} resolves every entry below
 * against that same jar, so an SDK rename breaks <em>Studio's</em> build on a named element. And this list is
 * what {@link ScaffoldCheck} asks a <em>newer</em> jar about — the one question a compiler here cannot
 * answer, because that jar did not exist when it ran.
 *
 * <h2>What counts as an element, and what does not</h2>
 *
 * <p>A <b>type</b> entry means an injected fragment writes that type's name in a <em>type position</em> — a
 * field's declared type, a type argument, a {@code .class} literal. Merely qualifying a static call does not
 * count: {@code Wire.duration(…)} names the member, and the type is only how it is spelled. That is why
 * {@code Wire} and {@code FlowGraph} appear here only through their members while {@code Point} and
 * {@code Rect} appear as types — and it is the same line the SDK's {@code @Scaffolding} annotations are
 * drawn on.
 *
 * <p><b>Constants are not elements.</b> The generated {@code Activities} names an enum constant as its
 * fallback ({@code Key.A}), but which one it names is derived from the enum, not fixed by the generator, so
 * there is nothing stable to declare. The <em>type</em> is declared and that is what has to keep resolving.
 *
 * <h2>Seed versus regenerated</h2>
 *
 * <p>{@link Origin} is carried per element because the two kinds of generated file fail differently, and are
 * governed differently. A {@link Origin#SEED} file is written once at creation and is the user's thereafter —
 * it only has to be correct against the jar pinned at that moment. A {@link Origin#REGENERATED} file is
 * rewritten wholesale on every model change and must be correct against whatever SDK the project pins today,
 * including one newer than Studio. An element can be both; none currently is, because the seeds are all
 * templates now and the one that is not — the "Empty" project's single {@code BotMaker.print} — is written
 * nowhere else.
 */
public final class ScaffoldSurface {

    private ScaffoldSurface() {}

    /** {@link Element#member()} for an entry that is the type itself rather than one of its members. */
    public static final String TYPE_ONLY = "";

    /** A constructor, spelled the way {@code @ReplacedBy}/{@code @Replaces} spell one. */
    public static final String CTOR = "<init>";

    /** {@link Element#arity()} for an entry with no parameter list — a type, or a field. */
    public static final int NO_ARITY = -1;

    /** Which generator writes an element — see the class javadoc. */
    public enum Origin {
        /** Written once at project creation and the user's thereafter: the entry point, GoHome, Popups, the stubs. */
        SEED,
        /** Rewritten wholesale on every model change: Activities, ActivityRegistry, FlowDriver, Templates. */
        REGENERATED
    }

    /**
     * One SDK element a generator names: a type, or a member of one.
     *
     * @param type    the fully qualified SDK type
     * @param member  the method or constructor name, {@link #CTOR} for a constructor, {@link #TYPE_ONLY} when
     *                the entry is the type itself
     * @param arity   the <em>declared</em> parameter count, or {@link #NO_ARITY} for a type. Declared, not the
     *                number of arguments a generator passes: a varargs member reached with none still has one
     *                parameter, and the SDK's gate — which reads the declaration and has no call site to count
     *                — could not agree with anything else
     * @param origins which generators write it — never empty
     */
    public record Element(String type, String member, int arity, Set<Origin> origins)
            implements Comparable<Element> {

        public Element {
            origins = Set.copyOf(origins);
        }

        public boolean isType() {
            return TYPE_ONLY.equals(member);
        }

        /** {@code fqn} or {@code fqn#member} — the spelling the SDK's pointer annotations use. */
        public String ref() {
            return isType() ? type : type + "#" + member;
        }

        /**
         * How this element is written where a human has to read it: {@link #ref()} plus the arity in
         * parentheses when it has one. It is the spelling a refusal names — {@code ScaffoldCheck} lists the
         * missing elements this way — so it is deliberately the same one the SDK's pointer annotations use.
         */
        public String line() {
            return arity == NO_ARITY ? ref() : ref() + "(" + arity + ")";
        }

        @Override
        public int compareTo(Element other) {
            return line().compareTo(other.line());
        }
    }

    private static Element type(String fqn, Origin... origins) {
        return new Element(fqn, TYPE_ONLY, NO_ARITY, EnumSet.copyOf(List.of(origins)));
    }

    private static Element member(String fqn, String member, int arity, Origin... origins) {
        return new Element(fqn, member, arity, EnumSet.copyOf(List.of(origins)));
    }

    private static final String BOTMAKER = "com.botmaker.sdk.api.util.BotMaker";
    private static final String FLOW_GRAPH = "com.botmaker.sdk.api.flow.FlowGraph";
    private static final String WIRE = "com.botmaker.sdk.api.config.Wire";
    private static final String KEY = "com.botmaker.sdk.api.interaction.Key";
    private static final String MOUSE_BUTTON = "com.botmaker.sdk.api.interaction.MouseButton";
    private static final String DIRECTION = "com.botmaker.sdk.api.geometry.Direction";
    private static final String POINT = "com.botmaker.sdk.api.geometry.Point";
    private static final String SIZE = "com.botmaker.sdk.api.geometry.Size";
    private static final String RECT = "com.botmaker.sdk.api.geometry.Rect";
    private static final String IMAGE_TEMPLATE = "com.botmaker.sdk.api.vision.ImageTemplate";
    private static final String PRECISION = "com.botmaker.sdk.api.vision.Precision";

    /**
     * The declaration. Grouped by the file that writes it rather than sorted, because the reader who has to
     * change it is holding one generator, not an alphabet.
     */
    private static final List<Element> DECLARED = List.of(
            // ── the "Empty" template's whole body, and the last generator that is still a text block ───
            member(BOTMAKER, "print", 1, Origin.SEED),

            // ── FlowDriver's FLOW token: the drawn flow as a table, one node() per reachable activity and
            //    one route() per wire. `FlowGraph.of(` and the walk around it are the template's own text,
            //    so they are not declared here — only what Studio drops between the fences is.
            member(FLOW_GRAPH, "node", 6, Origin.REGENERATED),
            member(FLOW_GRAPH, "route", 2, Origin.REGENERATED),

            // ── Activities' FIELDS and INITS tokens: one field per stored value, and the Wire reader it is
            //    read back with. The parsers were generated bodies until 2026-08-24 — which is why the
            //    constructors of Point, Rect and the rest are gone from here while their types stay: a
            //    field declaration still writes a type.
            member(WIRE, "one", 1, Origin.REGENERATED),
            member(WIRE, "many", 2, Origin.REGENERATED),
            member(WIRE, "text", 1, Origin.REGENERATED),
            member(WIRE, "flag", 1, Origin.REGENERATED),
            member(WIRE, "whole", 1, Origin.REGENERATED),
            member(WIRE, "decimal", 1, Origin.REGENERATED),
            member(WIRE, "letter", 1, Origin.REGENERATED),
            member(WIRE, "date", 1, Origin.REGENERATED),
            member(WIRE, "time", 1, Origin.REGENERATED),
            member(WIRE, "duration", 1, Origin.REGENERATED),
            member(WIRE, "color", 1, Origin.REGENERATED),
            member(WIRE, "template", 1, Origin.REGENERATED),
            member(WIRE, "key", 1, Origin.REGENERATED),
            member(WIRE, "mouseButton", 1, Origin.REGENERATED),
            member(WIRE, "direction", 1, Origin.REGENERATED),
            member(WIRE, "precision", 1, Origin.REGENERATED),
            member(WIRE, "point", 1, Origin.REGENERATED),
            member(WIRE, "size", 1, Origin.REGENERATED),
            member(WIRE, "area", 1, Origin.REGENERATED),
            type(IMAGE_TEMPLATE, Origin.REGENERATED),
            type(PRECISION, Origin.REGENERATED),
            type(POINT, Origin.REGENERATED),
            type(SIZE, Origin.REGENERATED),
            type(RECT, Origin.REGENERATED),
            type(KEY, Origin.REGENERATED),
            type(MOUSE_BUTTON, Origin.REGENERATED),
            type(DIRECTION, Origin.REGENERATED));

    /** Every declared element, in declaration order. */
    public static List<Element> all() {
        return DECLARED;
    }

    /** The elements {@code origin}'s generators write. */
    public static List<Element> of(Origin origin) {
        return DECLARED.stream().filter(e -> e.origins().contains(origin)).toList();
    }
}
