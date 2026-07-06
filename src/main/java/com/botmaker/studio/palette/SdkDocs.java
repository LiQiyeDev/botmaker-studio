package com.botmaker.studio.palette;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory SDK method documentation: {@code class → method → [overloads]}, each overload carrying a
 * Javadoc summary and its ordered parameters (real names + {@code @param} text).
 *
 * <p>The Studio does not depend on the SDK (see the module CLAUDE.md), so it cannot read the SDK's
 * Javadoc directly. Instead {@code index/SdkDocsParser} parses the resolved
 * {@code botmaker-sdk:<version>:sources} jar at runtime (via Eclipse JDT) into an instance of this
 * class, and {@code services/SdkDocsService} owns/caches it per project. This type is pure data +
 * lookup — no I/O — so it stays in the dependency-light {@code palette} package. {@link #EMPTY} is the
 * no-docs fallback (sources not resolved / offline).
 */
public final class SdkDocs {

    /** One parameter of an SDK method overload: its real name, declared type, and {@code @param} text. */
    public record Param(String name, String type, String desc) {}

    /** One method overload: the Javadoc summary plus its ordered parameters. */
    public record Overload(String summary, List<Param> params) {}

    /** No documentation available (sources jar unresolved / offline). */
    public static final SdkDocs EMPTY = new SdkDocs(Map.of());

    /** class simpleName → method name → overloads. */
    private final Map<String, Map<String, List<Overload>>> byClass;

    public SdkDocs(Map<String, Map<String, List<Overload>>> byClass) {
        this.byClass = byClass;
    }

    /** All overloads documented for {@code class.method} (empty if none). */
    public List<Overload> overloads(String className, String method) {
        Map<String, List<Overload>> byMethod = byClass.get(className);
        if (byMethod == null) {
            return List.of();
        }
        List<Overload> list = byMethod.get(method);
        return list != null ? list : List.of();
    }

    /**
     * The best matching overload for {@code class.method} given the call's argument type simple names.
     * Prefers an exact type-name match, then an arity match, then the first documented overload — so a
     * summary/param-names are returned even when the exact overload isn't pinned down.
     */
    public Optional<Overload> lookup(String className, String method, List<String> paramTypeSimpleNames) {
        List<Overload> all = overloads(className, method);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        if (all.size() == 1) {
            return Optional.of(all.get(0));
        }
        Overload arityMatch = null;
        for (Overload o : all) {
            if (o.params().size() != paramTypeSimpleNames.size()) {
                continue;
            }
            if (arityMatch == null) {
                arityMatch = o;
            }
            if (typesMatch(o.params(), paramTypeSimpleNames)) {
                return Optional.of(o);
            }
        }
        return Optional.of(arityMatch != null ? arityMatch : all.get(0));
    }

    /** The Javadoc summary for the best-matching overload, if documented and non-blank. */
    public Optional<String> summary(String className, String method, List<String> paramTypeSimpleNames) {
        return lookup(className, method, paramTypeSimpleNames)
                .map(Overload::summary)
                .filter(s -> s != null && !s.isBlank());
    }

    private static boolean typesMatch(List<Param> params, List<String> types) {
        for (int i = 0; i < params.size(); i++) {
            if (!simple(params.get(i).type()).equals(simple(types.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** Strip generics, varargs/array markers and package qualifiers for a lenient type comparison. */
    private static String simple(String type) {
        if (type == null) {
            return "";
        }
        String t = type;
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        t = t.replace("...", "").replace("[]", "").trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }
}
