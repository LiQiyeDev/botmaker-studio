package com.botmaker.studio.parser.helpers;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;

/**
 * The bridge between an SDK class and the JDT nodes that name it — so a rewrite writes
 * {@code SdkNodes.type(ast, ImageTemplate.class)} rather than
 * {@code ast.newSimpleType(ast.newSimpleName("ImageTemplate"))}.
 *
 * <p>The class literal is what makes the write path compiler-checked. Before it, the simple name
 * {@code "ImageTemplate"} was spelled at thirteen sites across eight files and the <em>qualified</em> names
 * were hand-written in two more, so a renamed SDK class silently produced source that no longer compiled;
 * now it fails this module's build.
 *
 * <p>Deliberately not on {@code EditContext}: half these call sites hold a bare {@link AST} and build their
 * own rewriter.
 */
public final class SdkNodes {

    private SdkNodes() {}

    /** {@code ImageFinder} as an expression name — the receiver of a static facade call. */
    public static SimpleName name(AST ast, Class<?> type) {
        return ast.newSimpleName(type.getSimpleName());
    }

    /** {@code ImageTemplate} as a type reference — a declared type, or a {@code new T(…)}'s type. */
    public static Type type(AST ast, Class<?> type) {
        return ast.newSimpleType(name(ast, type));
    }

    /**
     * The fully-qualified name, for the rare reference that must resolve with no import —
     * {@code com.botmaker.sdk.api.capture.CaptureSource.desktop()}.
     */
    public static Name qualifiedName(AST ast, Class<?> type) {
        return ast.newName(type.getName());
    }

    /** {@code new T(args…)} with int-literal arguments — {@code new Point(x, y)}, {@code new Rect(…)}. */
    public static ClassInstanceCreation intCtor(AST ast, Class<?> type, int... args) {
        ClassInstanceCreation cic = ast.newClassInstanceCreation();
        cic.setType(type(ast, type));
        for (int value : args) {
            cic.arguments().add(ast.newNumberLiteral(Integer.toString(value)));
        }
        return cic;
    }

    /** Whether {@code call} is a static call on {@code facade} — {@code Wait.time(…)}, {@code ImageFinder.find(…)}. */
    public static boolean isCallOn(MethodInvocation call, Class<?> facade) {
        Expression receiver = call == null ? null : call.getExpression();
        return receiver != null && namesType(receiver.toString(), facade);
    }

    /**
     * Whether {@code node} is a {@code new T(…)} of {@code type}. Matches the type node's source text, which is
     * how the source was written: an unresolved file has no binding to ask, and the pickers that read these
     * back run at edit time, before any compile.
     */
    public static boolean isInstantiationOf(Object node, Class<?> type) {
        return node instanceof ClassInstanceCreation cic
                && cic.getType() != null
                && namesType(cic.getType().toString(), type);
    }

    /** The source text {@code written} names {@code type}, whether the file imported it or qualified it. */
    private static boolean namesType(String written, Class<?> type) {
        return written.equals(type.getSimpleName()) || written.endsWith("." + type.getSimpleName());
    }

    // --- the argument of a new ImageTemplate(…) -------------------------------------------------------
    //
    // "Two spellings, one meaning" lived here — reading and writing a picture argument as either
    // `Templates.YTUJ` or `"src/main/resources/images/…"`. templatePathOf and imageTemplatePathOf (the
    // reading half) went on 2026-09-01 with the guarded switch that was their only caller, and
    // templateArgument (the writing half) went the same day with ListHandler's arm for it.
    //
    // Nothing in this class knows what a picture is now, which is the point: both spellings belong to the
    // plugin that owns ImageTemplate, and both are its to write — as a SourceSeed for a fresh one and
    // through its own slot editors for an existing one. The SDK's picture-naming class leaves Studio
    // entirely with them.
}
