package com.botmaker.types;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

import java.util.Arrays;
import java.util.List;
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

    Set<String> PRIMITIVE_NAMES =
            Set.of("int", "double", "boolean", "char", "long", "float", "short", "byte", "void");
    Set<String> NUMERIC_PRIMITIVES =
            Set.of("int", "double", "long", "float", "short", "byte");
    Set<String> NUMERIC_WRAPPERS = Set.of(
            "java.lang.Integer", "java.lang.Double", "java.lang.Float",
            "java.lang.Long", "java.lang.Short", "java.lang.Byte");

    /** Sentinel for an unresolved / unknown type (assignable to/from anything). */
    ResolvedType UNKNOWN = new Named("java.lang.Object");

    // --- Identity ---
    String simpleName();
    String qualifiedName();

    // --- Classification ---
    boolean isEnum();
    boolean isArray();
    boolean isPrimitive();
    boolean isString();
    boolean isNumeric();
    boolean isBoolean();
    boolean isVoid();

    default boolean isUnknown() { return false; }

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
        if (binding.isPrimitive()) return new Primitive(binding.getName());
        return new Bound(binding);
    }

    static ResolvedType of(ClassInfo info) {
        if (info == null) return UNKNOWN;
        return new FromIndex(info);
    }

    static ResolvedType primitive(String name) {
        return new Primitive(name);
    }

    /** Routes primitive names to {@link Primitive}, blanks to {@link #UNKNOWN}, else {@link Named}. */
    static ResolvedType named(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) return UNKNOWN;
        String t = qualifiedName.trim();
        String leaf = stripArray(t);
        if (PRIMITIVE_NAMES.contains(leaf) && leaf.equals(t)) return new Primitive(t);
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

    static String simpleOf(String qualifiedName) {
        String dims = "[]".repeat(dimensionsOf(qualifiedName));
        String leaf = stripArray(qualifiedName);
        int dot = leaf.lastIndexOf('.');
        return (dot >= 0 ? leaf.substring(dot + 1) : leaf) + dims;
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
        public boolean isString()     { return "java.lang.String".equals(binding.getQualifiedName()); }
        public boolean isBoolean() {
            return "boolean".equals(binding.getName()) || "java.lang.Boolean".equals(binding.getQualifiedName());
        }
        public boolean isVoid()       { return "void".equals(binding.getName()); }
        public boolean isNumeric() {
            if (binding.isPrimitive()) return NUMERIC_PRIMITIVES.contains(binding.getName());
            return NUMERIC_WRAPPERS.contains(binding.getQualifiedName());
        }
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
        public boolean isString()     { return "java.lang.String".equals(info.getName()); }
        public boolean isBoolean()    { return "java.lang.Boolean".equals(info.getName()); }
        public boolean isVoid()       { return false; }
        public boolean isNumeric()    { return NUMERIC_WRAPPERS.contains(info.getName()); }
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

    /** A primitive (or void) known only by name. */
    record Primitive(String name) implements ResolvedType {
        public String simpleName()    { return name; }
        public String qualifiedName() { return name; }
        public boolean isEnum()       { return false; }
        public boolean isArray()      { return false; }
        public boolean isPrimitive()  { return true; }
        public boolean isString()     { return false; }
        public boolean isBoolean()    { return "boolean".equals(name); }
        public boolean isVoid()       { return "void".equals(name); }
        public boolean isNumeric()    { return NUMERIC_PRIMITIVES.contains(name); }
        public int arrayDimensions()  { return 0; }
        public ResolvedType leafType() { return this; }
        public ResolvedType asArray(int dimensions) {
            return dimensions == 0 ? this : new Named(name + "[]".repeat(dimensions));
        }
        public List<String> enumConstants() { return List.of(); }
        public boolean isAssignmentCompatible(ResolvedType target) {
            return nameCompatible(this, target);
        }
        @Override public boolean equals(Object o) { return typeEquals(this, o); }
        @Override public int hashCode()           { return name.hashCode(); }
        @Override public String toString()        { return "Primitive{" + name + "}"; }
    }

    /** Any other type known only by (qualified) name, including array names. */
    record Named(String qualifiedName) implements ResolvedType {
        public String simpleName()    { return simpleOf(qualifiedName); }
        public boolean isEnum()       { return false; }
        public boolean isArray()      { return qualifiedName.endsWith("[]"); }
        public boolean isPrimitive()  { return PRIMITIVE_NAMES.contains(qualifiedName); }
        public boolean isString() {
            return "java.lang.String".equals(qualifiedName) || "String".equals(qualifiedName);
        }
        public boolean isBoolean() {
            return "java.lang.Boolean".equals(qualifiedName) || "boolean".equals(qualifiedName);
        }
        public boolean isVoid()       { return "void".equals(qualifiedName); }
        public boolean isNumeric() {
            return NUMERIC_WRAPPERS.contains(qualifiedName) || NUMERIC_PRIMITIVES.contains(qualifiedName);
        }
        public boolean isUnknown() {
            return qualifiedName.isBlank()
                    || "java.lang.Object".equals(qualifiedName)
                    || "Object".equals(qualifiedName);
        }
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
