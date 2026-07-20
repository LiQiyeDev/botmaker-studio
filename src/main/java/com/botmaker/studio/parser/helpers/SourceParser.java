package com.botmaker.studio.parser.helpers;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.Map;

/**
 * Parses a whole Java source file into a JDT {@link CompilationUnit} at the latest language level.
 *
 * <p><b>The compiler options are the entire point of this class.</b> {@code ASTParser} defaults to source
 * level <b>1.3</b> — where {@code enum} is an ordinary identifier, and generics and annotations are syntax
 * errors. It does not fail loudly: JDT recovers, and hands back a tree in which an {@code @Override} method or
 * a nested {@code enum} has quietly become a {@code FieldDeclaration} or vanished. Callers that walk that tree
 * then draw confident conclusions from it — "this file has no such method", "there is no outcome enum here" —
 * and act on them.
 *
 * <p>Three copies of the un-configured parser existed before this class, all of them reading files that
 * certainly contain {@code @Override}. Anything parsing a user's source file should come through here.
 * ({@code ProjectAnalyzer} deliberately does not: it needs bindings and a classpath environment, which is a
 * different and heavier setup.)
 */
public final class SourceParser {

    private SourceParser() {}

    /** Parses {@code source} as a compilation unit. Never null; unparseable input yields a recovered tree. */
    public static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);
        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * True when {@code cu} didn't parse cleanly. With bindings unresolved these are syntax errors only, so an
     * unknown type or an unimported class is <em>not</em> reported — which is what makes this usable as a
     * "is this file currently mangled?" check on a user's half-written source.
     */
    public static boolean hasSyntaxErrors(CompilationUnit cu) {
        for (IProblem problem : cu.getProblems()) {
            if (problem.isError()) return true;
        }
        return false;
    }
}
