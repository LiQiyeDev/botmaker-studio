package com.botmaker.studio.project;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Which parts of a file the Studio owns, at the granularity the editor actually locks at.
 *
 * <p><b>Why this is needed.</b> Persistence used to be all-or-nothing per file: a
 * {@link FileRole#GENERATED} file's in-memory edits were simply never flushed. Once a generated file can
 * contain an <em>editable</em> method ({@link MethodLock#SIGNATURE} grants the body back to the user, however
 * locked the file) that rule is wrong in both directions: skipping the file discards the user's body; writing
 * it wholesale lets a corrupted scaffold reach disk. So the question stops being "may this file be saved?" and
 * becomes "does this edit touch anything but the parts the user owns?".
 *
 * <p>Pure: parses with a plain {@link ASTParser} (no bindings, no classpath) and returns strings. All I/O
 * stays with the caller.
 */
public final class LockedRegions {

    private LockedRegions() {}

    /**
     * {@code source} with the body of every body-editable method blanked out — the file's locked skeleton.
     *
     * <p>Two sources with the same skeleton differ only where the user is allowed to differ. The result is
     * canonical (re-printed by JDT), so reformatting, indentation and line endings never read as changes.
     */
    public static String skeleton(ProjectConfig config, ProjectTemplate template, Path file, String source) {
        if (source == null) return null;

        CompilationUnit cu = parse(source);
        LockResolver resolver = new LockResolver(config, template, file);

        List<MethodDeclaration> editable = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() != null && resolver.bodyEditable(method)) editable.add(method);
                return true;
            }
        });

        if (editable.isEmpty()) return canonical(cu);

        ASTRewrite rewrite = ASTRewrite.create(cu.getAST());
        for (MethodDeclaration method : editable) {
            rewrite.replace(method.getBody(), cu.getAST().newBlock(), null);
        }

        try {
            Document document = new Document(source);
            rewrite.rewriteAST(document, null).apply(document);
            return canonical(parse(document.get()));
        } catch (Exception e) {
            // An unparseable/unrewritable file can't be proven safe, so say the locked parts differ and let
            // the caller refuse the write. Failing closed is the whole point of this class.
            return null;
        }
    }

    /**
     * True when {@code a} and {@code b} differ only inside body-editable methods — i.e. writing {@code b} over
     * {@code a} would not change anything the Studio owns.
     *
     * <p>False when either side can't be analysed: an edit that cannot be shown to be safe is not safe.
     */
    public static boolean lockedPartsMatch(ProjectConfig config, ProjectTemplate template, Path file,
                                           String a, String b) {
        String skeletonA = skeleton(config, template, file, a);
        String skeletonB = skeleton(config, template, file, b);
        return skeletonA != null && skeletonA.equals(skeletonB);
    }

    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    /** JDT's own printed form — the same code always prints the same way, however it was formatted. */
    private static String canonical(CompilationUnit cu) {
        return cu.toString();
    }
}
