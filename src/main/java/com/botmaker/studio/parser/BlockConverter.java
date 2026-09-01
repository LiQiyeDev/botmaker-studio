package com.botmaker.studio.parser;

import com.botmaker.studio.blocks.ClassBlock;
import com.botmaker.studio.blocks.expr.*;
import com.botmaker.studio.blocks.flow.*;
import com.botmaker.studio.blocks.func.ConstructorBlock;
import com.botmaker.studio.blocks.func.LibraryCallBlock;
import com.botmaker.studio.blocks.func.MainBlock;
import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.blocks.loop.DoWhileBlock;
import com.botmaker.studio.blocks.loop.ForBlock;
import com.botmaker.studio.blocks.loop.WhileBlock;
import com.botmaker.studio.blocks.misc.CommentBlock;
import com.botmaker.studio.blocks.misc.InitializerBlock;
import com.botmaker.studio.blocks.misc.PrintBlock;
import com.botmaker.studio.blocks.misc.ReadInputBlock;
import com.botmaker.studio.blocks.vision.LambdaCallBlock;
import com.botmaker.studio.blocks.var.AssignmentBlock;
import com.botmaker.studio.blocks.var.DeclareClassVariableBlock;
import com.botmaker.studio.blocks.var.DeclareEnumBlock;
import com.botmaker.studio.blocks.var.VariableDeclarationBlock;
import com.botmaker.studio.core.*;
import com.botmaker.studio.palette.BotMakerApi;
import com.botmaker.studio.palette.InputKind;
import com.botmaker.studio.parser.handlers.BranchChainHandler;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.parser.helpers.FileTypeDetector;
import com.botmaker.studio.parser.handlers.MatchesSwitchHandler;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.*;

import java.nio.file.Path;
import java.util.*;

import static com.botmaker.studio.suggestions.ProjectAnalyzer.createCompilationUnit;

/**
 * Converts Java source into a tree of {@link CodeBlock}s. Stateless: all per-parse state is
 * carried in an immutable {@link ParseContext} threaded through the recursion, so a single
 * instance is safe to reuse across files and edits.
 */
public class BlockConverter {

    private final ProjectState state;
    private final ProjectConfig config;

    public BlockConverter(ProjectConfig config, ProjectState state) {
        this.config = config;
        this.state = state;
    }

    /** Result of a {@link #convert} call: the root block plus the binding-resolved CU it was built from. */
    public record ConvertResult(AbstractCodeBlock root, CompilationUnit cu) {}

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public ConvertResult convert(String javaCode,
                                 Map<ASTNode, CodeBlock> nodeToBlockMap,
                                 BlockDragAndDropManager manager,
                                 boolean isReadOnly,
                                 boolean markNewIdentifiersAsUnedited) {
        return convert(null, javaCode, nodeToBlockMap, manager, isReadOnly, markNewIdentifiersAsUnedited);
    }

    /**
     * The same conversion, over a {@code CompilationUnit} the caller has already parsed.
     *
     * <p>{@code CodeEditorService} needs the parsed file in {@code ProjectState} <em>before</em> the blocks are
     * built — every screen that reacts to a write by re-reading the file depends on it — but it must not pay for
     * a second parse to get there. Passing {@code null} parses here, exactly as this always did.
     */
    public ConvertResult convert(CompilationUnit parsed,
                                 String javaCode,
                                 Map<ASTNode, CodeBlock> nodeToBlockMap,
                                 BlockDragAndDropManager manager,
                                 boolean isReadOnly,
                                 boolean markNewIdentifiersAsUnedited) {
        try {
            CompilationUnit ast = parsed != null ? parsed : parse(javaCode);

            List<Comment> comments = new ArrayList<>();
            for (Object obj : ast.getCommentList()) {
                if (obj instanceof Comment c && !(obj instanceof Javadoc)) comments.add(c);
            }

            ParseContext ctx = new ParseContext(
                    ast, javaCode, comments, nodeToBlockMap, manager, isReadOnly,
                    LockResolver.forActiveFile(config, state), state.getAudience(),
                    markNewIdentifiersAsUnedited);

            if (ast.types().isEmpty()) return new ConvertResult(null, ast);

            AbstractTypeDeclaration rootNode = (AbstractTypeDeclaration) ast.types().getFirst();
            return new ConvertResult(parseRoot(rootNode, ctx), ast);

        } catch (Exception e) {
            System.err.println("Critical error in BlockConverter.convert: " + e.getMessage());
            e.printStackTrace();
            return new ConvertResult(null, null);
        }
    }

    /**
     * The file as the editor parses it: bindings resolved against the project's classpath, named after the file
     * on disk so the resolver can find its siblings.
     *
     * <p>Public because the parse and the block build are now two steps that can happen a moment apart — see the
     * {@code parsed} overload above.
     */
    public CompilationUnit parse(String javaCode) {
        String unitName = state.getActiveFile() != null
                ? state.getActiveFile().getPath().toAbsolutePath().toString() : null;
        return createCompilationUnit(state.getResolvedClasspath(), javaCode, state.getSourcePath(), unitName);
    }

