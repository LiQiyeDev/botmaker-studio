package com.botmaker.studio.suggestions;

import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.helpers.FileTypeDetector;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.VariableWire;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.PrimitiveKind;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import com.botmaker.studio.util.VariableScopeVisitor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified type analysis and suggestion provider.
 * Combines:
 * <ul>
 *   <li><b>Library index</b> — raw ClassGraph {@link ClassInfo} from cached jar analysis via {@link TypeSummaryManager}</li>
 *   <li><b>Project AST</b> — rich {@link ITypeBinding} from live source parsing</li>
 * </ul>
 *
 * Owns CU creation (see {@link #createCompilationUnit(java.util.List, String, java.nio.file.Path)}).
 * Replaces: TypeManager, MethodResolver, ClassAnalyzer.
 */
public class ProjectAnalyzer {

    private final TypeSummaryManager libraryIndex;
    private final ProjectState state;

    // Memoized library-derived ResolvedTypes (instantiable, i.e. non-interface/non-abstract). Invalidated
    // when the library index size changes (add/remove jar → TypeSummaryManager re-indexes).
    private List<ResolvedType> libraryTypesCache;
    private int libraryTypesCacheCount = -1;

    // The types offered first in a type picker, in the order they are offered. Named, not spelled: a picker
    // entry that doesn't compile is the one bug this list can have.
    private static final List<String> FUNDAMENTAL_TYPES = List.of(
            PrimitiveKind.INT.keyword(), PrimitiveKind.DOUBLE.keyword(), PrimitiveKind.BOOLEAN.keyword(),
            JdkType.STRING.simpleName(), PrimitiveKind.LONG.keyword(), PrimitiveKind.FLOAT.keyword(),
            PrimitiveKind.CHAR.keyword());

    private static final Set<String> HIDDEN_VARIABLES =
            Set.of("args", "this", "super", "class");

    // =========================================================================
    // MEMBER RESOLUTION TYPES
    // =========================================================================

    /** A method resolved either from a live JDT binding (project type) or from a library index entry. */
    public sealed interface ResolvedMethod permits ResolvedMethod.Bound, ResolvedMethod.FromIndex {
        String name();
        boolean isStatic();

        record Bound(IMethodBinding binding) implements ResolvedMethod {
            public String name()     { return binding.getName(); }
            public boolean isStatic() { return Modifier.isStatic(binding.getModifiers()); }
        }

        record FromIndex(MethodInfo info) implements ResolvedMethod {
            public String name()     { return info.getName(); }
            public boolean isStatic() { return info.isStatic(); }
        }
    }

    /** A field resolved either from a live JDT binding (project type) or from a library index entry. */
    public sealed interface ResolvedField permits ResolvedField.Bound, ResolvedField.FromIndex {
        String name();
        boolean isStatic();

        record Bound(IVariableBinding binding) implements ResolvedField {
            public String name()     { return binding.getName(); }
            public boolean isStatic() { return Modifier.isStatic(binding.getModifiers()); }
        }

        record FromIndex(FieldInfo info) implements ResolvedField {
            public String name()     { return info.getName(); }
            public boolean isStatic() { return info.isStatic(); }
        }
    }

    /** Members resolved for every non-primitive variable in a scope snapshot. */
    public record ScopeMembers(
            Map<IVariableBinding, List<ResolvedMethod>> methods,
            Map<IVariableBinding, List<ResolvedField>>  fields
    ) {}

    public ProjectAnalyzer(TypeSummaryManager libraryIndex, ProjectState state) {
        this.libraryIndex = libraryIndex;
        this.state = state;
    }

    // =========================================================================
    // 1. COMPILATION UNIT CREATION
    // =========================================================================

    /**
     * Parses a single Java source string into a CompilationUnit with full bindings,
     * using the current project classpath and source path.
     */
    public CompilationUnit createCompilationUnit(String javaCode) {
        return createCompilationUnit(
                state.getResolvedClasspath(),
                javaCode,
                state.getSourcePath(),
                activeUnitName()
        );
    }

    /** Unit name (absolute on-disk path) of the active file, for JDT binding resolution; null if none. */
    private String activeUnitName() {
        ProjectFile active = state.getActiveFile();
        return active != null ? active.getPath().toAbsolutePath().toString() : null;
    }

    /**
     * Parses a single Java source string into a {@link CompilationUnit} with full binding
     * resolution, against the given classpath and source root.
     */
    public static CompilationUnit createCompilationUnit(List<String> classPaths, String javaCode, Path sourcePath) {
        return createCompilationUnit(classPaths, javaCode, sourcePath, null);
    }

    /**
     * Parses a single Java source string into a {@link CompilationUnit} with full binding resolution.
     * {@code unitName} (the absolute path of the file the source represents) is required by JDT for
     * char[]-source binding resolution — without it source-declared field/method/variable bindings come
     * back {@code null}. Falls back to a best-effort name derived from the source's public type when null.
     */
    public static CompilationUnit createCompilationUnit(List<String> classPaths, String javaCode, Path sourcePath, String unitName) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(javaCode.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setIgnoreMethodBodies(false);
        parser.setUnitName(unitName != null ? unitName : deriveUnitName(javaCode));

        String[] cpArray = classPaths.toArray(new String[0]);
        String[] sourcesArray = { sourcePath.toAbsolutePath().toString() };
        String[] encodingsArray = { "UTF-8" };
        parser.setEnvironment(cpArray, sourcesArray, encodingsArray, true);

        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);

        return (CompilationUnit) parser.createAST(null);
    }

    /** Best-effort unit name from the source's first top-level type, so JDT can resolve bindings. */
    private static String deriveUnitName(String javaCode) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(javaCode);
        return (m.find() ? m.group(1) : "Snippet") + ".java";
    }

    /**
     * Ensures a ProjectFile has a parsed AST with bindings.
     */
    public void ensureAstParsed(ProjectFile file) {
        if (file.getAst() != null) return;
        try {
            CompilationUnit cu = createCompilationUnit(
                    state.getResolvedClasspath(),
                    file.getContent(),
                    state.getSourcePath(),
                    file.getPath().toAbsolutePath().toString()
            );
            file.setAst(cu);
        } catch (Exception e) {
            System.err.println("Failed to parse AST for: " + file.getPath());
        }
    }

    // =========================================================================
    // 2. TYPE LOOKUP (unified across project + libraries)
    // =========================================================================

    /**
     * Finds a type by simple name, searching project source first, then library index.
     */
    public ResolvedType findTypeByName(String typeName) {
        if (typeName == null) return ResolvedType.UNKNOWN;
        if (FUNDAMENTAL_TYPES.contains(typeName)) return ResolvedType.named(typeName);

        // 1. Search project source files (rich bindings)
        for (ProjectFile file : state.getAllFiles()) {
            CompilationUnit cu = file.getAst();
            if (cu == null) continue;
            for (Object type : cu.types()) {
                if (type instanceof AbstractTypeDeclaration atd) {
                    if (atd.getName().getIdentifier().equals(typeName)) {
                        ITypeBinding binding = atd.resolveBinding();
                        if (binding != null) return ResolvedType.of(binding);
                    }
                }
            }
        }

        // 2. Search library index (lightweight summaries)
        if (libraryIndex != null) {
            Optional<ClassInfo> libType = libraryIndex.findBySimpleName(typeName);
            if (libType.isPresent()) {
                return ResolvedType.of(libType.get());
            }
        }

        // 3. Fallback
        return ResolvedType.named(typeName);
    }

    // ── ResolvedType resolution (picks the right variant: Bound / FromIndex / Primitive / Named) ──

    /**
     * Resolves a (possibly array) type name to a {@link ResolvedType}: project source bindings first
     * ({@link ResolvedType.Bound}), then the library index ({@link ResolvedType.FromIndex}),
     * then primitives ({@link ResolvedType.Primitive}), else {@link ResolvedType.Named}.
     */
    public ResolvedType resolveType(String typeName) {
        if (typeName == null || typeName.isBlank()) return ResolvedType.UNKNOWN;
        int dims = ResolvedType.dimensionsOf(typeName);
        ResolvedType base = resolveLeafType(ResolvedType.stripArray(typeName));
        return dims == 0 ? base : base.asArray(dims);
    }

    private ResolvedType resolveLeafType(String typeName) {
        Optional<PrimitiveKind> primitive = PrimitiveKind.fromKeyword(typeName);
        if (primitive.isPresent()) return ResolvedType.primitive(primitive.get());

        for (ProjectFile file : state.getAllFiles()) {
            CompilationUnit cu = file.getAst();
            if (cu == null) continue;
            for (Object type : cu.types()) {
                if (type instanceof AbstractTypeDeclaration atd
                        && atd.getName().getIdentifier().equals(typeName)) {
                    ITypeBinding binding = atd.resolveBinding();
                    if (binding != null) return ResolvedType.of(binding);
                }
            }
        }

        if (libraryIndex != null) {
            Optional<ClassInfo> libType = typeName.contains(".")
                    ? libraryIndex.findByQualifiedName(typeName)
                    : libraryIndex.findBySimpleName(typeName);
            if (libType.isPresent()) return ResolvedType.of(libType.get());
        }

        return ResolvedType.named(typeName);
    }

    /** Resolves an AST type node, preferring its live binding. */
    public static ResolvedType resolveType(Type type) {
        if (type == null) return ResolvedType.UNKNOWN;
        ITypeBinding b = type.resolveBinding();
        return b != null ? ResolvedType.of(b) : ResolvedType.named(type.toString());
    }

    /** Resolves the type of an expression from its binding. */
    public static ResolvedType resolveType(Expression expr) {
        if (expr == null) return ResolvedType.UNKNOWN;
        ITypeBinding b = expr.resolveTypeBinding();
        return b != null ? ResolvedType.of(b) : ResolvedType.UNKNOWN;
    }

    /**
     * Finds the fully qualified name for a simple class name.
     */
    public String findFullyQualifiedName(String simpleClassName) {
        if (simpleClassName == null) return null;
        if (simpleClassName.contains(".")) return simpleClassName;

        // Project files
        for (ProjectFile file : state.getAllFiles()) {
            if (file.getClassName().equals(simpleClassName)) {
                CompilationUnit cu = file.getAst();
                if (cu != null && cu.getPackage() != null) {
                    return cu.getPackage().getName().getFullyQualifiedName() + "." + simpleClassName;
                }
            }
        }

        // Library index
        if (libraryIndex != null) {
            Optional<ClassInfo> libType = libraryIndex.findBySimpleName(simpleClassName);
            if (libType.isPresent()) return libType.get().getName();
        }

        // Common java.util fallback. The FQN comes off the class literal rather than "java.util." + name, so a
        // simple name this doesn't actually own can't be turned into a plausible-looking FQN that resolves to
        // nothing.
        Optional<JdkType> jdk = JdkType.bySimpleName(simpleClassName)
                .filter(t -> "java.util".equals(t.packageName()));
        if (jdk.isPresent()) return jdk.get().qualifiedName();

        return null;
    }

    public static List<String> getFundamentalTypeNames() {
        return FUNDAMENTAL_TYPES;
    }

    /** Static fallback for callers that hold ProjectState but not a ProjectAnalyzer instance. */
    public static ResolvedType findTypeInProject(ProjectState state, String typeName) {
        if (state == null || typeName == null) return ResolvedType.UNKNOWN;
        if (FUNDAMENTAL_TYPES.contains(typeName)) return ResolvedType.named(typeName);
        for (ProjectFile file : state.getAllFiles()) {
            CompilationUnit cu = file.getAst();
            if (cu == null) continue;
            for (Object t : cu.types()) {
                if (t instanceof AbstractTypeDeclaration atd
                        && atd.getName().getIdentifier().equals(typeName)) {
                    ITypeBinding binding = atd.resolveBinding();
                    if (binding != null) return ResolvedType.of(binding);
                }
            }
        }
        return ResolvedType.named(typeName);
    }

    // =========================================================================
    // 3. AVAILABLE TYPES (for type selector menus)
    // =========================================================================

    /**
     * Returns all available types visible from a given context node.
     * Combines fundamentals + project types + library types + local types.
     */
    public List<ResolvedType> getAvailableTypes(ASTNode contextNode) {
        List<ResolvedType> types = new ArrayList<>();

        // 1. Fundamentals
        for (String name : FUNDAMENTAL_TYPES) {
            types.add(ResolvedType.named(name));
        }

        // 2. Project source types
        for (ProjectFile file : state.getAllFiles()) {
            ensureAstParsed(file);
            collectTypesFromFile(file, types);
        }

        // 3. Library types (non-abstract, non-interface for instantiation menus) — memoized; rebuilding a
        // ResolvedType per library ClassInfo on every menu open is the dominant cost otherwise.
        types.addAll(libraryTypes());

        // 4. Local types visible from context
        if (contextNode != null) {
            addVisibleLocalTypes(types, contextNode);
        }

        // 5. Deduplicate and sort
        return types.stream()
                .distinct()
                .sorted(Comparator.comparing(ResolvedType::simpleName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    /**
     * Pre-builds the memoized library-derived {@link ResolvedType}s so the first menu open doesn't pay for
     * it on the UI thread. Touches only the (thread-safe) library index — never project {@code state} — so it
     * is safe to call from a background thread while files are still loading.
     */
    public void warmLibraryTypes() {
        libraryTypes();
    }

    /** Memoized instantiable library types; rebuilt only when the library index size changes. */
    private synchronized List<ResolvedType> libraryTypes() {
        if (libraryIndex == null) return List.of();
        int count = libraryIndex.totalTypes();
        if (libraryTypesCache == null || count != libraryTypesCacheCount) {
            libraryTypesCache = libraryIndex.getAllTypes().stream()
                    .filter(ci -> !ci.isInterface() && !ci.isAbstract())
                    .map(ResolvedType::of)
                    .toList();
            libraryTypesCacheCount = count;
        }
        return libraryTypesCache;
    }

    /**
     * Returns types compatible with a target type.
     */
    public List<String> getCompatibleTypes(ResolvedType targetType) {
        List<String> allClasses = state.getAllFiles().stream()
                .map(ProjectFile::getClassName)
                .sorted()
                .collect(Collectors.toList());

        if (targetType == null || targetType.isUnknown()) return allClasses;

        return allClasses.stream()
                .filter(className -> findTypeByName(className).isAssignmentCompatible(targetType))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 4. CONSTRUCTORS
    // =========================================================================

    /**
     * Returns constructor signatures for a type, searching project first then libraries.
     */
    public List<MethodSignature> getConstructors(String className) {
        // 1. Project source (rich binding)
        ResolvedType projectType = findProjectType(className);
        if (projectType instanceof ResolvedType.Bound bound) {
            return constructorsOf(bound.binding());
        }

        // 2. Library index
        if (libraryIndex != null) {
            Optional<ClassInfo> libType = libraryIndex.findBySimpleName(className);
            if (libType.isPresent()) {
                ClassInfo ci = libType.get();
                return ci.getConstructorInfo().stream()
                        .map(mi -> toConstructorSignature(mi, ci.getSimpleName()))
                        .collect(Collectors.toList());
            }
        }

        // 3. Fallback: default no-arg constructor
        return List.of(new MethodSignature(
                className, List.of(), List.of(), ResolvedType.named(className)
        ));
    }

    /** Public constructor signatures from a resolved type binding. */
    private static List<MethodSignature> constructorsOf(ITypeBinding binding) {
        if (binding == null || binding.isPrimitive()) return List.of();
        List<MethodSignature> constructors = new ArrayList<>();
        String className = binding.getName();
        for (IMethodBinding mb : binding.getDeclaredMethods()) {
            if (mb.isConstructor() && Modifier.isPublic(mb.getModifiers())) {
                List<ResolvedType> paramTypes = Arrays.stream(mb.getParameterTypes())
                        .map(ResolvedType::of)
                        .collect(Collectors.toList());
                List<String> paramNames = new ArrayList<>();
                for (int i = 0; i < paramTypes.size(); i++) paramNames.add("arg" + i);
                constructors.add(new MethodSignature(className, paramTypes, paramNames, ResolvedType.of(binding)));
            }
        }
        return constructors;
    }

    // =========================================================================
    // 5. METHOD LOOKUP (for "Call Function" menu)
    // =========================================================================

    /**
     * Returns the full scope visible at a given context node via VariableScopeVisitor.
     * NodeScope.variables() — non-primitive instance targets
     * NodeScope.methods()   — directly callable methods at this scope
     * NodeScope.types()     — types in scope (source of static-method targets)
     */
    public VariableScopeVisitor.NodeScope getAvailableScopes(ASTNode contextNode) {
        if (contextNode == null) return new VariableScopeVisitor.NodeScope(List.of(), List.of(), List.of());
        List<IVariableBinding> variables = VariableScopeVisitor.getAvailableVariables(contextNode);
        List<IMethodBinding> methods    = VariableScopeVisitor.getAvailableMethods(contextNode);
        List<ITypeBinding>   types      = VariableScopeVisitor.getAvailableTypes(contextNode);
        return new VariableScopeVisitor.NodeScope(variables, methods, types);
    }

    /**
     * Lightweight view of a visible variable for menu population.
     *
     * <p>Carries the {@link ResolvedType}, not just its name: seeding a dropped block with a real variable
     * (see {@code StatementFactory}) has to ask whether a candidate is an enum, an array or numeric, and a bare
     * simple name can't answer that. {@link #typeName()} stays available for the menus that only render a label.
     */
    public record VariableOption(String name, ResolvedType type, boolean isField) {
        public String typeName() { return type.simpleName(); }
    }

    /** Lightweight view of a readable field (constant / member) of a type, for menu population. */
    public record FieldOption(String name, ResolvedType type, boolean isStatic) {}

    /**
     * Variables visible at {@code node}, optionally filtered to those assignable to
     * {@code requiredType}. Binding-backed (via {@link VariableScopeVisitor}); de-duplicated by name.
     *
     * <p>Enclosing lambda parameters are then added from the AST — see
     * {@link #enclosingLambdaParameters}. They are a scope the binding pass routinely misses, and missing
     * them is what made {@code whileFindAny(group, found -> …)}'s {@code found} unreachable in the editor.
     */
    public List<VariableOption> getVisibleVariables(ASTNode node, ResolvedType requiredType) {
        if (node == null) return List.of();
        List<VariableOption> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IVariableBinding b : VariableScopeVisitor.getAvailableVariables(node)) {
            String name = b.getName();
            if (HIDDEN_VARIABLES.contains(name)) continue;
            ResolvedType varType = ResolvedType.of(b.getType());
            if (!isCompatible(varType, requiredType)) continue;
            if (seen.add(name)) {
                results.add(new VariableOption(name, varType, b.isField()));
            }
        }
        for (VariableOption param : enclosingLambdaParameters(node)) {
            if (!isCompatible(param.type(), requiredType)) continue;
            if (seen.add(param.name())) results.add(param);
        }
        return results;
    }

    /**
     * The parameters of every {@link LambdaExpression} enclosing {@code node}, innermost first.
     *
     * <p>Why this exists rather than relying on {@link VariableScopeVisitor}: an inferred-type lambda
     * parameter ({@code found -> …}) has a binding only once JDT has resolved the <em>target</em> type,
     * which needs the SDK jar on the parse classpath. In the editor that resolution is routinely absent
     * (a freshly generated project, an unresolved classpath, a file mid-edit), and when it is, the
     * parameter simply does not exist as far as the scope walker is concerned — so the body of a vision
     * loop offered no way to reach what was found. Here the parameter is read off the AST, which is always
     * there, and only its <em>type</em> falls back to {@link #lambdaParameterType}.
     */
    private List<VariableOption> enclosingLambdaParameters(ASTNode node) {
        List<VariableOption> out = new ArrayList<>();
        for (ASTNode cur = node; cur != null; cur = cur.getParent()) {
            if (!(cur instanceof LambdaExpression lambda)) continue;
            for (Object p : lambda.parameters()) {
                String name = parameterName(p);
                if (name == null || HIDDEN_VARIABLES.contains(name)) continue;
                out.add(new VariableOption(name, lambdaParameterType(lambda, p), false));
            }
        }
        return out;
    }

    /** The declared name of a lambda parameter, inferred ({@code found}) or explicit ({@code Matches found}). */
    private static String parameterName(Object parameter) {
        return switch (parameter) {
            case VariableDeclarationFragment frag -> frag.getName().getIdentifier();
            case SingleVariableDeclaration svd -> svd.getName().getIdentifier();
            default -> null;
        };
    }

    /**
     * The type of a lambda parameter: its binding when JDT resolved one, else the explicit declared type,
     * else the functional interface's type argument read off the invoked method in the library index —
     * {@code ImageFinder.whileFindAny(ImageTemplateGroup, Consumer<Matches>)} yields {@code Matches}.
     * {@link ResolvedType#UNKNOWN} when nothing answers, which still leaves the name usable.
     */
    private ResolvedType lambdaParameterType(LambdaExpression lambda, Object parameter) {
        if (parameter instanceof VariableDeclarationFragment frag
                && frag.resolveBinding() instanceof IVariableBinding vb && vb.getType() != null) {
            ResolvedType bound = ResolvedType.of(vb.getType());
            if (!bound.isUnknown()) return bound;
        }
        if (parameter instanceof SingleVariableDeclaration svd) return resolveType(svd.getType());
        return functionalArgumentType(lambda);
    }

    /**
     * Reads the type argument of the functional-interface parameter the {@code lambda} is passed to
     * ({@code Consumer<Matches>} → {@code Matches}) from the library index. Generic arguments are only
     * available on the raw ClassGraph signature — {@link #resolveLibraryType} deliberately strips them —
     * so the descriptor string is parsed here rather than going through {@link MethodSignature}.
     */
    private ResolvedType functionalArgumentType(LambdaExpression lambda) {
        if (libraryIndex == null
                || !(lambda.getParent() instanceof MethodInvocation mi)
                || mi.getExpression() == null) {
            return ResolvedType.UNKNOWN;
        }
        String receiver = mi.getExpression().toString();
        Optional<ClassInfo> ci = libraryIndex.findBySimpleName(receiver);
        if (ci.isEmpty()) ci = libraryIndex.findByQualifiedName(receiver);
        if (ci.isEmpty()) return ResolvedType.UNKNOWN;

        int position = mi.arguments().indexOf(lambda);
        String name = mi.getName().getIdentifier();
        for (MethodInfo info : ci.get().getMethodInfo(name)) {
            var params = info.getParameterInfo();
            if (params.length != mi.arguments().size() || position < 0 || position >= params.length) continue;
            String arg = firstTypeArgument(params[position].getTypeSignatureOrTypeDescriptor().toString());
            if (arg != null) return resolveLibraryType(arg);
        }
        return ResolvedType.UNKNOWN;
    }

    /** {@code java.util.function.Consumer<com.…Matches>} → {@code com.…Matches}; null when not generic. */
    private static String firstTypeArgument(String descriptor) {
        int open = descriptor.indexOf('<');
        int close = descriptor.lastIndexOf('>');
        if (open < 0 || close <= open) return null;
        String args = descriptor.substring(open + 1, close);
        int depth = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) return args.substring(0, i).trim();
        }
        return args.isBlank() ? null : args.trim();
    }

    /**
     * The project's variables whose type is assignment-compatible with {@code requiredType}, each live
     * activity's enable flag included. Sourced from project state (not the AST), so they're available
     * regardless of scope. Used to populate the "Activities" expression submenu, which inserts
     * {@code Activities.<name>}.
     */
    public List<ActivityVariable> getActivityVariables(ResolvedType requiredType) {
        return state.getActivities().allVariables().stream()
                .filter(a -> isCompatible(VariableWire.resolvedType(a.type()), requiredType))
                .toList();
    }

    /**
     * The names of the project's defined activities, in configured (run) order. Used by the enable/disable
     * name picker to offer {@code Activity.enable("…")}/{@code disable("…")} the real activity names instead of
     * a free-typed string. Sourced from project state (not the AST), so it's available regardless of scope.
     */
    public List<String> getActivityNames() {
        return state.getActivities().activities().stream()
                .map(com.botmaker.studio.project.activity.ActivityDefinition::name)
                .toList();
    }

    private static boolean isCompatible(ResolvedType actual, ResolvedType required) {
        if (required == null || required.isUnknown()) return true;
        if (actual.simpleName().equals(required.simpleName())) return true;
        return actual.isAssignmentCompatible(required);
    }

    // ── Binding-first member resolution ──────────────────────────────────────

    /**
     * Returns methods of a type, binding-accurate for project types and
     * ClassGraph {@link MethodInfo}-based for external library types.
     * Resolution order: live {@code getDeclaredMethods()} → library index by qualified name → by simple name.
     */
    public List<ResolvedMethod> getMethodsOf(ITypeBinding type) {
        return getMethodsOf(type, false);
    }

    public List<ResolvedMethod> getMethodsOf(ITypeBinding type, boolean staticOnly) {
        if (type == null) return List.of();

        // 1. Live binding (project type or classpath-resolved library with sources)
        IMethodBinding[] declared = type.getDeclaredMethods();
        if (declared != null && declared.length > 0) {
            return Arrays.stream(declared)
                    .filter(mb -> isAccessibleMethod(mb, staticOnly))
                    .map(ResolvedMethod.Bound::new)
                    .sorted(Comparator.comparing(b -> b.binding().getName()))
                    .collect(Collectors.toList());
        }

        // 2. Library index — qualified name first, simple name fallback
        if (libraryIndex != null) {
            Optional<ClassInfo> ci = libraryIndex.findByQualifiedName(type.getQualifiedName());
            if (ci.isEmpty()) ci = libraryIndex.findBySimpleName(type.getName());
            if (ci.isPresent()) {
                return ci.get().getMethodInfo().stream()
                        .filter(mi -> mi.isPublic() && (!staticOnly || mi.isStatic()))
                        .map(ResolvedMethod.FromIndex::new)
                        .sorted(Comparator.comparing(f -> f.info().getName()))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    /**
     * What {@code call} evaluates to — the question a drag over an expression slot has to answer before the
     * file it came from has resolved.
     *
     * <p>Binding first, as everywhere else. Without one the receiver's simple name goes through the library
     * index, which knows the SDK facades whether or not the editor parsed with bindings this session — that is
     * the whole point of it here: {@code ImageClicker.click(ore)} gives back {@code void}, and a slot that
     * cannot see that accepts the drop and produces {@code if (ImageClicker.click(ore))}.
     *
     * <p>Overloads that disagree about their return type answer {@link ResolvedType#UNKNOWN}: with no binding
     * there is no way to tell which one is being dragged, and guessing refuses legal drops. Unknown is the
     * permissive answer, so the failure stays on the accepting side.
     */
    /**
     * What an expression <em>is worth</em> — the type a slot would receive if this expression were moved into
     * it. {@link #returnTypeOf} answers only a call; this answers every shape a line can be, which is what the
     * drag layer and the drop path both need: a slot judged only on calls waved through everything else.
     *
     * <p>Binding first, then the shapes that can be read off the source alone, then {@link ResolvedType#UNKNOWN}
     * — never a guess. Unknown is accepted everywhere, so being wrong here refuses a legal drop, which is the
     * one failure a user cannot work around.
     */
    public ResolvedType valueTypeOf(Expression expression) {
        if (expression == null) return ResolvedType.UNKNOWN;
        ITypeBinding binding = expression.resolveTypeBinding();
        if (binding != null) return ResolvedType.of(binding);
        return switch (expression) {
            case MethodInvocation call -> returnTypeOf(call);
            case ClassInstanceCreation creation -> resolveType(creation.getType());
            case CastExpression cast -> resolveType(cast.getType());
            case StringLiteral ignored -> ResolvedType.named("java.lang.String");
            case BooleanLiteral ignored -> ResolvedType.BOOLEAN;
            case CharacterLiteral ignored -> ResolvedType.named("char");
            case NumberLiteral literal -> numberLiteralType(literal);
            case InfixExpression infix -> infixType(infix);
            case PrefixExpression prefix ->
                    prefix.getOperator() == PrefixExpression.Operator.NOT
                            ? ResolvedType.BOOLEAN : valueTypeOf(prefix.getOperand());
            case ParenthesizedExpression parens -> valueTypeOf(parens.getExpression());
            // An assignment or a ++ is a value in Java and a line in the editor; the editor is right about
            // what the user means, so neither is offered as one.
            default -> ResolvedType.UNKNOWN;
        };
    }

    /** {@code 1} is an int, {@code 1.5} a double — the suffix decides the rest. */
    private static ResolvedType numberLiteralType(NumberLiteral literal) {
        String token = literal.getToken().toLowerCase(java.util.Locale.ROOT);
        if (token.endsWith("l")) return ResolvedType.named("long");
        if (token.endsWith("f")) return ResolvedType.named("float");
        if (token.endsWith("d") || token.contains(".") || token.contains("e")) return ResolvedType.named("double");
        return ResolvedType.named("int");
    }

    /** A comparison or a logical operator is a yes/no; arithmetic is left to the binding, if there ever is one. */
    private static ResolvedType infixType(InfixExpression infix) {
        InfixExpression.Operator op = infix.getOperator();
        boolean predicate = op == InfixExpression.Operator.EQUALS || op == InfixExpression.Operator.NOT_EQUALS
                || op == InfixExpression.Operator.LESS || op == InfixExpression.Operator.LESS_EQUALS
                || op == InfixExpression.Operator.GREATER || op == InfixExpression.Operator.GREATER_EQUALS
                || op == InfixExpression.Operator.CONDITIONAL_AND
                || op == InfixExpression.Operator.CONDITIONAL_OR;
        return predicate ? ResolvedType.BOOLEAN : ResolvedType.UNKNOWN;
    }

    public ResolvedType returnTypeOf(MethodInvocation call) {
        if (call == null) return ResolvedType.UNKNOWN;
        IMethodBinding bound = call.resolveMethodBinding();
        if (bound != null && bound.getReturnType() != null) return ResolvedType.of(bound.getReturnType());
        if (libraryIndex == null || !(call.getExpression() instanceof Name receiver)) return ResolvedType.UNKNOWN;

        String qualified = receiver.getFullyQualifiedName();
        String simple = qualified.contains(".") ? qualified.substring(qualified.lastIndexOf('.') + 1) : qualified;
        Optional<ClassInfo> owner = libraryIndex.findByQualifiedName(qualified);
        if (owner.isEmpty()) owner = libraryIndex.findBySimpleName(simple);
        if (owner.isEmpty()) return ResolvedType.UNKNOWN;

        Set<String> returned = owner.get().getMethodInfo(call.getName().getIdentifier()).stream()
                .filter(MethodInfo::isPublic)
                .map(mi -> mi.getTypeSignatureOrTypeDescriptor().getResultType().toString())
                .collect(Collectors.toSet());
        return returned.size() == 1 ? ResolvedType.named(returned.iterator().next()) : ResolvedType.UNKNOWN;
    }

    /**
     * Returns public fields of a type, binding-accurate for project types and
     * ClassGraph {@link FieldInfo}-based for external library types.
     */
    public List<ResolvedField> getFieldsOf(ITypeBinding type) {
        if (type == null) return List.of();

        // 1. Live binding
        IVariableBinding[] declared = type.getDeclaredFields();
        if (declared != null && declared.length > 0) {
            return Arrays.stream(declared)
                    .filter(vb -> Modifier.isPublic(vb.getModifiers()))
                    .map(ResolvedField.Bound::new)
                    .sorted(Comparator.comparing(b -> b.binding().getName()))
                    .collect(Collectors.toList());
        }

        // 2. Library index
        if (libraryIndex != null) {
            Optional<ClassInfo> ci = libraryIndex.findByQualifiedName(type.getQualifiedName());
            if (ci.isEmpty()) ci = libraryIndex.findBySimpleName(type.getName());
            if (ci.isPresent()) {
                return ci.get().getFieldInfo().stream()
                        .filter(FieldInfo::isPublic)
                        .map(ResolvedField.FromIndex::new)
                        .sorted(Comparator.comparing(f -> f.info().getName()))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    /**
     * For every non-primitive variable in {@code scope}, resolves its callable
     * methods and public fields. The primary entry point for autocomplete and
     * block-menu consumers.
     */
    public ScopeMembers resolveScope(VariableScopeVisitor.NodeScope scope) {
        Map<IVariableBinding, List<ResolvedMethod>> methods = new LinkedHashMap<>();
        Map<IVariableBinding, List<ResolvedField>>  fields  = new LinkedHashMap<>();

        for (IVariableBinding var : scope.variables()) {
            ITypeBinding type = var.getType();
            if (type == null || type.isPrimitive()) continue;
            methods.put(var, getMethodsOf(type, false));
            fields.put(var, getFieldsOf(type));
        }

        return new ScopeMembers(
                Collections.unmodifiableMap(methods),
                Collections.unmodifiableMap(fields)
        );
    }

    /**
     * Returns method signatures for a type by name.
     * Delegates to {@link #getMethodsOf(ITypeBinding, boolean)} when a binding can be resolved,
     * falling back to a string-only path for unresolved types.
     */
    public List<MethodSignature> getMethods(String typeName, boolean staticOnly) {
        ResolvedType type = findTypeByName(typeName);
        if (type instanceof ResolvedType.Bound bound) {
            return getMethodsOf(bound.binding(), staticOnly).stream()
                    .map(rm -> switch (rm) {
                        case ResolvedMethod.Bound b    -> createSignatureFromBinding(b.binding());
                        case ResolvedMethod.FromIndex f -> toMethodSignature(f.info());
                    })
                    .sorted(Comparator.comparing(MethodSignature::name))
                    .collect(Collectors.toList());
        }

        // String-only fallback for types that could not be resolved to a binding
        List<MethodSignature> signatures = new ArrayList<>();
        ProjectFile file = findProjectFile(typeName);
        if (file != null && file.getAst() != null && !file.getAst().types().isEmpty()) {
            Object firstType = file.getAst().types().getFirst();
            if (firstType instanceof TypeDeclaration td) {
                for (MethodDeclaration md : td.getMethods()) {
                    if (isAccessibleMethod(md, staticOnly)) signatures.add(createSignatureFromDeclaration(md));
                }
            }
        }
        if (signatures.isEmpty() && libraryIndex != null) {
            Optional<ClassInfo> libType = libraryIndex.findBySimpleName(typeName);
            if (libType.isEmpty()) libType = libraryIndex.findByQualifiedName(typeName);
            if (libType.isPresent()) {
                for (MethodInfo mi : libType.get().getMethodInfo()) {
                    if (mi.isPublic() && (!staticOnly || mi.isStatic())) signatures.add(toMethodSignature(mi));
                }
            }
        }
        signatures.sort(Comparator.comparing(MethodSignature::name));
        return signatures;
    }

    /**
     * Public readable fields of {@code typeName} matching {@code wantStatic} (static constants vs instance
     * members). Binding-accurate for project types, ClassGraph-based for library types — the field counterpart
     * of {@link #getMethods(String, boolean)}.
     */
    public List<FieldOption> getFields(String typeName, boolean wantStatic) {
        List<FieldOption> out = new ArrayList<>();
        ResolvedType type = findTypeByName(typeName);
        if (type instanceof ResolvedType.Bound bound) {
            for (ResolvedField f : getFieldsOf(bound.binding())) {
                if (f.isStatic() != wantStatic) continue;
                out.add(new FieldOption(f.name(), fieldType(f), f.isStatic()));
            }
            return out;
        }
        if (libraryIndex != null) {
            Optional<ClassInfo> ci = libraryIndex.findBySimpleName(typeName);
            if (ci.isEmpty()) ci = libraryIndex.findByQualifiedName(typeName);
            if (ci.isPresent()) {
                for (FieldInfo fi : ci.get().getFieldInfo()) {
                    if (!fi.isPublic() || fi.isStatic() != wantStatic) continue;
                    out.add(new FieldOption(fi.getName(),
                            ResolvedType.named(fi.getTypeSignatureOrTypeDescriptor().toString()), fi.isStatic()));
                }
            }
        }
        return out;
    }

    private static ResolvedType fieldType(ResolvedField f) {
        return switch (f) {
            case ResolvedField.Bound b -> ResolvedType.of(b.binding().getType());
            case ResolvedField.FromIndex fi -> ResolvedType.named(fi.info().getTypeSignatureOrTypeDescriptor().toString());
        };
    }

    // =========================================================================
    // 6. ENUM LOOKUP
    // =========================================================================

    public static EnumDeclaration findEnumDeclaration(CompilationUnit cu, String enumName) {
        if (cu == null || enumName == null) return null;
        for (Object obj : cu.types()) {
            if (obj instanceof EnumDeclaration ed && ed.getName().getIdentifier().equals(enumName)) {
                return ed;
            }
            if (obj instanceof TypeDeclaration td) {
                for (Object bodyObj : td.bodyDeclarations()) {
                    if (bodyObj instanceof EnumDeclaration ed && ed.getName().getIdentifier().equals(enumName)) {
                        return ed;
                    }
                    if (bodyObj instanceof MethodDeclaration md && md.getBody() != null) {
                        for (Object stmt : md.getBody().statements()) {
                            if (stmt instanceof TypeDeclarationStatement tds
                                    && tds.getDeclaration() instanceof EnumDeclaration ed
                                    && ed.getName().getIdentifier().equals(enumName)) {
                                return ed;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public static List<String> getEnumConstantNames(EnumDeclaration enumDecl) {
        if (enumDecl == null) return List.of();
        List<String> names = new ArrayList<>();
        for (Object obj : enumDecl.enumConstants()) {
            names.add(((EnumConstantDeclaration) obj).getName().getIdentifier());
        }
        return names;
    }

    // =========================================================================
    // 7. TYPE INFERENCE (from old TypeManager)
    // =========================================================================

    public static ResolvedType inferExpectedType(ASTNode node) {
        if (node == null || node.getParent() == null) return ResolvedType.UNKNOWN;
        ASTNode parent = node.getParent();

        switch (parent) {
            // A condition, in every shape the language spells one. Missing here, they all answered UNKNOWN —
            // which every check treats as "we don't know, allow it" — so an `if` slot accepted a number as
            // readily as a yes/no, and the drop that followed wrote source that would not compile.
            case IfStatement stmt when stmt.getExpression() == node -> {
                return ResolvedType.BOOLEAN;
            }
            case WhileStatement stmt when stmt.getExpression() == node -> {
                return ResolvedType.BOOLEAN;
            }
            case DoStatement stmt when stmt.getExpression() == node -> {
                return ResolvedType.BOOLEAN;
            }
            case ForStatement stmt when stmt.getExpression() == node -> {
                return ResolvedType.BOOLEAN;
            }
            case ConditionalExpression cond when cond.getExpression() == node -> {
                return ResolvedType.BOOLEAN;
            }
            case PrefixExpression pre when pre.getOperator() == PrefixExpression.Operator.NOT -> {
                return ResolvedType.BOOLEAN;
            }
            case VariableDeclarationFragment frag when frag.getInitializer() == node -> {
                ASTNode gp = frag.getParent();
                if (gp instanceof VariableDeclarationStatement vds) return resolveType(vds.getType());
                if (gp instanceof FieldDeclaration fd) return resolveType(fd.getType());
            }
            case Assignment as when as.getRightHandSide() == node -> {
                return resolveType(as.getLeftHandSide());
            }
            case ReturnStatement ignored -> {
                ASTNode current = parent;
                while (current != null && !(current instanceof MethodDeclaration)) current = current.getParent();
                if (current instanceof MethodDeclaration md && md.getReturnType2() != null) {
                    return resolveType(md.getReturnType2());
                }
            }
            case MethodInvocation mi -> {
                int index = mi.arguments().indexOf(node);
                IMethodBinding mb = mi.resolveMethodBinding();
                if (isPrintSink(mi, mb, index)) return ResolvedType.UNKNOWN;
                if (mb != null && index >= 0 && index < mb.getParameterTypes().length) {
                    return ResolvedType.of(mb.getParameterTypes()[index]);
                }
            }
            case SwitchCase sc when sc.getParent() instanceof SwitchStatement ss -> {
                if (ss.getExpression() != null) return resolveType(ss.getExpression());
            }
            case EnhancedForStatement efs when efs.getExpression() == node -> {
                return resolveType(efs.getParameter().getType()).asArray(1);
            }
            case ArrayInitializer ai -> {
                return inferArrayTypeForElement(ai);
            }
            default -> {
            }
        }

        return ResolvedType.UNKNOWN;
    }

    // =========================================================================
    // 8. AST NODE CREATION HELPERS (from old TypeManager)
    // =========================================================================

    public static Type createTypeNode(AST ast, ResolvedType type) {
        if (type == null || type.isUnknown()) {
            return ast.newSimpleType(ast.newSimpleName("Object"));
        }

        if (type instanceof ResolvedType.Bound bound) {
            ITypeBinding binding = bound.binding();
            if (binding.isPrimitive()) {
                return "void".equals(binding.getName())
                        ? ast.newPrimitiveType(PrimitiveType.VOID)
                        : ast.newPrimitiveType(PrimitiveType.toCode(binding.getName()));
            }
            if (binding.isArray()) {
                Type elementType = createTypeNode(ast, ResolvedType.of(binding.getElementType()));
                return ast.newArrayType(elementType, binding.getDimensions());
            }
            return ast.newSimpleType(ast.newName(binding.getName()));
        }

        // Primitive / FromIndex / Named — build from the (qualified) name.
        return createTypeNode(ast, type.qualifiedName());
    }

    /**
     * Like {@link #createTypeNode(AST, ResolvedType)} but always uses the type's SIMPLE (leaf) name. For callers
     * that also add an {@code import}, so the generated source reads {@code Point}, not
     * {@code com.botmaker.sdk.api.geometry.Point}.
     */
    public static Type createSimpleTypeNode(AST ast, ResolvedType type) {
        if (type == null || type.isUnknown()) return ast.newSimpleType(ast.newSimpleName("Object"));
        int dims = type.arrayDimensions();
        return createTypeNode(ast, type.leafType().simpleName() + "[]".repeat(dims));
    }

    public static Type createTypeNode(AST ast, String typeName) {
        int dimensions = 0;
        String baseName = typeName;
        // Drop any generic type arguments (Consumer<…> -> Consumer): the raw type is enough for a generated node,
        // and a '<'/'>'-bearing name would blow up ast.newName with "Invalid identifier".
        int generic = baseName.indexOf('<');
        if (generic >= 0) baseName = baseName.substring(0, generic).trim();
        while (baseName.endsWith("[]")) {
            dimensions++;
            baseName = baseName.substring(0, baseName.length() - 2).trim();
        }

        Type baseType = switch (baseName) {
            case "int" -> ast.newPrimitiveType(PrimitiveType.INT);
            case "double" -> ast.newPrimitiveType(PrimitiveType.DOUBLE);
            case "boolean" -> ast.newPrimitiveType(PrimitiveType.BOOLEAN);
            case "char" -> ast.newPrimitiveType(PrimitiveType.CHAR);
            case "long" -> ast.newPrimitiveType(PrimitiveType.LONG);
            case "float" -> ast.newPrimitiveType(PrimitiveType.FLOAT);
            case "short" -> ast.newPrimitiveType(PrimitiveType.SHORT);
            case "byte" -> ast.newPrimitiveType(PrimitiveType.BYTE);
            case "void" -> ast.newPrimitiveType(PrimitiveType.VOID);
            default -> ast.newSimpleType(ast.newName(baseName));
        };

        return dimensions > 0 ? ast.newArrayType(baseType, dimensions) : baseType;
    }

    // =========================================================================
    // 9. UTILITY (from old TypeManager)
    // =========================================================================

    public static String unwrapCollectionType(String typeName) {
        if (typeName == null) return "Object";
        String temp = typeName.trim();
        if ((temp.startsWith("ArrayList<") || temp.startsWith("List<")) && temp.endsWith(">")) {
            return temp.substring(temp.indexOf('<') + 1, temp.lastIndexOf('>'));
        }
        return typeName;
    }

    public static boolean isUserVariable(String variableName) {
        if (variableName == null || variableName.isEmpty()) return false;
        String cleanName = variableName.split(" ")[0].split(":")[0].trim();
        return !HIDDEN_VARIABLES.contains(cleanName) && !cleanName.startsWith("_");
    }

    public static void collectLeafValues(Expression expr, List<Expression> accumulator) {
        if (expr == null) return;
        boolean isContainer = false;

        if (expr instanceof ClassInstanceCreation cic) {
            if (cic.getType().toString().startsWith("ArrayList") && !cic.arguments().isEmpty()) {
                isContainer = true;
                collectLeafValues((Expression) cic.arguments().getFirst(), accumulator);
            }
        } else if (expr instanceof MethodInvocation mi) {
            String name = mi.getName().getIdentifier();
            if ("asList".equals(name) || "of".equals(name)) {
                isContainer = true;
                for (Object arg : mi.arguments()) collectLeafValues((Expression) arg, accumulator);
            }
        } else if (expr instanceof ArrayInitializer ai) {
            isContainer = true;
            for (Object e : ai.expressions()) collectLeafValues((Expression) e, accumulator);
        } else if (expr instanceof ArrayCreation ac) {
            isContainer = true;
            if (ac.getInitializer() != null) collectLeafValues(ac.getInitializer(), accumulator);
        }

        if (!isContainer) accumulator.add(expr);
    }

    public static boolean isEnumType(ResolvedType type, CompilationUnit cu) {
        if (type.isEnum()) return true;
        return findEnumDeclaration(cu, type.leafType().simpleName()) != null;
    }

    // =========================================================================
    // LIBRARY INDEX ACCESS
    // =========================================================================

    public TypeSummaryManager getLibraryIndex() { return libraryIndex; }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    private ResolvedType findProjectType(String className) {
        for (ProjectFile file : state.getAllFiles()) {
            if (file.getClassName().equals(className) && file.getAst() != null) {
                if (!file.getAst().types().isEmpty()) {
                    AbstractTypeDeclaration atd = (AbstractTypeDeclaration) file.getAst().types().getFirst();
                    ITypeBinding binding = atd.resolveBinding();
                    if (binding != null) return ResolvedType.of(binding);
                }
            }
        }
        return ResolvedType.named(className);
    }

    private ProjectFile findProjectFile(String className) {
        return state.getAllFiles().stream()
                .filter(f -> f.getClassName().equals(className))
                .findFirst().orElse(null);
    }

    private void collectTypesFromFile(ProjectFile file, List<ResolvedType> targetList) {
        CompilationUnit cu = file.getAst();
        if (cu == null) return;

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (isLocalType(node)) return false;
                addType(node);
                return true;
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                if (isLocalType(node)) return false;
                addType(node);
                return true;
            }

            private boolean isLocalType(AbstractTypeDeclaration node) {
                if (node.getParent() instanceof TypeDeclarationStatement) return true;
                ITypeBinding binding = node.resolveBinding();
                return binding != null && (binding.isLocal() || binding.isAnonymous());
            }

            private void addType(AbstractTypeDeclaration node) {
                ITypeBinding binding = node.resolveBinding();
                targetList.add(binding != null ? ResolvedType.of(binding) : ResolvedType.named(node.getName().getIdentifier()));
            }
        });
    }

    private static void addVisibleLocalTypes(List<ResolvedType> types, ASTNode node) {
        ASTNode current = node.getParent();
        while (current != null) {
            if (current instanceof Block block) {
                for (Object stmtObj : block.statements()) {
                    if (stmtObj instanceof TypeDeclarationStatement tds) {
                        AbstractTypeDeclaration atd = tds.getDeclaration();
                        ITypeBinding binding = atd.resolveBinding();
                        types.add(binding != null ? ResolvedType.of(binding) : ResolvedType.named(atd.getName().getIdentifier()));
                    }
                }
            }
            current = current.getParent();
        }
    }

    private static boolean isAccessibleMethod(MethodDeclaration md, boolean staticOnly) {
        if (md.isConstructor()) return false;
        if (FileTypeDetector.isMainMethod(md)) return false;
        int mods = md.getModifiers();
        if (!Modifier.isPublic(mods)) return false;
        if (staticOnly && !Modifier.isStatic(mods)) return false;
        return true;
    }

    private static boolean isAccessibleMethod(IMethodBinding mb, boolean staticOnly) {
        if (mb.isConstructor()) return false;
        int mods = mb.getModifiers();
        if (!Modifier.isPublic(mods)) return false;
        if (staticOnly && !Modifier.isStatic(mods)) return false;
        return true;
    }

    private static MethodSignature createSignatureFromDeclaration(MethodDeclaration md) {
        List<ResolvedType> types = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean varargs = false;
        for (Object p : md.parameters()) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) p;
            // For a varargs param JDT's getType() is already the element type (ImageTemplate for T...).
            types.add(resolveType(param.getType()));
            names.add(param.getName().getIdentifier());
            varargs = param.isVarargs();
        }
        ResolvedType returnType = md.getReturnType2() != null ? resolveType(md.getReturnType2()) : ResolvedType.VOID;
        return new MethodSignature(md.getName().getIdentifier(), types, names, returnType, varargs);
    }

    private static MethodSignature createSignatureFromBinding(IMethodBinding mb) {
        List<ResolvedType> types = Arrays.stream(mb.getParameterTypes())
                .map(ResolvedType::of).collect(Collectors.toList());
        // Bindings model a varargs param as its array type; normalize the trailing param to the element type.
        if (mb.isVarargs() && !types.isEmpty()) {
            ResolvedType last = types.get(types.size() - 1);
            if (last.isArray()) types.set(types.size() - 1, last.leafType().asArray(last.arrayDimensions() - 1));
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) names.add("arg" + i);
        return new MethodSignature(mb.getName(), types, names, ResolvedType.of(mb.getReturnType()), mb.isVarargs());
    }

    private MethodSignature toMethodSignature(MethodInfo mi) {
        List<ResolvedType> paramTypes = libraryParamTypes(mi);
        boolean varargs = mi.isVarArgs();
        // A varargs param's bytecode descriptor is the array type (…ImageTemplate[]); use the element type.
        if (varargs && !paramTypes.isEmpty()) {
            ResolvedType last = paramTypes.get(paramTypes.size() - 1);
            if (last.isArray()) paramTypes.set(paramTypes.size() - 1, last.leafType().asArray(last.arrayDimensions() - 1));
        }
        ResolvedType returnType = ResolvedType.named(mi.getTypeSignatureOrTypeDescriptor().getResultType().toString());
        return new MethodSignature(mi.getName(), paramTypes, libraryParamNames(mi), returnType, varargs);
    }

    /** A constructor signature carries the type's simple name (not {@code <init>}) and the type as its return. */
    private MethodSignature toConstructorSignature(MethodInfo mi, String className) {
        List<ResolvedType> paramTypes = libraryParamTypes(mi);
        return new MethodSignature(className, paramTypes, libraryParamNames(mi), ResolvedType.named(className));
    }

    /**
     * Real parameter names when the jar was compiled with {@code -parameters} (the BotMaker SDK is; most
     * libraries are not), else synthesized {@code arg0/arg1/…}. ClassGraph exposes the name from the
     * {@code MethodParameters} attribute, returning {@code null} when it's absent.
     */
    private List<String> libraryParamNames(MethodInfo mi) {
        var params = mi.getParameterInfo();
        List<String> names = new ArrayList<>(params.length);
        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName();
            names.add(name != null && !name.isBlank() ? name : "arg" + i);
        }
        return names;
    }

    private List<ResolvedType> libraryParamTypes(MethodInfo mi) {
        return Arrays.stream(mi.getParameterInfo())
                .map(p -> resolveLibraryType(p.getTypeSignatureOrTypeDescriptor().toString()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Resolves a bytecode type descriptor (e.g. {@code com.botmaker.sdk.api.geometry.Direction} or
     * {@code …ImageTemplate[]}) to a {@link ResolvedType}, preferring the library index so the result is
     * enum-aware ({@link ResolvedType.FromIndex}). Falls back to a name-only type for primitives / types not
     * in the index. Array/varargs suffixes are stripped and re-applied.
     */
    private ResolvedType resolveLibraryType(String descriptor) {
        int dims = 0;
        // Strip generic type arguments (…Consumer<…MatchResult> -> …Consumer) so the raw FQN resolves in the index
        // and carries a clean simple name (a functional interface is recognised by its raw name downstream).
        String base = descriptor;
        int generic = base.indexOf('<');
        if (generic >= 0) base = base.substring(0, generic);
        while (base.endsWith("[]")) { dims++; base = base.substring(0, base.length() - 2); }

        ResolvedType leaf = ResolvedType.named(base);
        if (libraryIndex != null) {
            String simple = base.contains(".") ? base.substring(base.lastIndexOf('.') + 1) : base;
            Optional<ClassInfo> ci = libraryIndex.findByQualifiedName(base);
            if (ci.isEmpty()) ci = libraryIndex.findBySimpleName(simple);
            if (ci.isPresent()) leaf = ResolvedType.of(ci.get());
        }
        return dims == 0 ? leaf : leaf.asArray(dims);
    }

    /**
     * Whether this invocation is a print sink — a call that accepts anything, so an argument slot inside it
     * constrains nothing and the caller should be offered every expression rather than a filtered list.
     *
     * <p>Recognised structurally rather than by name, because the name list was the bug: this used to match
     * only a receiver spelled exactly {@code System.out}, while the Print block emits the SDK's
     * {@code BotMaker.print(…)}. That resolved to the declared parameter type, and the method dropdown inside
     * a Print block then offered only methods returning it — for the {@code println(String)} overload, the
     * String-returning ones alone. A parameter declared as {@code Object} accepts every reference type, which
     * is the same statement "this constrains nothing" made by the language instead of by a list, and it
     * covers every future sink without naming it.
     *
     * <p>{@code System.out.print*} keeps its spelling-based clause underneath, because it is the one sink
     * that is <em>not</em> declared over {@code Object}: {@code PrintStream} overloads it per primitive plus
     * {@code String}, so a placeholder argument binds to {@code println(String)} and the general rule alone
     * would go on filtering it. Nothing else needs that treatment.
     */
    private static boolean isPrintSink(MethodInvocation invocation, IMethodBinding binding, int argumentIndex) {
        if (binding != null && argumentIndex >= 0) {
            ITypeBinding[] parameters = binding.getParameterTypes();
            if (argumentIndex < parameters.length
                    && "java.lang.Object".equals(parameters[argumentIndex].getQualifiedName())) {
                return true;
            }
        }
        String name = invocation.getName().getIdentifier();
        if (!"print".equals(name) && !"println".equals(name)) return false;
        Expression receiver = invocation.getExpression();
        return receiver != null && "System.out".equals(receiver.toString());
    }

    private static ResolvedType inferArrayTypeForElement(ArrayInitializer initializer) {
        int depth = 1;
        ASTNode current = initializer.getParent();
        Type declaredType = null;

        while (current != null) {
            if (current instanceof ArrayInitializer) { depth++; current = current.getParent(); continue; }
            if (current instanceof ArrayCreation ac) { declaredType = ac.getType(); break; }
            if (current instanceof VariableDeclarationFragment frag) {
                ASTNode gp = frag.getParent();
                if (gp instanceof VariableDeclarationStatement vds) declaredType = vds.getType();
                else if (gp instanceof FieldDeclaration fd) declaredType = fd.getType();
                break;
            }
            if (current instanceof MethodInvocation || current instanceof ClassInstanceCreation) break;
            current = current.getParent();
        }

        if (declaredType == null) return ResolvedType.UNKNOWN;
        ResolvedType rootType = resolveType(declaredType);
        int elementDimensions = rootType.arrayDimensions() - depth;
        if (elementDimensions > 0) return rootType.leafType().asArray(elementDimensions);
        if (elementDimensions == 0) return rootType.leafType();
        return ResolvedType.UNKNOWN;
    }
}
