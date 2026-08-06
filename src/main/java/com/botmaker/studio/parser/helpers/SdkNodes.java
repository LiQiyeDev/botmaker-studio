package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.palette.SdkType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;

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
}
