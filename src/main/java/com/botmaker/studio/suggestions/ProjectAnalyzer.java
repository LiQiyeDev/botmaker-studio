package com.botmaker.studio.suggestions;

import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.helpers.FileTypeDetector;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
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

    private static final List<String> FUNDAMENTAL_TYPES =
            List.of("int", "double", "boolean", "String", "long", "float", "char");

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
        if (ResolvedType.PRIMITIVE_NAMES.contains(typeName)) return ResolvedType.primitive(typeName);

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

        // Common java.util fallback
        if (Set.of("List", "ArrayList", "Map", "HashMap", "Set", "HashSet", "Arrays")
                .contains(simpleClassName)) {
            return "java.util." + simpleClassName;
        }

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

    /** Lightweight view of a visible variable for menu population. */
    public record VariableOption(String name, String typeName, boolean isField) {}

    /** Lightweight view of a readable field (constant / member) of a type, for menu population. */
    public record FieldOption(String name, ResolvedType type, boolean isStatic) {}

    /**
     * Variables visible at {@code node}, optionally filtered to those assignable to
     * {@code requiredType}. Binding-backed (via {@link VariableScopeVisitor}); de-duplicated by name.
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
                results.add(new VariableOption(name, varType.simpleName(), b.isField()));
            }
        }
        return results;
    }

    /**
     * The project's activities (global config variables) whose type is assignment-compatible with
     * {@code requiredType}. Sourced from project state (not the AST), so they're available regardless of
     * scope. Used to populate the "Activities" expression submenu, which inserts {@code Activities.<name>}.
     */
    public List<com.botmaker.studio.project.activity.ActivityVariable> getActivityVariables(ResolvedType requiredType) {
        return state.getActivities().activities().stream()
                .filter(a -> isCompatible(a.type().resolvedType(), requiredType))
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
                if (isSystemOutPrint(mi)) return ResolvedType.UNKNOWN;
                int index = mi.arguments().indexOf(node);
                IMethodBinding mb = mi.resolveMethodBinding();
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
     * {@code com.botmaker.sdk.api.Point}.
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
        ResolvedType returnType = md.getReturnType2() != null ? resolveType(md.getReturnType2()) : ResolvedType.primitive("void");
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
        // Bytecode does not carry parameter names (unless compiled with -parameters), so synthesize.
        List<String> paramNames = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) paramNames.add("arg" + i);
        ResolvedType returnType = ResolvedType.named(mi.getTypeSignatureOrTypeDescriptor().getResultType().toString());
        return new MethodSignature(mi.getName(), paramTypes, paramNames, returnType, varargs);
    }

    /** A constructor signature carries the type's simple name (not {@code <init>}) and the type as its return. */
    private MethodSignature toConstructorSignature(MethodInfo mi, String className) {
        List<ResolvedType> paramTypes = libraryParamTypes(mi);
        List<String> paramNames = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) paramNames.add("arg" + i);
        return new MethodSignature(className, paramTypes, paramNames, ResolvedType.named(className));
    }

    private List<ResolvedType> libraryParamTypes(MethodInfo mi) {
        return Arrays.stream(mi.getParameterInfo())
                .map(p -> resolveLibraryType(p.getTypeSignatureOrTypeDescriptor().toString()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Resolves a bytecode type descriptor (e.g. {@code com.botmaker.sdk.api.core.Direction} or
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

    private static boolean isSystemOutPrint(MethodInvocation mi) {
        String name = mi.getName().getIdentifier();
        if ("println".equals(name) || "print".equals(name)) {
            Expression expr = mi.getExpression();
            return expr != null && "System.out".equals(expr.toString());
        }
        return false;
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