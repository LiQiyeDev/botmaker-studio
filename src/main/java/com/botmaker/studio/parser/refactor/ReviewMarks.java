package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.EditContext;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The record a refactor leaves behind: {@code @NeedsReview} on the function it rewrote.
 *
 * <h2>Why the source, and not a sidecar</h2>
 *
 * <p>An SDK upgrade's repair is deliberately incomplete — a call to a member the new jar no longer offers
 * becomes a default value, which compiles and is very often wrong (see {@link SdkMigrationRunner}). Something
 * has to survive the dialog closing and tell the user which functions those were, and the diff cannot: once
 * the pom is bumped the old jar is out of the picture and re-diffing the project finds nothing at all. So the
 * mark goes where the change went.
 *
 * <p>It is an <b>annotation in the bot's own source</b> rather than a file under {@code .botmaker/} for three
 * reasons. It cannot drift from the code it describes — an edit that moves the function moves the mark, and
 * deleting the function deletes it. It survives the operations a sidecar would have to re-implement: a git
 * revert through Project History takes the marks back out with the change, and a merge puts them where the
 * merged code went. And it needs no schema, no migration and no cleanup pass for functions that no longer
 * exist.
 *
 * <h2>The annotation is generated into the bot, not shipped by the SDK</h2>
 *
 * <p>{@link #annotationSource} is written into the project's own package on demand, the first time something
 * marks anything. Putting it in {@code botmaker-sdk} would have been less code and would have meant a bot
 * pinned to an older SDK — which is <em>every</em> bot an upgrade is about to touch — could not be marked at
 * all, because the annotation would only arrive with the version it is meant to help the user leave.
 *
 * <p>{@code @Retention(SOURCE)}: it exists for the person and for the Studio's own scan, never at runtime, and
 * SOURCE keeps it out of the class file entirely so a marked bot ships exactly the bytes an unmarked one does.
 *
 * <h2>Merging, not stacking</h2>
 *
 * <p>Marks accumulate — two upgrades and a rename can all land on one function — so writing is a merge into
 * the annotation that is already there, deduplicated, rather than a second annotation Java would reject
 * anyway. Reviewing is the inverse: {@link #strip} removes one entry, and the last one removed takes the
 * annotation with it (and its import, once the file holds no marks at all). A function nobody rewrote never
 * carries the annotation, so "clean" is the absence of a mark, not an empty one.
 */
public final class ReviewMarks {

    private ReviewMarks() {}

    /** The generated annotation's simple name — what the bot's source writes. */
    public static final String ANNOTATION = "NeedsReview";

    /** The file {@link #annotationSource} produces, beside the entry point. */
    public static final String FILE_NAME = ANNOTATION + ".java";

    /**
     * The annotation's source for a bot in {@code packageName} (the package alone, e.g. {@code com.mybot}).
     *
     * <p>Regenerated from here rather than stored, so there is one definition of it in the Studio and none in
     * the project template — a bot that never gets refactored never carries the file.
     */
    public static String annotationSource(String packageName) {
        return """
            package %s;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            /**
             * Marks a function BotMaker changed for you and could not finish on its own.
             *
             * <p>Each entry says what happened at one place in the function — most often that a call to
             * something the new BotMaker library no longer offers was replaced with a placeholder value, so
             * the bot compiles again but no longer does what that line did. Open the Review tab to walk
             * through them; marking one reviewed removes its entry, and the last one removes this annotation.
             *
             * <p>BotMaker writes this file itself; you do not have to. It exists only while the code is being
             * edited (it is not compiled into the bot), so it costs nothing to leave in place.
             */
            @Retention(RetentionPolicy.SOURCE)
            @Target(ElementType.METHOD)
            public @interface %s {

                /** One line per thing that needs looking at, in the order BotMaker made the changes. */
                String[] value();
            }
            """.formatted(packageName, ANNOTATION);
    }

    /**
     * Writes {@link #FILE_NAME} into {@code packageDir} if it is not already there, and answers whether it had
     * to. Never overwrites: the file is the user's once it exists, and its shape is not load-bearing — this
     * class reads and writes the annotation by name, not by its declaration.
     *
     * @throws IOException if the file cannot be created, which the caller must treat as a refused refactor —
     *                     marks referring to an annotation that does not exist would not compile
     */
    public static boolean ensureFile(Path packageDir, String packageName) throws IOException {
        Path file = packageDir.resolve(FILE_NAME);
        if (Files.exists(file)) return false;
        Files.createDirectories(packageDir);
        Files.writeString(file, annotationSource(packageName));
        return true;
    }

    // --- reading ----------------------------------------------------------------------------------------------

    /** The nearest enclosing function of {@code node}, or null when it is not inside one. */
    public static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) return method;
        }
        return null;
    }

    /** {@code method}'s review entries in the order they were written, empty when it carries no mark. */
    public static List<String> entriesOf(MethodDeclaration method) {
        Annotation annotation = annotationOn(method);
        return annotation == null ? List.of() : entriesOf(annotation);
    }

    /** True when anything in {@code unit} is marked — the question {@link #strip} asks about the import. */
    public static boolean anyIn(CompilationUnit unit) {
        return !markedIn(unit).isEmpty();
    }

    /** Every marked function in {@code unit}, in source order. */
    public static List<MethodDeclaration> markedIn(CompilationUnit unit) {
        List<MethodDeclaration> marked = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (annotationOn(node) != null) marked.add(node);
                return true;
            }
        });
        return marked;
    }

    // --- writing ----------------------------------------------------------------------------------------------

    /**
     * Records {@code entries} on {@code method}, merging with a mark already there and importing the
     * annotation when {@code ctx}'s file is not in {@code annotationPackage} (an activity stub, which lives one
     * package down).
     *
     * <p>The edit goes into {@code ctx}'s rewrite, so the mark lands with the change it describes or not at
     * all — a refactor that is refused halfway leaves no orphan marks behind.
     */
    public static void mark(EditContext ctx, MethodDeclaration method, String annotationPackage,
                            List<String> entries) {
        if (method == null || entries == null || entries.isEmpty()) return;

        Annotation existing = annotationOn(method);
        Set<String> merged = new LinkedHashSet<>(existing == null ? List.of() : entriesOf(existing));
        merged.addAll(entries);

        Annotation replacement = annotationFor(ctx.ast(), List.copyOf(merged));
        if (existing != null) {
            ctx.rewriter().replace(existing, replacement, null);
        } else {
            modifiers(ctx.rewriter(), method).insertFirst(replacement, null);
        }
        if (annotationPackage != null && !annotationPackage.isBlank()) {
            ctx.addImport(annotationPackage + "." + ANNOTATION);
        }
    }

    /**
     * Removes one entry from {@code method}'s mark — the "I have looked at this" gesture. Removing the last
     * one removes the annotation, and with it the import once nothing in the file is marked any more.
     *
     * @return false when {@code method} does not carry that entry, so the caller can leave the file alone
     */
    public static boolean strip(EditContext ctx, MethodDeclaration method, String entry) {
        Annotation existing = annotationOn(method);
        if (existing == null) return false;

        List<String> remaining = new ArrayList<>(entriesOf(existing));
        if (!remaining.remove(entry)) return false;

        if (!remaining.isEmpty()) {
            ctx.rewriter().replace(existing, annotationFor(ctx.ast(), remaining), null);
            return true;
        }
        modifiers(ctx.rewriter(), method).remove(existing, null);
        removeImportIfLast(ctx, method);
        return true;
    }

    /** Removes {@code method}'s whole mark, however many entries it holds. */
    public static boolean stripAll(EditContext ctx, MethodDeclaration method) {
        Annotation existing = annotationOn(method);
        if (existing == null) return false;
        modifiers(ctx.rewriter(), method).remove(existing, null);
        removeImportIfLast(ctx, method);
        return true;
    }

    // --- the shape of the annotation --------------------------------------------------------------------------

    /** The {@code @NeedsReview} on {@code method}, whatever form it was written in, or null. */
    private static Annotation annotationOn(MethodDeclaration method) {
        if (method == null) return null;
        for (Object modifier : method.modifiers()) {
            if (modifier instanceof Annotation annotation
                    && ANNOTATION.equals(simpleNameOf(annotation.getTypeName().getFullyQualifiedName()))) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * The strings inside {@code annotation}, accepting every form Java allows one to be written in: this class
     * only ever writes {@code @NeedsReview("…")} and {@code @NeedsReview({"…", "…"})}, but the file is the
     * user's and a hand-edited {@code @NeedsReview(value = {…})} must still read back.
     */
    private static List<String> entriesOf(Annotation annotation) {
        Expression value = switch (annotation) {
            case SingleMemberAnnotation single -> single.getValue();
            case NormalAnnotation normal -> valuePairOf(normal);
            default -> null;
        };
        if (value == null) return List.of();

        List<String> entries = new ArrayList<>();
        if (value instanceof ArrayInitializer array) {
            for (Object element : array.expressions()) {
                if (element instanceof StringLiteral literal) entries.add(literal.getLiteralValue());
            }
        } else if (value instanceof StringLiteral literal) {
            entries.add(literal.getLiteralValue());
        }
        return entries;
    }

    private static Expression valuePairOf(NormalAnnotation annotation) {
        for (Object pair : annotation.values()) {
            if (pair instanceof MemberValuePair member && "value".equals(member.getName().getIdentifier())) {
                return member.getValue();
            }
        }
        return null;
    }

    /**
     * A fresh {@code @NeedsReview} holding {@code entries} — a bare string for one, a braced array for several,
     * which is how a person would have written it.
     */
    private static Annotation annotationFor(AST ast, List<String> entries) {
        SingleMemberAnnotation annotation = ast.newSingleMemberAnnotation();
        annotation.setTypeName(ast.newSimpleName(ANNOTATION));
        if (entries.size() == 1) {
            annotation.setValue(literal(ast, entries.getFirst()));
        } else {
            ArrayInitializer array = ast.newArrayInitializer();
            for (String entry : entries) array.expressions().add(literal(ast, entry));
            annotation.setValue(array);
        }
        return annotation;
    }

    private static StringLiteral literal(AST ast, String text) {
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(text);
        return literal;
    }

    @SuppressWarnings("unchecked")
    private static ListRewrite modifiers(ASTRewrite rewriter, MethodDeclaration method) {
        return rewriter.getListRewrite(method, MethodDeclaration.MODIFIERS2_PROPERTY);
    }

    /**
     * Drops the {@code NeedsReview} import once {@code method} was the file's last marked function.
     *
     * <p>Asked of the <em>original</em> tree, which still shows the annotation this same rewrite is removing —
     * hence "the only one left is this one" rather than "there are none".
     */
    private static void removeImportIfLast(EditContext ctx, MethodDeclaration method) {
        List<MethodDeclaration> marked = markedIn(ctx.cu());
        if (marked.size() != 1 || marked.getFirst() != method) return;

        for (Object each : ctx.cu().imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (!imported.isOnDemand() && !imported.isStatic()
                    && ANNOTATION.equals(simpleNameOf(imported.getName().getFullyQualifiedName()))) {
                ctx.rewriter().remove(imported, null);
            }
        }
    }

    private static String simpleNameOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }
}
