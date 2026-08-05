package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.ui.render.components.pickers.ImageTemplateGroupPicker;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.List;
import java.util.Set;

/**
 * Which templates a {@code Matches} value can possibly contain — the narrowing behind the switch's chip menus.
 *
 * <p>A {@code Matches} only ever holds matches for the group its enclosing find call was given, so offering the
 * whole project's template library in a case's {@code ＋} menu lets the user write a branch that is dead by
 * construction. Walking out to that call and reading its group argument turns the menu into the closed set it
 * always was.
 *
 * <p><b>Unresolvable means unrestricted, never empty.</b> A group built at runtime, a switch with no enclosing
 * find call, or a constant declared in another file all yield {@code null} — "no restriction" — because a menu
 * offering nothing is strictly worse than one offering too much: it makes the block unusable rather than merely
 * permissive.
 */
public final class MatchesGroupScope {

    /** The find calls that hand a lambda a {@code Matches}. Their group is always the first argument. */
    private static final Set<String> GROUP_LAMBDA_CALLS =
            Set.of("ifFindAny", "whileFindAny", "ifFindAll", "whileFindAll");

    private MatchesGroupScope() {}

    /**
     * The template paths reachable from {@code node}'s enclosing find call, or {@code null} when there is no
     * restriction to apply.
     */
    public static List<String> allowedPaths(ASTNode node) {
        MethodInvocation call = enclosingGroupCall(node);
        if (call == null || call.arguments().isEmpty()) return null;
        return groupPaths((Expression) call.arguments().getFirst());
    }

    /** The nearest enclosing {@code ImageFinder.whileFindAny(group, found -> …)}-shaped call, or null. */
    private static MethodInvocation enclosingGroupCall(ASTNode node) {
        for (ASTNode current = node; current != null; current = current.getParent()) {
            // Anchored on the lambda, not on any enclosing invocation: a find call nested somewhere in the
            // body is not what produced this Matches, and would narrow to the wrong group.
            if (current instanceof LambdaExpression lambda
                    && lambda.getParent() instanceof MethodInvocation call
                    && GROUP_LAMBDA_CALLS.contains(call.getName().getIdentifier())) {
                return call;
            }
        }
        return null;
    }

    /** The paths of a group argument, whether written inline or held in a constant. */
    private static List<String> groupPaths(Expression group) {
        List<String> inline = ImageTemplateGroupPicker.currentPaths(group);
        if (!inline.isEmpty()) return inline;
        if (group instanceof SimpleName name) {
            Expression initializer = constantInitializer(name);
            if (initializer != null) {
                List<String> paths = ImageTemplateGroupPicker.currentPaths(initializer);
                if (!paths.isEmpty()) return paths;
            }
        }
        return null;
    }

    /** The initializer of the field {@code name} refers to, resolved within the file being edited. */
    private static Expression constantInitializer(SimpleName name) {
        IBinding binding = name.resolveBinding();
        if (binding != null && name.getRoot() instanceof CompilationUnit cu
                && cu.findDeclaringNode(binding) instanceof VariableDeclarationFragment fragment) {
            return fragment.getInitializer();
        }
        // No binding is the ordinary case for a file that hasn't resolved yet, so fall back to a name match
        // over the enclosing type's own fields rather than giving up on the narrowing.
        return declaredInSameFile(name);
    }

    private static Expression declaredInSameFile(SimpleName name) {
        if (!(name.getRoot() instanceof CompilationUnit cu)) return null;
        Expression[] found = {null};
        cu.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration node) {
                for (Object o : node.fragments()) {
                    if (o instanceof VariableDeclarationFragment f
                            && f.getName().getIdentifier().equals(name.getIdentifier())) {
                        found[0] = f.getInitializer();
                    }
                }
                return true;
            }
        });
        return found[0];
    }
}
