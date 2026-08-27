package com.botmaker.studio.util;

import com.botmaker.plugin.api.catalog.MemberId;
import com.botmaker.studio.types.ResolvedType;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;

import java.lang.reflect.Executable;
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
     * The overload with the fewest parameters (ties broken by declaration order), or {@code null} when
     * {@code sigs} is empty. Used as the default overload when a fresh call is created and the project has no
     * favorite for it — the least-argument form is the simplest starting point for the user.
     */
    public static MethodSignature fewestParams(List<MethodSignature> sigs) {
        if (sigs == null || sigs.isEmpty()) return null;
        MethodSignature best = sigs.getFirst();
        for (MethodSignature sig : sigs) {
            if (sig.paramTypes().size() < best.paramTypes().size()) best = sig;
        }
        return best;
    }

    /**
     * A stable, human-readable key for this overload: its parameter type simple-names joined by {@code ,}
     * (e.g. {@code ImageTemplate,CaptureSource,double}). Used to persist a project's favorite overload
     * independently of parameter names / declaration order.
     */
    public String signatureKey() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(paramTypes.get(i).simpleName());
        }
        return sb.toString();
    }

    /**
     * The {@link #signatureKey()} of a method read straight from a jar, without building a
     * {@link MethodSignature} for it — the <em>other</em> vocabulary the same key has to be spelled in.
     *
     * <p>Two parts of Studio ask about the same overload from opposite ends. The menus and the blocks hold
     * {@code MethodSignature}s, which {@code ProjectAnalyzer} builds from the ClassGraph index; {@code
     * services/SdkSurfaceService} reads the index directly and never builds one, because it answers questions
     * about members the user has not inserted. Both must agree on the string, or a curated overload is
     * annotated in the SDK and silently never offered — a failure with no symptom other than a missing menu
     * entry. So the derivation lives here once, beside the key it has to match, and
     * {@code SignatureKeyAgreementTest} asserts the two agree for every method in the SDK jar.
     *
     * <p>The rules it mirrors are {@code ProjectAnalyzer.toMethodSignature}: generic arguments are dropped
     * ({@code Consumer<MatchResult>} → {@code Consumer}), and a varargs tail is spelled as its <em>element</em>
     * type, because the bytecode descriptor of {@code ImageTemplate...} is {@code ImageTemplate[]} and every
     * caller reasons about the element.
     *
     * <p><b>It reads the erased descriptor, never the generic signature</b>, and that is what makes a generic
     * method spellable at all. {@code FlowGraph.<O extends Enum<O>> route(O, String)} has a signature naming
     * the type <em>variable</em> — the key would read {@code O,String}, which no other vocabulary can produce:
     * a {@link MemberId} carries a descriptor and so can only ever say {@code Enum}. Erasure is also the better
     * key on its own merits, since {@code O} is a letter that means nothing to a slot editor and changes if the
     * SDK renames it. For every non-generic method the two are identical, generic <em>arguments</em> having
     * been stripped by {@link #simpleNameOf} already.
     */
    public static String signatureKeyOf(MethodInfo mi) {
        MethodParameterInfo[] params = mi.getParameterInfo();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            boolean varargsTail = mi.isVarArgs() && i == params.length - 1;
            sb.append(simpleNameOf(params[i].getTypeDescriptor().toString(), varargsTail));
        }
        return sb.toString();
    }

    /**
     * The {@link #signatureKey()} of a member a <em>plugin catalog</em> named — the <b>third</b> vocabulary
     * the same key has to be spelled in, and the one that arrived with the palette catalog.
     *
     * <p>A {@link MemberId} carries the JVM descriptor of the method reference that named it, so the
     * derivation is the same reduction as {@link #signatureKeyOf(MethodInfo)} over
     * {@link MemberId#parameterTypeNames()} — with one thing the descriptor cannot say. <b>Varargs is not in
     * a descriptor:</b> {@code findAny(ImageTemplate...)} and {@code findAny(ImageTemplate[])} compile to the
     * identical signature, while the key spells the first as its <em>element</em> type. The flag is a
     * declaration-site fact, so it is read back off the declaring {@link Class} — which the catalog holds for
     * exactly this kind of question.
     *
     * <p>The failure this prevents has no symptom of its own: a curated overload whose key matches nothing
     * the menu holds is simply never offered, indistinguishable from never having been catalogued.
     * {@code SignatureKeyAgreementTest} holds all three vocabularies against the real SDK jar.
     */
    public static String signatureKeyOf(MemberId id) {
        List<String> params = id.parameterTypeNames();
        boolean varargs = isVarArgs(id, params);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(simpleNameOf(params.get(i), varargs && i == params.size() - 1));
        }
        return sb.toString();
    }

    /**
     * Whether the member {@code id} names was declared varargs. Matched on name and parameter types rather
     * than on the descriptor string, because {@link Class#getName()} spells an array {@code [Lp.T;} while the
     * id spells it {@code p.T[]}; false for anything that does not resolve, which is the same answer a
     * non-varargs method gives and so changes nothing.
     */
    private static boolean isVarArgs(MemberId id, List<String> params) {
        Executable[] candidates = id.isConstructor()
                ? id.declaringClass().getDeclaredConstructors()
                : id.declaringClass().getDeclaredMethods();
        for (Executable candidate : candidates) {
            if (!candidate.isVarArgs() || candidate.getParameterCount() != params.size()) continue;
            if (!id.isConstructor() && !candidate.getName().equals(id.name())) continue;
            Class<?>[] declared = candidate.getParameterTypes();
            boolean same = true;
            for (int i = 0; i < declared.length && same; i++) {
                same = sourceName(declared[i]).equals(params.get(i));
            }
            if (same) return true;
        }
        return false;
    }

    /** {@code int}, {@code java.lang.String}, {@code p.T[]} — {@link MemberId}'s spelling of a type. */
    private static String sourceName(Class<?> type) {
        return type.isArray() ? sourceName(type.getComponentType()) + "[]" : type.getName();
    }

    /** A bytecode descriptor reduced to the simple name {@link ResolvedType#simpleName()} would give it. */
    private static String simpleNameOf(String descriptor, boolean dropOneArrayDimension) {
        String base = descriptor;
        int generic = base.indexOf('<');
        if (generic >= 0) base = base.substring(0, generic);
        int dims = 0;
        while (base.endsWith("[]")) {
            dims++;
            base = base.substring(0, base.length() - 2);
        }
        if (dropOneArrayDimension && dims > 0) dims--;
        return ResolvedType.named(base).asArray(dims).simpleName();
    }

    /**
     * The overload whose {@link #signatureKey()} equals {@code sigKey}, or {@code null} when {@code sigKey}
     * is null/blank or no overload matches (e.g. the stored favorite no longer exists after an SDK change) —
     * callers fall back to {@link #bestForArity}/first.
     */
    public static MethodSignature bestForKey(List<MethodSignature> sigs, String sigKey) {
        if (sigs == null || sigKey == null || sigKey.isBlank()) return null;
        for (MethodSignature sig : sigs) {
            if (sig.signatureKey().equals(sigKey)) return sig;
        }
        return null;
    }

    /**
     * Picks the overload that best matches the <em>actual</em> argument types — not just their count. This is
     * what lets same-arity overloads that differ only in a parameter's type be told apart, e.g.
     * {@code find(template, CaptureSource)} vs {@code find(template, Rect)} vs {@code find(template, double)}
     * (all arity-2): the one whose parameter types line up with the arguments wins, so the right per-slot
     * editor/picker is chosen. Falls back to {@link #bestForArity} when nothing scores (e.g. argument types are
     * unresolved), preserving the old count-only behaviour.
     */
    public static MethodSignature bestForArgs(List<MethodSignature> sigs, List<ResolvedType> argTypes) {
        if (sigs == null || sigs.isEmpty()) return null;
        int argCount = argTypes == null ? 0 : argTypes.size();
        MethodSignature best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MethodSignature sig : sigs) {
            if (!sig.acceptsArgCount(argCount)) continue;
            int score = 0;
            for (int i = 0; i < argCount; i++) {
                score += matchScore(argTypes.get(i), sig.paramTypeAt(i));
            }
            if (score > bestScore) {
                bestScore = score;
                best = sig;
            }
        }
        return best != null ? best : bestForArity(sigs, argCount);
    }

    /** Whether this signature can be called with {@code argCount} arguments (varargs stretches the tail). */
    public boolean acceptsArgCount(int argCount) {
        int n = paramTypes.size();
        return varargs ? argCount >= n - 1 : argCount == n;
    }

    /**
     * How well an argument of type {@code actual} fits a parameter of type {@code expected}: 2 for an exact or
     * assignment-compatible match, 1 when either side is unknown (no evidence either way — don't penalise), 0
     * for a concrete mismatch. Reference-type mismatches (CaptureSource vs Rect) score 0 and lose; unknowns
     * keep the old count-only outcome.
     */
    private static int matchScore(ResolvedType actual, ResolvedType expected) {
        if (expected == null || expected.isUnknown()) return 1;
        if (actual == null || actual.isUnknown()) return 1;
        if (actual.simpleName().equals(expected.simpleName())) return 2;
        if (actual.isAssignmentCompatible(expected)) return 2;
        return 0;
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