    private AbstractCodeBlock parseRoot(AbstractTypeDeclaration rootNode, ParseContext ctx) {
        // --- CASE A: Standard Class File ---
        if (rootNode instanceof TypeDeclaration typeDecl) {
            ClassBlock classBlock = new ClassBlock(
                    BlockId.of(typeDecl), typeDecl, ctx.manager());
            applyReadOnly(classBlock, ctx);
            ctx.nodeToBlockMap().put(typeDecl, classBlock);

            for (Object obj : typeDecl.bodyDeclarations()) {
                // Every member of every file is the user's since 2026-08-29, so none is filtered out of the
                // tree. What used to be dropped here — an activity's Outcome enum, its INSTANCE static, its
                // isEnabled() wiring — was dropped because BotMaker wrote it and rewrote it; nothing does.
                if (obj instanceof MethodDeclaration method) {
                    MethodDeclarationBlock methodBlock;
                    if (method.isConstructor()) {
                        methodBlock = new ConstructorBlock(
                                BlockId.of(method), method, ctx.manager());
                    } else if (FileTypeDetector.isMainMethod(method)) {
                        methodBlock = new MainBlock(
                                BlockId.of(method), method, ctx.manager());
                    } else {
                        methodBlock = new MethodDeclarationBlock(
                                BlockId.of(method), method, ctx.manager());
                    }
                    // LockResolver is the one authority on whether an edit is allowed — bundled library
                    // source, or a bot open for reading. Don't re-derive either from a path here.
                    methodBlock.setReadOnly(!signatureEditable(method, ctx));
                    ctx.nodeToBlockMap().put(method, methodBlock);

                    if (method.getBody() != null) {
                        methodBlock.setBody(parseBodyBlock(method.getBody(),
                                ctx.withReadOnly(!bodyEditable(method, ctx))));
                    }
                    classBlock.addBodyDeclaration(methodBlock);
                } else if (obj instanceof Initializer initializer) {
                    // static { … } / { … }. Modelled by JDT as neither a method nor a field, so without this
                    // branch the whole construct vanished from the tree — see blocks/misc/InitializerBlock.
                    InitializerBlock initBlock = new InitializerBlock(BlockId.of(initializer), initializer);
                    applyReadOnly(initBlock, ctx);
                    ctx.nodeToBlockMap().put(initializer, initBlock);

                    if (initializer.getBody() != null) {
                        initBlock.setBody(parseBodyBlock(initializer.getBody(), ctx));
                    }
                    classBlock.addBodyDeclaration(initBlock);
                } else if (obj instanceof EnumDeclaration enumDecl) {
                    DeclareEnumBlock enumBlock = new DeclareEnumBlock(
                            BlockId.of(enumDecl), enumDecl);
                    applyReadOnly(enumBlock, ctx);
                    // An activity's Outcome enum is generated from the flow dialog inside a file the user
                    // otherwise owns, so the file's own verdict is not the answer here.
                    if (!signatureEditable(enumDecl, ctx)) enumBlock.setReadOnly(true);
                    ctx.nodeToBlockMap().put(enumDecl, enumBlock);
                    classBlock.addBodyDeclaration(enumBlock);
                } else if (obj instanceof FieldDeclaration field) {
                    DeclareClassVariableBlock fieldBlock = new DeclareClassVariableBlock(
                            BlockId.of(field), field);
                    applyReadOnly(fieldBlock, ctx);
                    ctx.nodeToBlockMap().put(field, fieldBlock);

                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) field.fragments().getFirst();
                    if (fragment.getInitializer() != null) {
                        parseExpression(fragment.getInitializer(), ctx).ifPresent(fieldBlock::setInitializer);
                    }
                    classBlock.addBodyDeclaration(fieldBlock);
                }
            }
            return classBlock;
        }
        // --- CASE B: Standalone Enum File ---
        else if (rootNode instanceof EnumDeclaration enumDecl) {
            DeclareEnumBlock rootEnumBlock = new DeclareEnumBlock(
                    BlockId.of(enumDecl), enumDecl);
            applyReadOnly(rootEnumBlock, ctx);
            ctx.nodeToBlockMap().put(enumDecl, rootEnumBlock);
            return rootEnumBlock;
        }
        return null;
    }

    private void applyReadOnly(CodeBlock block, ParseContext ctx) {
        if (ctx.readOnly()) block.setReadOnly(true);
    }

    /** True when {@code node}'s name/params/return type — or class-level structure — may be changed. */
    private boolean signatureEditable(ASTNode node, ParseContext ctx) {
        return ctx.resolver() == null ? !ctx.readOnly() : ctx.resolver().signatureEditable(node);
    }

    /** True when statements inside {@code method} may be changed. */
    private boolean bodyEditable(MethodDeclaration method, ParseContext ctx) {
        return ctx.resolver() == null ? !ctx.readOnly() : ctx.resolver().bodyEditable(method);
    }

    // =========================================================================
    // BODY
    // =========================================================================

    public BodyBlock parseBodyBlock(Block astBlock, ParseContext ctx) {
        BodyBlock bodyBlock = new BodyBlock(BlockId.of(astBlock), astBlock, ctx.manager());
        applyReadOnly(bodyBlock, ctx);
        ctx.nodeToBlockMap().put(astBlock, bodyBlock);

        List<CodeBlock> allChildren = new ArrayList<>();
        for (Object statementObj : astBlock.statements()) {
            parseStatement((Statement) statementObj, ctx).ifPresent(allChildren::add);
        }

        int blockStart = astBlock.getStartPosition() + 1;
        int blockEnd = astBlock.getStartPosition() + astBlock.getLength() - 1;

        for (Comment comment : ctx.comments()) {
            int cPos = comment.getStartPosition();
            if (cPos > blockStart && cPos < blockEnd) {
                boolean isInsideChild = false;
                for (Object stmtObj : astBlock.statements()) {
                    Statement s = (Statement) stmtObj;
                    if (cPos >= s.getStartPosition() && cPos <= s.getStartPosition() + s.getLength()) {
                        isInsideChild = true;
                        break;
                    }
                }
                if (!isInsideChild) {
                    allChildren.add(parseCommentBlock(comment, ctx));
                }
            }
        }

        allChildren.sort(Comparator.comparingInt(b -> b.getAstNode().getStartPosition()));
        for (CodeBlock cb : allChildren) {
            if (cb instanceof StatementBlock) bodyBlock.addStatement((StatementBlock) cb);
        }
        return bodyBlock;
    }

    private CommentBlock parseCommentBlock(Comment astNode, ParseContext ctx) {
        String text = "Comment";
        if (ctx.sourceCode() != null) {
            try {
                String raw = ctx.sourceCode().substring(astNode.getStartPosition(), astNode.getStartPosition() + astNode.getLength());
                text = astNode.isLineComment() ? raw.substring(2).trim() : raw.substring(2, raw.length() - 2).trim();
            } catch (Exception ignored) {}
        }
        CommentBlock commentBlock = new CommentBlock(BlockId.of(astNode), astNode, text);
        applyReadOnly(commentBlock, ctx);
        ctx.nodeToBlockMap().put(astNode, commentBlock);
        return commentBlock;
    }

    // =========================================================================
    // STATEMENTS
    // =========================================================================

    public Optional<StatementBlock> parseStatement(Statement stmt, ParseContext ctx) {
        Optional<StatementBlock> result = dispatchStatement(stmt, ctx);
        result.ifPresent(b -> applyReadOnly(b, ctx));
        return result;
    }

    private Optional<StatementBlock> dispatchStatement(Statement stmt, ParseContext ctx) {
        try {
            if (stmt instanceof Block b) return Optional.of(parseBodyBlock(b, ctx));
            if (stmt instanceof TypeDeclarationStatement t) return parseTypeDeclaration(t, ctx);
            if (stmt instanceof VariableDeclarationStatement v) return parseVariableDecl(v, ctx);
            if (stmt instanceof IfStatement i) return parseIf(i, ctx);
            if (stmt instanceof WhileStatement w) return parseWhile(w, ctx);
            if (stmt instanceof EnhancedForStatement f) return parseFor(f, ctx);
            if (stmt instanceof DoStatement d) return parseDoWhile(d, ctx);
            // Ahead of the ordinary switch: a Matches switch is the arrow/guarded form, which parseSwitch's
            // colon-form walk would render as an unreadable expression label. Anything that isn't exactly the
            // shape MatchesSwitchHandler writes falls through to it unchanged.
            if (stmt instanceof SwitchStatement s && MatchesSwitchHandler.isMatchesSwitch(s)) {
                return parseMatchesSwitch(s, ctx);
            }
            if (stmt instanceof SwitchStatement s) return parseSwitch(s, ctx);
            if (stmt instanceof BreakStatement b) return Optional.of(new BreakBlock(BlockId.of(b), b));
            if (stmt instanceof ContinueStatement c) return Optional.of(new ContinueBlock(BlockId.of(c), c));
            if (stmt instanceof ReturnStatement r) return parseReturn(r, ctx);
            if (stmt instanceof TryStatement t) return parseTry(t, ctx);
            if (stmt instanceof ExpressionStatement e) return parseExprStmt(e, ctx);
        } catch (Exception e) {
            System.err.println("Error parsing statement: " + stmt);
            e.printStackTrace();
        }
        return Optional.empty();
    }

    private Optional<StatementBlock> parseReturn(ReturnStatement stmt, ParseContext ctx) {
        ReturnBlock block = new ReturnBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setExpression);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseTypeDeclaration(TypeDeclarationStatement stmt, ParseContext ctx) {
        if (stmt.getDeclaration() instanceof EnumDeclaration enumDecl) {
            DeclareEnumBlock block = new DeclareEnumBlock(BlockId.of(stmt), stmt);
            ctx.nodeToBlockMap().put(stmt, block);
            ctx.nodeToBlockMap().put(enumDecl, block);
            return Optional.of(block);
        }
        return Optional.empty();
    }

    private Optional<StatementBlock> parseExprStmt(ExpressionStatement stmt, ParseContext ctx) {
        Expression expr = stmt.getExpression();

        if (isPrintStatement(expr)) {
            return parsePrint(stmt, ctx);
        }
        if (expr instanceof Assignment) {
            return parseAssignment(stmt, ctx);
        }
        if (expr instanceof PostfixExpression || expr instanceof PrefixExpression) {
            AssignmentBlock block = new AssignmentBlock(BlockId.of(stmt), stmt);
            ctx.nodeToBlockMap().put(stmt, block);
            if (expr instanceof PostfixExpression pe) {
                parseExpression(pe.getOperand(), ctx).ifPresent(block::setLeftHandSide);
            }
            if (expr instanceof PrefixExpression pe) {
                parseExpression(pe.getOperand(), ctx).ifPresent(block::setLeftHandSide);
            }
            return Optional.of(block);
        }
        if (expr instanceof MethodInvocation mi) {
            // Ahead of everything else a method invocation can be: a branch chain is a call whose arguments
            // are all lambdas, chained leftward, and every arm below would draw only its outermost link —
            // LibraryCallBlock its receiver as a scope string, MethodInvocationBlock its two lambdas as
            // arguments. Anything that is not exactly that shape falls through unchanged.
            if (BranchChainHandler.isBranchChain(mi)) {
                return parseBranchChain(stmt, mi, ctx);
            }

            String scope = mi.getExpression() != null ? mi.getExpression().toString() : "";

            // Activity.disable/enable("X") and Bot.stop() are ordinary SDK facade calls — they fall through to
            // the standardized LibraryCallBlock path below (same chrome as every other SDK block), rather than
            // being special-cased into a bespoke fixed-label block.
            if (isLibraryClass(scope)) {
                if (LambdaCallHandler.isLambdaCall(mi)) {
                    return parseLambdaCall(stmt, mi, ctx);
                }
                LibraryCallBlock block = new LibraryCallBlock(BlockId.of(stmt), stmt, scope);
                ctx.nodeToBlockMap().put(stmt, block);
                for (Object arg : mi.arguments()) {
                    parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
                }
                return Optional.of(block);
            }

            MethodInvocationBlock block = new MethodInvocationBlock(BlockId.of(stmt), stmt);
            ctx.nodeToBlockMap().put(stmt, block);
            for (Object arg : mi.arguments()) {
                parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
            }
            return Optional.of(block);
        }
        return Optional.empty();
    }

    /**
     * A branch chain ({@code found.when(m -> m.hasAny(ORE), () -> { … }).otherwise(() -> { … })}) as one
     * {@link BranchChainBlock} with a row per link — the condition as an ordinary boolean expression slot, the
     * body as a droppable {@link BodyBlock}, both recursed exactly as every other block's are.
     *
     * <p>Nothing here knows what the chain is over. {@code BranchChainHandler} reads the shape and the source
     * supplies the captions, which is what lets one block draw any plugin's chain — see that class for why the
     * guarded-switch machinery this replaced could not be written that way.
     */
    private Optional<StatementBlock> parseBranchChain(ExpressionStatement stmt, MethodInvocation mi,
                                                      ParseContext ctx) {
        BranchChainBlock block =
                new BranchChainBlock(BlockId.of(stmt), stmt, BranchChainHandler.subjectOf(mi));
        ctx.nodeToBlockMap().put(stmt, block);

        for (BranchChainHandler.Link link : BranchChainHandler.read(mi)) {
            BranchChainBlock.LinkView view = block.addLink(link.method(), link.isTerminal());
            Expression condition = link.conditionExpression();
            if (condition != null) parseExpression(condition, ctx).ifPresent(view::setCondition);
            view.setBody(parseBodyBlock(link.body(), ctx));
        }
        return Optional.of(block);
    }

    /**
     * A facade call with a trailing body lambda ({@code ImageFinder.whileFind(img, m -> { … })}). Builds a
     * {@link LambdaCallBlock} exposing the leading image argument as a fillable slot and the lambda body as a
     * droppable {@link BodyBlock} (recursed via {@link #parseBodyBlock}), so the block round-trips.
     */
    private Optional<StatementBlock> parseLambdaCall(ExpressionStatement stmt, MethodInvocation mi, ParseContext ctx) {
        LambdaExpression lambda = LambdaCallHandler.lambdaArg(mi);
        LambdaCallBlock block = new LambdaCallBlock(BlockId.of(stmt), stmt, mi.getName().getIdentifier());
        ctx.nodeToBlockMap().put(stmt, block);

        List<?> args = mi.arguments();
        if (args.size() > 1) {
            // leading image argument (everything before the trailing lambda; the vision helpers have exactly one)
            parseExpression((Expression) args.get(0), ctx).ifPresent(block::setImage);
        }
        if (lambda.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseVariableDecl(VariableDeclarationStatement stmt, ParseContext ctx) {
        if (isReadInputStatement(stmt)) {
            VariableDeclarationFragment frag = (VariableDeclarationFragment) stmt.fragments().getFirst();
            MethodInvocation mi = (MethodInvocation) frag.getInitializer();
            ReadInputBlock block = new ReadInputBlock(BlockId.of(stmt), stmt,
                    InputKind.fromMethod(mi.getName().getIdentifier()).orElse(null));
            ctx.nodeToBlockMap().put(stmt, block);
            return Optional.of(block);
        } else {
            VariableDeclarationBlock block = new VariableDeclarationBlock(BlockId.of(stmt), stmt);
            ctx.nodeToBlockMap().put(stmt, block);
            VariableDeclarationFragment frag = (VariableDeclarationFragment) stmt.fragments().getFirst();
            if (frag.getInitializer() != null) parseExpression(frag.getInitializer(), ctx).ifPresent(block::setInitializer);
            return Optional.of(block);
        }
    }

    private Optional<StatementBlock> parseIf(IfStatement stmt, ParseContext ctx) {
        IfBlock block = new IfBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCondition);
        if (stmt.getThenStatement() instanceof Block b) block.setThenBody(parseBodyBlock(b, ctx));
        if (stmt.getElseStatement() != null) parseStatement(stmt.getElseStatement(), ctx).ifPresent(block::setElseStatement);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseWhile(WhileStatement stmt, ParseContext ctx) {
        WhileBlock block = new WhileBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCondition);
        if (stmt.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseFor(EnhancedForStatement stmt, ParseContext ctx) {
        ForBlock block = new ForBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        if (stmt.getParameter() != null) parseExpression(stmt.getParameter().getName(), ctx).ifPresent(block::setVariable);
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCollection);
        if (stmt.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseDoWhile(DoStatement stmt, ParseContext ctx) {
        DoWhileBlock block = new DoWhileBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCondition);
        if (stmt.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    /**
     * The guarded-arrow {@code switch (found) { case Matches m when … -> { … } }}.
     *
     * <p>Unlike {@link #parseSwitch}, a case's label mostly contributes no block: the checks and the
     * {@code and}/{@code or} that join them are described by the {@code Guard} tree, which the block renders as
     * toggles and chip rows, so there is nothing there for the user to drag or to fill. The exception is a
     * {@code Guard.Other} leaf — a condition this block cannot say in chips — which becomes a real expression
     * block so it renders as an ordinary, droppable expression slot instead of as text.
     */
    private Optional<StatementBlock> parseMatchesSwitch(SwitchStatement stmt, ParseContext ctx) {
        MatchesSwitchBlock block = new MatchesSwitchBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);

        for (Object o : stmt.statements()) {
            if (!(o instanceof SwitchCase sc)) continue;
            // The body is the case's single braced Block, so a branch is a real BodyBlock with the same drop
            // zones as an `if` — the switch's own statement list is never a drop target.
            Block braced = MatchesSwitchHandler.singleBlockBody(stmt, sc);
            BodyBlock body = braced == null ? null : parseBodyBlock(braced, ctx);
            if (body != null) applyReadOnly(body, ctx);

            if (sc.isDefault()) {
                block.setDefault(sc, body);
            } else {
                MatchesSwitchHandler.guardOf(sc).ifPresent(guard -> {
                    block.addCase(sc, guard, body);
                    parseGuardSlots(guard, block, ctx);
                });
            }
        }
        return Optional.of(block);
    }

    /** Gives every {@code Other} leaf of a guard tree the expression block its slot renders. */
    private void parseGuardSlots(MatchesSwitchHandler.Guard guard, MatchesSwitchBlock block, ParseContext ctx) {
        switch (guard) {
            case MatchesSwitchHandler.Guard.Other other ->
                    parseExpression(other.node(), ctx).ifPresent(e -> block.putGuardSlot(other.node(), e));
            case MatchesSwitchHandler.Guard.Not not -> parseGuardSlots(not.operand(), block, ctx);
            case MatchesSwitchHandler.Guard.Container container ->
                    container.operands().forEach(operand -> parseGuardSlots(operand, block, ctx));
            case MatchesSwitchHandler.Guard.Check ignored -> { }
        }
    }

    private Optional<StatementBlock> parseSwitch(SwitchStatement stmt, ParseContext ctx) {
        SwitchBlock block = new SwitchBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setExpression);
        List<?> statements = stmt.statements();
        BodyBlock currentBody = null;
        SwitchBlock.SwitchCaseBlock currentCase = null;
        // An arrow rule's body is one Block that follows its label; having become the case's BodyBlock below,
        // it must not also be parsed as a child statement of itself.
        Statement consumedRuleBody = null;
        for (int i = 0; i < statements.size(); i++) {
            Statement s = (Statement) statements.get(i);
            if (s == consumedRuleBody) continue;
            if (s instanceof SwitchCase sc) {
                currentCase = new SwitchBlock.SwitchCaseBlock(BlockId.of(sc), sc);
                applyReadOnly(currentCase, ctx);
                ctx.nodeToBlockMap().put(sc, currentCase);
                if (!sc.isDefault() && !sc.expressions().isEmpty()) parseExpression((Expression) sc.expressions().getFirst(), ctx).ifPresent(currentCase::setCaseExpression);
                // Which node backs the body decides where an inserted statement goes, and the two label forms
                // disagree. A colon case's statements are siblings of its label in the switch's own list, so
                // the label is the anchor. An arrow rule's are inside a Block of their own — anchoring on the
                // label there inserted the statement *in front of* that Block, as a bare block among arrow
                // rules, which doesn't parse. `parseMatchesSwitch` already backed its branches with the Block;
                // this is the same rule for every arrow switch.
                Block ruleBody = labeledRuleBody(sc, statements, i);
                if (ruleBody != null) {
                    consumedRuleBody = ruleBody;
                    currentBody = parseBodyBlock(ruleBody, ctx);
                } else {
                    currentBody = new BodyBlock(BlockId.of(sc), sc, ctx.manager());
                }
                applyReadOnly(currentBody, ctx);
                currentCase.setBody(currentBody);
                block.addCase(currentCase);
            } else if (currentBody != null) {
                // A case's closing break is the case's own chrome, not a statement in it: leaving it out of the
                // BodyBlock is what makes it undeletable and undraggable (there is no block to grab), and it
                // also makes "append to this case" land before it — insertIntoList offsets from the case label,
                // so the end of the visible body is exactly the break's slot.
                if (s instanceof BreakStatement && endsCase(statements, i)) {
                    currentCase.setClosingBreak(true);
                    continue;
                }
                BodyBlock target = currentBody;
                parseStatement(s, ctx).ifPresent(target::addStatement);
            }
        }
        return Optional.of(block);
    }

    /**
     * The braced body of the arrow rule labelled at {@code i}, or {@code null} when {@code sc} isn't an arrow
     * rule or its body isn't a single {@link Block}.
     *
     * <p>{@code case X -> foo();} has no block to put anything into; {@code SwitchNormalizer} gives it one when
     * the file is opened, so by the time a user can drop into it there is a Block here. Returning null in the
     * meantime falls back to the colon-form anchor, which is wrong for an arrow rule but is what it has always
     * done — an insert there is refused by the edit guard rather than corrupting the switch.
     */
    private static Block labeledRuleBody(SwitchCase sc, List<?> statements, int i) {
        if (!sc.isSwitchLabeledRule() || i + 1 >= statements.size()) return null;
        return statements.get(i + 1) instanceof Block body ? body : null;
    }

    /** Whether the statement at {@code i} is the last one before the next {@code case} label (or the switch's end). */
    private static boolean endsCase(List<?> statements, int i) {
        return i == statements.size() - 1 || statements.get(i + 1) instanceof SwitchCase;
    }

    private Optional<StatementBlock> parsePrint(ExpressionStatement stmt, ParseContext ctx) {
        PrintBlock block = new PrintBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        MethodInvocation mi = (MethodInvocation) stmt.getExpression();
        if (mi.arguments().isEmpty()) {
            block.addArgument(new LiteralBlock<>(BlockId.of(stmt), mi, ""));
        } else {
            for (Object arg : mi.arguments()) parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
        }
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseAssignment(ExpressionStatement stmt, ParseContext ctx) {
        AssignmentBlock block = new AssignmentBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        Assignment a = (Assignment) stmt.getExpression();
        parseExpression(a.getLeftHandSide(), ctx).ifPresent(block::setLeftHandSide);
        parseExpression(a.getRightHandSide(), ctx).ifPresent(block::setRightHandSide);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseTry(TryStatement stmt, ParseContext ctx) {
        if (isWait(stmt)) {
            WaitBlock block = new WaitBlock(BlockId.of(stmt), stmt);
            ctx.nodeToBlockMap().put(stmt, block);
            Statement inner = (Statement) stmt.getBody().statements().getFirst();
            MethodInvocation mi = (MethodInvocation) ((ExpressionStatement) inner).getExpression();
            if (!mi.arguments().isEmpty()) parseExpression((Expression) mi.arguments().getFirst(), ctx).ifPresent(block::setDuration);
            return Optional.of(block);
        }
        return Optional.empty();
    }

    // =========================================================================
    // EXPRESSIONS
    // =========================================================================

    public Optional<ExpressionBlock> parseExpression(Expression expr, ParseContext ctx) {
        if (expr instanceof ArrayCreation ac && ac.getInitializer() != null) {
            Optional<ExpressionBlock> inner = parseExpression(ac.getInitializer(), ctx);
            inner.ifPresent(b -> ctx.nodeToBlockMap().put(expr, b));
            return inner;
        }
        Optional<ExpressionBlock> result = dispatchExpression(expr, ctx);
        result.ifPresent(b -> applyReadOnly(b, ctx));
        return result;
    }

    private Optional<ExpressionBlock> dispatchExpression(Expression expr, ParseContext ctx) {
        Map<ASTNode, CodeBlock> map = ctx.nodeToBlockMap();

        if (expr instanceof ClassInstanceCreation cic) {
            InstantiationBlock block = new InstantiationBlock(BlockId.of(expr), cic);
            map.put(expr, block);
            for (Object arg : cic.arguments()) {
                parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
            }
            return Optional.of(block);
        }
        if (expr instanceof NullLiteral nl) {
            NullBlock b = new NullBlock(BlockId.of(expr), nl);
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof StringLiteral sl) {
            LiteralBlock<String> b = new LiteralBlock<>(BlockId.of(expr), expr, sl.getLiteralValue());
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof ArrayInitializer arrayInit) {
            ListBlock block = new ListBlock(BlockId.of(expr), arrayInit);
            map.put(expr, block);
            for (Object item : arrayInit.expressions()) {
                parseExpression((Expression) item, ctx).ifPresent(block::addElement);
            }
            return Optional.of(block);
        }
        if (expr instanceof PrefixExpression prefix) {
            if (prefix.getOperator() == PrefixExpression.Operator.NOT) {
                NotOperatorBlock b = new NotOperatorBlock(BlockId.of(expr), prefix);
                map.put(expr, b);
                parseExpression(prefix.getOperand(), ctx).ifPresent(b::setOperand);
                return Optional.of(b);
            }
        }
        if (isListStructure(expr)) {
            ListBlock b = new ListBlock(BlockId.of(expr), expr);
            map.put(expr, b);
            for (Expression item : getListItems(expr)) parseExpression(item, ctx).ifPresent(b::addElement);
            return Optional.of(b);
        }
        if (expr instanceof FieldAccess fa) {
            FieldAccessBlock b = new FieldAccessBlock(BlockId.of(expr), fa, ctx.markNewIdentifiersAsUnedited());
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof QualifiedName qn) {
            if (qn.resolveBinding() instanceof IVariableBinding vb) {
                if (vb.isEnumConstant()) {
                    EnumConstantBlock b = new EnumConstantBlock(BlockId.of(expr), qn);
                    map.put(expr, b);
                    return Optional.of(b);
                } else if (vb.isField()) {
                    FieldAccessBlock b = new FieldAccessBlock(BlockId.of(expr), qn, ctx.markNewIdentifiersAsUnedited());
                    map.put(expr, b);
                    return Optional.of(b);
                }
            } else if (qn.getQualifier() instanceof SimpleName) {
                // Unresolved bindings are routine, not exceptional: a sibling generated file may not be on the
                // classpath yet (Activities.java is rewritten and recompiled as activities change), and the
                // fallback below would render `Activities.Mining` as inert plain text — the same as a construct
                // we have no block for. `Qualifier.name` is unambiguously a field access syntactically, so
                // build the real block and let it round-trip.
                FieldAccessBlock b = new FieldAccessBlock(BlockId.of(expr), qn, ctx.markNewIdentifiersAsUnedited());
                map.put(expr, b);
                return Optional.of(b);
            }
        }
        if (expr instanceof MethodInvocation mi) {
            String scope = mi.getExpression() != null ? mi.getExpression().toString() : "";
            MethodInvocationBlock block = isLibraryClass(scope)
                    ? new LibraryCallBlock(BlockId.of(expr), expr, scope)
                    : new MethodInvocationBlock(BlockId.of(expr), expr);
            map.put(expr, block);
            for (Object arg : mi.arguments()) {
                parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
            }
            return Optional.of(block);
        }
        if (expr instanceof NumberLiteral nl) {
            String t = nl.getToken();
            ExpressionBlock b;
            if (t.toLowerCase().endsWith("f")) b = new LiteralBlock<>(BlockId.of(expr), expr, Float.parseFloat(t));
            else if (t.contains(".") || t.toLowerCase().endsWith("d")) b = new LiteralBlock<>(BlockId.of(expr), expr, Double.parseDouble(t));
            else b = new LiteralBlock<>(BlockId.of(expr), expr, Integer.parseInt(t));
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof BooleanLiteral bl) {
            BooleanLiteralBlock b = new BooleanLiteralBlock(BlockId.of(expr), bl);
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof SimpleName sn) {
            if (expr.getParent() instanceof Type) return Optional.empty();
            IdentifierBlock b = new IdentifierBlock(BlockId.of(expr), sn, ctx.markNewIdentifiersAsUnedited());
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof InfixExpression infix) {
            if (isComparisonOperator(infix.getOperator())) {
                ComparisonExpressionBlock b = new ComparisonExpressionBlock(BlockId.of(expr), infix);
                map.put(expr, b);
                parseExpression(infix.getLeftOperand(), ctx).ifPresent(b::setLeftOperand);
                parseExpression(infix.getRightOperand(), ctx).ifPresent(b::setRightOperand);
                return Optional.of(b);
            } else {
                BinaryExpressionBlock b = new BinaryExpressionBlock(BlockId.of(expr), infix);
                map.put(expr, b);
                parseExpression(infix.getLeftOperand(), ctx).ifPresent(b::setLeftOperand);
                parseExpression(infix.getRightOperand(), ctx).ifPresent(b::setRightOperand);
                return Optional.of(b);
            }
        }
        if (expr instanceof MethodReference mr) {
            MethodReferenceBlock b = new MethodReferenceBlock(BlockId.of(expr), mr);
            map.put(expr, b);
            return Optional.of(b);
        }
        // Fallback: never return empty. Callers use `.ifPresent(block::addArgument)`, so an empty Optional
        // silently DROPS the argument — the block then shows fewer args than the source has, and a later
        // rewrite from block state can delete them for real. Render it verbatim instead so it stays visible
        // and round-trips. Add a real branch above if you want the node type to be editable.
        UnknownExpressionBlock unknown = new UnknownExpressionBlock(BlockId.of(expr), expr);
        map.put(expr, unknown);
        return Optional.of(unknown);
    }

    // =========================================================================
    // PURE PREDICATES / HELPERS
    // =========================================================================

    private static boolean isLibraryClass(String name) {
        return com.botmaker.studio.plugin.PluginHost.isFacadeClass(name);
    }

    private static boolean isComparisonOperator(InfixExpression.Operator op) {
        return op == InfixExpression.Operator.EQUALS || op == InfixExpression.Operator.NOT_EQUALS ||
                op == InfixExpression.Operator.LESS || op == InfixExpression.Operator.GREATER ||
                op == InfixExpression.Operator.LESS_EQUALS || op == InfixExpression.Operator.GREATER_EQUALS ||
                op == InfixExpression.Operator.CONDITIONAL_AND || op == InfixExpression.Operator.CONDITIONAL_OR;
    }

    private static boolean isWait(TryStatement stmt) {
        if (stmt.getBody().statements().size() != 1) return false;
        Statement first = (Statement) stmt.getBody().statements().getFirst();
        if (!(first instanceof ExpressionStatement)) return false;
        Expression e = ((ExpressionStatement) first).getExpression();
        return e instanceof MethodInvocation mi && "sleep".equals(mi.getName().getIdentifier()) && "Thread".equals(mi.getExpression().toString());
    }

    public static boolean isPrintStatement(Expression expression) {
        if (!(expression instanceof MethodInvocation method)) return false;
        if (!method.getName().getIdentifier().equals(BotMakerApi.PRINT)) return false;
        return method.getExpression() instanceof SimpleName sn && sn.getIdentifier().equals("BotMaker");
    }

    public static boolean isReadInputStatement(VariableDeclarationStatement varDecl) {
        if (varDecl.fragments().isEmpty()) return false;
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) varDecl.fragments().getFirst();
        if (!(fragment.getInitializer() instanceof MethodInvocation mi)) return false;
        if (!(mi.getExpression() instanceof SimpleName sn && sn.getIdentifier().equals("BotMaker"))) return false;
        return mi.getName().getIdentifier().startsWith("read");
    }

    public static boolean isListStructure(Expression expr) {
        if (expr instanceof ArrayInitializer) return true;
        if (expr instanceof ArrayCreation) return true;
        if (expr instanceof ClassInstanceCreation cic) {
            String typeName = cic.getType().toString();
            return (typeName.startsWith(JdkType.ARRAY_LIST.simpleName())
                    || typeName.startsWith(JdkType.ARRAY_LIST.qualifiedName())) && !cic.arguments().isEmpty();
        }
        if (expr instanceof MethodInvocation mi) {
            String scope = mi.getExpression() != null ? mi.getExpression().toString() : "";
            return (scope.equals(JdkType.ARRAYS.simpleName()) && mi.getName().getIdentifier().equals("asList")) ||
                    (scope.equals(JdkType.LIST.simpleName()) && mi.getName().getIdentifier().equals("of"));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static List<Expression> getListItems(Expression expr) {
        if (expr instanceof ArrayInitializer ai) return ai.expressions();
        if (expr instanceof ArrayCreation ac) {
            return ac.getInitializer() != null ? ac.getInitializer().expressions() : Collections.emptyList();
        }
        if (expr instanceof ClassInstanceCreation cic) {
            if (!cic.arguments().isEmpty()) {
                return getListItems((Expression) cic.arguments().getFirst());
            }
        }
        if (expr instanceof MethodInvocation mi) return mi.arguments();
        return List.of();
    }

}
