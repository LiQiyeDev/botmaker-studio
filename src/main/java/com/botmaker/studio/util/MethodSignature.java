package com.botmaker.studio.util;

import com.botmaker.studio.types.ResolvedType;

import java.util.List;

public record MethodSignature(String name, List<ResolvedType> paramTypes, List<String> paramNames,
                              ResolvedType returnType, boolean varargs) {

    /** Convenience constructor for non-varargs signatures (the common case). */
    public MethodSignature(String name, List<ResolvedType> paramTypes, List<String> paramNames, ResolvedType returnType) {
        this(name, paramTypes, paramNames, returnType, false);
    }

    /**
     * The declared type of parameter {@code index}, stretching the trailing varargs parameter over every
     * index at or beyond it. For {@code findAny(ImageTemplate... t)} (paramTypes = [ImageTemplate], varargs)
     * this returns {@code ImageTemplate} for index 0, 1, 2, … — so every varargs argument resolves to the
     * element type instead of {@code UNKNOWN}. Returns {@code null} when {@code index} is out of range and
     * the method is not varargs.
     */
    public ResolvedType paramTypeAt(int index) {
        if (index < paramTypes.size()) return paramTypes.get(index);
        if (varargs && !paramTypes.isEmpty()) return paramTypes.get(paramTypes.size() - 1);
        return null;
    }

    /**
     * Picks the overload best matching {@code argCount}: the first signature with an exact parameter-count match,
     * else the first signature in the list, else {@code null} when {@code sigs} is empty. Shared selection logic
     * for argument rendering across {@code InstantiationBlock} / {@code MethodInvocationBlock}.
     */
    public static MethodSignature bestForArity(List<MethodSignature> sigs, int argCount) {
        if (sigs == null || sigs.isEmpty()) return null;
        for (MethodSignature sig : sigs) {
            if (sig.paramTypes().size() == argCount) return sig;
        }
        return sigs.getFirst();
    }

    /**
     * True when a value of {@code actual} can satisfy a slot expecting {@code expected}: an unknown slot
     * (or unknown actual) accepts anything, else the simple names match or {@code actual} is assignment-
     * compatible. Shared type-compatibility check for menu and block dropdown filtering.
     */
    public static boolean typeSatisfies(ResolvedType actual, ResolvedType expected) {
        if (expected == null || expected.isUnknown()) return true;
        if (actual == null || actual.isUnknown()) return true;
        return actual.simpleName().equals(expected.simpleName()) || actual.isAssignmentCompatible(expected);
    }

    /** True when this method returns a value usable in a slot of {@code expected} type (non-void and compatible). */
    public boolean returnsCompatibleWith(ResolvedType expected) {
        return returnType != null && !returnType.isVoid() && typeSatisfies(returnType, expected);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name).append("(");
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes.get(i).simpleName()).append(" ").append(paramNames.get(i));
        }
        sb.append(")");
        if (returnType != null) {
            sb.append(" : ").append(returnType.simpleName());
        }
        return sb.toString();
    }
}
