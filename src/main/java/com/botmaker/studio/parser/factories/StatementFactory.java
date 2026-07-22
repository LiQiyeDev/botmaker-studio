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
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class StatementFactory {

    /**
     * Builds the default AST {@link Statement} for a dropped {@link BlockType}. Dispatch is an exhaustive switch on
     * the sealed type — the data-carrying variants ({@link BlockType.VarDecl}, {@link BlockType.ScannerRead},
     * {@link BlockType.LibraryCall}) are built generically from their fields, so new variants are pure data.
     */
    public static Statement createStatement(AST ast, BlockType type, CompilationUnit cu, ASTRewrite rewriter,
                                            ProjectState state, ProjectAnalyzer analyzer, ASTNode context) {
        return switch (type) {
            case BlockType.ControlFlow cf -> createControlFlow(ast, cf.kind(), cu, rewriter, state, analyzer, context);
            case BlockType.VarDecl v -> buildVarDecl(ast, v, cu, rewriter, analyzer, context);
            case BlockType.ScannerRead r -> buildScannerRead(ast, r, cu, rewriter, analyzer, context);
            case BlockType.LibraryCall l -> buildLibraryCall(ast, l, cu, rewriter, state, analyzer);
            case BlockType.LambdaCall l -> buildLambdaCall(ast, l, cu, rewriter, analyzer);
            case BlockType.EnumDecl ignored -> createEnumDeclaration(ast);
            case BlockType.MethodMember ignored -> null; // a method is a class member, not a body statement
        };
    }

    private static Statement createControlFlow(AST ast, BlockType.ControlFlow.Kind kind,
                                               CompilationUnit cu, ASTRewrite rewriter, ProjectState state,
                                               ProjectAnalyzer analyzer, ASTNode context) {
        return switch (kind) {
            case PRINT -> createPrintStatement(ast, cu, rewriter);
            case IF -> createIfStatement(ast);
            case WHILE -> createWhileStatement(ast);
            case FOR -> createForStatement(ast, analyzer, context);
            case DO_WHILE -> createDoWhileStatement(ast);
            case SWITCH -> createSwitchStatement(ast, analyzer, context);
            case BREAK -> ast.newBreakStatement();
            case CONTINUE -> ast.newContinueStatement();
            case RETURN -> ast.newReturnStatement();
            case WAIT -> createWaitStatement(ast);
            case ASSIGNMENT -> createAssignmentStatement(ast, analyzer, context);
            case FUNCTION_CALL -> createFunctionCallStatement(ast, cu, rewriter, analyzer, context);
            case ARRAY -> createArrayDeclaration(ast, cu, state, analyzer, context);
            case COMMENT -> (Statement) rewriter.createStringPlaceholder("// Comment", ASTNode.EMPTY_STATEMENT);
        };
    }

    // --- Scope-aware defaults ------------------------------------------------
    //
    // These four blocks used to be seeded with invented identifiers (`switch (variable)`, `variable = 0`,
    // `for (String item : array)`, `BotMaker.DefaultMethod()`), so every drop produced an immediate
    // "cannot resolve symbol". The rule now: name only something that exists at the drop site, and when
    // nothing qualifies leave an empty "+" slot (a null literal, the same convention buildLibraryCall uses)
    // rather than inventing a name the user has to notice and fix.

    /** The first variable visible at {@code context} matching {@code filter}, or {@code null} if none is. */
    private static ProjectAnalyzer.VariableOption firstVisibleVariable(
            ProjectAnalyzer analyzer, ASTNode context, Predicate<ProjectAnalyzer.VariableOption> filter) {
        if (analyzer == null || context == null) return null;
        return analyzer.getVisibleVariables(context, ResolvedType.UNKNOWN).stream()
                .filter(filter)
                .findFirst()
                .orElse(null);
    }

    /** An empty slot the user fills from the expression menu — never an invented identifier. */
    private static Expression emptySlot(AST ast) {
        return ast.newNullLiteral();
    }

    /**
     * {@code base}, or {@code base2}, {@code base3}… if that name is already taken at the drop site. The declare
     * blocks carry a fixed variable name, so dropping the same one twice used to declare the name twice — a
     * duplicate-variable error from nothing but repeating a palette action.
     */
    private static String uniqueName(ProjectAnalyzer analyzer, ASTNode context, String base) {
        if (analyzer == null || context == null) return base;
        java.util.Set<String> taken = analyzer.getVisibleVariables(context, ResolvedType.UNKNOWN).stream()
                .map(ProjectAnalyzer.VariableOption::name)
                .collect(java.util.stream.Collectors.toSet());
        if (!taken.contains(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + i;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    // --- Data-driven builders ---

    private static Statement buildVarDecl(AST ast, BlockType.VarDecl v, CompilationUnit cu,
                                          ASTRewrite rewriter, ProjectAnalyzer analyzer, ASTNode context) {
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(uniqueName(analyzer, context, v.varName())));
        Expression init = buildExpression(ast, v.init(), cu, rewriter, analyzer);
        if (init != null) fragment.setInitializer(init);
        VariableDeclarationStatement varDecl = ast.newVariableDeclarationStatement(fragment);
        varDecl.setType(typeNode(ast, v.typeName(), v.primitive()));
        if (!v.primitive()) ImportManager.addImportForSimpleName(cu, rewriter, v.typeName(), analyzer, null);
        return varDecl;
    }

    private static Statement buildScannerRead(AST ast, BlockType.ScannerRead r, CompilationUnit cu,
                                              ASTRewrite rewriter, ProjectAnalyzer analyzer, ASTNode context) {
        ImportManager.addImport(cu, rewriter, "com.botmaker.sdk.api.BotMaker");
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(uniqueName(analyzer, context, r.varName())));
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

    private static Statement createArrayDeclaration(AST ast, CompilationUnit cu, ProjectState state,
                                                    ProjectAnalyzer analyzer, ASTNode context) {
        VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
        frag.setName(ast.newSimpleName(uniqueName(analyzer, context, "myList")));
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

    /**
     * {@code for (T item : <collection>)} over the first array/{@link Iterable} variable in scope, with the loop
     * variable typed from its element type. With nothing iterable in scope the loop variable is a {@code var} over
     * an empty slot, so the user picks the collection and the type follows.
     */
    private static Statement createForStatement(AST ast, ProjectAnalyzer analyzer, ASTNode context) {
        ProjectAnalyzer.VariableOption iterable =
                firstVisibleVariable(analyzer, context, v -> isIterable(v.type()));

        EnhancedForStatement enhancedFor = ast.newEnhancedForStatement();
        SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
        parameter.setType(elementTypeNode(ast, iterable == null ? null : iterable.type()));
        parameter.setName(ast.newSimpleName("item"));
        enhancedFor.setParameter(parameter);
        enhancedFor.setExpression(iterable == null ? emptySlot(ast) : ast.newSimpleName(iterable.name()));
        enhancedFor.setBody(ast.newBlock());
        return enhancedFor;
    }

    /** Arrays and the common {@code java.util} collection interfaces — what an enhanced-for can walk. */
    private static boolean isIterable(ResolvedType type) {
        if (type == null) return false;
        if (type.isArray()) return true;
        return ITERABLE_TYPES.contains(type.simpleName());
    }

    private static final java.util.Set<String> ITERABLE_TYPES = java.util.Set.of(
            "Iterable", "Collection", "List", "ArrayList", "LinkedList", "Set", "HashSet", "LinkedHashSet",
            "TreeSet", "Queue", "Deque", "ArrayDeque");

    /**
     * The loop-variable type for iterating {@code type}: an array's leaf type when known, else {@code var} —
     * the element type of a {@code List<T>} isn't recoverable from a {@link ResolvedType}'s simple name, and
     * {@code var} is correct for every case rather than a guess that may not compile.
     */
    private static Type elementTypeNode(AST ast, ResolvedType type) {
        if (type != null && type.isArray()) {
            ResolvedType leaf = type.leafType();
            if (leaf.isPrimitive()) return ast.newPrimitiveType(primitiveCode(leaf.simpleName()));
            return ProjectAnalyzer.createTypeNode(ast, leaf.simpleName());
        }
        return ast.newSimpleType(ast.newSimpleName("var"));
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

    /** {@code <var> = <default for its type>} over the first variable in scope; an empty slot when there is none. */
    private static Statement createAssignmentStatement(AST ast, ProjectAnalyzer analyzer, ASTNode context) {
        ProjectAnalyzer.VariableOption target = firstVisibleVariable(analyzer, context, v -> true);
        Assignment assignment = ast.newAssignment();
        assignment.setOperator(Assignment.Operator.ASSIGN);
        if (target == null) {
            assignment.setLeftHandSide(emptySlot(ast));
            assignment.setRightHandSide(emptySlot(ast));
        } else {
            assignment.setLeftHandSide(ast.newSimpleName(target.name()));
            assignment.setRightHandSide(InitializerFactory.createDefaultInitializer(ast, target.type()));
        }
        return ast.newExpressionStatement(assignment);
    }

    /**
     * A switch over the first switchable variable in scope, shaped as one real {@code case} plus a
     * {@code default} so the structure is obvious. Every case gets a trailing {@code break} — {@code SwitchBlock}
     * renders those as case chrome rather than deletable child blocks, so fall-through can't be created by
     * accident.
     */
    private static Statement createSwitchStatement(AST ast, ProjectAnalyzer analyzer, ASTNode context) {
        ProjectAnalyzer.VariableOption subject =
                firstVisibleVariable(analyzer, context, v -> isSwitchable(v.type()));

        SwitchStatement switchStmt = ast.newSwitchStatement();
        switchStmt.setExpression(subject == null ? emptySlot(ast) : ast.newSimpleName(subject.name()));

        SwitchCase firstCase = ast.newSwitchCase();
        firstCase.expressions().add(firstCaseLabel(ast, subject == null ? null : subject.type()));
        switchStmt.statements().add(firstCase);
        switchStmt.statements().add(ast.newBreakStatement());

        SwitchCase defaultCase = ast.newSwitchCase();
        switchStmt.statements().add(defaultCase);
        switchStmt.statements().add(ast.newBreakStatement());
        return switchStmt;
    }

    /** What Java lets you switch on: the integral types (and their boxes), {@code String}, {@code char}, enums. */
    private static boolean isSwitchable(ResolvedType type) {
        if (type == null) return false;
        if (type.isEnum() || type.isString()) return true;
        return SWITCHABLE_TYPES.contains(type.simpleName());
    }

    private static final java.util.Set<String> SWITCHABLE_TYPES = java.util.Set.of(
            "int", "short", "byte", "char", "Integer", "Short", "Byte", "Character", "String");

    /** A constant of the switch subject's type for the seeded first case. */
    private static Expression firstCaseLabel(AST ast, ResolvedType type) {
        if (type != null && type.isEnum()) {
            List<String> constants = type.enumConstants();
            // Enum case labels are unqualified — `case OPTION_A:`, never `case MyEnum.OPTION_A:`.
            if (!constants.isEmpty()) return ast.newSimpleName(constants.getFirst());
        }
        if (type != null && (type.isString() || "Character".equals(type.simpleName()) || "char".equals(type.simpleName()))) {
            StringLiteral lit = ast.newStringLiteral();
            lit.setLiteralValue("");
            if (type.isString()) return lit;
            CharacterLiteral charLit = ast.newCharacterLiteral();
            charLit.setCharValue('a');
            return charLit;
        }
        return ast.newNumberLiteral("0");
    }

    /**
     * "Call Function" calls one of <em>the project's own</em> methods, seeded with the first one visible at the
     * drop site. It used to emit {@code BotMaker.DefaultMethod()} — a method that has never existed in the SDK,
     * so every drop produced an unresolvable symbol (ROADMAP B7). When the class declares nothing else to call
     * yet, fall back to {@code BotMaker.print("")}, which always resolves.
     */
    private static Statement createFunctionCallStatement(AST ast, CompilationUnit cu, ASTRewrite rewriter,
                                                         ProjectAnalyzer analyzer, ASTNode context) {
        IMethodBinding target = firstCallableMethod(analyzer, context);
        if (target == null) return createPrintStatement(ast, cu, rewriter);

        MethodInvocation methodCall = ast.newMethodInvocation();
        methodCall.setName(ast.newSimpleName(target.getName()));
        for (ITypeBinding p : target.getParameterTypes()) {
            methodCall.arguments().add(InitializerFactory.createDefaultInitializer(ast, ResolvedType.of(p)));
        }
        return ast.newExpressionStatement(methodCall);
    }

    /**
     * The first method callable unqualified at {@code context}. Constructors are skipped (they aren't statements)
     * and so is the enclosing method itself — seeding a block with a call to the method you're editing would be
     * unbounded recursion, which compiles but is never what was meant.
     */
    private static IMethodBinding firstCallableMethod(ProjectAnalyzer analyzer, ASTNode context) {
        if (analyzer == null || context == null) return null;
        String enclosing = null;
        for (ASTNode n = context; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration md) {
                enclosing = md.getName().getIdentifier();
                break;
            }
        }
        for (IMethodBinding m : analyzer.getAvailableScopes(context).methods()) {
            if (m.isConstructor() || m.isSynthetic()) continue;
            if (m.getName().equals(enclosing)) continue;
            return m;
        }
        return null;
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

}
