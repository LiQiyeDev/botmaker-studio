package com.botmaker.studio.project.activity;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.project.TemplateConstants;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.ResolvedType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Everything a {@link BotType} has to answer to be a stored project variable: what it defaults to, how a
 * value that arrived from anywhere is reduced to something it can hold, and the Java source a running bot
 * reads it back with.
 *
 * <h2>Text on the wire</h2>
 *
 * <p>Every value is stored as a <b>list of strings</b> in {@code activities.json}, whatever its type — one
 * entry for an ordinary variable, one per item for a {@code List of …} one. That uniformity is the point: a
 * value has exactly one shape on disk, so the file has one reader, one writer and one normaliser rather than
 * a special case per type. The cost is that a duration reads as {@code ["90s"]} rather than {@code "90s"} in
 * a file nobody is expected to open by hand.
 *
 * <h2>Every conversion is total</h2>
 *
 * <p>{@link #normalize} takes whatever the wire actually said and answers something this type can hold — a
 * garbage number, a choice that is no longer offered, a duration in a unit nobody knows. It cannot throw and
 * cannot return something {@link #loadExpression} would choke on, and it is a fixed point: normalising twice
 * changes nothing. Doing it here means the editor is looking at the value the bot will actually get.
 *
 * <p>The generated bot is total in the same way, for the same reason and independently: it never fails to
 * start because of its own configuration file. So each type also carries a {@linkplain #helper(BotType)
 * runtime parser} that falls back rather than throwing — normalisation in Studio is what keeps the file
 * sensible, not what keeps the bot alive.
 *
 * <h2>Fully qualified, always</h2>
 *
 * <p>{@link #javaType} names everything outside {@code java.lang} in full, and so does every expression built
 * here. The generated {@code Activities} class therefore needs no import for a variable's type, which is the
 * cheapest way to guarantee it never needs one that was forgotten.
 */
public final class VariableWire {

    private VariableWire() {}

    // ---- the type's shape -----------------------------------------------------------------------------

    /** The Java type of the generated field — {@code int}, {@code java.time.Duration}, {@code List<Integer>}. */
    public static String javaType(BotType.Choice type) {
        BotType base = type.type();
        return type.list() ? "java.util.List<" + boxed(base) + ">" : qualified(base);
    }

    /** The resolved type, so the expression menu can filter variables against an expected slot type. */
    public static ResolvedType resolvedType(BotType.Choice type) {
        if (type.list()) return ResolvedType.named("java.util.List");
        return switch (type.type()) {
            case YES_NO -> ResolvedType.BOOLEAN;
            case WHOLE_NUMBER -> ResolvedType.INT;
            case DECIMAL_NUMBER -> ResolvedType.DOUBLE;
            case CHARACTER -> ResolvedType.named("char");
            case TEXT, CHOICE -> ResolvedType.of(JdkType.STRING);
            case DATE, TIME_OF_DAY, DURATION -> ResolvedType.named(qualified(type.type()));
            default -> type.type().sdkType().map(ResolvedType::of)
                    .orElseGet(() -> ResolvedType.named(qualified(type.type())));
        };
    }

    /** True when the editor writes the choices down — {@link BotType#CHOICE} and nothing else. */
    public static boolean hasOptions(BotType type) {
        return type == BotType.CHOICE;
    }

    /**
     * True when a declared {@link Bounds} means anything for this type — the two number types, which are the
     * ones {@link #normalizeItem} clamps.
     *
     * <p>It lives here, beside the clamp, so the dialog that offers a range and the code that enforces one
     * cannot come to disagree about which types have one.
     */
    public static boolean isBounded(BotType type) {
        return type == BotType.WHOLE_NUMBER || type == BotType.DECIMAL_NUMBER;
    }

    /**
     * The choices a type brings with it, for the ones whose option list is not the editor's to write: the
     * four SDK enums answer their own constants. Empty for everything else, including {@link BotType#CHOICE},
     * whose choices come from the variable.
     */
    public static List<String> fixedOptions(BotType type) {
        return switch (type) {
            case KEY -> SdkType.KEY.enumConstantNames();
            case MOUSE_BUTTON -> SdkType.MOUSE_BUTTON.enumConstantNames();
            case DIRECTION -> SdkType.DIRECTION.enumConstantNames();
            case PRECISION -> SdkType.PRECISION.enumConstantNames();
            default -> List.of();
        };
    }

    /** The choices actually in force: the type's own when it has any, else the editor's. */
    public static List<String> effectiveOptions(BotType type, List<String> declared) {
        List<String> fixed = fixedOptions(type);
        if (!fixed.isEmpty()) return fixed;
        return declared == null ? List.of() : declared.stream().filter(Objects::nonNull).toList();
    }

    // ---- values ---------------------------------------------------------------------------------------

    /** The wire value a freshly created variable of this type starts with; empty for a list. */
    public static List<String> defaultWire(BotType.Choice type) {
        if (type.list()) return List.of();
        return List.of(defaultItem(type.type()));
    }

    private static String defaultItem(BotType type) {
        return switch (type) {
            case YES_NO -> "false";
            case WHOLE_NUMBER -> "0";
            case DECIMAL_NUMBER -> "0.0";
            case CHARACTER -> "a";
            case TEXT, CHOICE, IMAGE_TEMPLATE -> "";
            case DATE -> "2000-01-01";
            case TIME_OF_DAY -> "00:00";
            case DURATION -> "0s";
            case POINT -> "0,0";
            case SIZE -> "0,0";
            case RECT -> "0,0,0,0";
            case KEY, MOUSE_BUTTON, DIRECTION, PRECISION -> firstConstant(type);
            default -> "";
        };
    }

    /**
     * {@code wire} reduced to something {@code type} can hold. Never throws, never returns null, and its own
     * output is always a fixed point.
     *
     * @param options the declared choices, for {@link #hasOptions(BotType) an option-bearing} type
     * @param bounds  the declared range, for a bounded number
     */
    public static List<String> normalize(List<String> wire, BotType.Choice type, List<String> options,
                                         Bounds bounds) {
        List<String> safe = wire == null ? List.of() : wire.stream().filter(Objects::nonNull).toList();
        List<String> choices = effectiveOptions(type.type(), options);
        Bounds range = bounds == null ? Bounds.NONE : bounds;

        if (!type.list()) {
            return List.of(normalizeItem(safe.isEmpty() ? null : safe.getFirst(), type.type(), choices, range));
        }
        // An option-bearing list follows the declaration order, not the file's: two projects that picked the
        // same choices in a different order must write the same line, or a diff shows a change nobody made.
        if (!choices.isEmpty()) {
            LinkedHashSet<String> chosen = new LinkedHashSet<>(safe);
            return choices.stream().filter(chosen::contains).toList();
        }
        return safe.stream().map(item -> normalizeItem(item, type.type(), choices, range)).toList();
    }

    private static String normalizeItem(String wire, BotType type, List<String> options, Bounds bounds) {
        return switch (type) {
            case YES_NO -> Boolean.parseBoolean(trim(wire)) ? "true" : "false";
            case WHOLE_NUMBER -> {
                long value = clampLong(parseLong(wire, 0L), bounds.min(), bounds.max());
                yield Long.toString(Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)));
            }
            case DECIMAL_NUMBER -> Double.toString(clampDouble(parseDouble(wire, 0.0), bounds.min(), bounds.max()));
            case CHARACTER -> trim(wire).isEmpty() ? "a" : trim(wire).substring(0, 1);
            case TEXT -> wire == null ? "" : wire;
            case IMAGE_TEMPLATE -> trim(wire);
            case DATE -> parseDate(wire).toString();
            case TIME_OF_DAY -> parseTime(wire).toString();
            case DURATION -> DurationWire.format(DurationWire.parse(wire, 0L));
            case POINT, SIZE -> numbers(wire, 2);
            case RECT -> numbers(wire, 4);
            case CHOICE -> {
                String chosen = trim(wire);
                if (options.isEmpty()) yield "";
                yield options.contains(chosen) ? chosen : options.getFirst();
            }
            case KEY, MOUSE_BUTTON, DIRECTION, PRECISION -> constantOrFirst(type, wire);
            default -> trim(wire);
        };
    }

    // ---- generated source -----------------------------------------------------------------------------

    /**
     * The Java expression the generated {@code Activities} class assigns to a variable named {@code name}.
     *
     * <p>Every form goes through a generated helper rather than inlining the parse, so the same wire text is
     * read the same way whether it stands alone or is one item of a list: {@code many(…)} maps the very
     * method reference {@code one(…)} would have called.
     */
    public static String loadExpression(BotType.Choice type, String name) {
        String key = '"' + name + '"';
        Helper helper = helper(type.type());
        if (!type.list()) return helper.name() + "(one(" + key + "))";
        return "many(" + key + ", Activities::" + helper.name() + ")";
    }

    /**
     * The runtime parser {@code type}'s values are read with: its method name, and the source of the method
     * (empty when it shares one already emitted for another type).
     *
     * <p>The four SDK enums share {@link #ENUM_HELPER} and the three geometry types share {@link #INTS_HELPER},
     * so what a generated file carries is what it uses and no more.
     */
    public static Helper helper(BotType type) {
        return switch (type) {
            case TEXT, CHOICE -> new Helper("text", TEXT_HELPER, List.of());
            case YES_NO -> new Helper("flag", FLAG_HELPER, List.of());
            case WHOLE_NUMBER -> new Helper("whole", WHOLE_HELPER, List.of());
            case DECIMAL_NUMBER -> new Helper("decimal", DECIMAL_HELPER, List.of());
            case CHARACTER -> new Helper("letter", LETTER_HELPER, List.of());
            case DATE -> new Helper("date", DATE_HELPER, List.of());
            case TIME_OF_DAY -> new Helper("time", TIME_HELPER, List.of());
            case DURATION -> new Helper("duration", DURATION_HELPER, List.of());
            case IMAGE_TEMPLATE -> new Helper("template", TEMPLATE_HELPER, List.of());
            case KEY -> enumHelper("key", SdkType.KEY);
            case MOUSE_BUTTON -> enumHelper("mouseButton", SdkType.MOUSE_BUTTON);
            case DIRECTION -> enumHelper("direction", SdkType.DIRECTION);
            case PRECISION -> enumHelper("precision", SdkType.PRECISION);
            case POINT -> geometryHelper("point", SdkType.POINT, 2);
            case RECT -> geometryHelper("area", SdkType.RECT, 4);
            case SIZE -> geometryHelper("size", SdkType.SIZE, 2);
            default -> throw new IllegalArgumentException(type + " is not a storable variable type");
        };
    }

    /**
     * One generated parser: the method a load expression calls, its own source, and the source of anything
     * shared it depends on. {@code shared} is separate so two types that both need {@code constant(…)} emit
     * it once — the generator de-duplicates on the text.
     */
    public record Helper(String name, String source, List<String> shared) {}

    private static Helper enumHelper(String name, SdkType type) {
        String fallback = type.qualifiedName() + "." + firstConstantOf(type);
        return new Helper(name, """
                    private static %s %s(String s) {
                        return constant(%s.class, s, %s);
                    }
                """.formatted(type.qualifiedName(), name, type.qualifiedName(), fallback),
                List.of(ENUM_HELPER));
    }

    private static Helper geometryHelper(String name, SdkType type, int count) {
        return new Helper(name, """
                    private static %s %s(String s) {
                        int[] n = ints(s, %d);
                        return new %s(%s);
                    }
                """.formatted(type.qualifiedName(), name, count, type.qualifiedName(),
                        String.join(", ", indices(count))),
                List.of(INTS_HELPER));
    }

    private static List<String> indices(int count) {
        List<String> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) args.add("n[" + i + "]");
        return args;
    }

    // ---- helper sources -------------------------------------------------------------------------------

    private static final String TEXT_HELPER = """
                private static String text(String s) {
                    return s;
                }
            """;

    private static final String FLAG_HELPER = """
                private static boolean flag(String s) {
                    return Boolean.parseBoolean(s.trim());
                }
            """;

    private static final String WHOLE_HELPER = """
                private static int whole(String s) {
                    try {
                        return (int) Math.rint(Double.parseDouble(s.trim()));
                    } catch (RuntimeException e) {
                        return 0;
                    }
                }
            """;

    private static final String DECIMAL_HELPER = """
                private static double decimal(String s) {
                    try {
                        return Double.parseDouble(s.trim());
                    } catch (RuntimeException e) {
                        return 0.0;
                    }
                }
            """;

    private static final String LETTER_HELPER = """
                private static char letter(String s) {
                    return s.isEmpty() ? 'a' : s.charAt(0);
                }
            """;

    private static final String DATE_HELPER = """
                private static java.time.LocalDate date(String s) {
                    try {
                        return java.time.LocalDate.parse(s.trim());
                    } catch (RuntimeException e) {
                        return java.time.LocalDate.of(2000, 1, 1);
                    }
                }
            """;

    private static final String TIME_HELPER = """
                private static java.time.LocalTime time(String s) {
                    try {
                        return java.time.LocalTime.parse(s.trim());
                    } catch (RuntimeException e) {
                        return java.time.LocalTime.MIDNIGHT;
                    }
                }
            """;

    /**
     * A port of {@link DurationWire#parse}, because the editor and the bot must read {@code 1h30m} the same
     * way and the bot cannot call into Studio. Kept line-for-line recognisable against the original so the
     * two can be diffed by eye; {@code DurationWireTest} pins the behaviour both are held to.
     */
    private static final String DURATION_HELPER = """
                private static java.time.Duration duration(String s) {
                    String t = s.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
                    long total = 0;
                    long digits = 0;
                    boolean sawDigit = false;
                    boolean sawAny = false;
                    for (int i = 0; i < t.length(); i++) {
                        char c = t.charAt(i);
                        if (Character.isDigit(c)) {
                            digits = digits * 10 + (c - '0');
                            if (digits > Integer.MAX_VALUE) return java.time.Duration.ZERO;
                            sawDigit = true;
                            continue;
                        }
                        if (!sawDigit) return java.time.Duration.ZERO;
                        if (c == 'm' && i + 1 < t.length() && t.charAt(i + 1) == 's') {
                            total += digits;
                            i++;
                        } else if (c == 'h') {
                            total += digits * 3600000L;
                        } else if (c == 'm') {
                            total += digits * 60000L;
                        } else if (c == 's') {
                            total += digits * 1000L;
                        } else {
                            return java.time.Duration.ZERO;
                        }
                        digits = 0;
                        sawDigit = false;
                        sawAny = true;
                    }
                    if (sawDigit) {
                        total += digits;
                        sawAny = true;
                    }
                    return java.time.Duration.ofMillis(sawAny ? total : 0L);
                }
            """;

    private static final String TEMPLATE_HELPER = """
                private static %s template(String s) {
                    return new %s("%s" + s + ".png");
                }
            """.formatted(SdkType.IMAGE_TEMPLATE.qualifiedName(), SdkType.IMAGE_TEMPLATE.qualifiedName(),
                    TemplateConstants.IMAGES_PREFIX);

    private static final String ENUM_HELPER = """
                private static <E extends Enum<E>> E constant(Class<E> type, String s, E fallback) {
                    try {
                        return Enum.valueOf(type, s.trim().toUpperCase(java.util.Locale.ROOT));
                    } catch (RuntimeException e) {
                        return fallback;
                    }
                }
            """;

    private static final String INTS_HELPER = """
                private static int[] ints(String s, int count) {
                    int[] out = new int[count];
                    String[] parts = s.split(",");
                    for (int i = 0; i < count && i < parts.length; i++) {
                        try {
                            out[i] = (int) Math.rint(Double.parseDouble(parts[i].trim()));
                        } catch (RuntimeException e) {
                            out[i] = 0;
                        }
                    }
                    return out;
                }
            """;

    // ---- shared parsing -------------------------------------------------------------------------------

    private static String qualified(BotType type) {
        return type.sdkType().map(SdkType::qualifiedName).orElse(type.typeName());
    }

    private static String boxed(BotType type) {
        return type.sdkType().map(SdkType::qualifiedName).orElse(type.boxedName());
    }

    private static String firstConstant(BotType type) {
        List<String> options = fixedOptions(type);
        return options.isEmpty() ? "" : options.getFirst();
    }

    private static String firstConstantOf(SdkType type) {
        List<String> constants = type.enumConstantNames();
        return constants.isEmpty() ? "" : constants.getFirst();
    }

    private static String constantOrFirst(BotType type, String wire) {
        List<String> constants = fixedOptions(type);
        if (constants.isEmpty()) return "";
        String trimmed = trim(wire).toUpperCase(Locale.ROOT);
        return constants.contains(trimmed) ? trimmed : constants.getFirst();
    }

    /** {@code count} comma-separated whole numbers, missing or unreadable ones read as zero. */
    private static String numbers(String wire, int count) {
        String[] parts = trim(wire).split(",");
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(Long.toString(i < parts.length ? parseLong(parts[i], 0L) : 0L));
        }
        return String.join(",", out);
    }

    private static String trim(String wire) {
        return wire == null ? "" : wire.trim();
    }

    private static long parseLong(String wire, long fallback) {
        if (wire == null || wire.isBlank()) return fallback;
        try {
            // Through double, so "3.0" — which a spinner or a hand edit produces easily — reads as 3 rather
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
