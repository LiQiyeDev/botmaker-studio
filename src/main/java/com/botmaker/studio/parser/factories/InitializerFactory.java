package com.botmaker.studio.parser.factories;

import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.parser.helpers.DefaultValueHelper;
import com.botmaker.studio.parser.helpers.SdkNodes;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.capture.CaptureExpr;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.*;

public class InitializerFactory {

    /**
     * Common functional interfaces → SAM (single-abstract-method) parameter count. Keyed on the SIMPLE name so it
     * matches whether the type resolved from the library index or is name-only (the JDK {@code java.util.function.*}
     * types are not in our index). A default value for one of these is a block-bodied lambda (not {@code new I()},
     * which is uncompilable) so it round-trips into an editable {@code LambdaCallBlock}.
     */
    private static final Map<String, Integer> FUNCTIONAL_INTERFACE_ARITY = Map.ofEntries(
            Map.entry("Runnable", 0),
            Map.entry("Supplier", 0), Map.entry("Consumer", 1), Map.entry("Predicate", 1),
            Map.entry("Function", 1), Map.entry("UnaryOperator", 1),
            Map.entry("IntConsumer", 1), Map.entry("IntSupplier", 0), Map.entry("IntPredicate", 1),
            Map.entry("BiConsumer", 2), Map.entry("BiFunction", 2), Map.entry("BiPredicate", 2),
            Map.entry("BinaryOperator", 2), Map.entry("Comparator", 2));

    /**
     * The {@link EditContext} form, for the write paths that hold one — which is every path that seeds an
     * argument.
     *
     * <p>It deliberately ignores {@link EditContext#rewriter()}: this factory has no rewriter of its own and
     * therefore <b>cannot add imports</b>, which is exactly why {@code newInstance} fills only
     * literal-valued constructor parameters. Filling {@code new Rect(Point, Point)} would trade "no such
     * constructor" for "cannot find symbol Point". Destructuring the context here rather than passing it down
     * keeps that limit visible in the signatures below.
     */
    public static Expression createDefaultInitializer(EditContext ctx, ResolvedType type) {
        return createDefaultInitializer(ctx.ast(), type, ctx.cu(), ctx.state(), ctx.analyzer());
    }

    public static Expression createDefaultInitializer(AST ast, ResolvedType type, CompilationUnit cu, ProjectState state) {
        return createDefaultInitializer(ast, type, cu, state, null);
    }

