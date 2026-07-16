package com.botmaker.studio.parser.factories;

import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.Initializer;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class StatementFactory {

    /**
     * Builds the default AST {@link Statement} for a dropped {@link BlockType}. Dispatch is an exhaustive switch on
     * the sealed type — the data-carrying variants ({@link BlockType.VarDecl}, {@link BlockType.ScannerRead},
     * {@link BlockType.LibraryCall}) are built generically from their fields, so new variants are pure data.
     */
    public static Statement createStatement(AST ast, BlockType type, CompilationUnit cu, ASTRewrite rewriter,
                                            ProjectState state, ProjectAnalyzer analyzer) {
        return switch (type) {
            case BlockType.ControlFlow cf -> createControlFlow(ast, cf.kind(), cu, rewriter, state);
            case BlockType.VarDecl v -> buildVarDecl(ast, v, cu, rewriter, analyzer);
            case BlockType.ScannerRead r -> buildScannerRead(ast, r, cu, rewriter);
            case BlockType.LibraryCall l -> buildLibraryCall(ast, l, cu, rewriter, state, analyzer);
            case BlockType.LambdaCall l -> buildLambdaCall(ast, l, cu, rewriter, analyzer);
            case BlockType.EnumDecl ignored -> createEnumDeclaration(ast);
            case BlockType.MethodMember ignored -> null; // a method is a class member, not a body statement
        };
    }

    private static Statement createControlFlow(AST ast, BlockType.ControlFlow.Kind kind,
                                               CompilationUnit cu, ASTRewrite rewriter, ProjectState state) {
        return switch (kind) {
            case PRINT -> createPrintStatement(ast, cu, rewriter);
            case IF -> createIfStatement(ast);
            case WHILE -> createWhileStatement(ast);
            case FOR -> createForStatement(ast);
            case DO_WHILE -> createDoWhileStatement(ast);
            case SWITCH -> createSwitchStatement(ast);
            case BREAK -> ast.newBreakStatement();
            case CONTINUE -> ast.newContinueStatement();
            case DISABLE_ACTIVITY -> createActivityToggleStatement(ast, cu, rewriter, state, "disable");
            case ENABLE_ACTIVITY -> createActivityToggleStatement(ast, cu, rewriter, state, "enable");
            case STOP_BOT -> createStopBotStatement(ast, cu, rewriter);
            case RETURN -> ast.newReturnStatement();
            case WAIT -> createWaitStatement(ast);
            case ASSIGNMENT -> createAssignmentStatement(ast);
            case FUNCTION_CALL -> createFunctionCallStatement(ast, cu, rewriter);
            case ARRAY -> createArrayDeclaration(ast, cu, state);
            case COMMENT -> (Statement) rewriter.createStringPlaceholder("// Comment", ASTNode.EMPTY_STATEMENT);
        };
    }

    // --- Data-driven builders ---

    private static Statement buildVarDecl(AST ast, BlockType.VarDecl v, CompilationUnit cu,
                                          ASTRewrite rewriter, ProjectAnalyzer analyzer) {
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(v.varName()));
        Expression init = buildExpression(ast, v.init(), cu, rewriter, analyzer);
        if (init != null) fragment.setInitializer(init);
        VariableDeclarationStatement varDecl = ast.newVariableDeclarationStatement(fragment);
        varDecl.setType(typeNode(ast, v.typeName(), v.primitive()));
        if (!v.primitive()) ImportManager.addImportForSimpleName(cu, rewriter, v.typeName(), analyzer, null);
        return varDecl;
    }

    private static Statement buildScannerRead(AST ast, BlockType.ScannerRead r, CompilationUnit cu, ASTRewrite rewriter) {
        ImportManager.addImport(cu, rewriter, "com.botmaker.sdk.api.BotMaker");
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(r.varName()));
        MethodInvocation readCall = ast.newMethodInvocation();
        readCall.setExpression(ast.newSimpleName("BotMaker"));
        readCall.setName(ast.newSimpleName(r.method()));
        fragment.setInitializer(readCall);
        VariableDeclarationStatement varDecl = ast.newVariableDeclarationStatement(fragment);
        varDecl.setType(typeNode(ast, r.typeName(), r.primitive()));
        return varDecl;
    }

    private static Statement buildLibraryCall(AST ast, BlockType.LibraryCall l, CompilationUnit cu,
                                              ASTRewrite rewriter, ProjectState state, ProjectAnalyzer analyzer) {
        MethodInvocation mi = ast.newMethodInvocation();
        mi.setExpression(ast.newSimpleName(l.className()));
        mi.setName(ast.newSimpleName(l.method()));
        ImportManager.addImportForSimpleName(cu, rewriter, l.className(), analyzer, null);
        if (!l.args().isEmpty()) {
            for (Initializer arg : l.args()) mi.arguments().add(buildExpression(ast, arg, cu, rewriter, analyzer));
        } else {
            // Choose the default overload: the project's pinned favorite if any, else the overload with the
            // fewest arguments (the simplest starting point). Seed a default value per parameter (a
            // CaptureSource slot gets the project-default target — see InitializerFactory). When no overload
            // resolves at all (unknown method), fall back to a single empty "+" slot the user fills.
            List<ResolvedType> params = defaultOverloadParams(l, state, analyzer);
            if (params != null) {
                for (ResolvedType p : params) {
                    mi.arguments().add(com.botmaker.studio.parser.NodeCreator.createDefaultInitializer(ast, p, cu, state));
                }
            } else {
                mi.arguments().add(ast.newNullLiteral());
            }
        }
        return ast.newExpressionStatement(mi);
    }

    /**
     * Parameter types of the default overload for {@code l}: the project's favorite overload when set and it
     * still resolves, otherwise the overload with the fewest parameters. {@code null} only when no overload of
     * the method resolves at all (an unknown method) — callers then use a single empty slot.
     */
    private static List<ResolvedType> defaultOverloadParams(BlockType.LibraryCall l, ProjectState state,
                                                            ProjectAnalyzer analyzer) {
        if (analyzer == null) return null;
        List<MethodSignature> sigs = analyzer.getMethods(l.className(), true).stream()
                .filter(s -> s.name().equals(l.method()))
                .collect(Collectors.toList());
        if (sigs.isEmpty()) return null;
        String favKey = (state != null && state.getSettings() != null)
                ? state.getSettings().favoriteSignature(l.className() + "#" + l.method()) : null;
        MethodSignature chosen = MethodSignature.bestForKey(sigs, favKey);
        if (chosen == null) chosen = MethodSignature.fewestParams(sigs);
        return chosen != null ? chosen.paramTypes() : null;
    }

    private static Statement buildLambdaCall(AST ast, BlockType.LambdaCall l, CompilationUnit cu,
                                             ASTRewrite rewriter, ProjectAnalyzer analyzer) {
        List<Expression> leading = new ArrayList<>();
        if (l.leadingArgs().isEmpty()) {
            leading.add(ast.newNullLiteral()); // empty "+" slot the user fills — same convention as buildLibraryCall
        } else {
            for (Initializer arg : l.leadingArgs()) leading.add(buildExpression(ast, arg, cu, rewriter, analyzer));
        }
        MethodInvocation mi = LambdaCallHandler.buildLambdaCall(
                ast, cu, rewriter, analyzer, l.className(), l.method(), leading, l.lambdaParam());
        return ast.newExpressionStatement(mi);
    }

    /** Turns a declarative {@link Initializer} into an AST expression (recursive for {@code new T(args...)}). */
    private static Expression buildExpression(AST ast, Initializer init, CompilationUnit cu,
                                              ASTRewrite rewriter, ProjectAnalyzer analyzer) {
        return switch (init) {
            case Initializer.IntLit i -> ast.newNumberLiteral(i.value());
            case Initializer.DoubleLit d -> ast.newNumberLiteral(d.value());
            case Initializer.BoolLit b -> ast.newBooleanLiteral(b.value());
            case Initializer.StrLit s -> {
                StringLiteral lit = ast.newStringLiteral();
                lit.setLiteralValue(s.value());
                yield lit;
            }
            case Initializer.NullLit ignored -> ast.newNullLiteral();
            case Initializer.NewInstance n -> {
                ClassInstanceCreation creation = ast.newClassInstanceCreation();
                creation.setType(ast.newSimpleType(ast.newSimpleName(n.typeName())));
                ImportManager.addImportForSimpleName(cu, rewriter, n.typeName(), analyzer, null);
                for (Initializer arg : n.args()) creation.arguments().add(buildExpression(ast, arg, cu, rewriter, analyzer));
                yield creation;
            }
            case Initializer.EnumConst e -> {
                ImportManager.addImportForSimpleName(cu, rewriter, e.typeName(), analyzer, null);
                yield ast.newQualifiedName(ast.newSimpleName(e.typeName()), ast.newSimpleName(e.constant()));
            }
            case Initializer.StaticCall c -> {
                ImportManager.addImportForSimpleName(cu, rewriter, c.typeName(), analyzer, null);
                MethodInvocation mi = ast.newMethodInvocation();
                mi.setExpression(ast.newSimpleName(c.typeName()));
                mi.setName(ast.newSimpleName(c.methodName()));
                for (Initializer arg : c.args()) mi.arguments().add(buildExpression(ast, arg, cu, rewriter, analyzer));
                yield mi;
            }
        };
    }

    private static Type typeNode(AST ast, String typeName, boolean primitive) {
        if (primitive) return ast.newPrimitiveType(primitiveCode(typeName));
        return ProjectAnalyzer.createTypeNode(ast, typeName);
    }

    private static PrimitiveType.Code primitiveCode(String name) {
        return switch (name) {
            case "int" -> PrimitiveType.INT;
            case "double" -> PrimitiveType.DOUBLE;
            case "boolean" -> PrimitiveType.BOOLEAN;
            case "long" -> PrimitiveType.LONG;
            case "float" -> PrimitiveType.FLOAT;
            case "char" -> PrimitiveType.CHAR;
            case "byte" -> PrimitiveType.BYTE;
            case "short" -> PrimitiveType.SHORT;
            default -> throw new IllegalArgumentException("Not a primitive type: " + name);
        };
    }

    // --- Bespoke one-off statement creators ---

    private static Statement createPrintStatement(AST ast, CompilationUnit cu, ASTRewrite rewriter) {
        ImportManager.addImport(cu, rewriter, "com.botmaker.sdk.api.BotMaker");
        MethodInvocation print = ast.newMethodInvocation();
        print.setExpression(ast.newSimpleName("BotMaker"));
        print.setName(ast.newSimpleName("print"));
        StringLiteral emptyString = ast.newStringLiteral();
        emptyString.setLiteralValue("");
        print.arguments().add(emptyString);
        return ast.newExpressionStatement(print);
    }

    private static Statement createArrayDeclaration(AST ast, CompilationUnit cu, ProjectState state) {
        VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
        frag.setName(ast.newSimpleName("myList"));
        ResolvedType arrayType = ResolvedType.named("int[]");
        frag.setInitializer(InitializerFactory.createArrayInitializer(ast, arrayType, Collections.emptyList(), cu, state));
        VariableDeclarationStatement listDecl = ast.newVariableDeclarationStatement(frag);
        listDecl.setType(ProjectAnalyzer.createTypeNode(ast, arrayType));
        return listDecl;
    }

    private static Statement createIfStatement(AST ast) {
        IfStatement ifStatement = ast.newIfStatement();
        ifStatement.setExpression(ast.newBooleanLiteral(true));
        ifStatement.setThenStatement(ast.newBlock());
        return ifStatement;
    }

    private static Statement createWhileStatement(AST ast) {
        WhileStatement whileStatement = ast.newWhileStatement();
        whileStatement.setExpression(ast.newBooleanLiteral(true));
        whileStatement.setBody(ast.newBlock());
        return whileStatement;
    }

    private static Statement createForStatement(AST ast) {
        EnhancedForStatement enhancedFor = ast.newEnhancedForStatement();
        SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
        parameter.setType(ProjectAnalyzer.createTypeNode(ast, "String"));
        parameter.setName(ast.newSimpleName("item"));
        enhancedFor.setParameter(parameter);
        enhancedFor.setExpression(ast.newSimpleName("array"));
        enhancedFor.setBody(ast.newBlock());
        return enhancedFor;
    }

    private static Statement createDoWhileStatement(AST ast) {
        DoStatement doStatement = ast.newDoStatement();
        doStatement.setExpression(ast.newBooleanLiteral(true));
        doStatement.setBody(ast.newBlock());
        return doStatement;
    }

    private static Statement createEnumDeclaration(AST ast) {
        TypeDeclarationStatement typeDeclStmt = ast.newTypeDeclarationStatement(ast.newEnumDeclaration());
        EnumDeclaration enumDecl = (EnumDeclaration) typeDeclStmt.getDeclaration();
        enumDecl.setName(ast.newSimpleName("MyEnum"));
        EnumConstantDeclaration const1 = ast.newEnumConstantDeclaration();
        const1.setName(ast.newSimpleName("OPTION_A"));
        enumDecl.enumConstants().add(const1);
        EnumConstantDeclaration const2 = ast.newEnumConstantDeclaration();
        const2.setName(ast.newSimpleName("OPTION_B"));
        enumDecl.enumConstants().add(const2);
        return typeDeclStmt;
    }

    private static Statement createAssignmentStatement(AST ast) {
        Assignment assignment = ast.newAssignment();
        assignment.setLeftHandSide(ast.newSimpleName("variable"));
        assignment.setOperator(Assignment.Operator.ASSIGN);
        assignment.setRightHandSide(ast.newNumberLiteral("0"));
        return ast.newExpressionStatement(assignment);
    }

    private static Statement createSwitchStatement(AST ast) {
        SwitchStatement switchStmt = ast.newSwitchStatement();
        switchStmt.setExpression(ast.newSimpleName("variable"));
        SwitchCase defaultCase = ast.newSwitchCase();
        switchStmt.statements().add(defaultCase);
        switchStmt.statements().add(ast.newBreakStatement());
        return switchStmt;
    }

    private static Statement createFunctionCallStatement(AST ast, CompilationUnit cu, ASTRewrite rewriter) {
        ImportManager.addImport(cu, rewriter, "com.botmaker.sdk.api.BotMaker");
        MethodInvocation methodCall = ast.newMethodInvocation();
        Name libraryClass = ast.newName("BotMaker");
        methodCall.setExpression(libraryClass);
        methodCall.setName(ast.newSimpleName("DefaultMethod"));
        return ast.newExpressionStatement(methodCall);
    }

    private static Statement createWaitStatement(AST ast) {
        TryStatement tryStmt = ast.newTryStatement();
        Block tryBody = ast.newBlock();
        MethodInvocation sleepCall = ast.newMethodInvocation();
        sleepCall.setExpression(ast.newSimpleName("Thread"));
        sleepCall.setName(ast.newSimpleName("sleep"));
        sleepCall.arguments().add(ast.newNumberLiteral("1000"));
        tryBody.statements().add(ast.newExpressionStatement(sleepCall));
        tryStmt.setBody(tryBody);
        CatchClause catchClause = ast.newCatchClause();
        SingleVariableDeclaration exceptionDecl = ast.newSingleVariableDeclaration();
        exceptionDecl.setType(ast.newSimpleType(ast.newSimpleName("InterruptedException")));
        exceptionDecl.setName(ast.newSimpleName("e"));
        catchClause.setException(exceptionDecl);
        Block catchBody = ast.newBlock();
        MethodInvocation printStackTrace = ast.newMethodInvocation();
        printStackTrace.setExpression(ast.newSimpleName("e"));
        printStackTrace.setName(ast.newSimpleName("printStackTrace"));
        catchBody.statements().add(ast.newExpressionStatement(printStackTrace));
        catchClause.setBody(catchBody);
        tryStmt.catchClauses().add(catchClause);
        return tryStmt;
    }

    /**
     * A name-targeted activity toggle — {@code Activity.disable("Name")} / {@code Activity.enable("Name")} — a
     * static call on {@code Activity} (so it is valid anywhere, and can target another activity), adding the
     * {@code Activity} import. The name seeds to the project's first activity so the picker has a sensible
     * default. Recognised back into an {@code ActivityToggleBlock} by {@code BlockConverter#parseExprStmt}.
     */
    private static Statement createActivityToggleStatement(AST ast, CompilationUnit cu, ASTRewrite rewriter,
                                                           ProjectState state, String methodName) {
        ImportManager.addImport(cu, rewriter, "com.botmaker.sdk.api.bot.Activity");
        MethodInvocation call = ast.newMethodInvocation();
        call.setExpression(ast.newSimpleName("Activity"));
        call.setName(ast.newSimpleName(methodName));
        StringLiteral arg = ast.newStringLiteral();
        arg.setLiteralValue(defaultActivityName(state));
        call.arguments().add(arg);
        return ast.newExpressionStatement(call);
    }

    /** The project's first activity name (the picker's default), or "" when there are no activities yet. */
    private static String defaultActivityName(ProjectState state) {
        if (state != null && state.getActivities() != null && !state.getActivities().activities().isEmpty()) {
            return state.getActivities().activities().get(0).name();
        }
        return "";
    }

    /**
     * The {@code Bot.stop();} statement that ends the bot. A static-qualified call (unlike the bare inherited
     * {@code disable()}/{@code enable()} self-calls), so it also adds the {@code Bot} import. Recognised back
     * into a {@code StopBotBlock} by {@code BlockConverter#parseExprStmt}.
     */
    private static Statement createStopBotStatement(AST ast, CompilationUnit cu, ASTRewrite rewriter) {
        ImportManager.addImport(cu, rewriter, "com.botmaker.sdk.api.bot.Bot");
        MethodInvocation call = ast.newMethodInvocation();
        call.setExpression(ast.newSimpleName("Bot"));
        call.setName(ast.newSimpleName("stop"));
        return ast.newExpressionStatement(call);
    }
}
