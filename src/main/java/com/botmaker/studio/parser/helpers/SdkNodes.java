package com.botmaker.studio.parser.helpers;

import com.botmaker.sdk.api.authoring.TemplateNames;
import com.botmaker.studio.palette.SdkType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.Type;

import java.util.Optional;

/**
 * The bridge between {@link SdkType} and the JDT nodes that name it — so a rewrite writes
 * {@code SdkNodes.type(ast, SdkType.IMAGE_TEMPLATE)} rather than
 * {@code ast.newSimpleType(ast.newSimpleName("ImageTemplate"))}.
 *
 * <p>{@code SdkType} exists to make the SDK surface compiler-checked, but the write path bypassed it: the
 * simple name {@code "ImageTemplate"} alone was spelled at thirteen sites across eight files, and the
 * <em>qualified</em> names were hand-written in two more, where the enum computes them from a class literal.
 * A renamed SDK class silently produced source that no longer compiled; now it fails this module's build.
 *
 * <p>Deliberately not on {@code SdkType} itself: {@code palette} is dependency-light on purpose (its only
 * imports are the SDK's own classes), and pulling JDT into it would make the catalog package depend on the
 * editor's AST library. Deliberately not on {@code EditContext} either — half these call sites hold a bare
 * {@link AST} and build their own rewriter.
 */
public final class SdkNodes {

    private SdkNodes() {}

    /** {@code ImageFinder} as an expression name — the receiver of a static facade call. */
    public static SimpleName name(AST ast, SdkType type) {
        return ast.newSimpleName(type.simpleName());
    }

    /** {@code ImageTemplate} as a type reference — a declared type, or a {@code new T(…)}'s type. */
    public static Type type(AST ast, SdkType type) {
        return ast.newSimpleType(name(ast, type));
    }

    /**
     * The fully-qualified name, for the rare reference that must resolve with no import —
     * {@code com.botmaker.sdk.api.capture.CaptureSource.desktop()}.
     */
    public static Name qualifiedName(AST ast, SdkType type) {
        return ast.newName(type.qualifiedName());
    }

    /** {@code new T(args…)} with int-literal arguments — {@code new Point(x, y)}, {@code new Rect(…)}. */
    public static ClassInstanceCreation intCtor(AST ast, SdkType type, int... args) {
        ClassInstanceCreation cic = ast.newClassInstanceCreation();
        cic.setType(type(ast, type));
        for (int value : args) {
            cic.arguments().add(ast.newNumberLiteral(Integer.toString(value)));
        }
        return cic;
    }

    /** Whether {@code call} is a static call on {@code facade} — {@code Wait.time(…)}, {@code ImageFinder.find(…)}. */
    public static boolean isCallOn(MethodInvocation call, SdkType facade) {
        Expression receiver = call == null ? null : call.getExpression();
        return receiver != null && namesType(receiver.toString(), facade);
    }

    /**
     * Whether {@code node} is a {@code new T(…)} of {@code type}. Matches the type node's source text, which is
     * how the source was written: an unresolved file has no binding to ask, and the pickers that read these
     * back run at edit time, before any compile.
     */
    public static boolean isInstantiationOf(Object node, SdkType type) {
        return node instanceof ClassInstanceCreation cic
                && cic.getType() != null
                && namesType(cic.getType().toString(), type);
    }

    /** The source text {@code written} names {@code type}, whether the file imported it or qualified it. */
    private static boolean namesType(String written, SdkType type) {
        return written.equals(type.simpleName()) || written.endsWith("." + type.simpleName());
    }

    // --- the argument of a new ImageTemplate(…) -------------------------------------------------------
    //
    // Two spellings, one meaning. `Templates.YTUJ` is what Studio writes now — the path declared once in the
    // generated constants class instead of repeated at every use site — and `"src/main/resources/images/…"`
    // is what it wrote before and what a hand-written bot may still say. Both are read here, in one place,
    // so no picker has to know there are two; and both round-trip, so opening an old project and editing one
    // block doesn't rewrite the others.

    /**
     * The project-relative path a template argument names — the literal itself, or the path the
     * {@code Templates} constant stands for. Empty for anything else: a variable, a field or a call is a
     * reference the pickers cannot represent and must not overwrite.
     */
    public static Optional<String> templatePathOf(Object argument) {
        if (argument instanceof StringLiteral literal) {
            return Optional.of(literal.getLiteralValue());
        }
        if (argument instanceof QualifiedName qualified
                && TemplateNames.CLASS_NAME.equals(qualified.getQualifier().toString())) {
            return Optional.ofNullable(TemplateNames.pathForConstant(qualified.getName().getIdentifier()));
        }
        return Optional.empty();
    }

    /** {@link #templatePathOf} applied to the first argument of a {@code new ImageTemplate(…)}. */
    public static Optional<String> imageTemplatePathOf(Object node) {
        if (isInstantiationOf(node, SdkType.IMAGE_TEMPLATE)
                && node instanceof ClassInstanceCreation cic
                && !cic.arguments().isEmpty()) {
            return templatePathOf(cic.arguments().getFirst());
        }
        return Optional.empty();
    }

    /**
     * How to write {@code path} as the argument of a {@code new ImageTemplate(…)}: {@code Templates.YTUJ} when
     * the template has a constant, the raw path otherwise. The fallback is what keeps a template named before
     * the lowercase-identifier rule editable — it has no constant to name it by, so it keeps its literal.
     */
    public static Expression templateArgument(AST ast, String path) {
        String constant = TemplateNames.constantForPath(path);
        if (constant != null) {
            return ast.newQualifiedName(ast.newSimpleName(TemplateNames.CLASS_NAME),
                    ast.newSimpleName(constant));
        }
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(path == null ? "" : path);
        return literal;
    }
}
