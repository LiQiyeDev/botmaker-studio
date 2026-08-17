package com.botmaker.studio.project.settings;

import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.project.TemplateConstants;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.ResolvedType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The curated set of types a {@link Setting} can have — the successor to
 * {@link com.botmaker.studio.project.activity.ActivityType}, and the one place that knows how a stored value
 * becomes Java source.
 *
 * <p><b>Text in, source out.</b> Every value travels as a list of strings (the <em>wire form</em>) and is
 * rendered into a Java literal by {@link #literal}. That replaces the previous model's arrangement, where the
 * value was a Jackson {@code JsonNode} and the generated class carried a {@code node(v, "NAME")} reader to
 * parse it back at bot startup. Nothing is parsed at startup any more: by the time a bot runs, its settings
 * are literals the compiler has already checked.
 *
 * <p><b>Every conversion is total, and it happens here rather than there.</b> {@link #normalize} takes
 * whatever the wire actually said and answers something this type can render — a garbage number, a choice
 * that is no longer offered, a duration in a unit nobody knows. It cannot throw and it cannot return
 * something {@link #literal} would choke on. The old model degraded the same failures inside the running bot,
 * silently; doing it in Studio means the editor is looking at the value that will actually be compiled.
 *
 * <p>{@link #javaType()} is <b>fully qualified</b> for everything outside {@code java.lang}, and so is every
 * literal these constants build. The generated {@code Settings} class therefore needs no import block at all,
 * which is the cheapest possible way to guarantee it never needs one that was forgotten.
 */
public enum SettingType {

    /** A tick box. */
    BOOL("Yes / No", "boolean", ResolvedType.BOOLEAN) {
        @Override public List<String> defaultWire() { return List.of("false"); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            return List.of(Boolean.parseBoolean(wire) ? "true" : "false");
        }
        @Override public String literal(List<String> wire) { return first(wire); }
    },

    /**
     * The enable flag of an activity: a {@code boolean} like {@link #BOOL}, kept as its own constant because
     * the two differ in who owns them. A {@code BOOL} is a knob somebody added; an {@code ENABLE} exists
     * because an activity exists, is named after it, and disappears when it is archived — so the dialog must
     * not offer to delete or retype one, and it can only tell them apart if the type says so.
     */
    ENABLE("Activity enabled", "boolean", ResolvedType.BOOLEAN) {
        @Override public List<String> defaultWire() { return List.of("true"); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            return List.of(Boolean.parseBoolean(wire) ? "true" : "false");
        }
        @Override public String literal(List<String> wire) { return first(wire); }
    },

    INT("Whole number", "int", ResolvedType.INT) {
        @Override public List<String> defaultWire() { return List.of("0"); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            long value = parseLong(wire, 0L);
            value = clampLong(value, bounds.min(), bounds.max());
            return List.of(Long.toString((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value))));
        }
        @Override public String literal(List<String> wire) { return first(wire); }
    },

    DOUBLE("Decimal number", "double", ResolvedType.DOUBLE) {
        @Override public List<String> defaultWire() { return List.of("0.0"); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            double value = parseDouble(wire, 0.0);
            value = clampDouble(value, bounds.min(), bounds.max());
            return List.of(Double.toString(value));
        }
        // The 'd' suffix is not required for a double literal, but it is required for the file to survive
        // somebody later changing the field's type to float without also changing the value.
        @Override public String literal(List<String> wire) { return first(wire) + "d"; }
    },

    TEXT("Text", "String", ResolvedType.of(JdkType.STRING)) {
        @Override public List<String> defaultWire() { return List.of(""); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            return List.of(wire == null ? "" : wire);
        }
        @Override public String literal(List<String> wire) { return JavaLiterals.string(first(wire)); }
    },

    TIME("Time of day", "java.time.LocalTime", ResolvedType.named("java.time.LocalTime")) {
        @Override public List<String> defaultWire() { return List.of("00:00"); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            return List.of(parseTime(wire).toString());
        }
        @Override public String literal(List<String> wire) {
            LocalTime time = parseTime(first(wire));
            return "java.time.LocalTime.of(" + time.getHour() + ", " + time.getMinute()
                    + (time.getSecond() == 0 ? "" : ", " + time.getSecond()) + ")";
        }
    },

    DATE("Date", "java.time.LocalDate", ResolvedType.named("java.time.LocalDate")) {
        @Override public List<String> defaultWire() { return List.of("2000-01-01"); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            return List.of(parseDate(wire).toString());
        }
        @Override public String literal(List<String> wire) {
            LocalDate date = parseDate(first(wire));
            return "java.time.LocalDate.of(" + date.getYear() + ", " + date.getMonthValue()
                    + ", " + date.getDayOfMonth() + ")";
        }
    },

    /**
     * How long — {@code 90s}, {@code 5m}, {@code 1h30m}. Generated as a {@code java.time.Duration} rather
     * than as a number, so nothing downstream has to remember whether the editor meant seconds or
     * milliseconds; see {@link DurationWire} for the text form.
     */
    DURATION("How long", "java.time.Duration", ResolvedType.named("java.time.Duration")) {
        @Override public List<String> defaultWire() { return List.of("0s"); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            long millis = DurationWire.parse(wire, 0L);
            millis = clampLong(millis, durationBound(bounds.min()), durationBound(bounds.max()));
            return List.of(DurationWire.format(millis));
        }
        @Override public String literal(List<String> wire) {
            return "java.time.Duration.ofMillis(" + DurationWire.parse(first(wire), 0L) + "L)";
        }
    },

    /** One of a declared list of choices, generated as the chosen {@code String}. */
    CHOICE("Choice", "String", ResolvedType.of(JdkType.STRING)) {
        @Override public boolean hasOptions() { return true; }
        @Override public List<String> defaultWire() { return List.of(""); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            String chosen = wire == null ? "" : wire;
            if (options.isEmpty()) return List.of("");
            return List.of(options.contains(chosen) ? chosen : options.getFirst());
        }
        @Override public String literal(List<String> wire) { return JavaLiterals.string(first(wire)); }
    },

    /** Any number of a declared list of choices, generated as an immutable {@code List<String>}. */
    MULTI_CHOICE("Multiple choice", "java.util.List<String>", ResolvedType.named("java.util.List")) {
        @Override public boolean isMultiValued() { return true; }
        @Override public boolean hasOptions() { return true; }
        @Override public List<String> defaultWire() { return List.of(); }
        @Override List<String> normalizeAll(List<String> wire, List<String> options, Setting.Bounds bounds) {
            // Order follows the declaration, not the file: two projects that picked the same choices in a
            // different order must generate the same line, or a diff shows a change nobody made.
            LinkedHashSet<String> chosen = new LinkedHashSet<>(wire);
            return options.stream().filter(chosen::contains).toList();
        }
        @Override public String literal(List<String> wire) {
            if (wire.isEmpty()) return "java.util.List.of()";
            return "java.util.List.of(" + String.join(", ", wire.stream().map(JavaLiterals::string).toList()) + ")";
        }
    },

    /**
     * An image template, picked from the project's gallery. The value is the template's base name and the
     * literal goes through the generated {@code Templates} constant, so renaming a template breaks the build
     * at the use site rather than leaving a bot that runs and finds nothing — the property
     * {@link TemplateConstants} exists to provide. A name too old to have a constant falls back to its path,
     * exactly as hand-written template code is allowed to.
     */
    TEMPLATE("Image template", SdkType.IMAGE_TEMPLATE.qualifiedName(),
            ResolvedType.of(SdkType.IMAGE_TEMPLATE)) {
        @Override public List<String> defaultWire() { return List.of(""); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            return List.of(wire == null ? "" : wire.trim());
        }
        @Override public String literal(List<String> wire) {
            String name = first(wire);
            String prefix = "new " + SdkType.IMAGE_TEMPLATE.qualifiedName() + "(";
            if (name.isBlank()) return prefix + "\"\")";
            String constant = TemplateConstants.constantFor(name);
            return constant != null
                    ? prefix + TemplateConstants.CLASS_NAME + "." + constant + ")"
                    : prefix + JavaLiterals.string(TemplateConstants.IMAGES_PREFIX + name + ".png") + ")";
        }
    },

    /** A keyboard key, from the SDK's own enum — the option list is the SDK's, not a copy of it. */
    KEY("Keyboard key", SdkType.KEY.qualifiedName(), ResolvedType.of(SdkType.KEY)) {
        @Override public List<String> fixedOptions() { return SdkType.KEY.enumConstantNames(); }
        @Override public List<String> defaultWire() { return List.of(firstOf(SdkType.KEY)); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            return List.of(constantOrFirst(SdkType.KEY, wire));
        }
        @Override public String literal(List<String> wire) {
            return SdkType.KEY.qualifiedName() + "." + constantOrFirst(SdkType.KEY, first(wire));
        }
    },

    /** A mouse button, from the SDK's own enum. */
    MOUSE_BUTTON("Mouse button", SdkType.MOUSE_BUTTON.qualifiedName(), ResolvedType.of(SdkType.MOUSE_BUTTON)) {
        @Override public List<String> fixedOptions() { return SdkType.MOUSE_BUTTON.enumConstantNames(); }
        @Override public List<String> defaultWire() { return List.of(firstOf(SdkType.MOUSE_BUTTON)); }
        @Override List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
            return List.of(constantOrFirst(SdkType.MOUSE_BUTTON, wire));
        }
        @Override public String literal(List<String> wire) {
            return SdkType.MOUSE_BUTTON.qualifiedName() + "." + constantOrFirst(SdkType.MOUSE_BUTTON, first(wire));
        }
    };

    private final String displayName;
    private final String javaType;
    private final ResolvedType resolvedType;

    SettingType(String displayName, String javaType, ResolvedType resolvedType) {
        this.displayName = displayName;
        this.javaType = javaType;
        this.resolvedType = resolvedType;
    }

    /** Human label for type pickers (e.g. "Whole number"). */
    public String displayName() { return displayName; }

    /** The Java type of the generated field — fully qualified unless it is in {@code java.lang}. */
    public String javaType() { return javaType; }

    /** The resolved type, so the expression menu can filter settings against an expected slot type. */
    public ResolvedType resolvedType() { return resolvedType; }

    /** True for the one type whose value is a list rather than a single item. */
    public boolean isMultiValued() { return false; }

    /** True when the editor declares the choices ({@link #CHOICE}, {@link #MULTI_CHOICE}). */
    public boolean hasOptions() { return false; }

    /**
     * The choices this type brings with it, for the ones whose option list isn't the editor's to write —
     * {@link #KEY} and {@link #MOUSE_BUTTON} answer the SDK enum's constants. Empty for every other type,
     * including the {@link #hasOptions() option-bearing} ones, whose choices come from the setting.
     */
    public List<String> fixedOptions() { return List.of(); }

    /** The wire value a freshly created setting of this type starts with. Never empty except for a list. */
    public abstract List<String> defaultWire();

    /**
     * The Java expression for {@code wire}, ready to be assigned in the generated static block. Assumes
     * {@code wire} has been through {@link #normalize}; on anything else it still produces valid source, but
     * the value may not be one the editor would recognise.
     */
    public abstract String literal(List<String> wire);

    /**
     * {@code wire} reduced to something this type can render — the whole of the "total conversion" contract.
     * Never throws, never returns null, and its own output is always a fixed point.
     *
     * @param wire    the stored value, however it got there
     * @param options the declared choices, for an {@link #hasOptions() option-bearing} type
     * @param bounds  the declared range, for a bounded number
     */
    public final List<String> normalize(List<String> wire, List<String> options, Setting.Bounds bounds) {
        List<String> safeWire = wire == null ? List.of() : wire.stream().filter(Objects::nonNull).toList();
        List<String> safeOptions = effectiveOptions(options);
        Setting.Bounds safeBounds = bounds == null ? Setting.Bounds.NONE : bounds;
        if (isMultiValued()) return normalizeAll(safeWire, safeOptions, safeBounds);
        return normalizeOne(safeWire.isEmpty() ? null : safeWire.getFirst(), safeOptions, safeBounds);
    }

    /** The choices actually in force: the type's own when it has any, else the editor's. */
    public final List<String> effectiveOptions(List<String> declared) {
        List<String> fixed = fixedOptions();
        if (!fixed.isEmpty()) return fixed;
        return declared == null ? List.of() : declared.stream().filter(Objects::nonNull).toList();
    }

    /** Single-valued normalisation. {@code wire} is null when nothing was stored. */
    List<String> normalizeOne(String wire, List<String> options, Setting.Bounds bounds) {
        return defaultWire();
    }

    /** Multi-valued normalisation; only {@link #MULTI_CHOICE} overrides it. */
    List<String> normalizeAll(List<String> wire, List<String> options, Setting.Bounds bounds) {
        return wire;
    }

    /**
     * The type {@code id} names, or {@code null} when this Studio has never heard of it.
     *
     * <p>Deliberately <em>not</em> total — unlike every other parse in this codebase, which falls back to a
     * safe default. A settings file is the only copy of its values, so a type from a newer Studio must be
     * recognisable as unknown and carried through untouched. Falling back to {@code TEXT} here would quietly
     * rewrite somebody's duration as a string the next time the file was saved.
     */
    public static SettingType fromId(String id) {
        if (id == null) return null;
        String trimmed = id.trim();
        for (SettingType t : values()) {
            if (t.name().equalsIgnoreCase(trimmed)) return t;
        }
        return null;
    }

    /** The types an editor may choose from — everything except {@link #ENABLE}, which is never chosen. */
    public static List<SettingType> selectable() {
        return Arrays.stream(values()).filter(t -> t != ENABLE).toList();
    }

    // ---- shared helpers -------------------------------------------------------------------------------

    static String first(List<String> wire) {
        return wire == null || wire.isEmpty() ? "" : wire.getFirst();
    }

    private static String firstOf(SdkType type) {
        List<String> constants = type.enumConstantNames();
        return constants.isEmpty() ? "" : constants.getFirst();
    }

    private static String constantOrFirst(SdkType type, String wire) {
        List<String> constants = type.enumConstantNames();
        if (constants.isEmpty()) return "";
        if (wire == null) return constants.getFirst();
        String trimmed = wire.trim().toUpperCase(Locale.ROOT);
        return constants.contains(trimmed) ? trimmed : constants.getFirst();
    }

    private static long parseLong(String wire, long fallback) {
        if (wire == null || wire.isBlank()) return fallback;
        try {
            // Through double, so "3.0" (which a picker or a hand edit can easily produce) reads as 3 rather
            // than as the fallback.
            return (long) Math.rint(Double.parseDouble(wire.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String wire, double fallback) {
        if (wire == null || wire.isBlank()) return fallback;
        try {
            double parsed = Double.parseDouble(wire.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long clampLong(long value, String min, String max) {
        if (min != null && !min.isBlank()) value = Math.max(value, parseLong(min, Long.MIN_VALUE));
        if (max != null && !max.isBlank()) value = Math.min(value, parseLong(max, Long.MAX_VALUE));
        return value;
    }

    private static double clampDouble(double value, String min, String max) {
        if (min != null && !min.isBlank()) value = Math.max(value, parseDouble(min, Double.NEGATIVE_INFINITY));
        if (max != null && !max.isBlank()) value = Math.min(value, parseDouble(max, Double.POSITIVE_INFINITY));
        return value;
    }

    /** A duration bound is written the same way a duration value is, so "30s" is a legal min. */
    private static String durationBound(String bound) {
        if (bound == null || bound.isBlank()) return bound;
        return Long.toString(DurationWire.parse(bound, 0L));
    }

    private static LocalTime parseTime(String wire) {
        if (wire == null || wire.isBlank()) return LocalTime.MIDNIGHT;
        try {
            return LocalTime.parse(wire.trim());
        } catch (RuntimeException e) {
            return LocalTime.MIDNIGHT;
        }
    }

    private static LocalDate parseDate(String wire) {
        if (wire == null || wire.isBlank()) return LocalDate.of(2000, 1, 1);
        try {
            return LocalDate.parse(wire.trim());
        } catch (RuntimeException e) {
            return LocalDate.of(2000, 1, 1);
        }
    }
}
