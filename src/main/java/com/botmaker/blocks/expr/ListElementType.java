package com.botmaker.blocks.expr;

import com.botmaker.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;

/**
 * Pure inference of a list/array's <em>element</em> type from the AST, given the list node
 * (an {@link ArrayInitializer}, a {@code List.of(...)}/{@code Arrays.asList(...)} {@link MethodInvocation},
 * or a {@code new ArrayList<>(...)} {@link ClassInstanceCreation}).
 *
 * <p>Two shapes are handled:
 * <ul>
 *   <li><b>Arrays</b> — the declared type (e.g. {@code String[][]}) carries the dimension count; the element
 *       type at this node is the leaf with {@code declaredDims - nestingDepth} dimensions, so the outer
 *       initializer of a 2-D array yields {@code String[]} and its nested initializers yield {@code String}.</li>
 *   <li><b>Generics</b> — a {@code List<T>}/{@code Collection<T>} declaration yields its first type argument
 *       {@code T}.</li>
 * </ul>
 *
 * <p>Kept side-effect-free and package-visible so it can be unit-tested directly against parsed sources.
 */
final class ListElementType {

    private ListElementType() {}

    /** Best-effort element type for {@code listNode}, or {@link ResolvedType#UNKNOWN} if it can't be inferred. */
    static ResolvedType of(ASTNode listNode) {
        Declared declared = findDeclared(listNode);
        if (declared == null) return ResolvedType.UNKNOWN;

        // Generic collection: element is the first type argument.
        if (declared.typeArgument != null) {
            return declared.typeArgument;
        }

        ResolvedType collectionType = declared.type;
        if (collectionType == null || collectionType.isUnknown()) return ResolvedType.UNKNOWN;

        int declaredDims = collectionType.arrayDimensions();
        if (declaredDims == 0) {
            // Non-array, non-parameterized declared type (e.g. a raw List) — nothing precise to offer.
            return ResolvedType.UNKNOWN;
        }

        int depth = nestingDepth(listNode, declared.anchor);
        int elementDims = declaredDims - depth;
        ResolvedType leaf = collectionType.leafType();
        return elementDims > 0 ? leaf.asArray(elementDims) : leaf;
    }

    /** Number of {@link ArrayInitializer} levels from {@code listNode} (inclusive) up to, but excluding, {@code anchor}. */
    private static int nestingDepth(ASTNode listNode, ASTNode anchor) {
        int depth = 0;
        ASTNode current = listNode;
        while (current != null && current != anchor) {
            if (current instanceof ArrayInitializer) depth++;
            current = current.getParent();
        }
        return depth;
    }

    /** Walks up from {@code node} to the point that declares the collection's type. */
    private static Declared findDeclared(ASTNode node) {
        ASTNode current = node;
        while (current != null) {
            ASTNode parent = current.getParent();
            if (parent == null) return null;

            // new int[][]{ ... } — the ArrayCreation carries the full array type.
            if (parent instanceof ArrayCreation ac) {
                return fromType(ac.getType(), ac);
            }

            // Type x = { ... } / Type x = List.of(...) — read the variable/field's declared type.
            if (parent instanceof VariableDeclarationFragment frag && frag.getInitializer() == current) {
                Type type = declaredTypeOf(frag);
                if (type != null) return fromType(type, frag);
            }

            // foo({ ... }) / foo(List.of(...)) — use the resolved parameter type.
            if (parent instanceof MethodInvocation mi) {
                int argIndex = mi.arguments().indexOf(current);
                if (argIndex >= 0) {
                    IMethodBinding binding = mi.resolveMethodBinding();
                    if (binding != null && argIndex < binding.getParameterTypes().length) {
                        return fromBinding(binding.getParameterTypes()[argIndex], mi);
                    }
                }
            }

            current = parent;
        }
        return null;
    }

    private static Type declaredTypeOf(VariableDeclarationFragment frag) {
        ASTNode gp = frag.getParent();
        if (gp instanceof VariableDeclarationStatement vds) return vds.getType();
        if (gp instanceof FieldDeclaration fd) return fd.getType();
        if (gp instanceof VariableDeclarationExpression vde) return vde.getType();
        return null;
    }

    /** Builds a {@link Declared} from a JDT {@link Type} node, recognising both array and generic shapes. */
    private static Declared fromType(Type type, ASTNode anchor) {
        if (type instanceof ParameterizedType pt) {
            ResolvedType arg = firstTypeArgument(pt);
            if (arg != null) return new Declared(null, anchor, arg);
        }
        ITypeBinding binding = type.resolveBinding();
        if (binding != null) return fromBinding(binding, anchor);
        return new Declared(ResolvedType.named(type.toString()), anchor, null);
    }

    private static Declared fromBinding(ITypeBinding binding, ASTNode anchor) {
        if (binding == null) return null;
        ITypeBinding[] typeArgs = binding.getTypeArguments();
        if (typeArgs != null && typeArgs.length > 0 && !binding.isArray()) {
            return new Declared(null, anchor, ResolvedType.of(typeArgs[0]));
        }
        return new Declared(ResolvedType.of(binding), anchor, null);
    }

    private static ResolvedType firstTypeArgument(ParameterizedType pt) {
        if (pt.typeArguments().isEmpty()) return null;
        Type arg = (Type) pt.typeArguments().getFirst();
        ITypeBinding binding = arg.resolveBinding();
        return binding != null ? ResolvedType.of(binding) : ResolvedType.named(arg.toString());
    }

    /**
     * A resolved collection declaration: either an array {@code type} (with dimensions) plus the {@code anchor}
     * node where nesting-depth counting stops, or — for generics — a non-null {@code typeArgument}.
     */
    private record Declared(ResolvedType type, ASTNode anchor, ResolvedType typeArgument) {}
}