    public static Expression createDefaultInitializer(AST ast, ResolvedType type, CompilationUnit cu,
                                                      ProjectState state, ProjectAnalyzer analyzer) {
        if (type == null) return ast.newNullLiteral();

        // ENRICHMENT: If the type isn't binding-backed, try to find the rich type in the project
        ResolvedType richType = type;
        if (!(type instanceof ResolvedType.Bound) && !type.isPrimitive() && state != null) {
            ResolvedType found = ProjectAnalyzer.findTypeInProject(state, type.leafType().simpleName());
            // If found, re-apply array dimensions
            if (!found.isUnknown()) {
                richType = found.asArray(type.arrayDimensions());
            }
        }

        if (richType.isArray()) {
            return createArrayInitializer(ast, richType, java.util.Collections.emptyList(), cu, state);
        }

        // Functional interface (Consumer/Runnable/…): an empty block-bodied lambda, so the call round-trips into an
        // editable LambdaCallBlock instead of an uncompilable `new Consumer<>()`. Strip generics off the qualified
        // name first — a name-only Consumer<…> otherwise yields the garbled simple name "MatchResult>".
        String raw = richType.leafType().qualifiedName();
        int generic = raw.indexOf('<');
        if (generic >= 0) raw = raw.substring(0, generic);
        String simpleName = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        Integer arity = FUNCTIONAL_INTERFACE_ARITY.get(simpleName);
        if (arity != null) {
            List<String> params = arity == 1 ? List.of("it")
                    : java.util.stream.IntStream.range(0, arity).mapToObj(i -> "arg" + i).toList();
            return LambdaCallHandler.emptyBlockLambda(ast, params);
        }

        // 1. Enum Handling (Now works because richType has binding)
        if (richType.isEnum()) {
            List<String> constants = richType.enumConstants();
            String constName = constants.isEmpty() ? "VALUE" : constants.getFirst();

            return ast.newQualifiedName(
                    ast.newSimpleName(richType.simpleName()),
                    ast.newSimpleName(constName)
            );
        }

        // 2. Primitives & Strings
        Expression primitiveDefault = DefaultValueHelper.createDefaultForPrimitive(ast, richType);
        if (primitiveDefault != null) {
            return primitiveDefault;
        }

        // 2b. CaptureSource is an SDK *interface* — `new CaptureSource()` won't compile. Seed a slot of that type
        // with the project default, emitted as the LIVE `Source.current()` call (CaptureExpr.projectDefault(),
        // fully qualified so it needs no import) — the same text the capture-source picker produces. It must not
        // be CaptureExpr.of(<today's default>): switching overload through the ⚙ picker runs this path, and a
        // snapshot would silently freeze the argument into a `CaptureSource.window("…")` literal that stops
        // following the project's source. Falls back to the whole desktop if the snippet fails to parse.
        if (richType.leafType().is(SdkType.CAPTURE_SOURCE)) {
            Expression seeded = parseExpr(ast, CaptureExpr.projectDefault());
            if (seeded != null) return seeded;
            MethodInvocation desktop = ast.newMethodInvocation();
            desktop.setExpression(SdkNodes.qualifiedName(ast, SdkType.CAPTURE_SOURCE));
            desktop.setName(ast.newSimpleName("desktop"));
            return desktop;
        }

        // 2c. java.awt.Color has no no-arg constructor, so the generic `new T()` below produced a
        // `new Color()` that failed to compile twice over — "cannot find symbol: class Color" (nothing imports
        // it) and, once imported, "no suitable constructor". Worse, it made the editor lie: ColorArgPicker
        // reads RGB back out of a `new Color(r, g, b)` literal and returns null for anything else, so the
        // swatch fell back to the JavaFX ColorPicker's own default — white — while the code said something
        // uncompilable. Seeding white makes the swatch and the source agree on the first render instead of
        // only after the first pick. Fully qualified so it needs no import, exactly as the picker's own
        // committed value is (see ColorArgPicker).
        if ("Color".equals(richType.leafType().simpleName())) {
            Expression seeded = parseExpr(ast, "new java.awt.Color(255, 255, 255)");
            if (seeded != null) return seeded;
        }

        // 2d. Precision is a record with required components, so the generic `new T()` below is uncompilable
        // for exactly the reason Color is (2c). It ships a named default that says what the slot means before
        // the user touches it — Precision.DEFAULT rather than a bare 12.0 is the entire point of the type, so
        // the seed has to be the constant, not a number. Simple name: the two paths that build argument
        // defaults (palette insert, overload switch) import each parameter's type alongside this call, and
        // unlike java.awt.Color it resolves through the analyzer's SDK index.
        // Not a `case` below only because a switch label must be a compile-time constant, and the name comes
        // from the type identity rather than a literal.
        if (richType.leafType().is(SdkType.PRECISION)) {
            Expression seeded = parseExpr(ast, SdkType.PRECISION.simpleName() + ".DEFAULT");
            if (seeded != null) return seeded;
        }
        String seededConstant = switch (richType.leafType().simpleName()) {
            // java.time.Duration has no public constructor either, and no meaningful "named default", so the
            // seed is a plausible literal wait instead — one second, in the unit the picker will show it in,
            // so the control opens reading back exactly what is in the source. Simple name like Precision
            // rather than qualified like the two below: ImportManager already maps the bare name Duration to
            // java.time.Duration, so the import the argument-default paths add alongside this seed resolves.
            case "Duration" -> "Duration.ofSeconds(1)";
            // java.time values, for the Time facade's window predicates — neither has a public constructor, so
            // the generic `new T()` below is uncompilable for them too. Fully qualified like java.awt.Color
            // and unlike Precision: these come from the JDK, not the SDK jar the analyzer indexes, so the
            // import the argument-default paths add alongside this seed would not resolve.
            case "LocalTime" -> "java.time.LocalTime.of(12, 0)";
            // LocalDate's absence from this list is what put `new LocalDate()` into a user's project: it has no
            // public constructor either, so the generic `new T()` below is uncompilable for it exactly as it is
            // for the other three. `now()` rather than a fixed date because a default is a starting point, and
            // any literal date here is a number three people would each read a different meaning into.
            case "LocalDate" -> "java.time.LocalDate.now()";
            case "DayOfWeek" -> "java.time.DayOfWeek.MONDAY";
            case "Month" -> "java.time.Month.JANUARY";
            default -> null;
        };
        if (seededConstant != null) {
            Expression seeded = parseExpr(ast, seededConstant);
            if (seeded != null) return seeded;
        }

        // 3. Objects
        if (!richType.isUnknown()) {
            return newInstance(ast, richType, analyzer);
        }

        return ast.newNullLiteral();
    }

