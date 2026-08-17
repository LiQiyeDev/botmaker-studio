package com.botmaker.studio.parser;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.MatchesCheck;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.parser.handlers.EnumManipulationHandler;
import com.botmaker.studio.parser.handlers.InstantiationHandler;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.parser.handlers.ListHandler;
import com.botmaker.studio.parser.handlers.MatchesSwitchHandler;
import com.botmaker.studio.parser.handlers.MethodHandler;
import com.botmaker.studio.parser.handlers.OperatorReplacementHandler;
import com.botmaker.studio.parser.handlers.RawExpressionHandler;
import com.botmaker.studio.parser.handlers.SwitchNormalizer;
import com.botmaker.studio.parser.handlers.TypeHandler;
import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.parser.guard.RefusedEdit;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SdkNodes;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.LockResolver.EditKind;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The single stateful layer of the write pipeline: every public method is a per-edit API call guarded by
 * {@link #canModify} that publishes a {@code CodeUpdatedEvent} when it lands. The pure {@code (cu, code) -> code}
 * rewrites are delegated straight to the stateless {@code parser/handlers/*} (signature-shaped operations) or to the
 * {@code private static} transforms at the bottom of this file (bespoke AST shapes). The former {@code AstRewriter}
 * pass-through façade is gone — its real-logic methods live here as those transforms.
 *
 * <p><b>This class is where read-only is enforced, and it is the only place that can be.</b> Every mutation of the
 * user's code funnels through here, so a lock checked here holds no matter which button, menu, dropdown or drag
 * reached it. That was not true before: {@code canModify()} tested for a path (`com/botmaker/sdk`) that had not
 * existed for some time, so it always returned true, and the real enforcement was "don't render the control".
 * Every UI path that forgot the check — the expression menu, the method-call dropdown, the separator "+" — was
 * therefore a hole that silently rewrote generated code and persisted it.
 *
 * <p>Not rendering the control is still right, and {@code Part E} does it: a locked block offers no affordance at
 * all. But that is a <em>UX</em> guarantee, and it lives in ~20 block classes that each have to remember. This is
 * the <em>correctness</em> guarantee, and it lives in one place. If a refusal here ever reaches the user, some
 * block forgot — the status message is deliberately user-facing rather than a silent {@code return}, so the gap
 * shows up as a visible "why can't I?" instead of a mystery no-op.
 */
public class CodeEditor {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;
    private final ProjectAnalyzer analyzer;
    private final RefusalJournal journal;

    private static final Map<String, InfixExpression.Operator> INFIX_OPS = Map.ofEntries(
            Map.entry("+", InfixExpression.Operator.PLUS),
            Map.entry("-", InfixExpression.Operator.MINUS),
            Map.entry("*", InfixExpression.Operator.TIMES),
            Map.entry("/", InfixExpression.Operator.DIVIDE),
            Map.entry("%", InfixExpression.Operator.REMAINDER),
            Map.entry("==", InfixExpression.Operator.EQUALS),
            Map.entry("!=", InfixExpression.Operator.NOT_EQUALS),
            Map.entry(">", InfixExpression.Operator.GREATER),
            Map.entry(">=", InfixExpression.Operator.GREATER_EQUALS),
            Map.entry("<", InfixExpression.Operator.LESS),
            Map.entry("<=", InfixExpression.Operator.LESS_EQUALS),
            Map.entry("&&", InfixExpression.Operator.CONDITIONAL_AND),
            Map.entry("||", InfixExpression.Operator.CONDITIONAL_OR)
    );
    private static final Map<String, Assignment.Operator> ASSIGNMENT_OPS = Map.ofEntries(
            Map.entry("=", Assignment.Operator.ASSIGN),
            Map.entry("+=", Assignment.Operator.PLUS_ASSIGN),
            Map.entry("-=", Assignment.Operator.MINUS_ASSIGN),
            Map.entry("*=", Assignment.Operator.TIMES_ASSIGN),
            Map.entry("/=", Assignment.Operator.DIVIDE_ASSIGN),
            Map.entry("%=", Assignment.Operator.REMAINDER_ASSIGN)
    );
    private static final Map<String, PrefixExpression.Operator> PREFIX_OPS = Map.ofEntries(
            Map.entry("++", PrefixExpression.Operator.INCREMENT),
            Map.entry("--", PrefixExpression.Operator.DECREMENT)
    );
    private static final Map<String, PostfixExpression.Operator> POSTFIX_OPS = Map.ofEntries(
            Map.entry("++", PostfixExpression.Operator.INCREMENT),
            Map.entry("--", PostfixExpression.Operator.DECREMENT)
    );

    public CodeEditor(ProjectConfig config, ProjectState state, EventBus eventBus, ProjectAnalyzer analyzer) {
        this(config, state, eventBus, analyzer, RefusalJournal.inCacheDir());
    }

    /** As above, writing refusals somewhere other than the cache dir — for a test that asserts on them. */
    public CodeEditor(ProjectConfig config, ProjectState state, EventBus eventBus, ProjectAnalyzer analyzer,
                      RefusalJournal journal) {
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
        this.analyzer = analyzer;
        this.journal = journal;
    }

    private String getCurrentCode() { return state.getCurrentCode(); }
    private CompilationUnit getCompilationUnit() { return state.getCompilationUnit().orElse(null); }

    /**
     * Whether {@code target} may be changed, telling the user why not when it may not. A null {@code config}
     * (tests, no project open) permits everything; a null {@code target} never does.
     */
    private boolean canModify(ASTNode target, EditKind kind) {
        LockResolver.Verdict verdict = LockResolver.forActiveFile(config, state).check(target, kind);
        if (!verdict.allowed()) {
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(verdict.reason()));
        }
        return verdict.allowed();
    }

    /**
     * Whether a statement may be inserted into {@code targetBody} at {@code index}.
     *
     * <p>Some bodies end in a statement that has to stay last — an activity's {@code run()} always closes by
     * reporting an outcome. That is a rule about a <em>position</em>, so {@link #canModify} can't express it:
     * the body is editable, and so is the return; only "after it" isn't.
     */
    private boolean canInsertAt(BodyBlock targetBody, int index) {
        LockResolver resolver = LockResolver.forActiveFile(config, state);
        ASTNode body = targetBody.getAstNode();
        var pinned = resolver.pinnedReturnOf(body);
        // The drop index counts BodyBlock children (comments included); the pinned return's position is a
        // statements() index (comments excluded). Translate before comparing, or a drop between a trailing
        // comment and the pinned return reads as "after the return" and is wrongly refused.
        int statementIndex = toStatementIndex(targetBody, index);
        if (pinned == null || statementIndex <= ((org.eclipse.jdt.core.dom.Block) body).statements().indexOf(pinned)) {
            return true;
        }
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(LockResolver.pinnedReturnReason()));
        return false;
    }

    /**
     * Translates a {@link BodyBlock} child index into a {@code Block.statements()} index by skipping the
     * children that occupy no statements() slot — the {@link Comment}s. A {@code Comment} is not a JDT
     * {@code Statement}, so the two indexings diverge whenever a body holds comment blocks.
     */
    private static int toStatementIndex(BodyBlock body, int childIndex) {
        int astIndex = 0;
        var children = body.getStatements();
        for (int i = 0; i < childIndex && i < children.size(); i++) {
            if (!(children.get(i).getAstNode() instanceof Comment)) astIndex++;
        }
        return astIndex;
    }

    /** Whether {@code toDelete} may be removed — a pinned trailing return may not. */
    private boolean canDelete(Statement toDelete) {
        if (!LockResolver.forActiveFile(config, state).isPinnedReturn(toDelete)) return true;
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(LockResolver.pinnedReturnReason()));
        return false;
    }

    private boolean triggerUpdate(String newCode, ASTNode target) {
        return triggerUpdate(newCode, false, target, EditKind.BODY);
    }

    /**
     * Publishes {@code newCode} as the file's new content — unless it wouldn't parse. Returns whether it was
     * published, so a caller with a follow-up event of its own can drop that too.
     *
     * <p>{@code target} and {@code kind} are carried only for the refusal journal — they say <em>which block</em>
     * the rewrite that emitted broken source was editing, which is the one thing a refusal can't be diagnosed
     * without and the one thing the guard couldn't see when it only took two strings. Both are nullable: a
     * whole-file rewrite ({@code normalizeSwitches}) has no target, and the journal treats every field as
     * best-effort.
     */
    private boolean triggerUpdate(String newCode, boolean markNewIdentifiersAsUnedited,
                                  ASTNode target, EditKind kind) {
        String previousCode = getCurrentCode();
        newCode = formatted(newCode);
        if (wouldBreak(newCode, previousCode, target, kind)) return false;
        eventBus.publish(new CoreApplicationEvents.CodeUpdatedEvent(newCode, previousCode, markNewIdentifiersAsUnedited));
        return true;
    }

    /**
     * {@code newCode} laid out — the single place Studio formats, sitting on the single place it publishes, so
     * no write path can skip it and none of them has to remember to ask.
     *
     * <p>Before the guard rather than after it, so the text the guard judges is the text the user gets; and
     * after every rewrite rather than inside them, because {@code ASTRewrite} only formats what it inserts and
     * the damage this repairs is cumulative — a file degrades across edits, not within one. Expect one large
     * diff the first time an existing project is saved: that is the backlog of unformatted edits coming due,
     * not a regression.
     *
     * <p>Skipped for a file the user can't edit anyway, on the same reasoning as
     * {@code CodeEditorService}'s call to {@code normalizeSwitches}: reformatting generated scaffolding
     * produces a diff nobody asked for in a file nobody can change.
     */
    private String formatted(String newCode) {
        if (newCode == null) return null;
        if (LockResolver.forActiveFile(config, state).suppressesInteraction()) return newCode;
        return SourceFormatter.format(newCode);
    }

    /**
     * Whether publishing {@code newCode} would leave the user with source that doesn't parse — the one gate
     * between a rewrite and the canvas.
     *
     * <p><b>Why it exists.</b> A rewrite that <em>throws</em> is already handled: {@code AstRewriteHelper
     * .applyRewrite} catches and returns the original code. A rewrite that succeeds and produces broken Java
     * had nothing checking it: the code was published, {@code refreshUI} re-parsed it, JDT recovered a mangled
     * tree, and the method rendered <em>empty</em>. Adding one block could therefore erase the visible contents
     * of a method, with Ctrl-Z as the only way back. Refusing here costs the user the edit; publishing costs
     * them the method.
     *
     * <p><b>Only a newly introduced break is refused</b>, which is the half that makes this liveable. If the
     * file already has a syntax error — a half-typed argument, a paste in progress — every subsequent edit
     * would fail the check, and the guard would lock the user out of the very edits that fix it. So a file that
     * was already broken is left entirely alone.
     *
     * <p>Syntax errors only, via {@link SourceParser}: with bindings unresolved an unimported class or an
     * unknown type isn't reported, and mustn't be — a block that names a type the project doesn't have yet is
     * a normal intermediate state. A broken brace is not.
     *
     * <p>Costs one full-file parse on the edit path, and only on the path that already parses: the new code is
     * checked first, so the overwhelmingly common clean edit pays a single parse and never touches the old
     * code. {@code refreshUI} parses the same file on every edit anyway.
     *
     * <p><b>Every refusal is recorded</b>, not just printed — see {@link RefusalJournal}. The refusal is the
     * symptom; the rewrite that emitted broken source is the bug, and it is fixed later, usually from someone
     * else's machine. So the problem, the rewrite, the block, the project and both full sources go to disk.
     */
    private boolean wouldBreak(String newCode, String previousCode, ASTNode target, EditKind kind) {
        if (newCode == null || newCode.equals(previousCode)) return false;
        IProblem problem = SourceParser.firstSyntaxError(SourceParser.parse(newCode));
        if (problem == null) return false;
        if (SourceParser.hasSyntaxErrors(SourceParser.parse(previousCode))) return false;
        RefusedEdit refused = RefusedEdit.of(problem, refusedBy(), kind, target, previousCode, config, state);
        System.err.println("Refused an edit that would have broken the code " + refused.summary());
        System.err.println("  the source it would have published: "
                + journal.record(refused, newCode, previousCode));
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                "That change would have broken the code, so nothing was changed."));
        return true;
    }

    /** The frames that are the guard itself rather than the rewrite that reached it. */
    private static final Set<String> GUARD_FRAMES =
            Set.of("refusedBy", "wouldBreak", "triggerUpdate", "edit");

    /**
     * The public {@link CodeEditor} method that reached the refused edit, for the log line. This is the handle
     * on <em>which rewrite</em> emits broken source — the thing worth fixing, of which the refusal is only the
     * symptom. Best-effort: a lambda or an inlined frame reads as its enclosing method, which is close enough
     * to find the handler by name.
     *
     * <p>The exclusion list has to include this method itself. It didn't, so the first frame matching
     * {@code CodeEditor} was always {@code refusedBy} and every refusal in the wild reported its own name —
     * the one thing the line existed to say was the one thing it never said.
     */
    private static String refusedBy() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().equals(CodeEditor.class.getName()))
                .filter(f -> !GUARD_FRAMES.contains(f.getMethodName()))
                .map(f -> f.getMethodName() + ":" + f.getLineNumber())
                .findFirst()
                .orElse("unknown"));
    }

    /**
     * The write-path context for a rewrite of {@code cu} — this editor's analyzer and project state, plus a
     * fresh {@link ASTRewrite}. Every {@link #edit} lambda that calls a handler builds one of these, which is
     * why the handlers take a single {@link EditContext} rather than re-listing the same four arguments.
     */
    private EditContext ctx(CompilationUnit cu) {
        return EditContext.of(cu, analyzer, state);
    }

    /**
     * Runs a pure rewrite under the modify-guard and publishes the result. {@code op} is a
     * {@code (CompilationUnit, originalCode) -> newCode} transform — a handler call or a local static transform.
     *
     * <p>{@code target} is the node being changed and {@code kind} says which half of its method that touches;
     * together they are what the guard needs to resolve a lock. Passing the node the rewrite actually mutates
     * matters: {@code deleteMethod} targets the method itself ({@code SIGNATURE}, refused for a supervise hook),
     * while {@code addStatement} targets its body ({@code BODY}, allowed for the same hook).
     */
    private void edit(ASTNode target, EditKind kind, boolean markUnedited,
                      BiFunction<CompilationUnit, String, String> op) {
        if (!canModify(target, kind)) return;
        CompilationUnit cu = getCompilationUnit();
        if (cu == null) return;
        String newCode = op.apply(cu, getCurrentCode());
        if (newCode != null) triggerUpdate(newCode, markUnedited, target, kind);
    }

    // =================================================================================
    // TYPE / INSTANTIATION
    // =================================================================================

    public void replaceVariableType(VariableDeclarationStatement toReplace, ResolvedType newType) {
        edit(toReplace, EditKind.BODY, false, (cu, code) -> TypeHandler.replaceVariableType(ctx(cu), code, toReplace, newType));
    }

    public void replaceFieldType(FieldDeclaration fieldDecl, String newTypeName) {
        edit(fieldDecl, EditKind.SIGNATURE, false, (cu, code) -> TypeHandler.replaceFieldType(ctx(cu), code, fieldDecl, ResolvedType.named(newTypeName)));
    }

    public void updateInstantiation(ClassInstanceCreation node, String newTypeName, List<ResolvedType> newParamTypes) {
        edit(node, EditKind.BODY, true, (cu, code) -> InstantiationHandler.updateInstantiation(ctx(cu), code, node, ResolvedType.named(newTypeName), newParamTypes));
    }

    public void replaceWithInstantiation(Expression toReplace, String typeName, List<ResolvedType> paramTypes) {
        edit(toReplace, EditKind.BODY, true, (cu, code) -> InstantiationHandler.replaceWithInstantiation(ctx(cu), code, toReplace, ResolvedType.named(typeName), paramTypes));
    }

    public void replaceWithVariable(Expression toReplace, String variableName) {
        edit(toReplace, EditKind.BODY, false, (cu, code) -> replaceNode(cu, code, toReplace, cu.getAST().newSimpleName(variableName)));
    }

    /** Replaces {@code toReplace} with a ready-made expression snippet (e.g. a capture-source helper call). */
    public void replaceWithRawExpression(Expression toReplace, String exprCode) {
        edit(toReplace, EditKind.BODY, false, (cu, code) -> RawExpressionHandler.replaceWithExpression(cu, code, toReplace, exprCode));
    }

    /**
     * Replaces {@code toReplace} with a snippet that names {@code importFqn} by its simple name, adding that
     * import. Lets a picker commit {@code Precision.TIGHT} instead of the fully-qualified form the
     * import-free {@link #replaceWithRawExpression(Expression, String)} above requires.
     */
    public void replaceWithRawExpression(Expression toReplace, String exprCode, String importFqn) {
        edit(toReplace, EditKind.BODY, false,
                (cu, code) -> RawExpressionHandler.replaceWithExpression(cu, code, toReplace, exprCode, importFqn));
    }

    /**
     * Declares a new local variable {@code type name = <default>;} just before the statement enclosing
     * {@code toReplace}, then references it in that slot — a single atomic rewrite. Lets the user create a
     * typed variable (e.g. a {@code Direction}) inline from the Variables submenu. Falls back to a plain
     * reference when there is no enclosing block to host the declaration.
     */
    public void declareVariableBeforeAndReference(Expression toReplace, ResolvedType type, String name) {
        edit(toReplace, EditKind.BODY, true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);

            Statement stmt = enclosingBlockStatement(toReplace);
            if (stmt == null) {
                rewriter.replace(toReplace, ast.newSimpleName(name), null);
                return AstRewriteHelper.applyRewrite(rewriter, code);
            }

            VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
            frag.setName(ast.newSimpleName(name));
            Expression init = NodeCreator.createDefaultInitializer(ast, type);
            if (init != null) frag.setInitializer(init);
            VariableDeclarationStatement decl = ast.newVariableDeclarationStatement(frag);
            decl.setType(ProjectAnalyzer.createSimpleTypeNode(ast, type));

            Block block = (Block) stmt.getParent();
            rewriter.getListRewrite(block, Block.STATEMENTS_PROPERTY).insertBefore(decl, stmt, null);
            rewriter.replace(toReplace, ast.newSimpleName(name), null);
            ImportManager.addImportForSimpleName(cu, rewriter, type.leafType().simpleName(), analyzer, null);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /** The nearest ancestor {@link Statement} directly contained in a {@link Block}, or {@code null}. */
    private static Statement enclosingBlockStatement(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof Statement s && s.getParent() instanceof Block) return s;
        }
        return null;
    }

    /** Replaces {@code toReplace} with {@code new ImageTemplate("<path>")} — the image-template arg picker. */
    public void setImageTemplate(Expression toReplace, String path) {
        setImageTemplate(toReplace, path, null);
    }

    /**
     * Like {@link #setImageTemplate(Expression, String)}, but when {@code windowTitle} is non-null and the
     * template is the sole argument of an {@code ImageFinder.find(...)} call, also injects
     * {@code Window.find("<windowTitle>").orElseThrow()} as the capture source — so a project whose default
     * capture target is a window finds inside that window automatically, with no per-call window choice
     * (Phase 5a). When {@code windowTitle} is null or the enclosing call isn't a single-argument
     * {@code ImageFinder.find}, it behaves exactly like the plain setter (whole-screen search).
     */
    public void setImageTemplate(Expression toReplace, String path, String windowTitle) {
        edit(toReplace, EditKind.BODY, true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            ClassInstanceCreation cic = ast.newClassInstanceCreation();
            cic.setType(SdkNodes.type(ast, SdkType.IMAGE_TEMPLATE));
            cic.arguments().add(SdkNodes.templateArgument(ast, path));
            ImportManager.addImport(cu, rewriter, SdkType.IMAGE_TEMPLATE);
            ImportManager.addTemplatesImport(cu, rewriter);
            rewriter.replace(toReplace, cic, null);

            if (windowTitle != null && isSoleFindArgument(toReplace)) {
                MethodInvocation find = (MethodInvocation) toReplace.getParent();
                rewriter.getListRewrite(find, MethodInvocation.ARGUMENTS_PROPERTY)
                        .insertLast(windowFindOrElseThrow(ast, windowTitle), null);
                ImportManager.addImport(cu, rewriter, SdkType.WINDOW);
            }
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /** True when {@code templateSlot} is the only argument of an {@code ImageFinder.find(...)} call. */
    private static boolean isSoleFindArgument(Expression templateSlot) {
        if (!(templateSlot.getParent() instanceof MethodInvocation mi)) return false;
        if (!"find".equals(mi.getName().getIdentifier())) return false;
        if (!SdkNodes.isCallOn(mi, SdkType.IMAGE_FINDER)) return false;
        return mi.arguments().size() == 1 && mi.arguments().get(0) == templateSlot;
    }

    /** Builds the AST for {@code Window.find("<title>").orElseThrow()} (a window-targeted capture source). */
    private static Expression windowFindOrElseThrow(AST ast, String title) {
        MethodInvocation find = ast.newMethodInvocation();
        find.setExpression(SdkNodes.name(ast, SdkType.WINDOW));
        find.setName(ast.newSimpleName("find"));
        StringLiteral t = ast.newStringLiteral();
        t.setLiteralValue(title);
        find.arguments().add(t);
        MethodInvocation orElseThrow = ast.newMethodInvocation();
        orElseThrow.setExpression(find);
        orElseThrow.setName(ast.newSimpleName("orElseThrow"));
        return orElseThrow;
    }

    /**
     * Replaces {@code toReplace} with {@code ImageTemplateGroup.of(new ImageTemplate("p1"), …)} — the
     * multi-template group picker. Passing the full desired path list each time (rather than mutating
     * in place) keeps the picker's add/remove/change operations a single, uniform rewrite.
     *
     * <p>It is also where a group find's body gets seeded with its combination switch, because filling this
     * slot is the second of the two edits that can make the seed possible — see
     * {@link LambdaCallHandler#seedIfReady}. Hooked here rather than generically after every expression
     * replacement: this is the only write that puts a group into an argument slot, so a generic hook would
     * pay a walk up the tree on every edit in the file to answer a question only this one can answer yes to.
     */
    public void setImageTemplateGroup(Expression toReplace, java.util.List<String> paths) {
        edit(toReplace, EditKind.BODY, true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            MethodInvocation call = ast.newMethodInvocation();
            call.setExpression(SdkNodes.name(ast, SdkType.IMAGE_TEMPLATE_GROUP));
            call.setName(ast.newSimpleName("of"));
            for (String path : paths) {
                ClassInstanceCreation cic = ast.newClassInstanceCreation();
                cic.setType(SdkNodes.type(ast, SdkType.IMAGE_TEMPLATE));
                cic.arguments().add(SdkNodes.templateArgument(ast, path));
                call.arguments().add(cic);
            }
            ImportManager.addImport(cu, rewriter, SdkType.IMAGE_TEMPLATE);
            ImportManager.addTemplatesImport(cu, rewriter);
            ImportManager.addImport(cu, rewriter, SdkType.IMAGE_TEMPLATE_GROUP);
            rewriter.replace(toReplace, call, null);
            LambdaCallHandler.seedIfReady(EditContext.of(cu, rewriter, analyzer, state), toReplace,
                    paths.isEmpty() ? null : paths.getFirst());
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /**
     * Rewrites the trailing {@code ImageTemplate...} varargs of {@code call} to exactly {@code paths} — the
     * chip row an image varargs slot renders instead of one fixed picker per existing argument
     * ({@code Matches.hasAny(a, b, c)}, {@code ImageFinder.findAny(…)}).
     *
     * <p>It takes the whole desired list and replaces the tail, the same shape as
     * {@link #setImageTemplateGroup}: add, remove and change are then one uniform rewrite rather than three
     * argument-list surgeries, and the empty list is a legal state (the call keeps its fixed arguments and
     * the row falls back to its "Choose images…" prompt). Arguments before {@code fromIndex} are untouched.
     */
    public void setImageTemplateArgs(MethodInvocation call, int fromIndex, java.util.List<String> paths) {
        edit(call, EditKind.BODY, true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            ListRewrite args = rewriter.getListRewrite(call, MethodInvocation.ARGUMENTS_PROPERTY);

            List<?> existing = call.arguments();
            for (int i = fromIndex; i < existing.size(); i++) {
                args.remove((ASTNode) existing.get(i), null);
            }
            for (String path : paths) {
                ClassInstanceCreation cic = ast.newClassInstanceCreation();
                cic.setType(SdkNodes.type(ast, SdkType.IMAGE_TEMPLATE));
                cic.arguments().add(SdkNodes.templateArgument(ast, path));
                args.insertLast(cic, null);
            }
            ImportManager.addImport(cu, rewriter, SdkType.IMAGE_TEMPLATE);
            ImportManager.addTemplatesImport(cu, rewriter);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /**
     * Switches a vision loop statement between its single / {@code …Any} / {@code …All} variants (the method
     * dropdown on {@code LambdaCallBlock}). Delegates to {@link LambdaCallHandler#switchVariant}: renames the
     * method, converts the image arg single↔group, and adds/removes/renames the lambda parameter — a group
     * variant's body receives a {@code Matches}, a single one's a {@code MatchResult}.
     *
     * @param lambdaParam the name the body receives the value under, or {@code null} for a {@code () -> {}} body
     */
    public void switchLambdaVariant(Statement lambdaStmt, String newMethod, boolean group, String lambdaParam) {
        edit(lambdaStmt, EditKind.BODY, true, (cu, code) -> {
            if (!(lambdaStmt instanceof ExpressionStatement es
                    && es.getExpression() instanceof MethodInvocation mi)) {
                return code;
            }
            EditContext ctx = EditContext.of(cu, analyzer, state);
            LambdaCallHandler.switchVariant(ctx, mi, newMethod, group, lambdaParam);
            return ctx.applyTo(code);
        });
    }

    /**
     * Re-points a vision-loop statement at a different SDK facade — the class dropdown on
     * {@code LambdaCallBlock}. The whole call is <em>replaced</em>, not edited: the lambda body means nothing
     * on another facade (only {@code ImageFinder}'s loop helpers take one), so the trailing lambda and the
     * image argument both go, and the target's arguments are seeded from its own parameter types the same way
     * an inserted call's are. {@code updateMethodInvocation} is deliberately not reused — it syncs arguments
     * positionally and would try to fit the old image and lambda into the new signature's slots.
     *
     * <p>The caller warns before discarding a non-empty body; by the time this runs, that is decided.
     */
    public void replaceLambdaCallWithFacadeCall(Statement lambdaStmt, ExpressionChoice.Method choice) {
        edit(lambdaStmt, EditKind.BODY, true, (cu, code) -> {
            if (!(lambdaStmt instanceof ExpressionStatement es
                    && es.getExpression() instanceof MethodInvocation mi)) {
                return code;
            }
            EditContext ctx = EditContext.of(cu, analyzer, state);
            ctx.rewriter().replace(mi, MethodHandler.createMethodInvocation(ctx, choice), null);
            return ctx.applyTo(code);
        });
    }

    /** Replaces {@code toReplace} with {@code new Rect(x, y, w, h)} — the screen-region arg picker. */
    public void setRect(Expression toReplace, int x, int y, int w, int h) {
        replaceWithIntCtor(toReplace, SdkType.RECT, x, y, w, h);
    }

    /** Replaces {@code toReplace} with {@code new Point(x, y)} — the cursor-position arg picker. */
    public void setPoint(Expression toReplace, int x, int y) {
        replaceWithIntCtor(toReplace, SdkType.POINT, x, y);
    }

    /** A value to drop into a call argument slot by the multi-argument "Pick all on screen" session. */
    public sealed interface ArgValue {
        record RectVal(int x, int y, int w, int h) implements ArgValue {}
        record PointVal(int x, int y) implements ArgValue {}
        record ImageVal(String path) implements ArgValue {}
    }

    /**
     * Replaces several arguments of {@code mi} in a single rewrite — used by the "Pick all on screen"
     * session so a whole call's on-screen args are set atomically (applying them one-by-one would
     * invalidate the other cached argument nodes after the first re-parse). Keys are argument indices.
     */
    public void setCallArguments(MethodInvocation mi, java.util.Map<Integer, ArgValue> values) {
        if (values == null || values.isEmpty()) return;
        edit(mi, EditKind.BODY, true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            List<?> args = mi.arguments();
            for (var e : values.entrySet()) {
                int idx = e.getKey();
                if (idx < 0 || idx >= args.size()) continue;
                Expression slot = (Expression) args.get(idx);
                Expression replacement;
                SdkType imported;
                switch (e.getValue()) {
                    case ArgValue.RectVal r -> {
                        replacement = SdkNodes.intCtor(ast, SdkType.RECT, r.x(), r.y(), r.w(), r.h());
                        imported = SdkType.RECT;
                    }
                    case ArgValue.PointVal p -> {
                        replacement = SdkNodes.intCtor(ast, SdkType.POINT, p.x(), p.y());
                        imported = SdkType.POINT;
                    }
                    case ArgValue.ImageVal im -> {
                        ClassInstanceCreation cic = ast.newClassInstanceCreation();
                        cic.setType(SdkNodes.type(ast, SdkType.IMAGE_TEMPLATE));
                        cic.arguments().add(SdkNodes.templateArgument(ast, im.path()));
                        ImportManager.addTemplatesImport(cu, rewriter);
                        replacement = cic;
                        imported = SdkType.IMAGE_TEMPLATE;
                    }
                    default -> { continue; }
                }
                ImportManager.addImport(cu, rewriter, imported);
                rewriter.replace(slot, replacement, null);
            }
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /** {@code new <typeName>(a, b, …)} with int-literal arguments (helper for the batch rewrite). */
    /** Replaces {@code toReplace} with {@code new <type>(a, b, …)} using int-literal arguments. */
    private void replaceWithIntCtor(Expression toReplace, SdkType type, int... args) {
        edit(toReplace, EditKind.BODY, true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            ImportManager.addImport(cu, rewriter, type);
            rewriter.replace(toReplace, SdkNodes.intCtor(ast, type, args), null);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    // =================================================================================
    // METHODS
    // =================================================================================

    public void changeMethodParameterType(MethodDeclaration method, int index, ResolvedType newType) {
        edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.changeMethodParameterType(ctx(cu), code, method, index, newType));
    }

    public void addConstructorToClass(TypeDeclaration typeDecl) {
        edit(typeDecl, EditKind.SIGNATURE, true, (cu, code) -> MethodHandler.addConstructorToClass(cu, code, typeDecl));
    }

    public void updateMethodInvocation(MethodInvocation mi, String newScope, String newMethodName, List<ResolvedType> newParamTypes) {
        edit(mi, EditKind.BODY, true, (cu, code) -> MethodHandler.updateMethodInvocation(ctx(cu), code, mi, newScope, newMethodName, newParamTypes));
    }

    public void addArgumentToMethodInvocation(MethodInvocation mi, ExpressionType type) {
        edit(mi, EditKind.BODY, true, (cu, code) -> MethodHandler.addArgumentToMethodInvocation(ctx(cu), code, mi, type));
    }

    public void addArgumentToMethodInvocation(MethodInvocation mi, Expression expr) {
        edit(mi, EditKind.BODY, false, (cu, code) -> MethodHandler.addArgumentToMethodInvocation(cu, code, mi, expr));
    }

    public void addStringArgumentToMethodInvocation(MethodInvocation mi, String text) {
        edit(mi, EditKind.BODY, false, (cu, code) -> {
            StringLiteral newArg = cu.getAST().newStringLiteral();
            newArg.setLiteralValue(text);
            return MethodHandler.addArgumentToMethodInvocation(cu, code, mi, newArg);
        });
    }

    /**
     * Appends one more argument of {@code elementType} to {@code mi} — the writer behind the {@code ＋} a
     * varargs slot renders. The new argument is the type's default initializer, so it lands as an editable
     * slot rather than a compile error.
     */
    public void addVarargsArgument(MethodInvocation mi, ResolvedType elementType) {
        edit(mi, EditKind.BODY, true, (cu, code) ->
                MethodHandler.addVarargsArgument(ctx(cu), code, mi, elementType));
    }

    /** Drops the argument at {@code index} from {@code mi} — the {@code ✕} on a varargs argument. */
    public void deleteArgumentFromMethodInvocation(MethodInvocation mi, int index) {
        edit(mi, EditKind.BODY, false, (cu, code) -> MethodHandler.deleteArgument(cu, code, mi, index));
    }

    public void replaceWithMethodCall(Expression toReplace, ExpressionChoice.Method choice) {
        // If we're inside an ArrayCreation (new int[]{...}) replace the whole creation, not just the initializer.
        ASTNode targetNode = toReplace;
        if (toReplace instanceof ArrayInitializer && toReplace.getParent() instanceof ArrayCreation) {
            targetNode = toReplace.getParent();
        }
        Expression target = (Expression) targetNode;
        edit(target, EditKind.BODY, false, (cu, code) -> MethodHandler.replaceWithMethodCall(ctx(cu), code, target, choice));
    }

    public void addMethodCallStatement(BodyBlock targetBody, ExpressionChoice.Method choice, int index) {
        if (!canInsertAt(targetBody, index)) return;
        edit(targetBody.getAstNode(), EditKind.BODY, false, (cu, code) -> MethodHandler.addMethodCallStatement(ctx(cu), code, targetBody, choice, index));
    }

    public void addMethodToClass(TypeDeclaration typeDecl, String methodName, String returnType, int index) {
        edit(typeDecl, EditKind.SIGNATURE, true, (cu, code) -> MethodHandler.addMethodToClass(cu, code, typeDecl, methodName, ResolvedType.named(returnType), index));
    }

    /** Adds a function the user described in the Add Function dialog. See {@link FunctionDraft}. */
    public void addFunctionToClass(TypeDeclaration typeDecl, FunctionDraft draft, int index) {
        edit(typeDecl, EditKind.SIGNATURE, true,
                (cu, code) -> MethodHandler.addFunctionToClass(ctx(cu), code, typeDecl, draft, index));
    }

    public void deleteMethod(MethodDeclaration method) {
        edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.deleteMethodFromClass(cu, code, method));
    }

    public void renameMethodParameter(MethodDeclaration method, int index, String newName) {
        edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.renameMethodParameter(cu, code, method, index, newName));
    }

    public void setMethodReturnType(MethodDeclaration method, ResolvedType newType) {
        edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.setMethodReturnType(ctx(cu), code, method, newType));
    }

    public void addParameterToMethod(MethodDeclaration method, ResolvedType type, String paramName) {
        edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.addParameterToMethod(ctx(cu), code, method, type, paramName));
    }

    public void deleteParameterFromMethod(MethodDeclaration method, int index) {
        edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.deleteParameterFromMethod(cu, code, method, index));
    }

    public void renameMethod(MethodDeclaration method, String newName) {
        edit(method, EditKind.SIGNATURE, false, (cu, code) -> MethodHandler.renameMethod(cu, code, method, newName));
    }

    public void moveBodyDeclaration(BodyDeclaration decl, TypeDeclaration targetType, int index) {
        edit(targetType, EditKind.SIGNATURE, true, (cu, code) -> MethodHandler.moveBodyDeclaration(cu, code, decl, targetType, index));
    }

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setReturnExpression(ReturnStatement returnStmt, Object selection) {
        edit(returnStmt, EditKind.BODY, true, (cu, code) -> setReturnExpression(cu, code, returnStmt, selection, analyzer));
    }

    // =================================================================================
    // ENUMS
    // =================================================================================

    public void replaceWithEnumConstant(Expression toReplace, String enumType, String constantName) {
        edit(toReplace, EditKind.BODY, false, (cu, code) -> EnumManipulationHandler.replaceWithEnumConstant(ctx(cu), code, toReplace, enumType, constantName));
    }

    /** Replaces with a field reference {@code scope.fieldName} (same {@code QualifiedName} shape as an enum constant). */
    public void replaceWithFieldReference(Expression toReplace, String scope, String fieldName) {
        edit(toReplace, EditKind.BODY, false, (cu, code) -> EnumManipulationHandler.replaceWithEnumConstant(ctx(cu), code, toReplace, scope, fieldName));
    }

    public void renameEnum(EnumDeclaration enumNode, String newName) {
        edit(enumNode, EditKind.SIGNATURE, false, (cu, code) -> EnumManipulationHandler.renameEnum(cu, code, enumNode, newName));
    }

    public void addEnumConstant(EnumDeclaration enumNode, String constantName) {
        edit(enumNode, EditKind.SIGNATURE, false, (cu, code) -> EnumManipulationHandler.addEnumConstant(cu, code, enumNode, constantName));
    }

    public void deleteEnumConstant(EnumDeclaration enumNode, int index) {
        edit(enumNode, EditKind.SIGNATURE, false, (cu, code) -> EnumManipulationHandler.deleteEnumConstant(cu, code, enumNode, index));
    }

    public void renameEnumConstant(EnumDeclaration enumNode, int index, String newName) {
        edit(enumNode, EditKind.SIGNATURE, false, (cu, code) -> EnumManipulationHandler.renameEnumConstant(cu, code, enumNode, index, newName));
    }

    public void addEnumToClass(TypeDeclaration typeDecl, String enumName, int index) {
        edit(typeDecl, EditKind.SIGNATURE, true, (cu, code) -> EnumManipulationHandler.addEnumToClass(cu, code, typeDecl, enumName, index));
    }

    public void deleteEnumFromClass(EnumDeclaration enumDecl) {
        edit(enumDecl, EditKind.SIGNATURE, false, (cu, code) -> EnumManipulationHandler.deleteEnumFromClass(cu, code, enumDecl));
    }

    // =================================================================================
    // IMPORTS
    // =================================================================================

    /** The fully-qualified names of the current file's imports (read-only; no edit). */
    public List<String> getImports() {
        return ImportManager.listImports(getCompilationUnit());
    }

    public void addImport(String qualifiedName) {
        edit(getCompilationUnit(), EditKind.SIGNATURE, false, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            ImportManager.addImport(cu, rewriter, qualifiedName);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    public void removeImport(String qualifiedName) {
        edit(getCompilationUnit(), EditKind.SIGNATURE, false, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            ImportManager.removeImport(cu, rewriter, qualifiedName);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    // =================================================================================
    // LISTS
    // =================================================================================

    public void addElementToList(ASTNode listNode, ExpressionType type, int insertIndex) {
        edit(listNode, EditKind.BODY, true, (cu, code) -> ListHandler.addElementToList(ctx(cu), code, listNode, type, insertIndex));
    }

    /**
     * Inserts an element built from an expression-menu {@code selection} (an {@link ExpressionType} or an
     * {@link com.botmaker.studio.parser.ExpressionChoice}) at {@code insertIndex}. Powers the type-aware list "+" menu.
     * {@code elementType} is the list's inferred element type, used to build sensible default arguments.
     */
    public void insertIntoList(ASTNode listNode, int insertIndex, Object selection, ResolvedType elementType) {
        edit(listNode, EditKind.BODY, true, (cu, code) -> ListHandler.insertChoiceIntoList(ctx(cu), code, listNode, insertIndex, selection, elementType));
    }

    /** Moves the list element at {@code fromIndex} to {@code toIndex} (used by the per-row up/down buttons). */
    public void moveListElement(ASTNode listNode, int fromIndex, int toIndex) {
        edit(listNode, EditKind.BODY, false, (cu, code) -> ListHandler.moveElement(cu, code, listNode, fromIndex, toIndex));
    }

    /** Adds a {@code new ImageTemplate("")} element to the list — drives the per-element image picker. */
    public void addImageTemplateToList(ASTNode listNode, int insertIndex) {
        edit(listNode, EditKind.BODY, true, (cu, code) -> ListHandler.addImageTemplateElement(ctx(cu), code, listNode, insertIndex));
    }

    public void deleteElementFromList(ASTNode listNode, int elementIndex) {
        edit(listNode, EditKind.BODY, false, (cu, code) -> ListHandler.deleteElementFromList(cu, code, listNode, elementIndex));
    }

    // =================================================================================
    // INITIALIZERS / EXPRESSIONS
    // =================================================================================

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setVariableInitializer(VariableDeclarationStatement varDecl, Object selection) {
        edit(varDecl, EditKind.BODY, true, (cu, code) -> setVariableInitializer(ctx(cu), code, varDecl, selection));
    }

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setFieldInitializer(FieldDeclaration fieldDecl, Object selection) {
        edit(fieldDecl, EditKind.SIGNATURE, true, (cu, code) -> setFieldInitializer(ctx(cu), code, fieldDecl, selection));
    }

    public void setFieldInitializerToDefault(FieldDeclaration fieldDecl, ResolvedType fieldType) {
        ExpressionType defaultType = mapTypeToExpressionType(fieldType.simpleName());
        edit(fieldDecl, EditKind.SIGNATURE, true, (cu, code) -> setFieldInitializer(ctx(cu), code, fieldDecl, defaultType));
    }

    /**
     * Fills an expression slot with a palette block dropped onto it — the drag counterpart of picking the same
     * call from the expression menu.
     *
     * <p>The block is built as the statement it normally is and then unwrapped, rather than given a second
     * expression-shaped factory: a {@link BlockType.LibraryCall}'s statement form is only the invocation plus a
     * semicolon, so the statement builder already knows the receiver, the default overload and the import. A
     * block whose statement is not an {@code ExpressionStatement} has no expression to contribute and is left
     * alone — the drag layer refuses those before they reach here, and this is the second door.
     */
    public void fillSlotFromPalette(Expression toReplace, BlockType type) {
        edit(toReplace, EditKind.BODY, true, (cu, code) -> {
            EditContext context = ctx(cu);
            Statement built = NodeCreator.createDefaultStatement(context, type, toReplace);
            if (!(built instanceof ExpressionStatement stmt)) return code;
            // copySubtree: the built expression is still parented to the throwaway statement, and a rewrite
            // may only place a node that belongs to no other tree.
            context.rewriter().replace(toReplace, ASTNode.copySubtree(cu.getAST(), stmt.getExpression()), null);
            return AstRewriteHelper.applyRewrite(context.rewriter(), code);
        });
    }

    /**
     * Moves an existing statement's value into an expression slot: the slot takes the expression, and the
     * statement it came from is removed. Dropping {@code Window.title();} onto a print's argument leaves
     * {@code print(Window.title())} and no orphan line.
     *
     * <p>A move, not a copy, because the alternative is worse in both directions: leaving the statement behind
     * duplicates a call the user dragged away, and an expression statement whose value is now consumed
     * elsewhere is exactly the dead line they were trying to get rid of.
     */
    public void moveExpressionIntoSlot(Expression toReplace, ExpressionStatement source) {
        edit(toReplace, EditKind.BODY, true, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            rewriter.replace(toReplace, ASTNode.copySubtree(cu.getAST(), source.getExpression()), null);
            rewriter.remove(source, null);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    public void replaceExpression(Expression toReplace, ExpressionType type) {
        edit(toReplace, EditKind.BODY, true, (cu, code) -> replaceExpression(cu, code, toReplace, type, analyzer));
    }

    public void replaceLiteralValue(Expression toReplace, String newLiteralValue) {
        edit(toReplace, EditKind.BODY, false, (cu, code) -> replaceLiteral(cu, code, toReplace, newLiteralValue));
    }

    public void replaceSimpleName(SimpleName toReplace, String newName) {
        edit(toReplace, EditKind.BODY, false, (cu, code) -> AstRewriteHelper.renameSimpleName(cu, code, toReplace, newName));
    }

    /**
     * Renames a lambda parameter (the name chip on {@code LambdaCallBlock}), carrying its references in the
     * lambda body along — same reason as {@link #renameForEachVariable}.
     */
    public void renameLambdaParameter(SimpleName toRename, String newName) {
        edit(toRename, EditKind.BODY, false, (cu, code) -> AstRewriteHelper.renameLambdaParameter(cu, code, toRename, newName));
    }

    /**
     * Renames an enhanced-for loop variable, updating its references in the loop body too so the code still
     * compiles. Plain {@link #replaceSimpleName} renames only the declaration, which broke compilation.
     */
    public void renameForEachVariable(SimpleName toRename, String newName) {
        edit(toRename, EditKind.BODY, false, (cu, code) -> AstRewriteHelper.renameForEachVariable(cu, code, toRename, newName));
    }

    // =================================================================================
    // STATEMENTS / FLOW
    // =================================================================================

    public void addStatement(BodyBlock targetBody, BlockType type, int index) {
        if (!canModify(targetBody.getAstNode(), EditKind.BODY) || !canInsertAt(targetBody, index)) return;
        CompilationUnit cu = getCompilationUnit();
        if (cu == null) return;
        String newCode = addStatement(ctx(cu), getCurrentCode(), targetBody, type, index);
        if (newCode == null) return;
        // The refusal has to take the announcement with it: a BlockAddedEvent for a block that was never
        // published scrolls the canvas to a block that isn't there.
        if (!triggerUpdate(newCode, true, targetBody.getAstNode(), EditKind.BODY)) return;
        eventBus.publish(new CoreApplicationEvents.BlockAddedEvent(type));
    }

    public void deleteStatement(Statement toDelete) {
        if (!canDelete(toDelete)) return;
        edit(toDelete, EditKind.BODY, false, (cu, code) -> deleteStatement(cu, code, toDelete));
    }

    public void pasteCode(BodyBlock targetBody, int index, String codeToPaste) {
        if (!canInsertAt(targetBody, index)) return;
        edit(targetBody.getAstNode(), EditKind.BODY, false,
                (cu, code) -> pasteCodeString(ctx(cu), code, targetBody, index, codeToPaste));
    }

    /**
     * A move is two edits, so both ends must permit it: dragging a statement <em>out of</em> a locked body
     * removes code from it just as surely as dropping one in adds code. Checking only the destination would
     * let a drag empty out a generated method.
     */
    public void moveStatement(StatementBlock blockToMove, BodyBlock sourceBody, BodyBlock targetBody, int targetIndex) {
        if (!canModify(sourceBody.getAstNode(), EditKind.BODY) || !canInsertAt(targetBody, targetIndex)) return;
        // A pinned return is not the user's to move out of its body either — dragging it away is a delete.
        if (blockToMove.getAstNode() instanceof Statement moved && !canDelete(moved)) return;
        edit(targetBody.getAstNode(), EditKind.BODY, false,
                (cu, code) -> moveStatement(cu, code, blockToMove, sourceBody, targetBody, targetIndex));
    }

    public void deleteElseFromIfStatement(IfStatement ifStmt) {
        edit(ifStmt, EditKind.BODY, false, (cu, code) -> deleteElseFromIfStatement(cu, code, ifStmt));
    }

    public void convertElseToElseIf(IfStatement ifStmt) {
        edit(ifStmt, EditKind.BODY, false, (cu, code) -> convertElseToElseIf(cu, code, ifStmt));
    }

    public void addElseToIfStatement(IfStatement ifStmt) {
        edit(ifStmt, EditKind.BODY, false, (cu, code) -> addElseToIfStatement(cu, code, ifStmt));
    }

    public void addCaseToSwitch(SwitchStatement switchStmt) {
        edit(switchStmt, EditKind.BODY, true, (cu, code) -> addCaseToSwitch(cu, code, switchStmt, List.of()));
    }

    /**
     * Appends one case per name in {@code caseLabels} (unqualified constants — enum labels are never qualified),
     * each with its own closing {@code break}, in a single rewrite so "add all remaining cases" is one undo step.
     */
    public void addCasesToSwitch(SwitchStatement switchStmt, List<String> caseLabels) {
        if (caseLabels == null || caseLabels.isEmpty()) return;
        edit(switchStmt, EditKind.BODY, true, (cu, code) -> addCaseToSwitch(cu, code, switchStmt, caseLabels));
    }

    /**
     * Puts every {@code switch} in the active file into the shape the editor renders — the missing
     * {@code break} on a falling-through colon case, braces around a bare arrow-rule body (see
     * {@link SwitchNormalizer}). Called when a file is opened, so the {@code break} the switch block draws as
     * fixed case chrome is backed by a real one and every branch it offers as a drop target has a block to drop
     * into. A no-op — no edit, no history entry, no {@code CodeUpdatedEvent} — when both are already true,
     * which is the normal case.
     */
    public void normalizeSwitches() {
        CompilationUnit cu = getCompilationUnit();
        if (cu == null) return;
        String newCode = SwitchNormalizer.normalize(cu, getCurrentCode());
        // No target: this normalises every switch in the file, so there is no one block it is editing.
        if (newCode != null) triggerUpdate(newCode, true, null, null);
    }

    public void moveSwitchCase(SwitchCase caseNode, boolean moveUp) {
        edit(caseNode, EditKind.BODY, false, (cu, code) -> moveSwitchCase(cu, code, caseNode, moveUp));
    }

    // --- The Matches switch (guarded arrow rules; see MatchesSwitchHandler) ---

    /** Rewrites one check's templates to exactly {@code paths}, keeping its any/all mode. */
    public void setMatchesCheckTemplates(MethodInvocation call, List<String> paths) {
        edit(call, EditKind.BODY, true,
                (cu, code) -> MatchesSwitchHandler.setCheckTemplates(cu, code, call, paths));
    }

    /** Flips one check between "any of" ({@code hasAny}) and "all of" ({@code hasAll}). */
    public void setMatchesCheckMode(MethodInvocation call, MatchesCheck check) {
        edit(call, EditKind.BODY, true,
                (cu, code) -> MatchesSwitchHandler.setCheckMode(cu, code, call, check));
    }

    /**
     * Rewrites a branch's whole condition to {@code newTree} — every composition gesture the block offers goes
     * through here, having built the new tree with {@link com.botmaker.studio.parser.handlers.GuardTree}. A null
     * tree is a refused gesture (removing the last condition, flipping a container to the word it already has),
     * so it is a no-op rather than an edit that writes nothing.
     */
    public void setMatchesGuard(SwitchCase caseNode, MatchesSwitchHandler.Guard newTree) {
        if (caseNode == null || newTree == null) return;
        edit(caseNode, EditKind.BODY, true,
                (cu, code) -> MatchesSwitchHandler.setGuard(cu, code, caseNode, newTree));
    }

    /**
     * Adds a branch seeded with {@code templatePath}, before the {@code default} rule. A null path is a no-op:
     * a guard with no templates wouldn't compile, so there is nothing to insert.
     */
    public void addMatchesCase(SwitchStatement switchStmt, String templatePath) {
        if (templatePath == null) return;
        edit(switchStmt, EditKind.BODY, true,
                (cu, code) -> MatchesSwitchHandler.addCase(
                        cu, code, switchStmt, MatchesCheck.ANY, List.of(templatePath)));
    }

    public void removeMatchesCase(SwitchCase caseNode) {
        edit(caseNode, EditKind.BODY, true, (cu, code) -> MatchesSwitchHandler.removeCase(cu, code, caseNode));
    }

    // =================================================================================
    // COMMENTS / OPERATORS
    // =================================================================================

    public void updateComment(Comment commentNode, String newText) {
        edit(commentNode, EditKind.BODY, false, (cu, code) -> updateComment(code, commentNode, newText));
    }

    public void deleteComment(Comment commentNode) {
        edit(commentNode, EditKind.BODY, false, (cu, code) -> deleteComment(code, commentNode));
    }

    public void updateAssignmentOperator(ASTNode node, String newOperatorSymbol) {
        if (!canModify(node, EditKind.BODY)) return;
        String newCode = null;
        if (node instanceof Assignment) {
            Assignment.Operator op = ASSIGNMENT_OPS.get(newOperatorSymbol);
            if (op != null) newCode = OperatorReplacementHandler.replaceAssignmentOperator(getCompilationUnit(), getCurrentCode(), (Assignment) node, op);
        } else if (node instanceof PrefixExpression) {
            PrefixExpression.Operator op = PREFIX_OPS.get(newOperatorSymbol);
            if (op != null) newCode = OperatorReplacementHandler.replacePrefixOperator(getCompilationUnit(), getCurrentCode(), (PrefixExpression) node, op);
        } else if (node instanceof PostfixExpression) {
            PostfixExpression.Operator op = POSTFIX_OPS.get(newOperatorSymbol);
            if (op != null) newCode = OperatorReplacementHandler.replacePostfixOperator(getCompilationUnit(), getCurrentCode(), (PostfixExpression) node, op);
        }
        if (newCode != null) triggerUpdate(newCode, node);
    }

    public void updateBinaryOperator(ASTNode node, String newOperatorSymbol) {
        if (!canModify(node, EditKind.BODY)) return;
        if (node instanceof InfixExpression) {
            InfixExpression.Operator op = INFIX_OPS.get(newOperatorSymbol);
            if (op != null) {
                String newCode = OperatorReplacementHandler.replaceInfixOperator(getCompilationUnit(), getCurrentCode(), (InfixExpression) node, op);
                triggerUpdate(newCode, node);
            }
        }
    }

    private ExpressionType mapTypeToExpressionType(String uiTargetType) {
        return switch (uiTargetType) {
            case "number" -> ExpressionCatalog.NUMBER;
            case "boolean" -> ExpressionCatalog.FALSE;
            case "String" -> ExpressionCatalog.TEXT;
            case "list" -> ExpressionCatalog.LIST;
            case "enum" -> ExpressionCatalog.ENUM_CONSTANT;
            default -> ExpressionCatalog.VARIABLE;
        };
    }

    // =================================================================================
    // PURE TRANSFORMS — bespoke AST shapes (formerly AstRewriter). Each is (cu, code, …) -> code.
    // =================================================================================

    private static String setVariableInitializer(EditContext ctx, String originalCode, VariableDeclarationStatement varDecl, Object selection) {
        return setFragmentInitializer(ctx, originalCode,
                (VariableDeclarationFragment) varDecl.fragments().getFirst(), varDecl.getType().toString(), selection);
    }

    private static String setFieldInitializer(EditContext ctx, String originalCode, FieldDeclaration fieldDecl, Object selection) {
        return setFragmentInitializer(ctx, originalCode,
                (VariableDeclarationFragment) fieldDecl.fragments().getFirst(), fieldDecl.getType().toString(), selection);
    }

    private static String setFragmentInitializer(EditContext ctx, String originalCode,
                                                 VariableDeclarationFragment fragment, String declaredType, Object selection) {
        ResolvedType contextType = ResolvedType.named(ProjectAnalyzer.unwrapCollectionType(declaredType));
        Expression newExpr = NodeCreator.createExpression(ctx, selection, contextType);
        if (newExpr == null) return originalCode;
        if (fragment.getInitializer() == null) {
            ctx.rewriter().set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, newExpr, null);
        } else {
            ctx.rewriter().replace(fragment.getInitializer(), newExpr, null);
        }
        return ctx.applyTo(originalCode);
    }

    private static String setReturnExpression(CompilationUnit cu, String originalCode, ReturnStatement returnStmt, Object selection, ProjectAnalyzer analyzer) {
        EditContext ctx = EditContext.of(cu, analyzer, null);
        ResolvedType contextType = ProjectAnalyzer.inferExpectedType(returnStmt.getExpression() != null
                ? returnStmt.getExpression() : returnStmt);
        Expression newExpr = NodeCreator.createExpression(ctx, selection, contextType);
        if (newExpr == null) return originalCode;
        if (returnStmt.getExpression() == null) {
            ctx.rewriter().set(returnStmt, ReturnStatement.EXPRESSION_PROPERTY, newExpr, null);
        } else {
            ctx.rewriter().replace(returnStmt.getExpression(), newExpr, null);
        }
        return ctx.applyTo(originalCode);
    }

    private static String replaceExpression(CompilationUnit cu, String originalCode, Expression toReplace, ExpressionType type, ProjectAnalyzer analyzer) {
        EditContext ctx = EditContext.of(cu, analyzer, null);
        String contextType = ProjectAnalyzer.inferExpectedType(toReplace).simpleName();
        Expression newExpression = NodeCreator.createDefaultExpression(ctx, type, contextType);
        if (newExpression == null) return originalCode;
        ctx.rewriter().replace(toReplace, newExpression, null);
        return ctx.applyTo(originalCode);
    }

    private static String replaceLiteral(CompilationUnit cu, String originalCode, Expression toReplace, String newLiteralValue) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Expression newExpression;
        if (toReplace instanceof StringLiteral) {
            StringLiteral newString = ast.newStringLiteral();
            newString.setLiteralValue(newLiteralValue);
            newExpression = newString;
        } else if (toReplace instanceof NumberLiteral) {
            newExpression = ast.newNumberLiteral(newLiteralValue);
        } else if (toReplace instanceof BooleanLiteral) {
            newExpression = ast.newBooleanLiteral(Boolean.parseBoolean(newLiteralValue));
        } else {
            return originalCode;
        }
        rewriter.replace(toReplace, newExpression, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String replaceNode(CompilationUnit cu, String originalCode, ASTNode oldNode, ASTNode newNode) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        rewriter.replace(oldNode, newNode, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String addStatement(EditContext ctx, String originalCode, BodyBlock targetBody, BlockType type, int index) {
        ASTRewrite rewriter = ctx.rewriter();
        Statement newStatement = NodeCreator.createDefaultStatement(ctx, type, targetBody.getAstNode());
        if (newStatement == null) return originalCode;
        ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        PendingInsert deferred = insertIntoList(listRewrite, targetBody, newStatement, index, newStatement.toString());
        if (deferred != null) {
            // The rewriter still carries any imports the new statement needs; only the placement is textual.
            return AstRewriteHelper.applyRewriteAndInsertAt(rewriter, originalCode, deferred.offset(), deferred.text());
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String deleteStatement(CompilationUnit cu, String originalCode, Statement statement) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        if (statement instanceof IfStatement ifStmt && ifStmt.getParent() instanceof IfStatement parent
                && parent.getElseStatement() == ifStmt) {
            Statement childElse = ifStmt.getElseStatement();
            if (childElse != null) {
                ASTNode moveTarget = rewriter.createMoveTarget(childElse);
                rewriter.replace(ifStmt, moveTarget, null);
                return AstRewriteHelper.applyRewrite(rewriter, originalCode);
            }
        }
        rewriter.remove(statement, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String pasteCodeString(EditContext ctx, String originalCode, BodyBlock targetBody, int index,
                                          String codeToPaste) {
        ASTRewrite rewriter = ctx.rewriter();
        // The clipboard carries bare source with no import context (an in-app copy is just node.toString()),
        // so a pasted ImageFinder.find(...) would land in a file that never imported ImageFinder. Bring the
        // imports along; addImportForSimpleName is a no-op for anything already imported or unresolvable.
        for (String typeName : referencedTypeNames(codeToPaste)) {
            ctx.addImportForSimpleName(typeName);
        }
        Statement placeHolder = (Statement) rewriter.createStringPlaceholder(codeToPaste, ASTNode.EMPTY_STATEMENT);
        ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        PendingInsert deferred = insertIntoList(listRewrite, targetBody, placeHolder, index, codeToPaste);
        if (deferred != null) {
            return AstRewriteHelper.applyRewriteAndInsertAt(rewriter, originalCode, deferred.offset(), deferred.text());
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String moveStatement(CompilationUnit cu, String originalCode, StatementBlock blockToMove, BodyBlock sourceBody, BodyBlock targetBody, int targetIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        // A block's backing node isn't always the Statement itself: a bare expression block (e.g.
        // ImageFinder.find(...)) stores the MethodInvocation, whose enclosing ExpressionStatement is what
        // the list rewrite operates on. Walk up to the nearest Statement rather than casting blindly.
        Statement statement = enclosingStatement(blockToMove.getAstNode());
        if (statement == null) return originalCode;
        ListRewrite sourceListRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, sourceBody);
        ListRewrite targetListRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        Statement copiedStatement = (Statement) ASTNode.copySubtree(ast, statement);
        sourceListRewrite.remove(statement, null);
        PendingInsert deferred =
                insertIntoList(targetListRewrite, targetBody, copiedStatement, targetIndex, statement.toString());
        if (deferred != null) {
            // The removal stays on the rewriter; the RangeMarker keeps the drop offset correct across it.
            return AstRewriteHelper.applyRewriteAndInsertAt(rewriter, originalCode, deferred.offset(), deferred.text());
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /** The nearest enclosing {@link Statement} of {@code node} (itself if already one), or {@code null}. */
    private static Statement enclosingStatement(ASTNode node) {
        while (node != null && !(node instanceof Statement)) {
            node = node.getParent();
        }
        return (Statement) node;
    }

    private static String convertElseToElseIf(CompilationUnit cu, String originalCode, IfStatement ifStatement) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Statement elseStatement = ifStatement.getElseStatement();
        if (elseStatement != null && elseStatement.getNodeType() == ASTNode.BLOCK) {
            IfStatement newElseIf = ast.newIfStatement();
            newElseIf.setExpression(ast.newBooleanLiteral(true));
            newElseIf.setThenStatement((Block) ASTNode.copySubtree(ast, elseStatement));
            rewriter.replace(elseStatement, newElseIf, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String addElseToIfStatement(CompilationUnit cu, String originalCode, IfStatement ifStatement) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        if (ifStatement.getElseStatement() == null) {
            rewriter.set(ifStatement, IfStatement.ELSE_STATEMENT_PROPERTY, ast.newBlock(), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String deleteElseFromIfStatement(CompilationUnit cu, String originalCode, IfStatement ifStatement) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        if (ifStatement.getElseStatement() != null) {
            rewriter.remove(ifStatement.getElseStatement(), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /**
     * Appends cases to {@code switchStmt}, each closed by its own {@code break} — the break is not optional, see
     * {@code SwitchNormalizer}. With {@code caseLabels} empty a single case is added labelled with the next free
     * ordinal, which is the "+ Add Case" button's behaviour; otherwise one case per given constant name.
     */
    private static String addCaseToSwitch(CompilationUnit cu, String originalCode, SwitchStatement switchStmt,
                                          List<String> caseLabels) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite listRewrite = rewriter.getListRewrite(switchStmt, SwitchStatement.STATEMENTS_PROPERTY);

        List<Expression> labels = new ArrayList<>();
        if (caseLabels.isEmpty()) {
            int count = 0;
            for (Object o : switchStmt.statements()) {
                if (o instanceof SwitchCase) count++;
            }
            labels.add(ast.newNumberLiteral(String.valueOf(count)));
        } else {
            for (String name : caseLabels) labels.add(ast.newSimpleName(name));
        }

        // New cases go before `default:`, not after it. Java allows either, but a default that isn't last reads
        // as a mistake — and the seeded switch now ships with one, so appending would put every case after it.
        SwitchCase defaultCase = null;
        for (Object o : switchStmt.statements()) {
            if (o instanceof SwitchCase sc && sc.isDefault()) { defaultCase = sc; break; }
        }

        for (Expression label : labels) {
            SwitchCase newCase = ast.newSwitchCase();
            newCase.expressions().add(label);
            BreakStatement newBreak = ast.newBreakStatement();
            if (defaultCase != null) {
                listRewrite.insertBefore(newCase, defaultCase, null);
                listRewrite.insertBefore(newBreak, defaultCase, null);
            } else {
                listRewrite.insertLast(newCase, null);
                listRewrite.insertLast(newBreak, null);
            }
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String moveSwitchCase(CompilationUnit cu, String originalCode, SwitchCase caseNode, boolean moveUp) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        SwitchStatement parent = (SwitchStatement) caseNode.getParent();
        List<Statement> statements = parent.statements();

        List<List<Statement>> chunks = new ArrayList<>();
        List<Statement> currentChunk = null;
        for (Statement stmt : statements) {
            if (stmt instanceof SwitchCase) {
                if (currentChunk != null) chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
            }
            if (currentChunk != null) currentChunk.add(stmt);
        }
        if (currentChunk != null) chunks.add(currentChunk);

        int targetIndex = -1;
        for (int i = 0; i < chunks.size(); i++) {
            if (!chunks.get(i).isEmpty() && chunks.get(i).getFirst() == caseNode) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex == -1) return originalCode;

        int neighborIndex = moveUp ? targetIndex - 1 : targetIndex + 1;
        if (neighborIndex < 0 || neighborIndex >= chunks.size()) return originalCode;

        List<Statement> targetChunk = chunks.get(targetIndex);
        List<Statement> neighborChunk = chunks.get(neighborIndex);
        ListRewrite listRewrite = rewriter.getListRewrite(parent, SwitchStatement.STATEMENTS_PROPERTY);

        if (moveUp) {
            ASTNode insertPoint = neighborChunk.getFirst();
            for (Statement stmt : targetChunk) {
                ASTNode moveTarget = rewriter.createMoveTarget(stmt);
                listRewrite.insertBefore(moveTarget, insertPoint, null);
            }
        } else {
            ASTNode insertPoint = targetChunk.getFirst();
            for (Statement stmt : neighborChunk) {
                ASTNode moveTarget = rewriter.createMoveTarget(stmt);
                listRewrite.insertBefore(moveTarget, insertPoint, null);
            }
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String updateComment(String originalCode, Comment commentNode, String newText) {
        try {
            IDocument document = new Document(originalCode);
            String replacement = newText.contains("\n") ? "/* " + newText + " */" : "// " + newText;
            document.replace(commentNode.getStartPosition(), commentNode.getLength(), replacement);
            return document.get();
        } catch (Exception e) {
            return originalCode;
        }
    }

    private static String deleteComment(String originalCode, Comment commentNode) {
        try {
            IDocument document = new Document(originalCode);
            document.replace(commentNode.getStartPosition(), commentNode.getLength(), "");
            return document.get();
        } catch (Exception e) {
            return originalCode;
        }
    }

    /**
     * The simple type names a pasted snippet refers to, for import resolution.
     *
     * <p>Parsed rather than regex-matched: the snippet is re-parsed as a statement sequence (no bindings
     * needed — {@link ImportManager} resolves the names itself) and two shapes are collected, which between
     * them cover what a copied block looks like:
     * <ul>
     *   <li>{@link SimpleType} — declarations and {@code new Foo(...)}</li>
     *   <li>the scope of a static call or constant, i.e. the {@code Foo} in {@code Foo.bar()} /
     *       {@code Foo.BAZ}, which appears only as a {@link SimpleName} and has no type node at all</li>
     * </ul>
     *
     * <p>Best-effort: unparseable clipboard text yields an empty set rather than failing the paste, and a
     * false positive (a variable named like a type) is harmless — the import simply won't resolve.
     */
    private static java.util.Set<String> referencedTypeNames(String snippet) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_STATEMENTS);
            parser.setSource(snippet.toCharArray());
            parser.createAST(null).accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleType node) {
                    names.add(node.getName().getFullyQualifiedName());
                    return true;
                }

                @Override
                public boolean visit(MethodInvocation node) {
                    if (node.getExpression() instanceof SimpleName scope) addIfTypeLike(scope);
                    return true;
                }

                @Override
                public boolean visit(QualifiedName node) {
                    if (node.getQualifier() instanceof SimpleName scope) addIfTypeLike(scope);
                    return true;
                }

                /** Uppercase-initial is the only signal available without bindings — the JLS naming convention. */
                private void addIfTypeLike(SimpleName name) {
                    String id = name.getIdentifier();
                    if (!id.isEmpty() && Character.isUpperCase(id.charAt(0))) names.add(id);
                }
            });
        } catch (Exception e) {
            return java.util.Set.of();
        }
        return names;
    }

    /**
     * Inserts {@code newStatement} at a {@link BodyBlock} child index.
     *
     * <p>Returns a {@link PendingInsert} when the insertion could <b>not</b> be expressed on the rewriter and
     * has to be done as a raw text edit instead — see {@link #commentAnchor}. In that case the caller must
     * finish through {@link AstRewriteHelper#applyRewriteAndInsertAt} and {@code newStatement} is not added to
     * the list. {@code null} means the rewriter has it.
     */
    private static PendingInsert insertIntoList(ListRewrite listRewrite, BodyBlock body, Statement newStatement,
                                                int relativeIndex, String textIfDeferred) {
        ASTNode node = body.getAstNode();
        if (node instanceof Block) {
            Comment anchor = commentAnchor(body, relativeIndex);
            if (anchor != null) return PendingInsert.after(anchor, textIfDeferred, body);

            // relativeIndex counts BodyBlock children, which include CommentBlocks — a Comment is not a JDT
            // Statement and so isn't in Block.statements(). Inserting at the raw index into a body whose only
            // child is a comment asked to insert at index 1 of an empty statement list and threw. Translate to
            // the statements() index by counting the children that DO occupy a statements() slot — i.e. all but
            // the comments. (Checking `instanceof Statement` is wrong: an expression-backed statement block —
            // e.g. a bare method call — has a MethodInvocation as its node, not the ExpressionStatement, yet it
            // still occupies a slot.)
            listRewrite.insertAt(newStatement, toStatementIndex(body, relativeIndex), null);
        } else if (node instanceof SwitchCase caseNode) {
            // Colon form only. A case's statements are siblings of its label here, so offsetting from the label
            // is the insertion point. An arrow rule's body is a Block instead, and this arithmetic would write
            // the statement in front of that Block — a bare block among arrow rules, which doesn't parse and
            // takes the branch's contents off the canvas with it. BlockConverter backs an arrow rule's body
            // with its Block precisely so nothing reaches here; refuse rather than trust that silently.
            if (caseNode.isSwitchLabeledRule()) {
                throw new IllegalArgumentException(
                        "an arrow rule's body is its Block, not its label — see BlockConverter.labeledRuleBody");
            }
            SwitchStatement parent = (SwitchStatement) caseNode.getParent();
            List<?> allStatements = parent.statements();
            int caseIndex = allStatements.indexOf(caseNode);
            int absoluteIndex = caseIndex + 1 + relativeIndex;
            listRewrite.insertAt(newStatement, absoluteIndex, null);
        }
        return null;
    }

    /**
     * The comment the insertion point sits immediately after, or {@code null} when it doesn't.
     *
     * <p>Why this exists: a {@link Comment} occupies no slot in {@code Block.statements()}, and JDT treats a
     * leading comment as part of the <em>extended</em> source range of the statement that follows it. So there
     * is no statements() index that means "after the comment" — inserting at the translated index puts the new
     * code <b>before</b> it, which is why "Paste After" on a comment block pasted before the comment. The
     * position is expressible only in raw offsets, hence the text-edit path.
     */
    private static Comment commentAnchor(BodyBlock body, int relativeIndex) {
        var children = body.getStatements();
        if (relativeIndex <= 0 || relativeIndex > children.size()) return null;
        return children.get(relativeIndex - 1).getAstNode() instanceof Comment c ? c : null;
    }

    /** A text insertion the rewriter can't express: {@code text} goes at {@code offset} in the original code. */
    private record PendingInsert(int offset, String text) {

        /** Placed at the end of {@code anchor}'s line, indented to match it. */
        static PendingInsert after(Comment anchor, String text, BodyBlock body) {
            if (text == null || text.isBlank()) return null;
            String indent = indentOf(anchor, body);
            return new PendingInsert(anchor.getStartPosition() + anchor.getLength(),
                    "\n" + indent + text.strip());
        }

        /** The anchor's own column, so the inserted statement lines up with the comment rather than the brace. */
        private static String indentOf(Comment anchor, BodyBlock body) {
            ASTNode root = anchor.getRoot();
            if (!(root instanceof CompilationUnit cu)) return "    ";
            int line = cu.getLineNumber(anchor.getStartPosition());
            if (line < 1) return "    ";
            int lineStart = cu.getPosition(line, 0);
            int column = anchor.getStartPosition() - lineStart;
            return column > 0 ? " ".repeat(column) : "    ";
        }
    }
}
