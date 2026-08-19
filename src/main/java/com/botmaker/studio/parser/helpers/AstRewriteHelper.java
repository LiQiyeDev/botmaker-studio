package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.core.BodyBlock;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.RangeMarker;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Common utilities for AST rewriting operations.
 */
public class AstRewriteHelper {

    /**
     * Applies an ASTRewrite to source code and returns the modified code.
     * @param rewriter The ASTRewrite to apply
     * @param originalCode The original source code
     * @return The modified code, or original code if rewrite fails
     */
    public static String applyRewrite(ASTRewrite rewriter, String originalCode) {
        IDocument document = new Document(originalCode);
        try {
            TextEdit edits = rewriter.rewriteAST(document, null);
            edits.apply(document);
            return document.get();
        } catch (Exception e) {
            e.printStackTrace();
            return originalCode;
        }
    }

    /**
     * Applies {@code rewriter}, then inserts {@code text} at what {@code offset} in the <em>original</em> code
     * has become — for edits that must land at a raw source position the AST cannot name.
     *
     * <p>The position is tracked with a {@link RangeMarker} added to the rewrite's own edit tree and applied
     * with {@link TextEdit#UPDATE_REGIONS}, so Eclipse shifts it for us. A plain
     * "{@code offset + (newLength - oldLength)}" delta would only be right when every edit happens to precede
     * the offset; the marker is correct wherever the other edits land.
     *
     * <p>Falls back to a plain {@link #applyRewrite} if the marker can't be attached (it would overlap an
     * edit) — better to lose the extra insertion than to corrupt the file.
     */
    public static String applyRewriteAndInsertAt(ASTRewrite rewriter, String originalCode, int offset, String text) {
        IDocument document = new Document(originalCode);
        try {
            TextEdit edits = rewriter.rewriteAST(document, null);
            RangeMarker marker = new RangeMarker(offset, 0);
            try {
                edits.addChild(marker);
            } catch (MalformedTreeException overlapping) {
                edits.apply(document);
                return document.get();
            }
            edits.apply(document, TextEdit.UPDATE_REGIONS);
            document.replace(marker.getOffset(), 0, text);
            return document.get();
        } catch (Exception e) {
            e.printStackTrace();
            return originalCode;
        }
    }

