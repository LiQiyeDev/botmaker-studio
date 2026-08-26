package com.botmaker.studio.types;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified representation of a Java type, resolved from one of four sources:
 * <ul>
 *   <li>{@link Bound} — a live JDT {@link ITypeBinding} (strong: project/classpath types)</li>
 *   <li>{@link FromIndex} — a ClassGraph {@link ClassInfo} (weak: external library types)</li>
 *   <li>{@link Primitive} — a primitive/void by name (no binding)</li>
 *   <li>{@link Named} — any other type known only by (qualified) name, plus array names</li>
 * </ul>
 *
 * This is the DTO that bridges JDT and ClassGraph; consumers depend on the sealed interface so
 * neither dependency leaks past the suggestion layer. Mirrors the
 * {@code ProjectAnalyzer.ResolvedMethod}/{@code ResolvedField} pattern.
 */
public sealed interface ResolvedType
        permits ResolvedType.Bound, ResolvedType.FromIndex, ResolvedType.Primitive, ResolvedType.Named {

    /** The boxed numerics by qualified name — the one group still compared as strings, since a
     * {@link Bound}/{@link FromIndex} only ever offers its name. */
    Set<String> NUMERIC_BOX_NAMES = JdkType.qualifiedNames(JdkType.NUMERIC_BOXES);

    /** Sentinel for an unresolved / unknown type (assignable to/from anything). */
    ResolvedType UNKNOWN = new Named(JdkType.OBJECT.qualifiedName());

    /** The three primitives the block layer asks for by far the most, ready-made. */
    ResolvedType BOOLEAN = primitive(PrimitiveKind.BOOLEAN);
    ResolvedType INT = primitive(PrimitiveKind.INT);
    ResolvedType DOUBLE = primitive(PrimitiveKind.DOUBLE);
    ResolvedType VOID = primitive(PrimitiveKind.VOID);

    // --- Identity ---
    String simpleName();
    String qualifiedName();

    // --- Classification ---
    boolean isEnum();
    boolean isArray();
    boolean isPrimitive();

    /**
     * The four questions every variant used to answer for itself, by comparing its name against the same
     * literals — three parallel copies of {@code "java.lang.String".equals(…)} and of the numeric-wrapper set.
     * They are defaults now because the answer never actually depended on where the type came from, only on
     * the name it reports.
     */
    default boolean isString()  { return is(JdkType.STRING); }
    default boolean isBoolean() { return is(PrimitiveKind.BOOLEAN) || is(JdkType.BOOLEAN); }
    default boolean isVoid()    { return is(PrimitiveKind.VOID); }

    default boolean isNumeric() {
        Optional<PrimitiveKind> kind = PrimitiveKind.fromKeyword(qualifiedName());
        if (kind.isPresent()) return kind.get().isNumeric();
        return NUMERIC_BOX_NAMES.contains(qualifiedName());
    }

    default boolean isUnknown() { return false; }

    /**
     * Whether this type <em>is</em> {@code jdkType}. The qualified name always counts; the bare simple name
     * counts only for {@code java.lang}, which is the one package the language auto-imports — a source file
     * writing {@code String} can mean nothing else, whereas a bare {@code List} could be a project class.
     */
    default boolean is(JdkType jdkType) {
        return qualifiedName().equals(jdkType.qualifiedName())
                || ("java.lang".equals(jdkType.packageName()) && qualifiedName().equals(jdkType.simpleName()));
    }

    /** Whether this type is the primitive {@code kind} (never its box — {@link #isBoolean()} covers both). */
    default boolean is(PrimitiveKind kind) { return kind.keyword().equals(qualifiedName()); }

    /**
     * Whether this type <em>is</em> {@code type} — the single owner of a test four pickers each spelled out
     * ({@code PickerContext.isType}, {@code PickAllSession.isType}, {@code ImageTemplatePicker}'s and
     * {@code ImageTemplateGroupPicker}'s own), always against a string literal.
     *
     * <p>Simple name <em>or</em> a qualified name ending in it, because a slot's declared type reaches the
     * editor both ways: resolved through the analyzer it is fully qualified, but a lambda parameter or a type
     * the index hasn't seen arrives as the bare identifier the source wrote. Accepting both is why the picker
     * still appears on a file that hasn't resolved yet.
     */
    default boolean is(Class<?> type) {
        return simpleName().equals(type.getSimpleName())
                || qualifiedName().endsWith("." + type.getSimpleName());
    }

    // --- Array structure ---
    int arrayDimensions();
    ResolvedType leafType();
    ResolvedType asArray(int dimensions);

    // --- Content ---
    List<String> enumConstants();

    // --- Compatibility (real type-to-type; UI 'category' matching lives in TypeExpectation) ---
    boolean isAssignmentCompatible(ResolvedType target);

    // --- Factories ---

    static ResolvedType of(ITypeBinding binding) {
        if (binding == null) return UNKNOWN;
        if (binding.isPrimitive()) {
            Optional<PrimitiveKind> kind = PrimitiveKind.fromKeyword(binding.getName());
            if (kind.isPresent()) return primitive(kind.get());
        }
        return new Bound(binding);
    }

    static ResolvedType of(ClassInfo info) {
        if (info == null) return UNKNOWN;
        return new FromIndex(info);
    }

    static ResolvedType primitive(PrimitiveKind kind) {
        return new Primitive(kind);
    }

    /** A JDK type by identity, carrying its qualified name — {@code ResolvedType.of(JdkType.STRING)}. */
    static ResolvedType of(JdkType type) {
        return new Named(type.qualifiedName());
    }

    /**
     * A type by class identity — {@code ResolvedType.of(ImageTemplate.class)} rather than
     * {@code ResolvedType.named("ImageTemplate")}. Carries the <em>qualified</em> name, which the simple-name
     * spelling never could: the SDK's facades and value types live in sub-packages, so nothing could derive
     * {@code com.botmaker.sdk.api.vision.ImageTemplate} from the string a slot was declared with.
     *
     * <p>The class literal is the point: a type renamed in the SDK breaks this build rather than a menu.
     */
    static ResolvedType of(Class<?> type) {
        return new Named(type.getName());
    }

    /** Routes primitive names to {@link Primitive}, blanks to {@link #UNKNOWN}, else {@link Named}. */
    static ResolvedType named(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) return UNKNOWN;
        String t = qualifiedName.trim();
        Optional<PrimitiveKind> kind = PrimitiveKind.fromKeyword(t);
        if (kind.isPresent()) return primitive(kind.get());
        return new Named(t);
    }

    // --- Shared helpers ---

    static String stripArray(String name) {
        String s = name;
        while (s.endsWith("[]")) s = s.substring(0, s.length() - 2);
        return s;
    }

    static int dimensionsOf(String name) {
        int count = 0;
        String s = name;
        while (s.endsWith("[]")) { count++; s = s.substring(0, s.length() - 2); }
        return count;
    }

    /**
     * The last segment of a name.
     *
     * <p>{@code $} counts as a separator, not as part of the name. A nested type reaches us spelled both ways
     * — {@code FlowGraph.Node} from a generic type signature, {@code FlowGraph$Node} from a plain bytecode
     * descriptor — and the same type has to answer the same simple name whichever door it came through.
     * Signature keys are built out of these, and two spellings of one parameter type is an overload that
     * silently matches nothing.
     */
    static String simpleOf(String qualifiedName) {
        String dims = "[]".repeat(dimensionsOf(qualifiedName));
        String leaf = stripArray(qualifiedName);
        int separator = Math.max(leaf.lastIndexOf('.'), leaf.lastIndexOf('$'));
        return (separator >= 0 ? leaf.substring(separator + 1) : leaf) + dims;
    }

    /** Type identity is by qualified name (array suffix included). */
    static boolean typeEquals(ResolvedType self, Object o) {
        return o instanceof ResolvedType other && self.qualifiedName().equals(other.qualifiedName());
    }

    /** Name-based fallback compatibility shared by non-{@link Bound} variants. */
    private static boolean nameCompatible(ResolvedType self, ResolvedType target) {
        if (target == null || target.isUnknown() || self.isUnknown()) return true;
        if (self.qualifiedName().equals(target.qualifiedName())) return true;
        if (self.simpleName().equals(target.simpleName())) return true;
        if (self.isNumeric() && target.isNumeric()) return true;
        if (self.isBoolean() && target.isBoolean()) return true;
        return self.isString() && target.isString();
    }

    // =====================================================================
    // Variants
    // =====================================================================

    /** Strong: a resolved JDT type binding. */
    record Bound(ITypeBinding binding) implements ResolvedType {
        public String simpleName()    { return binding.getName(); }
        public String qualifiedName() { return binding.getQualifiedName(); }
        public boolean isEnum()       { return binding.isEnum(); }
        public boolean isArray()      { return binding.isArray(); }
        public boolean isPrimitive()  { return binding.isPrimitive(); }
        public int arrayDimensions()  { return binding.getDimensions(); }
        public ResolvedType leafType() {
            return binding.isArray() ? ResolvedType.of(binding.getElementType()) : this;
        }
        public ResolvedType asArray(int dimensions) {
            if (dimensions == 0) return leafType();
            ITypeBinding leaf = binding.isArray() ? binding.getElementType() : binding;
            return ResolvedType.of(leaf.createArrayType(dimensions));
        }
        public List<String> enumConstants() {
            if (!binding.isEnum()) return List.of();
            return Arrays.stream(binding.getDeclaredFields())
                    .filter(IVariableBinding::isEnumConstant)
                    .map(IVariableBinding::getName)
                    .collect(Collectors.toList());
        }
        public boolean isAssignmentCompatible(ResolvedType target) {
            if (target == null || target.isUnknown()) return true;
            if (target instanceof Bound b) return binding.isAssignmentCompatible(b.binding());
            return nameCompatible(this, target);
        }
        @Override public boolean equals(Object o) { return typeEquals(this, o); }
        @Override public int hashCode()           { return qualifiedName().hashCode(); }
        @Override public String toString()        { return "Bound{" + qualifiedName() + "}"; }
    }

    /** Weak: an external library type from the ClassGraph index. */
    record FromIndex(ClassInfo info) implements ResolvedType {
        public String simpleName()    { return info.getSimpleName(); }
        public String qualifiedName() { return info.getName(); }
        public boolean isEnum()       { return info.isEnum(); }
        public boolean isArray()      { return false; }
        public boolean isPrimitive()  { return false; }
        public int arrayDimensions()  { return 0; }
        public ResolvedType leafType() { return this; }
        public ResolvedType asArray(int dimensions) {
            return dimensions == 0 ? this : ResolvedType.named(info.getName() + "[]".repeat(dimensions));
        }
        public List<String> enumConstants() {
            if (!info.isEnum()) return List.of();
            return info.getFieldInfo().stream()
                    .filter(FieldInfo::isStatic)
                    .filter(FieldInfo::isFinal)
                    .filter(fi -> info.getName().equals(fi.getTypeSignatureOrTypeDescriptor().toString()))
                    .map(FieldInfo::getName)
                    .collect(Collectors.toList());
        }
        public boolean isAssignmentCompatible(ResolvedType target) {
            return nameCompatible(this, target);
        }
        @Override public boolean equals(Object o) { return typeEquals(this, o); }
        @Override public int hashCode()           { return qualifiedName().hashCode(); }
        @Override public String toString()        { return "FromIndex{" + qualifiedName() + "}"; }
    }

    /** A primitive (or void). Holds the {@link PrimitiveKind}, so the variant can no longer exist for a name
     * that isn't one — which the {@code Primitive(String)} it replaced could. */
    record Primitive(PrimitiveKind kind) implements ResolvedType {
        public String simpleName()    { return kind.keyword(); }
        public String qualifiedName() { return kind.keyword(); }
        public boolean isEnum()       { return false; }
        public boolean isArray()      { return false; }
        public boolean isPrimitive()  { return true; }
        public boolean isString()     { return false; }
        public boolean isNumeric()    { return kind.isNumeric(); }
        public int arrayDimensions()  { return 0; }
        public ResolvedType leafType() { return this; }
        public ResolvedType asArray(int dimensions) {
            return dimensions == 0 ? this : new Named(kind.keyword() + "[]".repeat(dimensions));
        }
        public List<String> enumConstants() { return List.of(); }
        public boolean isAssignmentCompatible(ResolvedType target) {
            return nameCompatible(this, target);
        }
        @Override public boolean equals(Object o) { return typeEquals(this, o); }
        @Override public int hashCode()           { return kind.keyword().hashCode(); }
        @Override public String toString()        { return "Primitive{" + kind.keyword() + "}"; }
    }

    /** Any other type known only by (qualified) name, including array names. */
    record Named(String qualifiedName) implements ResolvedType {
        public String simpleName()    { return simpleOf(qualifiedName); }
        public boolean isEnum()       { return false; }
        public boolean isArray()      { return qualifiedName.endsWith("[]"); }
        public boolean isPrimitive()  { return PrimitiveKind.isPrimitiveKeyword(qualifiedName); }
        public boolean isUnknown()    { return qualifiedName.isBlank() || is(JdkType.OBJECT); }
        public int arrayDimensions()  { return dimensionsOf(qualifiedName); }
        public ResolvedType leafType() { return ResolvedType.named(stripArray(qualifiedName)); }
        public ResolvedType asArray(int dimensions) {
            String leaf = stripArray(qualifiedName);
            return dimensions == 0 ? ResolvedType.named(leaf) : new Named(leaf + "[]".repeat(dimensions));
        }
        public List<String> enumConstants() { return List.of(); }
        public boolean isAssignmentCompatible(ResolvedType target) {
            return nameCompatible(this, target);
        }
        @Override public boolean equals(Object o) { return typeEquals(this, o); }
        @Override public int hashCode()           { return qualifiedName.hashCode(); }
        @Override public String toString()        { return "Named{" + qualifiedName + "}"; }
    }
}
