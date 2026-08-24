package com.botmaker.studio.project.scaffold;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Every SDK element the generated files name — declared once, as data.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Studio's generators are text blocks. {@code ProjectCreator.gameBotSources} writes {@code Bot.start} and
 * {@code PopupGuard.install}; {@code ActivityService.generateDriverSource} writes {@code Watchdog.checkpoint}
 * and {@code Wait.milliseconds}; {@code VariableWire} writes {@code new Point(…)}. Nothing checked that the
 * SDK a project pins actually <em>has</em> any of them, so a renamed SDK member surfaced as a broken file in
 * somebody's project rather than as a failing build here — and an upgrade that touched one was refused
 * mid-apply, after the user had committed to it.
 *
 * <p>A text block cannot be asked what it names. A list can. So the set is written down here, and
 * {@code ScaffoldSurfaceTest} keeps the list honest in both directions: it parses what the generators actually
 * emit and asserts the symbols it finds <b>equal</b> this declaration — neither an undeclared symbol nor a
 * stale entry survives — and then resolves every entry against the SDK Studio builds against. An SDK rename
 * therefore breaks <em>Studio's</em> build, on a named element.
 *
 * <h2>What counts as an element, and what does not</h2>
 *
 * <p>A <b>type</b> entry means the generated source writes that type's name in a <em>type position</em> —
 * {@code extends Activity<…>}, a field or parameter type, a type argument, a {@code .class} literal. Merely
 * qualifying a static call does not count: {@code Bot.start(…)} names the member, and the type is only how it
 * is spelled. That is why {@code Bot}, {@code Debug} and {@code Wait} appear here only through their members
 * while {@code Point} and {@code Activity} appear as types too — and it is the same line the SDK's
 * {@code @Scaffolding} annotations are drawn on.
 *
 * <p><b>Constants are not elements.</b> The generated {@code Activities} names an enum constant as its
 * fallback ({@code Key.A}), but which one it names is derived from the enum, not fixed by the generator, so
 * there is nothing stable to declare. The <em>type</em> is declared and that is what has to keep resolving.
 *
 * <h2>Seed versus regenerated</h2>
 *
 * <p>{@link Origin} is carried per element because the two kinds of generated file fail differently, and from
 * phase 10 on they are governed differently. A {@link Origin#SEED} file is written once at creation and is the
 * user's thereafter — it only has to be correct against the jar pinned at that moment. A
 * {@link Origin#REGENERATED} file is rewritten wholesale on every model change and must be correct against
 * whatever SDK the project pins today, including one newer than Studio. An element can be both, and several
 * are: the entry point and the generated driver both call {@code Bot.stop}.
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
         * The line this element takes in {@code scaffolding-surface.txt}: {@link #ref()} plus the arity in
         * parentheses when it has one. The SDK's gate rebuilds exactly this string from its
         * {@code @Scaffolding} annotations, so the two sets can be compared without either side knowing the
         * other's vocabulary — which it could not, since the dependency runs one way.
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

    private static final String BOT = "com.botmaker.sdk.api.bot.Bot";
    private static final String ACTIVITY = "com.botmaker.sdk.api.bot.Activity";
    private static final String POPUP_GUARD = "com.botmaker.sdk.api.bot.PopupGuard";
    private static final String WATCHDOG = "com.botmaker.sdk.api.bot.Watchdog";
    private static final String DEBUG = "com.botmaker.sdk.api.util.Debug";
    private static final String BOTMAKER = "com.botmaker.sdk.api.util.BotMaker";
    private static final String WAIT = "com.botmaker.sdk.api.interaction.Wait";
    private static final String KEY = "com.botmaker.sdk.api.interaction.Key";
    private static final String MOUSE_BUTTON = "com.botmaker.sdk.api.interaction.MouseButton";
    private static final String DIRECTION = "com.botmaker.sdk.api.geometry.Direction";
    private static final String POINT = "com.botmaker.sdk.api.geometry.Point";
    private static final String SIZE = "com.botmaker.sdk.api.geometry.Size";
    private static final String RECT = "com.botmaker.sdk.api.geometry.Rect";
    private static final String IMAGE_FINDER = "com.botmaker.sdk.api.vision.ImageFinder";
    private static final String IMAGE_TEMPLATE = "com.botmaker.sdk.api.vision.ImageTemplate";
    private static final String IMAGE_TEMPLATE_GROUP = "com.botmaker.sdk.api.vision.ImageTemplateGroup";
    private static final String PRECISION = "com.botmaker.sdk.api.vision.Precision";

    /**
     * The declaration. Grouped by the file that writes it rather than sorted, because the reader who has to
     * change it is holding one generator, not an alphabet.
     */
    private static final List<Element> DECLARED = List.of(
            // ── the "Empty" template's whole body ──────────────────────────────────────────────────────
            member(BOTMAKER, "print", 1, Origin.SEED),

            // ── the game-bot entry point ───────────────────────────────────────────────────────────────
            member(BOT, "start", 2, Origin.SEED),
            member(POPUP_GUARD, "install", 1, Origin.SEED),

            // ── FlowDriver: seeded empty at creation, regenerated from the flow thereafter ─────────────
            member(BOT, "stop", 0, Origin.SEED, Origin.REGENERATED),
            member(DEBUG, "error", 1, Origin.SEED, Origin.REGENERATED),
            member(WATCHDOG, "checkpoint", 0, Origin.SEED, Origin.REGENERATED),
            member(WAIT, "milliseconds", 1, Origin.REGENERATED),
            member(POPUP_GUARD, "enabled", 1, Origin.REGENERATED),

            // ── Activity: extended by GoHome, Popups and every stub; driven by FlowDriver ──────────────
            type(ACTIVITY, Origin.SEED, Origin.REGENERATED),
            member(ACTIVITY, "isEnabled", 0, Origin.SEED),
            member(ACTIVITY, "run", 0, Origin.SEED),
            member(ACTIVITY, "execute", 0, Origin.SEED, Origin.REGENERATED),
            member(ACTIVITY, "active", 0, Origin.REGENERATED),

            // ── Popups: the guard body, shipped with an empty group ────────────────────────────────────
            member(IMAGE_FINDER, "whileFindAny", 2, Origin.SEED),
            type(IMAGE_TEMPLATE_GROUP, Origin.SEED),
            // 1, not 0: `of()` passes nothing to a varargs parameter, and the surface records the declaration.
            member(IMAGE_TEMPLATE_GROUP, "of", 1, Origin.SEED),

            // ── Activities: one field, one parser and one literal per stored variable type ─────────────
            type(IMAGE_TEMPLATE, Origin.REGENERATED),
            member(IMAGE_TEMPLATE, CTOR, 1, Origin.REGENERATED),
            type(PRECISION, Origin.REGENERATED),
            member(PRECISION, CTOR, 3, Origin.REGENERATED),
            type(POINT, Origin.REGENERATED),
            member(POINT, CTOR, 2, Origin.REGENERATED),
            type(SIZE, Origin.REGENERATED),
            member(SIZE, CTOR, 2, Origin.REGENERATED),
            type(RECT, Origin.REGENERATED),
            member(RECT, CTOR, 4, Origin.REGENERATED),
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

    /**
     * The whole surface as {@code scaffolding-surface.txt} holds it: one {@link Element#line()} per line,
     * sorted, newline-terminated. Sorted because it is a file two repositories compare — a diff has to mean a
     * change to the surface, never a change to the order somebody happened to declare it in.
     */
    public static String surfaceFile() {
        List<Element> sorted = new ArrayList<>(DECLARED);
        sorted.sort(Comparator.naturalOrder());
        StringBuilder out = new StringBuilder();
        for (Element e : sorted) out.append(e.line()).append('\n');
        return out.toString();
    }
}
