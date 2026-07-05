package com.botmaker.studio.util;

import org.eclipse.jdt.core.dom.*;

import java.util.*;

public class VariableScopeVisitor extends ASTVisitor {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private final Deque<Map<String, IVariableBinding>> variableStack = new ArrayDeque<>();
    private final Deque<Map<String, IMethodBinding>>   methodStack   = new ArrayDeque<>();
    private final Deque<Map<String, ITypeBinding>>     typeStack     = new ArrayDeque<>();

    private final Map<ASTNode, List<IVariableBinding>> variableResult = new LinkedHashMap<>();
    private final Map<ASTNode, List<IMethodBinding>>   methodResult   = new LinkedHashMap<>();
    private final Map<ASTNode, List<ITypeBinding>>     typeResult     = new LinkedHashMap<>();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Full-file analysis — returns a holder with all three maps. */
    public static ScopeResult analyze(CompilationUnit cu) {
        VariableScopeVisitor v = new VariableScopeVisitor();
        cu.accept(v);
        return new ScopeResult(
                Collections.unmodifiableMap(v.variableResult),
                Collections.unmodifiableMap(v.methodResult),
                Collections.unmodifiableMap(v.typeResult)
        );
    }

    /** Variables in scope at the given node. */
    public static List<IVariableBinding> getAvailableVariables(ASTNode node) {
        return getScopeAt(node).variables();
    }

    /** Methods in scope at the given node. */
    public static List<IMethodBinding> getAvailableMethods(ASTNode node) {
        return getScopeAt(node).methods();
    }

    /** Types in scope at the given node. */
    public static List<ITypeBinding> getAvailableTypes(ASTNode node) {
        return getScopeAt(node).types();
    }

    public static List<IVariableBinding> getAvailableVariables(ASTNode node, ITypeBinding type) {
        return getAvailableVariables(node).stream()
                .filter(b -> b.getType().isAssignmentCompatible(type))
                .toList();
    }

    public static List<IMethodBinding> getAvailableMethods(ASTNode node, ITypeBinding returnType) {
        return getAvailableMethods(node).stream()
                .filter(b -> b.getReturnType().isAssignmentCompatible(returnType))
                .toList();
    }

    /** Result holder — all three scopes for a given node. */
    public record NodeScope(
            List<IVariableBinding> variables,
            List<IMethodBinding>   methods,
            List<ITypeBinding>     types
    ) {}

    /** All three maps from a full-file analysis. */
    public record ScopeResult(
            Map<ASTNode, List<IVariableBinding>> variables,
            Map<ASTNode, List<IMethodBinding>>   methods,
            Map<ASTNode, List<ITypeBinding>>     types
    ) {
        public NodeScope at(ASTNode node) {
            return new NodeScope(
                    variables.getOrDefault(node, List.of()),
                    methods.getOrDefault(node, List.of()),
                    types.getOrDefault(node, List.of())
            );
        }
    }

    // -----------------------------------------------------------------------
    // Scope boundaries — push on enter, pop on leave
    // -----------------------------------------------------------------------
    @Override
    public boolean visit(CompilationUnit node) {
        // Imports live above all type scopes — push a permanent bottom-level scope
        pushAll();
        return true;
    }

    @Override
    public void endVisit(CompilationUnit node) { popAll(); }

    @Override
    public boolean visit(ImportDeclaration node) {
        if (node.isStatic()) {
            IBinding b = node.resolveBinding();
            if (b instanceof IMethodBinding mb && !methodStack.isEmpty())
                methodStack.peek().put(methodKey(mb), mb);
        } else {
            IBinding b = node.resolveBinding();
            if (b instanceof ITypeBinding tb && !typeStack.isEmpty())
                typeStack.peek().put(tb.getName(), tb);
        }
        return false;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        pushAll();

        // Register the type itself first — so it's visible to everything inside it
        ITypeBinding self = node.resolveBinding();
        if (self != null) typeStack.peek().put(self.getName(), self);

        // Inner types
        for (Object body : node.bodyDeclarations())
            if (body instanceof AbstractTypeDeclaration atd) {
                ITypeBinding tb = atd.resolveBinding();
                if (tb != null) typeStack.peek().put(tb.getName(), tb);
            }

        // Methods declared here (visible regardless of order)
        for (MethodDeclaration m : node.getMethods()) {
            IMethodBinding mb = m.resolveBinding();
            if (mb != null) methodStack.peek().put(methodKey(mb), mb);
        }

        // Fields (visible throughout the class regardless of declaration order)
        for (FieldDeclaration f : node.getFields())
            for (Object frag : f.fragments())
                if (frag instanceof VariableDeclarationFragment vdf) {
                    IVariableBinding vb = (IVariableBinding) vdf.getName().resolveBinding();
                    if (vb != null) variableStack.peek().put(vdf.getName().getIdentifier(), vb);
                }

        // Inherited members
        ITypeBinding typeBinding = node.resolveBinding();
        if (typeBinding != null) {
            collectInheritedMethods(typeBinding.getSuperclass());
            for (ITypeBinding iface : typeBinding.getInterfaces())
                collectInheritedMethods(iface);
        }

        return true;
    }



