package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.VisionLoop;
import com.botmaker.studio.ui.render.components.pickers.ImageTemplateGroupPicker;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * The find calls that hand a lambda a {@code Matches}. Their group is always the first argument.
     *
     * <p>Derived from {@link VisionLoop}, not listed: this used to be a hand-written
     * {@code Set.of("ifFindAny", "whileFindAny", "ifFindAll", "whileFindAll")} beside the dropdown's own table
     * of all nine forms, so a tenth helper had two places to be added and only one of them was obvious.
     */
    private static final Set<String> GROUP_LAMBDA_CALLS = Arrays.stream(VisionLoop.values())
            .filter(VisionLoop::handsOverMatches)
            .map(VisionLoop::methodName)
            .collect(Collectors.toUnmodifiableSet());

    private MatchesGroupScope() {}

    /**
     * Whether {@code method} is one of the find calls that hand their lambda a {@code Matches} — the set whose
     * body is worth seeding with a combination switch. {@code ifFind}/{@code whileFind} hand over a single
     * {@code MatchResult}, which has no combination to test, and {@code untilFind…} loop <em>until</em>
     * something is found and hand over nothing at all. Exposed rather than duplicated because
     * {@code LambdaCallHandler} asks the same question from the writing side.
     */
    public static boolean isGroupLambdaCall(String method) {
        return GROUP_LAMBDA_CALLS.contains(method);
    }

    /**
     * The template <b>paths</b> reachable from {@code node}'s enclosing find call, or {@code null}.
     *
     * <p>What is left of this after the chip row moved to the plugin: it has one caller, {@code
     * StatementFactory}, which is <em>generating</em> a seeded switch and needs a path to write into
     * {@code new ImageTemplate(…)}. That is emission, and emission is where the host still spells the SDK's
     * API. The narrowing question — which pictures a branch may offer — is {@link #allowedSources}, and the
     * two are deliberately separate answers rather than one shape stretched over both.
     */
    public static List<String> allowedPaths(ASTNode node) {
        MethodInvocation call = enclosingGroupCall(node);
        if (call == null || call.arguments().isEmpty()) return null;
        return groupPaths((Expression) call.arguments().getFirst());
    }

    /**
     * The <b>element sources</b> reachable from {@code node}'s enclosing find call, or {@code null} when there
     * is no restriction to apply — what {@link com.botmaker.plugin.api.SlotRun#allowed()} hands a plugin.
     *
     * <p>Java source rather than decoded template paths, and that is the whole point of it. Listing the
     * arguments of the group this branch narrows against is a <em>syntactic</em> operation: it needs no idea
     * what a picture is, which is what lets the host narrow a set of values whose meaning belongs to a plugin.
     * The plugin, which does know, decodes them itself. {@link #groupPaths} still answers in paths for
     * {@code LambdaCallHandler}, which is generating source rather than narrowing a menu — two questions, two
     * answers, rather than one shape stretched over both.
     */
    public static List<String> allowedSources(ASTNode node) {
        MethodInvocation call = enclosingGroupCall(node);
        if (call == null || call.arguments().isEmpty()) return null;
        return groupSources((Expression) call.arguments().getFirst());
    }

    /**
     * The argument expressions of a find call's leading image argument, as written, or {@code null}.
     *
     * <p>The same three shapes {@link #groupPaths} accepts — an inline {@code ImageTemplateGroup.of(…)}, a
     * constant holding one, and a bare single template — read without decoding any of them.
     */
    private static List<String> groupSources(Expression group) {
        List<String> inline = elementSources(group);
        if (inline != null) return inline;
        if (group instanceof SimpleName name) {
            Expression initializer = constantInitializer(name);
            if (initializer != null) return elementSources(initializer);
        }
        return null;
    }

    /** {@code of(a, b)} as {@code [a, b]}; a lone expression as itself; anything else as {@code null}. */
    private static List<String> elementSources(Expression group) {
        if (group instanceof MethodInvocation mi && "of".equals(mi.getName().getIdentifier())) {
            List<String> out = new ArrayList<>();
            for (Object argument : mi.arguments()) out.add(argument.toString());
            return out.isEmpty() ? null : out;
        }
        // The pre-conversion shape of a whileFind becoming a whileFindAny: one template, not a group yet.
        return group instanceof ClassInstanceCreation ? List.of(group.toString()) : null;
    }

    /**
     * The name of the {@code Matches} value in scope at {@code node} — the enclosing find call's lambda
     * parameter — or {@code null} when there is no such call.
     *
     * <p>Taken from the lambda rather than from a type lookup on purpose. Studio does not compile against the
     * SDK, so a lambda parameter's inferred type routinely resolves to nothing at edit time and a search for
     * "a variable of type {@code Matches}" comes back empty in exactly the place the answer is certain: the
     * parameter of a {@code whileFindAny}-shaped call <em>is</em> the {@code Matches}, by the signature. The
     * symptom when this was a type lookup was a switch inserted over {@code null}.
     */
    public static String matchesVariable(ASTNode node) {
        MethodInvocation call = enclosingGroupCall(node);
        if (call == null) return null;
        for (Object arg : call.arguments()) {
            if (arg instanceof LambdaExpression lambda && lambda.parameters().size() == 1) {
                Object parameter = lambda.parameters().getFirst();
                if (parameter instanceof VariableDeclarationFragment fragment) {
                    return fragment.getName().getIdentifier();
                }
                if (parameter instanceof SingleVariableDeclaration declared) {
                    return declared.getName().getIdentifier();
                }
            }
        }
        return null;
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

    /**
     * The template paths a find call's leading image argument names, or {@code null} when it names none that
     * can be read from source.
     *
     * <p>The single owner of "what images can this call produce?", which is asked from two directions: the
     * chip narrowing here, and {@code LambdaCallHandler} seeding a group form's body with the first of them.
     * It accepts all three shapes that argument takes — an inline {@code ImageTemplateGroup.of(…)}, a constant
     * holding one, and a bare {@code new ImageTemplate("…")} from the single-template form being converted.
     */
    public static List<String> groupPaths(Expression group) {
        List<String> inline = ImageTemplateGroupPicker.currentPaths(group);
        if (!inline.isEmpty()) return inline;

        // A single template, i.e. the pre-conversion shape of a `whileFind` becoming a `whileFindAny`.
        String single = ImageTemplateGroupPicker.templatePath(group).orElse(null);
        if (single != null) return List.of(single);

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