    /**
     * Removes an AST node and applies the change.
     */
    public static String removeNode(CompilationUnit cu, String originalCode, ASTNode node) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        rewriter.remove(node, null);
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * Renames a SimpleName node and applies the change.
     */
    public static String renameSimpleName(CompilationUnit cu, String originalCode,
                                          SimpleName nameNode, String newName) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.replace(nameNode, ast.newSimpleName(newName), null);
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * Renames a variable declared by an enhanced-for loop, updating the declaration <em>and</em> every
     * reference to it within the loop, so the result still compiles. {@link #renameSimpleName} replaces only
     * the single node it is handed; a loop variable also appears in the loop body, and renaming just the
     * declaration leaves those references dangling on the old name.
     *
     * <p>The walk is scoped to the enclosing {@link EnhancedForStatement} (the variable's whole scope), so a
     * same-named variable elsewhere in the method is untouched, and matches by binding key rather than by text
     * so shadowing can't misfire. Falls back to renaming the lone declaration node when the loop or the binding
     * can't be resolved (routine while a sibling file is uncompiled) — never worse than the old behaviour.
     */
    public static String renameForEachVariable(CompilationUnit cu, String originalCode,
                                               SimpleName declName, String newName) {
        EnhancedForStatement loop = enclosingEnhancedFor(declName);
        IVariableBinding target = declName.resolveBinding() instanceof IVariableBinding vb ? vb : null;
        if (loop == null || target == null) {
            return renameSimpleName(cu, originalCode, declName, newName);
        }
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        String targetKey = target.getKey();
        loop.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.resolveBinding() instanceof IVariableBinding vb && targetKey.equals(vb.getKey())) {
                    rewriter.replace(node, ast.newSimpleName(newName), null);
                }
                return true;
            }
        });
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * Renames a lambda parameter — the name chip on {@code LambdaCallBlock} — updating the declaration
     * <em>and</em> every reference to it inside the lambda body, so the body still compiles.
     *
     * <p>Unlike {@link #renameForEachVariable} this cannot lean on binding keys: an inferred-type lambda
     * parameter has a binding only once JDT resolved the target type, which in the editor is routinely
     * unavailable — and a rename that silently dropped the body references would break the user's code. So
     * the walk matches by identifier within the lambda, skipping the names that are never a variable
     * reference (a method's own name, the field of a {@code x.y} access, the tail of a qualified name) and
     * stopping at a nested lambda that redeclares the same name.
     */
    public static String renameLambdaParameter(CompilationUnit cu, String originalCode,
                                               SimpleName declName, String newName) {
        LambdaExpression lambda = enclosingLambda(declName);
        if (lambda == null) return renameSimpleName(cu, originalCode, declName, newName);

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        String oldName = declName.getIdentifier();
        rewriter.set(declName, SimpleName.IDENTIFIER_PROPERTY, newName, null);

        if (lambda.getBody() != null) {
            lambda.getBody().accept(new ASTVisitor() {
                @Override
                public boolean visit(LambdaExpression nested) {
                    // A nested lambda re-declaring the same name shadows ours — leave its subtree alone.
                    return nested.parameters().stream()
                            .map(AstRewriteHelper::lambdaParamIdentifier)
                            .noneMatch(oldName::equals);
                }

                @Override
                public boolean visit(SimpleName node) {
                    if (node.getIdentifier().equals(oldName) && isVariableReference(node)) {
                        rewriter.set(node, SimpleName.IDENTIFIER_PROPERTY, newName, null);
                    }
                    return true;
                }
            });
        }
        return applyRewrite(rewriter, originalCode);
    }

    private static String lambdaParamIdentifier(Object parameter) {
        return switch (parameter) {
            case VariableDeclarationFragment frag -> frag.getName().getIdentifier();
            case SingleVariableDeclaration svd -> svd.getName().getIdentifier();
            default -> null;
        };
    }

    /**
     * Renames a method parameter <em>inside an in-flight rewrite</em>: the declaration, plus every reference
     * to it in the method's own body. Unlike the other renamers here it neither creates the {@link ASTRewrite}
     * nor applies it, because it is one step of a whole-signature rewrite that has to land as a single edit.
     *
     * <p>Matched by identifier rather than by binding, for the reason {@link #renameLambdaParameter} gives:
     * in the editor a sibling file is routinely uncompiled and bindings are routinely absent, and a rename
     * that silently dropped the body references would break code the user did not touch.
     */
    public static void renameWithinMethod(ASTRewrite rewriter, MethodDeclaration method,
                                          SimpleName declName, String newName) {
        String oldName = declName.getIdentifier();
        rewriter.set(declName, SimpleName.IDENTIFIER_PROPERTY, newName, null);
        if (method.getBody() == null) return;

        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(LambdaExpression nested) {
                // A nested lambda re-declaring the same name shadows the parameter — leave its subtree alone.
                return nested.parameters().stream()
                        .map(AstRewriteHelper::lambdaParamIdentifier)
                        .noneMatch(oldName::equals);
            }

            @Override
            public boolean visit(SimpleName node) {
                if (node.getIdentifier().equals(oldName) && isVariableReference(node)) {
                    rewriter.set(node, SimpleName.IDENTIFIER_PROPERTY, newName, null);
                }
                return true;
            }
        });
    }

    /**
     * Renames a local variable declaration <em>and every reference to it in the same method</em>.
     *
     * <p>The same problem {@link #renameForEachVariable} solves, for an ordinary declaration: the Variables
     * screen renames a variable that is, by definition, likely to be used somewhere, and
     * {@link #renameSimpleName} on its own leaves those uses pointing at a name that no longer exists.
     * Reuses {@link #renameWithinMethod}, which already handles shadowing by a nested lambda and skips the
     * names that can't denote a variable.
     */
    public static String renameLocalVariable(CompilationUnit cu, String originalCode,
                                             SimpleName declName, String newName) {
        MethodDeclaration method = enclosingMethod(declName);
        if (method == null) return renameSimpleName(cu, originalCode, declName, newName);
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        renameWithinMethod(rewriter, method, declName, newName);
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * Every {@link SimpleName} in {@code method}'s body that reads or writes the variable {@code name} —
     * the declaration itself excluded. What "is this variable used?" means to the Variables screen, which
     * refuses a delete that would leave those uses dangling and says how many there are.
     */
    public static List<SimpleName> referencesWithin(MethodDeclaration method, SimpleName declName) {
        List<SimpleName> found = new ArrayList<>();
        if (method == null || method.getBody() == null) return found;
        String name = declName.getIdentifier();
        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node != declName && node.getIdentifier().equals(name) && isVariableReference(node)) {
                    found.add(node);
                }
                return true;
            }
        });
        return found;
    }

    /** The {@link MethodDeclaration} {@code node} sits in, or null when it sits outside one. */
    public static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) return method;
        }
        return null;
    }

    /** False for the names that can never denote a variable: {@code foo()}, {@code x.foo}, {@code a.b}. */
    private static boolean isVariableReference(SimpleName node) {
        StructuralPropertyDescriptor location = node.getLocationInParent();
        return location != MethodInvocation.NAME_PROPERTY
                && location != FieldAccess.NAME_PROPERTY
                && location != SuperFieldAccess.NAME_PROPERTY
                && location != QualifiedName.NAME_PROPERTY
                && location != MethodDeclaration.NAME_PROPERTY;
    }

    private static LambdaExpression enclosingLambda(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof LambdaExpression lambda) return lambda;
        }
        return null;
    }

    private static EnhancedForStatement enclosingEnhancedFor(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof EnhancedForStatement efs) return efs;
        }
        return null;
    }

    /**
     * Returns the statement {@link ListRewrite} for a body block, whether it is backed by a
     * {@link Block} or a {@link SwitchCase}.
     */
    public static ListRewrite getListRewriteForBody(ASTRewrite rewriter, BodyBlock body) {
        ASTNode node = body.getAstNode();
        if (node instanceof Block) {
            return rewriter.getListRewrite(node, Block.STATEMENTS_PROPERTY);
        } else if (node instanceof SwitchCase) {
            return rewriter.getListRewrite(node.getParent(), SwitchStatement.STATEMENTS_PROPERTY);
        }
        throw new IllegalArgumentException("Unsupported body node type: " + node.getClass());
    }
}
