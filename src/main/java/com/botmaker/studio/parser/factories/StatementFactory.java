package com.botmaker.studio.parser.factories;

import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.Initializer;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.parser.helpers.SdkNodes;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.SdkSurfaceService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.PrimitiveKind;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StatementFactory {

    /**
     * Builds the default AST {@link Statement} for a dropped {@link BlockType}. Dispatch is an exhaustive switch on
     * the sealed type — the data-carrying variants ({@link BlockType.VarDecl}, {@link BlockType.ScannerRead},
     * {@link BlockType.LibraryCall}) are built generically from their fields, so new variants are pure data.
     */
    public static Statement createStatement(EditContext ctx, BlockType type, ASTNode context) {
        return switch (type) {
            case BlockType.ControlFlow cf -> createControlFlow(ctx, cf.kind(), context);
            case BlockType.VarDecl v -> buildVarDecl(ctx, v, context);
            case BlockType.LibraryCall l -> buildLibraryCall(ctx, l);
            case BlockType.LambdaCall l -> buildLambdaCall(ctx, l);
            case BlockType.EnumDecl ignored -> createEnumDeclaration(ctx.ast());
            case BlockType.MethodMember ignored -> null; // a method is a class member, not a body statement
        };
    }

    private static Statement createControlFlow(EditContext ctx, BlockType.ControlFlow.Kind kind,
                                               ASTNode context) {
        AST ast = ctx.ast();
        return switch (kind) {
            case PRINT -> createPrintStatement(ctx);
            case IF -> createIfStatement(ast);
            case WHILE -> createWhileStatement(ast);
            case FOR -> createForStatement(ast, ctx.analyzer(), context);
            case DO_WHILE -> createDoWhileStatement(ast);
            case SWITCH -> createSwitchStatement(ast, ctx.analyzer(), context);
            case BREAK -> ast.newBreakStatement();
            case CONTINUE -> ast.newContinueStatement();
            case RETURN -> ast.newReturnStatement();
            case WAIT -> createWaitStatement(ast);
            case ASSIGNMENT -> createAssignmentStatement(ast, ctx.analyzer(), context);
            case FUNCTION_CALL -> createFunctionCallStatement(ctx, context);
            case ARRAY -> createArrayDeclaration(ctx, context);
            case COMMENT -> (Statement) ctx.rewriter().createStringPlaceholder("// Comment", ASTNode.EMPTY_STATEMENT);
        };
    }

    // --- Scope-aware defaults ------------------------------------------------
    //
    // These four blocks used to be seeded with invented identifiers (`switch (variable)`, `variable = 0`,
    // `for (String item : array)`, `BotMaker.DefaultMethod()`), so every drop produced an immediate
    // "cannot resolve symbol". The rule now: name only something that exists at the drop site, and when
    // nothing qualifies leave an empty "+" slot rather than inventing a name the user has to notice and fix.
    //
    // That slot used to be a null literal in all four. `null` does not compile in two of them — `switch (null)`
    // and `for (var item : null)` are errors on the spot — so where a *value* can stand the block is now seeded
    // with one that compiles and reads as a placeholder (`switch (0)`, an empty array). Only the two positions
    // that need a *name* — an assignment's left-hand side, a pattern switch's subject — are left unfilled; see
    // UnfilledSlot, which is what the pre-run check now asks instead of "is it a null".

    /** The first variable visible at {@code context} matching {@code filter}, or {@code null} if none is. */
    private static ProjectAnalyzer.VariableOption firstVisibleVariable(
            ProjectAnalyzer analyzer, ASTNode context, Predicate<ProjectAnalyzer.VariableOption> filter) {
        if (analyzer == null || context == null) return null;
        return analyzer.getVisibleVariables(context, ResolvedType.UNKNOWN).stream()
                .filter(filter)
                .findFirst()
                .orElse(null);
    }

    /** A slot needing a name nothing at the drop site supplies. See {@link UnfilledSlot}. */
    private static Expression emptySlot(AST ast) {
        return UnfilledSlot.of(ast);
    }

    /**
     * {@code base}, or {@code base2}, {@code base3}… if that name is already taken at the drop site. The declare
     * blocks carry a fixed variable name, so dropping the same one twice used to declare the name twice — a
     * duplicate-variable error from nothing but repeating a palette action.
     *
     * <p>The names come from <b>two</b> sources, and the syntactic one is why this was still producing
     * collisions. {@link ProjectAnalyzer#getVisibleVariables} walks JDT <em>bindings</em>, and a compilation
     * unit parsed without a resolved classpath — the ordinary case while a project is still being edited —
     * has none, so that list came back empty and every drop kept its base name. Reading the enclosing method's
     * declaration nodes needs no bindings and cannot come back empty when a name really is there. The analyzer
     * is kept as the second source: it also sees fields and captured names, which the syntactic walk does not.
     */
    private static String uniqueName(ProjectAnalyzer analyzer, ASTNode context, String base) {
        java.util.Set<String> taken = new java.util.HashSet<>(declaredNamesAround(context));
        if (analyzer != null && context != null) {
            analyzer.getVisibleVariables(context, ResolvedType.UNKNOWN).stream()
                    .map(ProjectAnalyzer.VariableOption::name)
                    .forEach(taken::add);
        }
        if (!taken.contains(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + i;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    /**
     * Every variable name declared inside the method (or initializer, or lambda body) enclosing {@code context}
     * — locals, {@code for} indices, catch parameters and the method's own parameters alike. Purely syntactic:
     * it reads the names off the declaration nodes, so it works on an unresolved AST.
     *
     * <p>Scoping is deliberately ignored. A name declared in a sibling block cannot actually clash with one
     * declared here, but reusing it would still read as the same variable to someone looking at the method, and
     * "one more than you needed" is the cheap side of this trade.
     */
    private static java.util.Set<String> declaredNamesAround(ASTNode context) {
        if (context == null) return java.util.Set.of();
        ASTNode scope = context;
        while (scope.getParent() != null && !(scope instanceof MethodDeclaration)) {
            scope = scope.getParent();
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        scope.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                names.add(node.getName().getIdentifier());
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                names.add(node.getName().getIdentifier());
                return true;
            }
        });
        return names;
    }

    // --- Data-driven builders ---

    private static Statement buildVarDecl(EditContext ctx, BlockType.VarDecl v, ASTNode context) {
        AST ast = ctx.ast();
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(uniqueName(ctx.analyzer(), context, v.varName())));
        Expression init = buildExpression(ctx, v.init());
        if (init != null) fragment.setInitializer(init);
        VariableDeclarationStatement varDecl = ast.newVariableDeclarationStatement(fragment);
        varDecl.setType(typeNode(ast, v.typeName(), v.primitive()));
        if (!v.primitive()) ctx.addImportForSimpleName(v.typeName());
        return varDecl;
    }

    // buildScannerRead went on 2026-09-01 with the three Read blocks it built. It wrote
    // `String s = BotMaker.readLine();` — an SDK facade call, emitted by the editor on the SDK's behalf, which
    // is exactly what this pass removes. There is no JDK one-liner to put in its place (a Scanner needs a
    // field), so the blocks went rather than being rewritten; a bot that wants to read stdin calls whatever
    // its own libraries offer, through the member menus.

    private static Statement buildLibraryCall(EditContext ctx, BlockType.LibraryCall l) {
        AST ast = ctx.ast();
        MethodInvocation mi = ast.newMethodInvocation();
        mi.setExpression(SdkNodes.name(ast, l.facade()));
        mi.setName(ast.newSimpleName(l.method()));
        ctx.addImport(l.facade());
        if (!l.args().isEmpty()) {
            for (Initializer arg : l.args()) mi.arguments().add(buildExpression(ctx, arg));
        } else {
            // Choose the default overload: the project's pinned favorite if any, else the overload with the
            // fewest arguments (the simplest starting point). Seed a default value per parameter (a
            // CaptureSource slot gets the project-default target — see InitializerFactory). When no overload
            // resolves at all (unknown method), fall back to a single empty "+" slot the user fills.
            List<ResolvedType> params = defaultOverloadParams(l, ctx.state(), ctx.analyzer(), ctx.surface());
            if (params != null) {
                for (ResolvedType p : params) {
                    mi.arguments().add(InitializerFactory.createDefaultInitializer(ctx, p));
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
                                                            ProjectAnalyzer analyzer,
                                                            SdkSurfaceService surface) {
        if (analyzer == null) return null;
        List<MethodSignature> sigs = analyzer.getMethods(l.facade().getSimpleName(), true).stream()
                .filter(s -> s.name().equals(l.method()))
                .collect(Collectors.toList());
        if (sigs.isEmpty()) return null;
        // A fresh insert has no current overload to protect, so the offered set applies without exception:
        // the menu proposed this name because some overload is offered, and that is the one to create.
        // fewestParams over the unfiltered list would happily land the user on a hidden shape.
        if (surface != null) sigs = surface.retainOffered(l.facade().getSimpleName(), l.method(), sigs, null);
        String favKey = (state != null && state.getSettings() != null)
                ? state.getSettings().favoriteSignature(l.facade().getSimpleName() + "#" + l.method()) : null;
        // A favorite pinned before the facade was curated may name an overload that is no longer offered;
        // bestForKey then finds nothing in the filtered list and the fallback takes over, which is the
        // intended degradation — a stale preference must not resurrect a hidden overload.
        MethodSignature chosen = MethodSignature.bestForKey(sigs, favKey);
        if (chosen == null) chosen = MethodSignature.fewestParams(sigs);
        return chosen != null ? chosen.paramTypes() : null;
    }

    private static Statement buildLambdaCall(EditContext ctx, BlockType.LambdaCall l) {
        AST ast = ctx.ast();
        List<Expression> leading = new ArrayList<>();
        if (l.leadingArgs().isEmpty()) {
            leading.add(ast.newNullLiteral()); // empty "+" slot the user fills — same convention as buildLibraryCall
        } else {
            for (Initializer arg : l.leadingArgs()) leading.add(buildExpression(ctx, arg));
        }
        MethodInvocation mi = LambdaCallHandler.buildLambdaCall(
                ctx, l.facade(), l.method(), leading, l.lambdaParam());
        return ast.newExpressionStatement(mi);
    }

    /**
     * Turns a declarative {@link Initializer} into an AST expression (recursive for {@code new T(args...)}).
     *
     * <p>Public because {@code BotType} carries one of these per type as "what a fresh value of this looks
     * like", and that answer is wanted in two places now: seeding a declaration, and seeding the {@code return}
     * of a function the user just added.
     */
    public static Expression buildExpression(EditContext ctx, Initializer init) {
        AST ast = ctx.ast();
        return switch (init) {
            case Initializer.IntLit i -> ast.newNumberLiteral(i.value());
            case Initializer.DoubleLit d -> ast.newNumberLiteral(d.value());
            case Initializer.BoolLit b -> ast.newBooleanLiteral(b.value());
            case Initializer.CharLit c -> {
                CharacterLiteral lit = ast.newCharacterLiteral();
                lit.setCharValue(c.value());
                yield lit;
            }
            case Initializer.StrLit s -> {
                StringLiteral lit = ast.newStringLiteral();
                lit.setLiteralValue(s.value());
                yield lit;
            }
            case Initializer.NullLit ignored -> ast.newNullLiteral();
            case Initializer.NewInstance n -> {
                ClassInstanceCreation creation = ast.newClassInstanceCreation();
                creation.setType(ast.newSimpleType(seedTypeName(ctx, n.typeName())));
                for (Initializer arg : n.args()) creation.arguments().add(buildExpression(ctx, arg));
                yield creation;
            }
            case Initializer.EnumConst e -> ast.newQualifiedName(seedTypeName(ctx, e.typeName()),
                    ast.newSimpleName(e.constant()));
            case Initializer.StaticCall c -> {
                MethodInvocation mi = ast.newMethodInvocation();
                mi.setExpression(seedTypeName(ctx, c.typeName()));
                mi.setName(ast.newSimpleName(c.methodName()));
                for (Initializer arg : c.args()) mi.arguments().add(buildExpression(ctx, arg));
                yield mi;
            }
        };
    }

    /**
     * The name node for a seed's type, plus the import that makes it resolve.
     *
     * <p>A seed names its type in one of two ways. Most name it simply — {@code Point}, {@code Direction} —
     * because it is an SDK type the project index can resolve. The JDK ones have to name it fully
     * ({@code java.time.LocalDate}, {@code java.awt.Color}) because nothing would resolve {@code LocalDate}
     * on its own. Both used to go to {@code ast.newSimpleName}, which rejects a dotted string outright: that
     * is why dropping a Date, a Time of day or a Duration inserted nothing at all, and why the Colour seed
     * would have done the same had it not been filtered out of the menu.
     *
     * <p>A simple name still gets its import; a qualified one is emitted as written and needs none — which
     * matches the declared type those seeds sit under ({@code java.time.LocalDate date = …}) and the reason
     * {@link com.botmaker.studio.palette.BotType#DATE} spells it out in the first place.
     */
    private static Name seedTypeName(EditContext ctx, String typeName) {
        if (typeName.indexOf('.') < 0) {
            ctx.addImportForSimpleName(typeName);
            return ctx.ast().newSimpleName(typeName);
        }
        return ctx.ast().newName(typeName);
    }

    /**
     * A type node for {@code typeName}. The {@code primitive} flag comes from the catalog entry, so a
     * disagreement between it and the name is a catalog bug: fall back to a named type rather than throw,
     * which is what the old {@code primitiveCode} switch did on any name it didn't list (including
     * {@code void}, which it omitted outright).
     */
    private static Type typeNode(AST ast, String typeName, boolean primitive) {
        if (primitive) {
            Optional<PrimitiveKind> kind = PrimitiveKind.fromKeyword(typeName);
            if (kind.isPresent()) return ast.newPrimitiveType(kind.get().code());
        }
        return ProjectAnalyzer.createTypeNode(ast, typeName);
    }

    // --- Bespoke one-off statement creators ---

    /**
     * {@code System.out.println("")}.
     *
     * <p>It emitted {@code BotMaker.print("")} until 2026-09-01 — an SDK facade call written by the editor,
     * needing an import the editor had to know the package of. The JDK does this, and Studio's own
     * {@code StarterSources} has printed with {@code System.out.println} since 2026-08-30 for the reason
     * recorded there: teaching a BotMaker spelling of a Java call spends a user's first line on nothing.
     * Nothing is imported — {@code java.lang.System} needs none.
     */
    private static Statement createPrintStatement(EditContext ctx) {
        AST ast = ctx.ast();
        MethodInvocation print = ast.newMethodInvocation();
        FieldAccess out = ast.newFieldAccess();
        out.setExpression(ast.newSimpleName("System"));
        out.setName(ast.newSimpleName("out"));
        print.setExpression(out);
        print.setName(ast.newSimpleName("println"));
        StringLiteral emptyString = ast.newStringLiteral();
        emptyString.setLiteralValue("");
        print.arguments().add(emptyString);
        return ast.newExpressionStatement(print);
    }

    private static Statement createArrayDeclaration(EditContext ctx, ASTNode context) {
        AST ast = ctx.ast();
        VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
        frag.setName(ast.newSimpleName(uniqueName(ctx.analyzer(), context, "myList")));
        ResolvedType arrayType = ResolvedType.named("int[]");
        frag.setInitializer(InitializerFactory.createArrayInitializer(ast, arrayType, Collections.emptyList(), ctx.cu(), ctx.state()));
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
     * variable typed from its element type.
     *
     * <p>With nothing iterable in scope it is {@code for (String item : new String[0])} — a loop over nothing,
     * which is what an unfilled one means and, unlike the {@code for (var item : null)} it used to emit, is
     * source that compiles. {@code String} rather than {@code var} because {@code var} needs the collection to
     * infer from, and an empty array needs no import.
     */
    private static Statement createForStatement(AST ast, ProjectAnalyzer analyzer, ASTNode context) {
        ProjectAnalyzer.VariableOption iterable =
                firstVisibleVariable(analyzer, context, v -> isIterable(v.type()));

        EnhancedForStatement enhancedFor = ast.newEnhancedForStatement();
        SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
        parameter.setType(iterable == null
                ? ast.newSimpleType(ast.newSimpleName(JdkType.STRING.simpleName()))
                : elementTypeNode(ast, iterable.type()));
        parameter.setName(ast.newSimpleName("item"));
        enhancedFor.setParameter(parameter);
        enhancedFor.setExpression(iterable == null
                ? emptyArray(ast, JdkType.STRING.simpleName())
                : ast.newSimpleName(iterable.name()));
        enhancedFor.setBody(ast.newBlock());
        return enhancedFor;
    }

    /** {@code new T[0]} — the "iterate nothing yet" seed, needing no name and no import. */
    private static Expression emptyArray(AST ast, String elementTypeName) {
        ArrayCreation creation = ast.newArrayCreation();
        creation.setType(ast.newArrayType(ast.newSimpleType(ast.newSimpleName(elementTypeName)), 1));
        creation.dimensions().add(ast.newNumberLiteral("0"));
        return creation;
    }

    /** Arrays and the common {@code java.util} collection interfaces — what an enhanced-for can walk. */
    private static boolean isIterable(ResolvedType type) {
        if (type == null) return false;
        if (type.isArray()) return true;
        return ITERABLE_TYPES.contains(type.simpleName());
    }

    private static final java.util.Set<String> ITERABLE_TYPES = JdkType.simpleNames(JdkType.ITERABLES);

    /**
     * The loop-variable type for iterating {@code type}: an array's leaf type when known, else {@code var} —
     * the element type of a {@code List<T>} isn't recoverable from a {@link ResolvedType}'s simple name, and
     * {@code var} is correct for every case rather than a guess that may not compile.
     */
    private static Type elementTypeNode(AST ast, ResolvedType type) {
        if (type != null && type.isArray()) {
            ResolvedType leaf = type.leafType();
            return typeNode(ast, leaf.simpleName(), leaf.isPrimitive());
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
     *
     * <p>With nothing switchable in scope the subject is {@code 0}, matching the {@code case 0:} label
     * {@link #firstCaseLabel} seeds for the same unknown type — a switch that compiles and always takes its
     * first branch. It was {@code switch (null)}, which is an error in Java outright.
     */
    private static Statement createSwitchStatement(AST ast, ProjectAnalyzer analyzer, ASTNode context) {
        ProjectAnalyzer.VariableOption subject =
                firstVisibleVariable(analyzer, context, v -> isSwitchable(v.type()));

        SwitchStatement switchStmt = ast.newSwitchStatement();
        switchStmt.setExpression(subject == null
                ? ast.newNumberLiteral("0")
                : ast.newSimpleName(subject.name()));

        SwitchCase firstCase = ast.newSwitchCase();
        firstCase.expressions().add(firstCaseLabel(ast, subject == null ? null : subject.type()));
        switchStmt.statements().add(firstCase);
        switchStmt.statements().add(ast.newBreakStatement());

        SwitchCase defaultCase = ast.newSwitchCase();
        switchStmt.statements().add(defaultCase);
        switchStmt.statements().add(ast.newBreakStatement());
        return switchStmt;
    }

    // createMatchesSwitchStatement stood here until 2026-09-01. It built a guarded pattern switch over the
    // SDK's Matches, which meant naming that type, its templates, the pattern variable and the mandatory
    // default rule — this factory spelling one library's vocabulary because a switch is a language construct
    // and cannot be assembled out of catalogued members. Branching on a frame is a chain of ordinary calls
    // now, so it arrives through the ordinary member-insert path and needs no bespoke shape here. It also
    // carried a note that Matches was deliberately absent from SWITCHABLE_TYPES so the colon-form switch kept
    // rejecting it; that is no longer a decision anybody has to keep, since the set is the integral types,
    // String, char and enums, and nothing offers a switch over a library value at all.

    /** What Java lets you switch on: the integral types (and their boxes), {@code String}, {@code char}, enums. */
    private static boolean isSwitchable(ResolvedType type) {
        if (type == null) return false;
        if (type.isEnum() || type.isString()) return true;
        return SWITCHABLE_TYPES.contains(type.simpleName());
    }

    private static final java.util.Set<String> SWITCHABLE_TYPES = Stream.concat(
            Stream.of(PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE, PrimitiveKind.CHAR)
                    .map(PrimitiveKind::keyword),
            Stream.of(JdkType.INTEGER, JdkType.SHORT, JdkType.BYTE, JdkType.CHARACTER, JdkType.STRING)
                    .map(JdkType::simpleName))
            .collect(Collectors.toUnmodifiableSet());

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
    private static Statement createFunctionCallStatement(EditContext ctx, ASTNode context) {
        AST ast = ctx.ast();
        IMethodBinding target = firstCallableMethod(ctx.analyzer(), context);
        if (target == null) return createPrintStatement(ctx);

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
