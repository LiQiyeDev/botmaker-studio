package com.botmaker.studio.project.seed;

import com.botmaker.plugin.api.catalog.ScaffoldEntry;
import com.botmaker.plugin.api.catalog.ScaffoldPlan;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the substituted parts of a seed the user now owns in step with the plan, and touches nothing else.
 *
 * <p>This is the second of the surface's two operations, and the distinction is the whole design:
 * {@link SeedWriter} writes a file <b>once</b>, from the plugin's own source; this maintains the marked
 * regions of that file <b>forever</b>, from the user's copy of it. Everything between the marks is theirs
 * from the moment it lands — a body they wrote, a helper they added, a comment they left — and no amount of
 * model change may rewrite it.
 *
 * <h2>What it reconciles</h2>
 *
 * <p>Exactly one thing: each {@code @EnumValues} hole's constants. That is not a stopping point on the way to
 * more, it is the only region a plugin has declared substitutable that can still change after the file
 * exists. The name is the other substituted region and its later change is a <em>rename</em> — a source
 * refactor across the whole bot rather than an edit inside one file — which is {@link SeedSync}'s job.
 * {@code @Editable} is a mark that says <em>do not touch</em>, so there is nothing here to do for it.
 *
 * <p><b>Constants are replaced as a set, in the order given.</b> Whoever supplied them owns their order, and a
 * constant that is gone is one nothing offers any more. That can leave the user's own code naming a constant
 * that no longer exists — {@code return Outcome.BAG_FULL;} after the outcome is deleted on the canvas — and
 * the compiler pointing at it is the intended outcome: it is the one place a compile check survives the move
 * to reading configuration at run time, which is the whole argument for keeping the enum a hole at all.
 *
 * <h2>What it deliberately no longer does</h2>
 *
 * <p>{@code services/ActivityStubSync}, which this replaces, also carried an existing project across two
 * renames of the SDK's own generated shape ({@code void run()} → {@code Outcome run()}, and the implicit
 * outcome's {@code DEFAULT} → {@code NEXT}), retyped {@code extends Activity<Name.Outcome>} and appended a
 * trailing {@code return} when the user's body had none. Every one of those is a fact about <em>the SDK's</em>
 * activity, and Studio knowing them is precisely what this package exists to end: a second plugin's seed has
 * a different superclass, a different method and no outcomes at all. A plugin that needs its own migration
 * carries it in the seed it ships.
 *
 * <h2>Nothing here throws, and doing nothing is the common case</h2>
 *
 * <p>{@link #reconcile} answers the source it was given, unchanged, whenever it will not produce something it
 * believes in: a file mid-edit that does not parse, a file whose top-level type is not the one expected, a
 * plan with nothing to say, or a rewrite that will not apply. It is called on every save, and on almost every
 * save there is nothing to do.
 */
public final class SeedReconciler {

    private SeedReconciler() {}

    /**
     * {@code source} with every substituted enum brought in line with {@code file}; {@code source} itself when
     * there is nothing to do or the file is not the shape the plan describes.
     */
    public static String reconcile(String source, ScaffoldPlan.PlannedFile file) {
        if (source == null || file == null) return source;

        CompilationUnit cu = SourceParser.parse(source);
        // Mid-edit or mangled: let the compiler have the last word rather than rewriting on a guess.
        if (SourceParser.hasSyntaxErrors(cu)) return source;

        TypeDeclaration type = firstType(cu);
        // Only touch a file that really is this seed's. JDT recovers aggressively from broken input, so "it
        // parsed" is not on its own evidence that we are looking at the right thing.
        if (type == null || !file.typeName().equals(type.getName().getIdentifier())) return source;

        AST ast = cu.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        boolean changed = false;
        for (ScaffoldEntry.EnumHole hole : file.seed().enums()) {
            changed |= setConstants(ast, rewrite, type, hole.enumName(), file.constantsFor(hole));
        }
        if (!changed) return source;

        try {
            Document document = new Document(source);
            TextEdit edits = rewrite.rewriteAST(document, null);
            edits.apply(document);
            return document.get();
        } catch (Exception e) {
            return source;   // an edit that won't apply cleanly is not worth risking the user's file for
        }
    }

    /**
     * Makes one nested enum's constants exactly {@code constants}, declaring the enum when the file has not
     * got it — which is what an older project, or a user who deleted it, looks like.
     *
     * <p>A {@code null} list means the seeding said nothing about this hole, which is not the same as saying
     * it is empty: the file's own constants stand, exactly as the seed's own do when it is first written.
     */
    private static boolean setConstants(AST ast, ASTRewrite rewrite, TypeDeclaration type, String enumName,
                                        List<String> constants) {
        if (constants == null) return false;
        EnumDeclaration existing = nestedEnum(type, enumName);
        if (existing != null && constantNames(existing).equals(constants)) return false;

        if (existing == null) {
            EnumDeclaration created = ast.newEnumDeclaration();
            created.setName(ast.newSimpleName(enumName));
            created.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
            for (String constant : constants) created.enumConstants().add(constant(ast, constant));
            // First member: the class's own type parameter may refer to it, so it reads before its use.
            rewrite.getListRewrite(type, TypeDeclaration.BODY_DECLARATIONS_PROPERTY).insertFirst(created, null);
            return true;
        }

        ListRewrite list = rewrite.getListRewrite(existing, EnumDeclaration.ENUM_CONSTANTS_PROPERTY);
        for (Object old : existing.enumConstants()) list.remove((ASTNode) old, null);
        for (String constant : constants) list.insertLast(constant(ast, constant), null);
        return true;
    }

    // ---- AST helpers ------------------------------------------------------------------------------------

    private static EnumConstantDeclaration constant(AST ast, String name) {
        EnumConstantDeclaration declaration = ast.newEnumConstantDeclaration();
        declaration.setName(ast.newSimpleName(name));
        return declaration;
    }

    private static List<String> constantNames(EnumDeclaration declaration) {
        List<String> names = new ArrayList<>();
        for (Object constant : declaration.enumConstants()) {
            names.add(((EnumConstantDeclaration) constant).getName().getIdentifier());
        }
        return names;
    }

    private static EnumDeclaration nestedEnum(TypeDeclaration type, String name) {
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof EnumDeclaration e && name.equals(e.getName().getIdentifier())) return e;
        }
        return null;
    }

    private static TypeDeclaration firstType(CompilationUnit cu) {
        for (Object type : cu.types()) {
            if (type instanceof TypeDeclaration declaration) return declaration;
        }
        return null;
    }
}
