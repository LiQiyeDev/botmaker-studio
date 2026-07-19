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
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.project.MethodLock;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
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
        try {
            String unitName = state.getActiveFile() != null
                    ? state.getActiveFile().getPath().toAbsolutePath().toString() : null;
            CompilationUnit ast = createCompilationUnit(state.getResolvedClasspath(), javaCode, state.getSourcePath(), unitName);

            List<Comment> comments = new ArrayList<>();
            for (Object obj : ast.getCommentList()) {
                if (obj instanceof Comment c && !(obj instanceof Javadoc)) comments.add(c);
            }

            ParseContext ctx = new ParseContext(
                    ast, javaCode, comments, nodeToBlockMap, manager, isReadOnly,
                    LockResolver.forActiveFile(config, state), markNewIdentifiersAsUnedited);

            if (ast.types().isEmpty()) return new ConvertResult(null, ast);

            AbstractTypeDeclaration rootNode = (AbstractTypeDeclaration) ast.types().getFirst();
            return new ConvertResult(parseRoot(rootNode, ctx), ast);

        } catch (Exception e) {
            System.err.println("Critical error in BlockConverter.convert: " + e.getMessage());
            e.printStackTrace();
            return new ConvertResult(null, null);
        }
    }

    private AbstractCodeBlock parseRoot(AbstractTypeDeclaration rootNode, ParseContext ctx) {
        // --- CASE A: Standard Class File ---
        if (rootNode instanceof TypeDeclaration typeDecl) {
            ClassBlock classBlock = new ClassBlock(
                    BlockId.of(typeDecl), typeDecl, ctx.manager());
            applyReadOnly(classBlock, ctx);
            ctx.nodeToBlockMap().put(typeDecl, classBlock);

            for (Object obj : typeDecl.bodyDeclarations()) {
                if (obj instanceof MethodDeclaration method) {
                    MethodDeclarationBlock methodBlock;
                    if (method.isConstructor()) {
                        methodBlock = new ConstructorBlock(
                                BlockId.of(method), method, ctx.manager());
                    } else if (isMainMethod(method)) {
                        methodBlock = new MainBlock(
                                BlockId.of(method), method, ctx.manager());
                    } else {
                        methodBlock = new MethodDeclarationBlock(
                                BlockId.of(method), method, ctx.manager());
                    }
                    // A method's lock is not its file's. An activity's run() is an @Override whose signature
                    // is fixed but whose body is the whole reason the stub exists; its isEnabled() next door
                    // is generated wiring inside a file the user otherwise owns.
                    // LockResolver combines the two verdicts; don't re-derive either here.
                    methodBlock.setReadOnly(!signatureEditable(method, ctx));
                    methodBlock.setLockBadge(lockBadgeFor(method, ctx));
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

    /** True when {@code method}'s name/params/return type may be changed. */
    private boolean signatureEditable(MethodDeclaration method, ParseContext ctx) {
        return ctx.resolver() == null ? !ctx.readOnly() : ctx.resolver().signatureEditable(method);
    }

    /** True when statements inside {@code method} may be changed. */
    private boolean bodyEditable(MethodDeclaration method, ParseContext ctx) {
        return ctx.resolver() == null ? !ctx.readOnly() : ctx.resolver().bodyEditable(method);
    }

    /**
     * The badge for {@code method}'s header. "Your code goes here" wins over the lock's own badge: an activity's
     * {@code run()} is both signature-locked and the one method the user came to write, and telling them its
     * parameters are fixed is not the point — telling them this is where they work is.
     */
    private String lockBadgeFor(MethodDeclaration method, ParseContext ctx) {
        if (isUsersEntryPoint(method)) return "Your code goes here";
        Path file = activeFilePath();
        return file == null ? null : MethodLock.of(config, state.getTemplate(), file, method).badge();
    }

    /** True when {@code method} is the one the user is meant to fill in (an activity's {@code run()}). */
    private boolean isUsersEntryPoint(MethodDeclaration method) {
        Path file = activeFilePath();
        return file != null && MethodLock.isUsersEntryPoint(config, state.getTemplate(), file, method);
    }

    private Path activeFilePath() {
        return state.getActiveFile() == null ? null : state.getActiveFile().getPath();
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
            ReadInputBlock block = new ReadInputBlock(BlockId.of(stmt), stmt, mi.getName().getIdentifier());
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

    private Optional<StatementBlock> parseSwitch(SwitchStatement stmt, ParseContext ctx) {
        SwitchBlock block = new SwitchBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setExpression);
        BodyBlock currentBody = null;
        SwitchBlock.SwitchCaseBlock currentCase = null;
        for (Object obj : stmt.statements()) {
            Statement s = (Statement) obj;
            if (s instanceof SwitchCase sc) {
                currentCase = new SwitchBlock.SwitchCaseBlock(BlockId.of(sc), sc);
                applyReadOnly(currentCase, ctx);
                ctx.nodeToBlockMap().put(sc, currentCase);
                if (!sc.isDefault() && !sc.expressions().isEmpty()) parseExpression((Expression) sc.expressions().getFirst(), ctx).ifPresent(currentCase::setCaseExpression);
                currentBody = new BodyBlock(BlockId.of(sc), sc, ctx.manager());
                applyReadOnly(currentBody, ctx);
                currentCase.setBody(currentBody);
                block.addCase(currentCase);
            } else if (currentBody != null) {
                BodyBlock target = currentBody;
                parseStatement(s, ctx).ifPresent(target::addStatement);
            }
        }
        return Optional.of(block);
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
        return com.botmaker.studio.palette.SdkApi.isFacadeClass(name);
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
        if (!method.getName().getIdentifier().equals("print")) return false;
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
            return (typeName.startsWith("ArrayList") || typeName.startsWith("java.util.ArrayList")) && !cic.arguments().isEmpty();
        }
        if (expr instanceof MethodInvocation mi) {
            String scope = mi.getExpression() != null ? mi.getExpression().toString() : "";
            return (scope.equals("Arrays") && mi.getName().getIdentifier().equals("asList")) ||
                    (scope.equals("List") && mi.getName().getIdentifier().equals("of"));
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

    private static boolean isMainMethod(MethodDeclaration method) {
        if (!"main".equals(method.getName().getIdentifier())) return false;
        if (!Modifier.isStatic(method.getModifiers())) return false;
        if (!Modifier.isPublic(method.getModifiers())) return false;
        return method.parameters().size() == 1;
    }
}
