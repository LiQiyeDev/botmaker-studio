package com.botmaker.studio.project.activity;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.ResolvedType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The editor's half of a stored value: what a fresh one starts as, how a value that arrived from anywhere is
 * reduced to something its type can hold, and how it is written into the user's own source.
 *
 * <h2>What this is not, since it replaced something larger</h2>
 *
 * <p>This is what is left of {@code VariableWire} after the vocabulary moved to the plugin contract
 * (plugin-platform phase 10b). That class held two things under one name: a {@code switch} over seventeen
 * hard-coded types saying what each one's text meant, and the shape-level rules that apply to all of them.
 * The first is gone — it was the host's copy of an answer only the plugin that owns the type can give, and
 * it is now {@link com.botmaker.plugin.api.value.ValueCodec}, asked through
 * {@link PluginHost#valueTypes()}. What stays here is the second, and the split is the repo's standing line:
 * <b>derivation belongs to the model, coercion belongs to the editor</b>. Clamping a number to a declared
 * range, pruning a value to the choices still on offer, ordering a multi-pick by its declaration — those are
 * rules a user can watch happen in a dialog, and they are the same rules whatever the type underneath is.
 *
 * <h2>Text on the wire</h2>
 *
 * <p>Every value is stored as a <b>list of strings</b> in {@code activities.json}, whatever its type — one
 * entry for an ordinary variable, one per item for a {@code List of …} one. That uniformity is the point: a
 * value has exactly one shape on disk, so the file has one reader, one writer and one normaliser rather than
 * a special case per type. It is also what lets a value survive a retype, a hand edit, and a plugin that is
 * not installed today.
 *
 * <h2>Every conversion is total</h2>
 *
 * <p>{@link #normalize} takes whatever the wire actually said and answers something this type can hold — a
 * garbage number, a choice that is no longer offered, a duration in a unit nobody knows. It cannot throw,
 * and it is a fixed point: normalising twice changes nothing.
 *
 * <p><b>A type nothing registered is the one thing left untouched.</b> Its text is kept exactly as the file
 * had it, because the host cannot canonicalise what it cannot read and rewriting it would destroy the value
 * of a variable whose plugin is merely absent. That state was unreachable while the vocabulary was a closed
 * enum; it is the ordinary state of a project opened without one of its plugins.
 */
public final class ValueWire {

    private ValueWire() {}

    private static ValueCatalog catalog() {
        return PluginHost.valueTypes();
    }

    /**
     * The registered type with this id, or an {@linkplain ValueType#unknown unknown} one.
     *
     * <p><b>An id written down in Studio's own source is not a back door</b>, and the distinction is worth
     * stating because it looks like one. The id is the word {@code activities.json} holds; naming
     * {@code "YES_NO"} here is Studio reading its own file format, exactly as it reads {@code "ONE_OF"}. What
     * it is not allowed to do is reach past the host for the <em>answer</em> — hence
     * {@link PluginHost#valueTypes()} and never {@code SdkValueTypes} — so an id no plugin claims yields an
     * unknown type here rather than a compile error somewhere.
     */
    public static ValueType type(String id) {
        return catalog().type(id);
    }

    /** One free value of the type with this id — the shape almost every caller wants. */
    public static ValueChoice one(String id) {
        return ValueChoice.of(type(id));
    }

    /** Every registered type, in the order the plugins registered them. */
    public static List<ValueType> registered() {
        return catalog().types();
    }

    /**
     * The registered type a <em>Java</em> type name denotes — the inverse of {@link ValueType#sourceName()},
     * empty when no plugin claims it.
     *
     * <p>Matched on the <b>simple</b> name, so {@code Duration} and {@code java.time.Duration} both find the
     * duration type, exactly as the type name written in a bot's own source may be either. The one caller
     * reads a variable declaration out of the file rather than out of a dialog, and a declaration is free to
     * spell a type any way that compiles.
     */
    public static Optional<ValueType> bySourceName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String simple = name.substring(name.lastIndexOf('.') + 1).trim();
        return registered().stream()
                .filter(t -> t.sourceName().equals(simple) || t.boxedName().equals(simple))
                .findFirst();
    }

    // ---- the type's shape -----------------------------------------------------------------------------

    /**
     * The Java type of the generated field — {@code int}, {@code java.time.Duration},
     * {@code java.util.List<com.botmaker.sdk.api.geometry.Point>}.
     *
     * <p>Everything outside {@code java.lang} is named in full, so the generated {@code Activities} class
     * needs no import for a variable's type — the cheapest way to guarantee it never needs one that was
     * forgotten. That is why this is not {@link ValueChoice#sourceName()}, which writes the simple name a
     * type declares an import for.
     */
    public static String javaType(ValueChoice type) {
        ValueType base = type.type();
        return type.isList() ? "java.util.List<" + boxed(base) + ">" : qualified(base);
    }

    /**
     * The resolved type, so the expression menu can filter variables against an expected slot type.
     *
     * <p>Was a {@code switch} over seventeen constants; it is three questions the type answers about itself
     * now — {@link ResolvedType#named} routes a primitive keyword to its own variant, so
     * {@code boolean}/{@code int}/{@code double}/{@code char} need no arm each. The one case a name cannot
     * carry is {@code String}: it is written unqualified and imports nothing, so nothing in the type says
     * {@code java.lang}.
     */
    public static ResolvedType resolvedType(ValueChoice type) {
        if (type.isList()) return ResolvedType.named("java.util.List");
        ValueType base = type.type();
        if (!base.isPrimitive() && "String".equals(base.sourceName()) && base.importName().isEmpty()) {
            return ResolvedType.of(JdkType.STRING);
        }
        return ResolvedType.named(qualified(base));
    }

    /** True when the editor writes the choices down — any shape that declares a set. */
    public static boolean hasOptions(ValueChoice type) {
        return type.hasOptions();
    }

    /** True when a declared {@link Bounds} means anything for this type — the type's own answer. */
    public static boolean isBounded(ValueType type) {
        return type != null && type.bounded();
    }

    /**
     * The choices a type brings with it, for the ones whose option list is not the editor's to write: an enum
     * answers its own constants. Empty for everything else, whose choices come from the variable.
     */
    public static List<String> fixedOptions(ValueType type) {
        return type == null ? List.of() : type.options();
    }

    /** The choices actually in force: the type's own when it has any, else the editor's. */
    public static List<String> effectiveOptions(ValueType type, List<String> declared) {
        List<String> fixed = fixedOptions(type);
        if (!fixed.isEmpty()) return fixed;
        return declared == null ? List.of() : declared.stream().filter(Objects::nonNull).toList();
    }

    // ---- values ---------------------------------------------------------------------------------------

    /** The wire value a freshly created variable of this type starts with; empty for a list. */
    public static List<String> defaultWire(ValueChoice type) {
        if (type.isList()) return List.of();
        return List.of(catalog().defaultItem(type.type().id()));
    }

    /**
     * {@code wire} reduced to something {@code type} can hold. Never throws, never returns null, and its own
     * output is always a fixed point.
     *
     * @param options the declared choices, for {@linkplain #hasOptions an option-bearing} shape
     * @param bounds  the declared range, for a bounded number
     */
    public static List<String> normalize(List<String> wire, ValueChoice type, List<String> options,
                                         Bounds bounds) {
        List<String> safe = wire == null ? List.of() : wire.stream().filter(Objects::nonNull).toList();
        List<String> choices = normalizeOptions(options, type, bounds);
        Bounds range = bounds == null ? Bounds.NONE : bounds;

        if (!type.isList()) {
            return List.of(constrain(
                    normalizeItem(safe.isEmpty() ? null : safe.getFirst(), type.type(), range), choices));
        }
        // An option-bearing list follows the declaration order, not the file's: two projects that picked the
        // same choices in a different order must write the same line, or a diff shows a change nobody made.
        if (!choices.isEmpty()) {
            LinkedHashSet<String> chosen = safe.stream()
                    .map(item -> normalizeItem(item, type.type(), range))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return choices.stream().filter(chosen::contains).toList();
        }
        return safe.stream().map(item -> normalizeItem(item, type.type(), range)).toList();
    }

    /**
     * The declared choices as this type actually stores them: each normalised, duplicates dropped, order kept.
     * Empty when the shape declares no set.
     *
     * <p>Every choice is itself a value of the base type, so it goes through the same normaliser the value
     * does — otherwise {@code "10 "} and {@code "10"} are two different choices, the radio button is labelled
     * with one and the stored value matches neither.
     */
    public static List<String> normalizeOptions(List<String> options, ValueChoice type, Bounds bounds) {
        if (!type.hasOptions() || options == null) return List.of();
        // The author's own list, never {@link #effectiveOptions}: an enum's constants are what its editor
        // offers to pick from, not a set to be copied onto every variable of that type and stored.
        return options.stream()
                .filter(Objects::nonNull)
                .map(option -> normalizeItem(option, type.type(), bounds == null ? Bounds.NONE : bounds))
                .distinct()
                .toList();
    }

    /** {@code value} if it is still on offer, else the first thing that is. Unconstrained when nothing is. */
    private static String constrain(String value, List<String> choices) {
        if (choices.isEmpty()) return value;
        return choices.contains(value) ? value : choices.getFirst();
    }

    /**
     * One item, canonicalised by its own codec and then clamped to the declared range.
     *
     * <p>Clamping runs <em>after</em> the codec and is re-canonicalised afterwards, so the two cannot
     * disagree about the spelling of the result — a clamp that produced {@code "5"} for a decimal would
     * otherwise store text its own reader normalises to {@code "5.0"} on the very next open.
     */
    private static String normalizeItem(String wire, ValueType type, Bounds bounds) {
        String canonical = catalog().normalize(type.id(), wire);
        if (!type.bounded() || bounds.isEmpty()) return canonical;
        return catalog().normalize(type.id(), clamp(canonical, bounds));
    }

    private static String clamp(String canonical, Bounds bounds) {
        double value = parseDouble(canonical, 0.0);
        double min = parseDouble(bounds.min(), Double.NEGATIVE_INFINITY);
        double max = parseDouble(bounds.max(), Double.POSITIVE_INFINITY);
        double clamped = Math.max(min, Math.min(max, value));
        // Written back through the plain double spelling and read by the type's own codec, which is what
        // turns it into an int again for a whole number.
        return clamped == value ? canonical : Double.toString(clamped);
    }

    private static double parseDouble(String text, double fallback) {
        if (text == null || text.isBlank()) return fallback;
        try {
            double parsed = Double.parseDouble(text.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- the user's own source ------------------------------------------------------------------------

    /**
     * The Java source of a single wire value, written out as a literal, with the one import it needs.
     *
     * <p>One caller, and it is not the generator: dropping a value into the <em>user's own</em> source from
     * the Variables screen. What a generated file gets is {@link ValueCatalog#initializer}, which composes
     * the shape and writes everything fully qualified so that no import can be forgotten; here an import is
     * arranged rather than avoided, because the user's file is one a person reads.
     *
     * @return the literal and the class to import ({@code null} when none is needed), or {@code null} for a
     *         type nothing registered — which has no written form at all, and must not be guessed one
     */
    public static Literal literalSource(ValueType type, String wire) {
        if (type == null) return null;
        return catalog().literal(type.id(), wire)
                .map(l -> new Literal(l.source(), l.importName().isEmpty() ? null : l.importName()))
                .orElse(null);
    }

    /** One literal and the import it needs — what {@code CodeEditor.replaceWithRawExpression} takes. */
    public record Literal(String source, String importFqn) {}

    // ---- naming ---------------------------------------------------------------------------------------

    private static String qualified(ValueType type) {
        return type.importName().isEmpty() ? type.sourceName() : type.importName();
    }

    private static String boxed(ValueType type) {
        return type.importName().isEmpty() ? type.boxedName() : type.importName();
    }
}
