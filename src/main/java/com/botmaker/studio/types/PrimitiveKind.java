package com.botmaker.studio.types;

import org.eclipse.jdt.core.dom.PrimitiveType;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The nine primitive types (counting {@code void}), which is a closed set the language itself fixes — yet the
 * editor spelled them as bare strings: {@code ResolvedType.primitive("boolean")} at 19 block call sites, a
 * {@code Set.of("int","double",…)} of the names, a second one of just the numeric ones, and a
 * {@code switch (name)} in {@code StatementFactory} mapping each to its JDT {@link PrimitiveType.Code} that
 * <em>threw</em> on anything it didn't recognise.
 *
 * <p>Each constant owns the three facts those sites re-derived: the {@link #keyword()} that is both the
 * source spelling and the type's own name, whether it {@link #isNumeric()}, and the {@link #code()} JDT wants
 * when a primitive type node is built. {@link #fromKeyword} is total — an identifier that isn't a primitive
 * is simply not one, which is the question every caller was actually asking.
 */
public enum PrimitiveKind {
    INT("int", PrimitiveType.INT, true),
    LONG("long", PrimitiveType.LONG, true),
    DOUBLE("double", PrimitiveType.DOUBLE, true),
    FLOAT("float", PrimitiveType.FLOAT, true),
    SHORT("short", PrimitiveType.SHORT, true),
    BYTE("byte", PrimitiveType.BYTE, true),
    CHAR("char", PrimitiveType.CHAR, false),
    BOOLEAN("boolean", PrimitiveType.BOOLEAN, false),
    VOID("void", PrimitiveType.VOID, false);

    private static final Map<String, PrimitiveKind> BY_KEYWORD = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(PrimitiveKind::keyword, Function.identity()));

    private final String keyword;
    private final PrimitiveType.Code code;
    private final boolean numeric;

    PrimitiveKind(String keyword, PrimitiveType.Code code, boolean numeric) {
        this.keyword = keyword;
        this.code = code;
        this.numeric = numeric;
    }

    /** The source spelling — also the type's simple <em>and</em> qualified name, primitives having no package. */
    public String keyword() { return keyword; }

    /** Whether arithmetic applies: the six integral/floating kinds, so neither {@code boolean} nor {@code void}. */
    public boolean isNumeric() { return numeric; }

    /** The JDT code for {@code ast.newPrimitiveType(...)}. */
    public PrimitiveType.Code code() { return code; }

    /** The wrapper this boxes to, or empty for {@code void} — {@code Void} exists but is not a boxed value. */
    public Optional<JdkType> boxed() {
        return switch (this) {
            case INT -> Optional.of(JdkType.INTEGER);
            case LONG -> Optional.of(JdkType.LONG);
            case DOUBLE -> Optional.of(JdkType.DOUBLE);
            case FLOAT -> Optional.of(JdkType.FLOAT);
            case SHORT -> Optional.of(JdkType.SHORT);
            case BYTE -> Optional.of(JdkType.BYTE);
            case CHAR -> Optional.of(JdkType.CHARACTER);
            case BOOLEAN -> Optional.of(JdkType.BOOLEAN);
            case VOID -> Optional.empty();
        };
    }

    /** Total: an identifier that names no primitive yields empty rather than throwing. */
    public static Optional<PrimitiveKind> fromKeyword(String keyword) {
        return Optional.ofNullable(keyword).map(BY_KEYWORD::get);
    }

    /** Whether {@code keyword} spells a primitive — the {@code PRIMITIVE_NAMES.contains(…)} test, typed. */
    public static boolean isPrimitiveKeyword(String keyword) {
        return fromKeyword(keyword).isPresent();
    }

    @Override public String toString() { return keyword; }
}
