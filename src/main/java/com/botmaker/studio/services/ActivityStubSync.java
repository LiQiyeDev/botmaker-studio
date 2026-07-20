package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the BotMaker-owned parts of a hand-written activity stub in step with the flow editor, without
 * touching the parts that are the user's.
 *
 * <p>{@code ActivityService.ensureStubs} creates {@code activities/<Name>.java} once and never overwrites it,
 * which is right — {@code run()}'s body is the whole point of the file. But two things inside it <em>are</em>
 * generated and have to keep up: the nested {@code Outcome} enum (its constants are edited on the flow canvas)
 * and the {@code extends Activity<Name.Outcome>} that binds it. Rewriting the file wholesale would destroy the
 * user's work, so this makes the smallest AST edits that reconcile the two.
 *
 * <p>It also carries an existing project across the {@code void run()} → {@code Outcome run()} change: the
 * signature is retyped and the body patched to actually return something ({@link #patchReturns}). That is
 * best-effort by design — a body it can't reason about is left for the compiler to point at, which is far
 * better than mangling it.
 */
final class ActivityStubSync {

    private static final String OUTCOME_ENUM = "Outcome";

    private ActivityStubSync() {}

    /**
     * Reconciles every existing stub with its definition. Missing files are skipped (that is
     * {@code ensureStubs}' job) and a file that already matches is not rewritten, so this is a no-op on the
     * common save.
     *
     * <p>Archived activities are synced too: their file still compiles against the generated
     * {@code Activities} fields, so it has to stay valid Java even though nothing runs it.
     */
    static void sync(ProjectConfig config, ActivitiesConfig cfg) throws IOException {
        Path dir = config.activitiesPackageDir();
        for (ActivityDefinition a : cfg.activities()) {
            Path stub = dir.resolve(a.name() + ".java");
            if (!Files.exists(stub)) continue;
            String current = Files.readString(stub);
            String synced = syncSource(current, a);
            if (!synced.equals(current)) Files.writeString(stub, synced);
        }
    }

    /**
     * {@code source} with its outcome enum, superclass and {@code run()} signature brought in line with
     * {@code definition}; returns {@code source} unchanged when there is nothing to do or the file can't be
     * parsed into the shape we expect.
     */
    static String syncSource(String source, ActivityDefinition definition) {
        CompilationUnit cu = SourceParser.parse(source);
        // Mid-edit or mangled: let the compiler have the last word rather than rewriting on a guess.
        if (SourceParser.hasSyntaxErrors(cu)) return source;

        TypeDeclaration type = firstType(cu);
        // Only touch a file that really is this activity's subclass. JDT recovers aggressively from broken
        // input, so "it parsed" is not on its own evidence that we are looking at the right thing.
        if (type == null || !definition.name().equals(type.getName().getIdentifier())) return source;
        if (type.getSuperclassType() == null) return source;

        AST ast = cu.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        boolean changed = false;

        changed |= syncOutcomeEnum(ast, rewrite, type, definition.allOutcomes());
        changed |= syncSuperclass(ast, rewrite, type, definition.name());
        changed |= syncRunSignature(ast, rewrite, type);

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
     * Makes the nested {@code Outcome} enum's constants exactly {@code outcomes}, adding the enum when the
     * file predates outcomes entirely. Constants are replaced as a set rather than diffed: the flow editor
     * already owns their order, and a constant that has gone is one the canvas no longer offers a port for.
     */
    private static boolean syncOutcomeEnum(AST ast, ASTRewrite rewrite, TypeDeclaration type,
                                           List<String> outcomes) {
        EnumDeclaration existing = nestedEnum(type);
        if (existing != null && constantNames(existing).equals(outcomes)) return false;

        if (existing == null) {
            EnumDeclaration created = ast.newEnumDeclaration();
            created.setName(ast.newSimpleName(OUTCOME_ENUM));
            created.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
            for (String outcome : outcomes) created.enumConstants().add(constant(ast, outcome));
            // First member: it is what the class's own type parameter refers to, so it reads before its use.
            rewrite.getListRewrite(type, TypeDeclaration.BODY_DECLARATIONS_PROPERTY)
                    .insertFirst(created, null);
            return true;
        }

        ListRewrite constants = rewrite.getListRewrite(existing, EnumDeclaration.ENUM_CONSTANTS_PROPERTY);
        for (Object old : existing.enumConstants()) constants.remove((ASTNode) old, null);
        for (String outcome : outcomes) constants.insertLast(constant(ast, outcome), null);
        return true;
    }

    /** Retypes {@code extends Activity} to {@code extends Activity<Name.Outcome>} when it isn't already. */
    private static boolean syncSuperclass(AST ast, ASTRewrite rewrite, TypeDeclaration type, String name) {
        String wanted = "Activity<" + name + "." + OUTCOME_ENUM + ">";
        if (type.getSuperclassType() == null) return false;   // not an Activity subclass; leave it alone
        if (wanted.equals(type.getSuperclassType().toString())) return false;
        rewrite.set(type, TypeDeclaration.SUPERCLASS_TYPE_PROPERTY,
                rewrite.createStringPlaceholder(wanted, ASTNode.SIMPLE_TYPE), null);
        return true;
    }

    /**
     * Retypes a legacy {@code void run()} to {@code Outcome run()} and patches its body to return one. Only
     * fires when the return type is literally {@code void}: once migrated, the user's own return statements
     * are none of our business.
     */
    private static boolean syncRunSignature(AST ast, ASTRewrite rewrite, TypeDeclaration type) {
        MethodDeclaration run = methodNamed(type, "run");
        if (run == null || run.getReturnType2() == null) return false;
        if (!(run.getReturnType2() instanceof PrimitiveType primitive)
                || primitive.getPrimitiveTypeCode() != PrimitiveType.VOID) {
            return false;
        }
        rewrite.set(run, MethodDeclaration.RETURN_TYPE2_PROPERTY,
                rewrite.createStringPlaceholder(OUTCOME_ENUM, ASTNode.SIMPLE_TYPE), null);
        patchReturns(ast, rewrite, run.getBody());
        return true;
    }

    /**
     * Makes a body written for {@code void} valid for an {@code Outcome} return: every bare {@code return;}
     * becomes {@code return Outcome.DEFAULT;}, and one is appended when control can fall off the end.
     *
     * <p>"Can fall off the end" is judged by the last statement alone — a real reachability analysis would be
     * the compiler's job, and guessing wrong here only ever produces an unreachable statement the user can
     * delete, never a silent change in what the activity does.
     */
    private static void patchReturns(AST ast, ASTRewrite rewrite, Block body) {
        if (body == null) return;

        for (ReturnStatement bare : bareReturns(body)) {
            rewrite.set(bare, ReturnStatement.EXPRESSION_PROPERTY,
                    rewrite.createStringPlaceholder(OUTCOME_ENUM + ".DEFAULT", ASTNode.SIMPLE_NAME), null);
        }

        List<?> statements = body.statements();
        Statement last = statements.isEmpty() ? null : (Statement) statements.get(statements.size() - 1);
        if (last instanceof ReturnStatement || last instanceof ThrowStatement) return;
        rewrite.getListRewrite(body, Block.STATEMENTS_PROPERTY).insertLast(
                (Statement) rewrite.createStringPlaceholder(
                        "return " + OUTCOME_ENUM + ".DEFAULT;", ASTNode.RETURN_STATEMENT), null);
    }

    /** Every {@code return;} anywhere in {@code body}, including inside nested ifs and loops. */
    private static List<ReturnStatement> bareReturns(Block body) {
        List<ReturnStatement> found = new ArrayList<>();
        body.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                if (node.getExpression() == null) found.add(node);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.LambdaExpression node) {
                return false;   // a lambda's returns belong to the lambda, not to run()
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.TypeDeclaration node) {
                return false;   // ditto a local/anonymous class's methods
            }
        });
        return found;
    }

    // --- AST helpers -------------------------------------------------------------------------------------

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

    private static EnumDeclaration nestedEnum(TypeDeclaration type) {
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof EnumDeclaration e && OUTCOME_ENUM.equals(e.getName().getIdentifier())) {
                return e;
            }
        }
        return null;
    }

    private static MethodDeclaration methodNamed(TypeDeclaration type, String name) {
        for (MethodDeclaration m : type.getMethods()) {
            if (m.getName().getIdentifier().equals(name)) return m;
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
