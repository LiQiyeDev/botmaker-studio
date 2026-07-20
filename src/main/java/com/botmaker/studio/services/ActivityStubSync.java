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
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
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
 * <p>It also carries an existing project across two renames of the generated shape: {@code void run()} →
 * {@code Outcome run()}, and the implicit outcome's {@code DEFAULT} → {@code NEXT}. Both are best-effort by
 * design — a body it can't reason about is left for the compiler to point at, which is far better than
 * mangling it.
 *
 * <p>The last statement of {@code run()} is always a {@code return}, appended here when the user's body
 * doesn't end in one. The editor refuses to delete it or to insert after it ({@code project/GeneratedMembers}),
 * so "what this activity reports" is a decision the file always states explicitly.
 */
final class ActivityStubSync {

    private static final String OUTCOME_ENUM = "Outcome";

    /** The implicit outcome's constant, and its previous spelling — see {@link #renameLegacyDefault}. */
    private static final String NEXT = "NEXT";
    private static final String LEGACY_NEXT = "DEFAULT";

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
        changed |= syncRunSignature(rewrite, type);
        changed |= renameLegacyDefault(rewrite, type, definition.allOutcomes());
        changed |= ensureTerminalReturn(rewrite, type);

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
    private static boolean syncRunSignature(ASTRewrite rewrite, TypeDeclaration type) {
        MethodDeclaration run = methodNamed(type, "run");
        if (run == null || run.getReturnType2() == null) return false;
        if (!(run.getReturnType2() instanceof PrimitiveType primitive)
                || primitive.getPrimitiveTypeCode() != PrimitiveType.VOID) {
            return false;
        }
        rewrite.set(run, MethodDeclaration.RETURN_TYPE2_PROPERTY,
                rewrite.createStringPlaceholder(OUTCOME_ENUM, ASTNode.SIMPLE_TYPE), null);
        patchBareReturns(rewrite, run.getBody());
        return true;
    }

    /**
     * Makes a body written for {@code void} valid for an {@code Outcome} return: every bare {@code return;}
     * becomes {@code return Outcome.NEXT;}. Falling off the end is {@link #ensureTerminalReturn}'s job, which
     * runs on every sync rather than only on this migration.
     */
    private static void patchBareReturns(ASTRewrite rewrite, Block body) {
        if (body == null) return;
        for (ReturnStatement bare : bareReturns(body)) {
            rewrite.set(bare, ReturnStatement.EXPRESSION_PROPERTY,
                    rewrite.createStringPlaceholder(OUTCOME_ENUM + "." + NEXT, ASTNode.SIMPLE_NAME), null);
        }
    }

    /**
     * Guarantees {@code run()} ends in a {@code return}, appending {@code return Outcome.NEXT;} when the last
     * statement isn't one. Every path out of an activity has to say what it reports, and this is the statement
     * the editor then pins in place.
     *
     * <p>A trailing {@code throw} is left alone: it can't fall through, and appending after it would be
     * unreachable code. Otherwise "can fall off the end" is judged by the last statement alone — real
     * reachability analysis is the compiler's job, and guessing wrong here only ever produces one statement the
     * compiler will point at, never a silent change in what the activity does.
     */
    private static boolean ensureTerminalReturn(ASTRewrite rewrite, TypeDeclaration type) {
        MethodDeclaration run = methodNamed(type, "run");
        if (run == null || run.getBody() == null) return false;

        List<?> statements = run.getBody().statements();
        Statement last = statements.isEmpty() ? null : (Statement) statements.get(statements.size() - 1);
        if (last instanceof ReturnStatement || last instanceof ThrowStatement) return false;

        rewrite.getListRewrite(run.getBody(), Block.STATEMENTS_PROPERTY).insertLast(
                (Statement) rewrite.createStringPlaceholder(
                        "return " + OUTCOME_ENUM + "." + NEXT + ";", ASTNode.RETURN_STATEMENT), null);
        return true;
    }

    /**
     * Renames references to the implicit outcome's old spelling: {@code Outcome.DEFAULT} → {@code Outcome.NEXT}.
     *
     * <p>{@link #syncOutcomeEnum} rewrites the enum's constants as a set, so a stub written before the rename
     * loses {@code DEFAULT} from the enum while its {@code return Outcome.DEFAULT;} stays behind, referring to
     * a constant that no longer exists. Skipped entirely when the activity really does declare an outcome
     * called {@code DEFAULT} — then it isn't a legacy reference, it is the user's own.
     */
    private static boolean renameLegacyDefault(ASTRewrite rewrite, TypeDeclaration type, List<String> outcomes) {
        if (outcomes.contains(LEGACY_NEXT)) return false;

        List<SimpleName> stale = new ArrayList<>();
        type.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(QualifiedName node) {
                if (OUTCOME_ENUM.equals(node.getQualifier().toString())
                        && LEGACY_NEXT.equals(node.getName().getIdentifier())) {
                    stale.add(node.getName());
                }
                return true;
            }
        });
        for (SimpleName name : stale) rewrite.set(name, SimpleName.IDENTIFIER_PROPERTY, NEXT, null);
        return !stale.isEmpty();
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