    /**
     * {@code new T(…)} naming a constructor {@code T} actually declares.
     *
     * <p>A bare {@code new T()} was emitted here regardless of what {@code T} declares, so any type without a
     * no-arg constructor produced source that does not compile — the SDK's {@code ImageTemplate} has only
     * {@code (String)} and {@code (String, double)}, and {@code new ImageTemplate()} reached two user projects
     * on disk. The five special cases above are that same bug, each patched once by hand; this is the rule they
     * were standing in for.
     *
     * <p>Zero-arg wins when one exists, because it is what the user will fill in anyway. Otherwise the
     * fewest-parameter constructor, seeded with literals. Falls back to the bare {@code new T()} — today's
     * behaviour, uncompilable or not — whenever there is nothing better to say: no analyzer (the short overloads
     * pass none), a type the analyzer can't find (its {@link ProjectAnalyzer#getConstructors} answers with a
     * synthetic no-arg, which lands here as the empty-parameter case), or the restriction below.
     *
     * <p><b>Only literal-valued parameters.</b> A candidate is used only when every parameter seeds to a
     * primitive/String literal. This class can add no imports — it has the {@code CompilationUnit} but not the
     * {@code ASTRewrite}, and its callers import the argument's <em>own</em> type, not the types nested inside
     * it — so filling {@code new Rect(Point, Point)} would trade "no such constructor" for "cannot find symbol
     * Point". A literal needs nothing imported and is therefore always safe. This is also why there is no
     * recursion here: under this restriction an argument is never itself a {@code new}, so a depth cap and a
     * cycle guard would have nothing to guard. Thread the rewriter through if a real type ever needs more.
     */
    static ClassInstanceCreation newInstance(AST ast, ResolvedType type, ProjectAnalyzer analyzer) {
        ClassInstanceCreation cic = ast.newClassInstanceCreation();
        cic.setType(ProjectAnalyzer.createTypeNode(ast, type));
        if (analyzer == null) return cic;

        List<MethodSignature> constructors = analyzer.getConstructors(type.leafType().simpleName());
        MethodSignature chosen = constructors.stream()
                .filter(c -> c.paramTypes().stream().allMatch(p -> DefaultValueHelper.createDefaultForPrimitive(ast, p) != null))
                .min(Comparator.comparingInt(c -> c.paramTypes().size()))
                .orElse(null);
        if (chosen == null) return cic;

        for (ResolvedType p : chosen.paramTypes()) {
            cic.arguments().add(DefaultValueHelper.createDefaultForPrimitive(ast, p));
        }
        return cic;
    }

    // Overload for backward compatibility
    public static Expression createDefaultInitializer(AST ast, ResolvedType type) {
        return createDefaultInitializer(ast, type, null, null);
    }

    /** Parses a Java expression snippet into an AST node owned by {@code ast}, or {@code null} on failure. */
    private static Expression parseExpr(AST ast, String code) {
        if (code == null || code.isBlank()) return null;
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(code.toCharArray());
        ASTNode parsed = parser.createAST(null);
        return parsed instanceof Expression expr ? (Expression) ASTNode.copySubtree(ast, expr) : null;
    }

    public static Expression createArrayInitializer(AST ast, ResolvedType type, List<Expression> valuesToPreserve, CompilationUnit cu, ProjectState state) {
        int dimensions = type.arrayDimensions();
        ResolvedType leafType = type.leafType();

        if (dimensions == 0) {
            return createDefaultInitializer(ast, leafType, cu,state);
        }

        ArrayCreation arrayCreation = ast.newArrayCreation();
        Type elementType = ProjectAnalyzer.createTypeNode(ast, type);
        arrayCreation.setType((ArrayType) elementType);

        ArrayInitializer initializer = createNestedArrayInitializer(ast, leafType, dimensions, valuesToPreserve, cu, state);
        arrayCreation.setInitializer(initializer);

        return arrayCreation;
    }

    private static ArrayInitializer createNestedArrayInitializer(AST ast, ResolvedType leafType, int dimensions,
                                                          List<Expression> valuesToPreserve, CompilationUnit cu, ProjectState state) {
        ArrayInitializer initializer = ast.newArrayInitializer();

        if (dimensions == 1) {
            if (valuesToPreserve != null && !valuesToPreserve.isEmpty()) {
                for (Expression value : valuesToPreserve) {
                    initializer.expressions().add(ASTNode.copySubtree(ast, value));
                }
            } else {
                Expression defaultValue = createDefaultInitializer(ast, leafType, cu,state);
                initializer.expressions().add(defaultValue);
            }
        } else {
            ArrayInitializer subArray = createNestedArrayInitializer(ast, leafType, dimensions - 1, valuesToPreserve, cu,state);
            initializer.expressions().add(subArray);
        }

        return initializer;
    }

    public static Expression createRecursiveListInitializer(AST ast, String typeName, CompilationUnit cu,
                                                            List<Expression> leavesToPreserve, ProjectState state) {
        return createArrayInitializer(ast, ResolvedType.named(typeName), leavesToPreserve, cu, state);
    }
}