    @Override public void endVisit(TypeDeclaration node) { popAll(); }

    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        pushAll();
        for (Object decl : node.bodyDeclarations()) {
            if (decl instanceof MethodDeclaration m) {
                IMethodBinding mb = m.resolveBinding();
                if (mb != null) methodStack.peek().put(methodKey(mb), mb);
            }
        }
        ITypeBinding tb = node.resolveBinding();
        if (tb != null && tb.getSuperclass() != null)
            collectInheritedMethods(tb.getSuperclass());
        return true;
    }

    @Override public void endVisit(AnonymousClassDeclaration node) { popAll(); }

    @Override public boolean visit(MethodDeclaration node) { pushAll(); return true; }
    @Override public void endVisit(MethodDeclaration node) { popAll(); }

    @Override public boolean visit(LambdaExpression node)  { pushAll(); return true; }
    @Override public void endVisit(LambdaExpression node)  { popAll(); }

    @Override public boolean visit(Block node)             { pushAll(); return true; }
    @Override public void endVisit(Block node)             { popAll(); }

    @Override public boolean visit(SwitchStatement node)   { pushAll(); return true; }
    @Override public void endVisit(SwitchStatement node)   { popAll(); }

    @Override public boolean visit(CatchClause node)       { pushAll(); return true; }
    @Override public void endVisit(CatchClause node)       { popAll(); }

    // -----------------------------------------------------------------------
    // Declarations — endVisit so RHS doesn't see its own symbol
    // -----------------------------------------------------------------------

    @Override
    public void endVisit(VariableDeclarationFragment node) {
        declareVariable(node.getName());
    }

    @Override
    public void endVisit(SingleVariableDeclaration node) {
        declareVariable(node.getName());
    }

    @Override
    public void endVisit(TypeDeclarationStatement node) {
        // Local class declared inside a block
        ITypeBinding tb = node.getDeclaration().resolveBinding();
        if (tb != null && !typeStack.isEmpty())
            typeStack.peek().put(tb.getName(), tb);
    }

    // -----------------------------------------------------------------------
    // Expressions of interest — snapshot all three stacks
    // -----------------------------------------------------------------------

    @Override
    public boolean visit(SimpleName node) {
        if (node.resolveBinding() instanceof IVariableBinding) record(node);
        return true;
    }

    @Override public boolean visit(MethodInvocation node)      { record(node); return true; }
    @Override public boolean visit(Assignment node)            { record(node); return true; }
    @Override public boolean visit(ClassInstanceCreation node) { record(node); return true; }
    @Override public boolean visit(ConditionalExpression node) { record(node); return true; }

    // -----------------------------------------------------------------------
    // Stack helpers
    // -----------------------------------------------------------------------

    private void pushAll() {
        variableStack.push(new LinkedHashMap<>());
        methodStack.push(new LinkedHashMap<>());
        typeStack.push(new LinkedHashMap<>());
    }

    private void popAll() {
        if (!variableStack.isEmpty()) variableStack.pop();
        if (!methodStack.isEmpty())   methodStack.pop();
        if (!typeStack.isEmpty())     typeStack.pop();
    }


    @Override
    public boolean visit(RecordDeclaration node) {
        pushAll();
        ITypeBinding self = node.resolveBinding();
        if (self != null) {
            assert typeStack.peek() != null;
            typeStack.peek().put(self.getName(), self);
        }

        for (MethodDeclaration m : node.getMethods()) {
            IMethodBinding mb = m.resolveBinding();
            if (mb != null) {
                assert methodStack.peek() != null;
                methodStack.peek().put(methodKey(mb), mb);
            }
        }
        // record components are the constructor params — treat as fields
        for (Object comp : node.recordComponents())
            if (comp instanceof SingleVariableDeclaration svd) {
                IVariableBinding vb = (IVariableBinding) svd.getName().resolveBinding();
                if (vb != null) {
                    assert variableStack.peek() != null;
                    variableStack.peek().put(svd.getName().getIdentifier(), vb);
                }
            }

        ITypeBinding tb = node.resolveBinding();
        if (tb != null) collectInheritedMethods(tb.getSuperclass());

        return true;
    }

    @Override public void endVisit(RecordDeclaration node) { popAll(); }

    private void declareVariable(SimpleName name) {
        if (variableStack.isEmpty()) return;
        IBinding b = name.resolveBinding();
        if (b instanceof IVariableBinding vb)
            variableStack.peek().put(name.getIdentifier(), vb);
    }

    private void collectInheritedMethods(ITypeBinding type) {
        if (type == null || methodStack.isEmpty()) return;
        for (IMethodBinding m : type.getDeclaredMethods())
            methodStack.peek().put(methodKey(m), m);
        collectInheritedMethods(type.getSuperclass());
    }

    protected void record(ASTNode node) {
        variableResult.put(node, snapshotVariables());
        methodResult.put(node, snapshotMethods());
        typeResult.put(node, snapshotTypes());
    }

    protected List<IVariableBinding> snapshotVariables() {
        return variableStack.stream()
                .flatMap(s -> s.values().stream())
                .filter(Objects::nonNull)
                .toList();
    }

    protected List<IMethodBinding> snapshotMethods() {
        return methodStack.stream()
                .flatMap(s -> s.values().stream())
                .filter(Objects::nonNull)
                .toList();
    }

    protected List<ITypeBinding> snapshotTypes() {
        return typeStack.stream()
                .flatMap(s -> s.values().stream())
                .filter(Objects::nonNull)
                .toList();
    }

    /** Unique key for a method: name + param types (handles overloads). */
    private static String methodKey(IMethodBinding m) {
        return m.getName() + Arrays.stream(m.getParameterTypes())
                .map(ITypeBinding::getName)
                .reduce("(", (a, b) -> a + (a.equals("(") ? "" : ",") + b) + ")";
    }

    // -----------------------------------------------------------------------
    // Single-node shortcut — runs a targeted traversal and stops after target
    // -----------------------------------------------------------------------

    private static final NodeScope EMPTY_SCOPE = new NodeScope(List.of(), List.of(), List.of());

    private static NodeScope getScopeAt(ASTNode node) {
        CompilationUnit cu = (CompilationUnit) node.getRoot();
        if (cu == null) return EMPTY_SCOPE;

        // Capture the live scope stacks the instant traversal reaches the target node — independent of the
        // node's type. preVisit(node) fires after every ancestor scope has been pushed and after the
        // endVisit of any preceding-sibling declaration, so the snapshot is exactly what is in scope at the
        // node (and excludes a variable from its own initializer). Using record() instead would only answer
        // for the handful of "trigger" expression kinds, missing literals / placeholders / statements.
        NodeScope[] captured = { null };
        VariableScopeVisitor visitor = new VariableScopeVisitor() {
            @Override
            public void preVisit(ASTNode candidate) {
                if (captured[0] == null && candidate == node) {
                    captured[0] = new NodeScope(snapshotVariables(), snapshotMethods(), snapshotTypes());
                }
            }
        };

        cu.accept(visitor);
        NodeScope scope = captured[0] != null ? captured[0] : EMPTY_SCOPE;

        // A declaration's own variable is not in scope inside its initializer. Locals are already excluded
        // (declared on endVisit), but fields are pre-registered for the whole type, so drop the enclosing
        // declaration's own name explicitly.
        String selfName = enclosingDeclaredName(node);
        if (selfName != null && !scope.variables().isEmpty()) {
            List<IVariableBinding> filtered = scope.variables().stream()
                    .filter(v -> !selfName.equals(v.getName()))
                    .toList();
            scope = new NodeScope(filtered, scope.methods(), scope.types());
        }
        return scope;
    }

    /** Name declared by the nearest enclosing variable/field declaration (or {@code null} if none on the path). */
    private static String enclosingDeclaredName(ASTNode node) {
        ASTNode cur = node;
        while (cur != null) {
            if (cur instanceof VariableDeclarationFragment f) return f.getName().getIdentifier();
            if (cur instanceof SingleVariableDeclaration s) return s.getName().getIdentifier();
            if (cur instanceof Statement || cur instanceof BodyDeclaration) break;
            cur = cur.getParent();
        }
        return null;
    }
}