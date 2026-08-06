package com.botmaker.studio.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The JDK types the editor names, each holding a real {@link Class} literal — the same trick
 * {@code palette.SdkType} plays for the SDK surface, for the same reason: a name spelled as a string is
 * checked by nothing.
 *
 * <p>Four files had each re-listed a slice of this: {@code ProjectAnalyzer}'s "common {@code java.util}
 * fallback" (which then built its answer as {@code "java.util." + simpleName}, so a type that moved package
 * would have produced a plausible-looking wrong FQN), {@code StatementFactory}'s {@code ITERABLE_TYPES} and
 * {@code SWITCHABLE_TYPES}, {@code DefaultValueHelper}'s wrapper names, and {@link ResolvedType}'s own
 * {@code NUMERIC_WRAPPERS} plus three parallel {@code "java.lang.String".equals(…)} comparisons.
 *
 * <p>Only the types Studio actually reasons about are here; this is not a mirror of the JDK. A constant earns
 * its place by being named in the editor's own logic.
 */
public enum JdkType {

    // --- java.lang ---
    OBJECT(Object.class),
    STRING(String.class),
    BOOLEAN(Boolean.class),
    CHARACTER(Character.class),
    INTEGER(Integer.class),
    LONG(Long.class),
    DOUBLE(Double.class),
    FLOAT(Float.class),
    SHORT(Short.class),
    BYTE(Byte.class),
    ITERABLE(Iterable.class),

    // --- java.util ---
    COLLECTION(Collection.class),
    LIST(List.class),
    ARRAY_LIST(ArrayList.class),
    LINKED_LIST(LinkedList.class),
    MAP(Map.class),
    HASH_MAP(HashMap.class),
    SET(Set.class),
    HASH_SET(HashSet.class),
    LINKED_HASH_SET(LinkedHashSet.class),
    TREE_SET(TreeSet.class),
    QUEUE(Queue.class),
    DEQUE(Deque.class),
    ARRAY_DEQUE(ArrayDeque.class),
    ARRAYS(Arrays.class);

    /** The boxes of the six numeric primitives — what makes a <em>reference</em> type arithmetic. */
    public static final Set<JdkType> NUMERIC_BOXES =
            Set.of(INTEGER, DOUBLE, FLOAT, LONG, SHORT, BYTE);

    /** What an enhanced-for can walk. */
    public static final Set<JdkType> ITERABLES = Set.of(
            ITERABLE, COLLECTION, LIST, ARRAY_LIST, LINKED_LIST,
            SET, HASH_SET, LINKED_HASH_SET, TREE_SET, QUEUE, DEQUE, ARRAY_DEQUE);

    private static final Map<String, JdkType> BY_SIMPLE_NAME = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(JdkType::simpleName, Function.identity()));

    private final Class<?> type;

    JdkType(Class<?> type) { this.type = type; }

    public String simpleName()    { return type.getSimpleName(); }
    public String qualifiedName() { return type.getName(); }
    public String packageName()   { return type.getPackageName(); }

    /** Total lookup by simple name — {@code "ArrayList"} → {@link #ARRAY_LIST}, anything else empty. */
    public static Optional<JdkType> bySimpleName(String simpleName) {
        return Optional.ofNullable(simpleName).map(BY_SIMPLE_NAME::get);
    }

    /** The qualified names of a group, for the string comparisons that can't take the enum. */
    public static Set<String> qualifiedNames(Set<JdkType> types) {
        return types.stream().map(JdkType::qualifiedName).collect(Collectors.toUnmodifiableSet());
    }

    /** The simple names of a group, for matching an unresolved type that reached the editor bare. */
    public static Set<String> simpleNames(Set<JdkType> types) {
        return types.stream().map(JdkType::simpleName).collect(Collectors.toUnmodifiableSet());
    }

    @Override public String toString() { return qualifiedName(); }
}
