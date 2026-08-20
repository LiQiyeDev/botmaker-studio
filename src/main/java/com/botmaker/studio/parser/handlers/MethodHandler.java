package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.ExpressionChoice;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.NodeCreator;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.parser.factories.StatementFactory;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.DefaultValueHelper;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.parser.refactor.SignatureMigration;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;
import java.util.Optional;

public class MethodHandler {

    public static String addMethodToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl,
                                   String methodName, String returnType, int index) {
        // Wrapper for string-based calls
        return addMethodToClass(cu, originalCode, typeDecl, methodName, ResolvedType.named(returnType), index);
    }
    public static String replaceWithMethodCall(EditContext ctx, String originalCode, Expression toReplace,
                                               ExpressionChoice.Method choice) {
        ctx.rewriter().replace(toReplace, createMethodInvocation(ctx, choice), null);
        return ctx.applyTo(originalCode);
    }

    public static String addMethodCallStatement(EditContext ctx, String originalCode, BodyBlock targetBody,
                                                ExpressionChoice.Method choice, int index) {
        ExpressionStatement stmt = ctx.ast().newExpressionStatement(createMethodInvocation(ctx, choice));

        // Insert into body
        AstRewriteHelper.getListRewriteForBody(ctx.rewriter(), targetBody).insertAt(stmt, index, null);

        return ctx.applyTo(originalCode);
    }

    /**
     * Builds a {@code MethodInvocation} from a menu pick; shared by NodeCreator's unified expression builder.
     *
     * <p>The context's {@link ProjectState} is what lets a {@code CaptureSource} argument default be seeded
     * from the project's default capture target instead of the whole desktop (see {@code InitializerFactory}).
     */
    public static MethodInvocation createMethodInvocation(EditContext ctx, ExpressionChoice.Method choice) {
        AST ast = ctx.ast();
        MethodInvocation mi = ast.newMethodInvocation();

        // Scope
        if (choice.scope() != null && !choice.scope().isEmpty()) {
            mi.setExpression(ast.newSimpleName(choice.scope()));
            // A static call's scope is a type name that may need importing; a local-variable scope won't resolve.
            if (choice.isStatic()) {
                ctx.addImportForSimpleName(choice.scope());
            }
        }

        // Name
        mi.setName(ast.newSimpleName(choice.methodName()));

        // Arguments (Defaults). Each default initializer references its type by simple name
        // (`Color.RED`, `new Color()`), so the parameter types need importing just as the scope does.
        for (ResolvedType paramType : choice.paramTypes()) {
            mi.arguments().add(InitializerFactory.createDefaultInitializer(ctx, paramType));
            ctx.addImportForType(paramType);
        }
        return mi;
    }

    public static String addMethodToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl,
                                   String methodName, ResolvedType returnType, int index) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        MethodDeclaration newMethod = ast.newMethodDeclaration();
        newMethod.setName(ast.newSimpleName(methodName));
        newMethod.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        newMethod.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));

        if (returnType.isVoid()) {
            newMethod.setReturnType2(ast.newPrimitiveType(PrimitiveType.VOID));
        } else {
            newMethod.setReturnType2(ProjectAnalyzer.createTypeNode(ast, returnType));
        }

        Block body = ast.newBlock();
        // A non-void method needs a return to compile; seed it with a default value the user can replace.
        if (!returnType.isVoid()) {
            ReturnStatement ret = ast.newReturnStatement();
            ret.setExpression(defaultReturnExpression(ast, returnType));
            body.statements().add(ret);
        }
        newMethod.setBody(body);

        ListRewrite listRewrite = rewriter.getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        listRewrite.insertAt(newMethod, index, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /**
     * Adds the function described by {@code draft} — name, return type, parameters — at {@code index}.
     *
     * <p>The older {@link #addMethodToClass(CompilationUnit, String, TypeDeclaration, String, ResolvedType, int)}
     * remains for the two callers that have no dialog behind them (a palette drop onto a class header, which
     * has nowhere to ask). This one exists because a signature is more than a return type: it takes an
     * {@link EditContext} so the types it names get imported, which a {@code Point} parameter needs and the
     * plain rewriter could not do.
     */
    public static String addFunctionToClass(EditContext ctx, String originalCode, TypeDeclaration typeDecl,
                                            FunctionDraft draft, int index) {
        AST ast = ctx.ast();
        MethodDeclaration method = ast.newMethodDeclaration();
        method.setName(ast.newSimpleName(draft.name().trim()));
        method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));
        method.setReturnType2(typeNodeFor(ctx, draft.returnType()));

        for (FunctionDraft.Parameter param : draft.parameters()) {
            SingleVariableDeclaration decl = ast.newSingleVariableDeclaration();
            decl.setType(typeNodeFor(ctx, param.type()));
            decl.setName(ast.newSimpleName(param.name().trim()));
            method.parameters().add(decl);
        }

        Block body = ast.newBlock();
        // A non-void function needs a return to compile. Seeding it from the type's own default is what makes
        // "add a function that gives back a Matches" produce something that builds before it is filled in.
        if (!draft.returnType().isVoid()) {
            ReturnStatement ret = ast.newReturnStatement();
            ret.setExpression(defaultValueFor(ctx, draft.returnType()));
            body.statements().add(ret);
        }
        method.setBody(body);

        ListRewrite listRewrite =
                ctx.rewriter().getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        listRewrite.insertAt(method, index, null);
        return ctx.applyTo(originalCode);
    }

    /**
     * Rewrites {@code method}'s whole signature to {@code draft} in one pass: the name, the return type and
     * the parameter list, plus every reference in the body to a parameter that was renamed.
     *
     * <p><b>One pass, on purpose.</b> The header used to offer five separate controls — a name field, a
     * return-type chip, a type chip and a name field per parameter, a "+" and a "×" — each of which called
     * its own {@code CodeEditor} method, and each of those re-writes the file and re-parses it. Between two
     * of them the signature is a state the user never asked for: {@code click(int)} on the way to
     * {@code click(Point, int)} exists on disk, does not compile, and its call sites are broken by it. There
     * is no way to reach that state from here, which is what the maintainer meant by wanting the change to
     * be "compilation safe".
     *
     * <p>Parameters are matched to the draft <em>by origin</em> — see {@link #applyParameters} — so retyping
     * keeps the body's references intact, only a parameter the user actually deleted is removed, and moving a
     * row moves the parameter rather than renaming whatever sat at that index. Renaming rewrites the
     * references inside this method's own body, which the per-parameter field it replaces never did.
     */
    public static String applyFunctionSignature(EditContext ctx, String originalCode, MethodDeclaration method,
                                                FunctionDraft draft) {
        return applyFunctionSignature(ctx, originalCode, method, draft, null);
    }

    /**
     * As above, and in the <em>same</em> rewrite: the calls to this method that live in this file, and a local
     * for each removed parameter its body still reads.
     *
     * <p>Same rewrite because the alternative is two writes — the declaration, then its callers — and between
     * them sits a file that does not compile, which is exactly the state this dialog was built to make
     * unreachable. Calls in <em>other</em> files are not this method's business; see {@link CallMigrator}.
     *
     * @param plan the approved migration, or null for a change with nothing to migrate
     */
    public static String applyFunctionSignature(EditContext ctx, String originalCode, MethodDeclaration method,
                                                FunctionDraft draft, SignatureMigration.Plan plan) {
        ASTRewrite rewriter = ctx.rewriter();

        String newName = draft.name().trim();
        if (!method.getName().getIdentifier().equals(newName)) {
            rewriter.set(method.getName(), SimpleName.IDENTIFIER_PROPERTY, newName, null);
        }

        applyReturnType(ctx, method, draft.returnType());
        applyParameters(ctx, method, draft.parameters());
        if (plan != null) {
            rescueRemovedParameters(ctx, method, plan.rescued());
            CallMigrator.applyIn(ctx, plan);
        }

        return ctx.applyTo(originalCode);
    }

    /**
     * Declares a local for each parameter the user removed that the body still refers to, at the top of that
     * body and seeded with its type's default.
     *
     * <p>Without it, removing an input that the function actually uses produces a body full of names that no
     * longer exist — a change the user asked for, reported as several errors they did not. The local says the
     * same thing the compiler would, in the one place the value can now come from.
     */
    private static void rescueRemovedParameters(EditContext ctx, MethodDeclaration method,
                                                List<SignatureMigration.RescuedParameter> rescued) {
        if (rescued.isEmpty() || method.getBody() == null) return;
        AST ast = ctx.ast();
        ListRewrite body = ctx.rewriter().getListRewrite(method.getBody(), Block.STATEMENTS_PROPERTY);
        int at = 0;
        for (SignatureMigration.RescuedParameter each : rescued) {
            VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
            fragment.setName(ast.newSimpleName(each.name()));
            fragment.setInitializer(CallMigrator.defaultFor(ctx, each.type()));
            VariableDeclarationStatement statement = ast.newVariableDeclarationStatement(fragment);
            statement.setType(typeNodeFor(ctx, each.type()));
            body.insertAt(statement, at++, null);
        }
    }

    /** The return type and the trailing {@code return} that has to agree with it. */
    private static void applyReturnType(EditContext ctx, MethodDeclaration method, SignatureType returnType) {
        // A constructor has no return type to change, and `getReturnType2()` being null does not mean "it
        // returns nothing" here the way it does for a method — it means the question does not apply. Without
        // this, editing a constructor's parameters would set RETURN_TYPE2 to `void` and quietly turn
        // `GoHome(int)` into a method named GoHome, which compiles and is never called again.
        if (method.isConstructor()) return;
        // Left alone when it is already what the file says — by text, for the reason spelled out in
        // editInPlace: a type the editor merely carries (`Outcome run(…)`) round-trips to the same source and
        // so is protected here, while a type the user deliberately picked is written even though the catalogue
        // cannot describe it.
        Type oldNode = method.getReturnType2();
        if (oldNode != null && oldNode.toString().equals(returnType.sourceName())) return;

        SignatureType oldType = oldNode == null
                ? SignatureType.of(BotType.NOTHING) : MethodSignatures.signatureTypeOf(oldNode.toString());

        Type newNode = typeNodeFor(ctx, returnType);
        if (oldNode == null) {
            ctx.rewriter().set(method, MethodDeclaration.RETURN_TYPE2_PROPERTY, newNode, null);
        } else {
            ctx.rewriter().replace(oldNode, newNode, null);
        }
        updateTrailingReturn(ctx, method, oldType, returnType);
    }

    /**
     * The parameter list, matched to the draft <em>by origin</em>.
     *
     * <p>Matching by position — what this did — reads a reorder as a pair of retypes: send {@code int tries}
     * above {@code Point where} with the ▲, and position 0 "becomes" an {@code int} named {@code tries} while
     * position 1 "becomes" a {@code Point} named {@code where}. The list ends up right, and every reference in
     * the body to either name has silently changed meaning on the way. {@link FunctionDraft.Parameter#origin}
     * says which parameter a row <em>is</em>, so the same gesture moves it whole.
     *
     * <p>Two shapes of edit, and the cheaper one is preferred: when the kept parameters are still in their old
     * relative order and the new ones are all at the end, each one is edited where it stands and the file's own
     * formatting survives untouched. Only a genuine move rebuilds the list, and then from copies of the
     * original declarations, so a {@code final} or an annotation on a parameter travels with it.
     */
    private static void applyParameters(EditContext ctx, MethodDeclaration method,
                                        List<FunctionDraft.Parameter> wanted) {
        List<?> current = method.parameters();
        if (isReordered(current.size(), wanted)) {
            rebuildParameters(ctx, method, wanted);
            return;
        }

        ASTRewrite rewriter = ctx.rewriter();
        ListRewrite listRewrite = rewriter.getListRewrite(method, MethodDeclaration.PARAMETERS_PROPERTY);

        for (FunctionDraft.Parameter target : wanted) {
            if (target.isNew() || target.origin() >= current.size()) continue;
            editInPlace(ctx, method, (SingleVariableDeclaration) current.get(target.origin()), target);
        }

        for (int i = current.size() - 1; i >= 0; i--) {
            if (!isKept(wanted, i)) listRewrite.remove((ASTNode) current.get(i), null);
        }

        for (FunctionDraft.Parameter target : wanted) {
            if (target.isNew() || target.origin() >= current.size()) {
                listRewrite.insertLast(freshParameter(ctx, target), null);
            }
        }
    }

    /** True when the wanted list cannot be reached by editing in place, removing and appending. */
    private static boolean isReordered(int currentSize, List<FunctionDraft.Parameter> wanted) {
        int lastOrigin = -1;
        boolean seenNew = false;
        for (FunctionDraft.Parameter target : wanted) {
            if (target.isNew() || target.origin() >= currentSize) {
                seenNew = true;
                continue;
            }
            // A surviving parameter after a new one, or out of its old order, is a move.
            if (seenNew || target.origin() <= lastOrigin) return true;
            lastOrigin = target.origin();
        }
        return false;
    }

    private static boolean isKept(List<FunctionDraft.Parameter> wanted, int origin) {
        return wanted.stream().anyMatch(p -> !p.isNew() && p.origin() == origin);
    }

    /** Retypes and renames one surviving parameter where it stands, body references included. */
    private static void editInPlace(EditContext ctx, MethodDeclaration method,
                                    SingleVariableDeclaration param, FunctionDraft.Parameter target) {
        // A type is left alone when it is the one the file already writes — compared by text, not by whether
        // the catalogue happens to describe it. Those two used to be the same test (`isKept()` meant "don't
        // touch"), which silently protected the wrong thing: the header's type picker offers every type in the
        // project, most of which the catalogue has never heard of, so deliberately retyping an input to one of
        // them did nothing at all. A carried type read out of this very declaration still compares equal, so
        // `Outcome run(…)` is as safe as it was — safe by value now rather than by category.
        if (!param.getType().toString().equals(target.type().sourceName())) {
            ctx.rewriter().replace(param.getType(), typeNodeFor(ctx, target.type()), null);
        }
        String newName = target.name().trim();
        if (!param.getName().getIdentifier().equals(newName)) {
            AstRewriteHelper.renameWithinMethod(ctx.rewriter(), method, param.getName(), newName);
        }
    }

    /** Writes the whole list again in the wanted order, each survivor a copy of the declaration it came from. */
    private static void rebuildParameters(EditContext ctx, MethodDeclaration method,
                                          List<FunctionDraft.Parameter> wanted) {
        AST ast = ctx.ast();
        ASTRewrite rewriter = ctx.rewriter();
        ListRewrite listRewrite = rewriter.getListRewrite(method, MethodDeclaration.PARAMETERS_PROPERTY);
        List<?> current = method.parameters();

        for (int i = current.size() - 1; i >= 0; i--) listRewrite.remove((ASTNode) current.get(i), null);

        for (FunctionDraft.Parameter target : wanted) {
            if (target.isNew() || target.origin() >= current.size()) {
                listRewrite.insertLast(freshParameter(ctx, target), null);
                continue;
            }
            SingleVariableDeclaration origin = (SingleVariableDeclaration) current.get(target.origin());
            SingleVariableDeclaration moved =
                    (SingleVariableDeclaration) ASTNode.copySubtree(ast, origin);
            if (!target.type().isKept() && !origin.getType().toString().equals(target.type().sourceName())) {
                moved.setType(typeNodeFor(ctx, target.type()));
            }
            String newName = target.name().trim();
            if (!origin.getName().getIdentifier().equals(newName)) {
                moved.setName(ast.newSimpleName(newName));
                // The copy carries the new name; the body still spells the old one, and that is a rewrite of
                // its own — the moved declaration is a new node, so renaming *it* would reach nothing else.
                AstRewriteHelper.renameWithinMethod(rewriter, method, origin.getName(), newName);
            }
            listRewrite.insertLast(moved, null);
        }
    }

    private static SingleVariableDeclaration freshParameter(EditContext ctx, FunctionDraft.Parameter target) {
        SingleVariableDeclaration added = ctx.ast().newSingleVariableDeclaration();
        added.setType(typeNodeFor(ctx, target.type()));
        added.setName(ctx.ast().newSimpleName(target.name().trim()));
        return added;
    }

    /** A type node for a signature type: a curated choice imports what it names, a carried one is copied. */
    private static Type typeNodeFor(EditContext ctx, SignatureType type) {
        Optional<BotType.Choice> described = type.described();
        if (described.isEmpty()) return ProjectAnalyzer.createTypeNode(ctx.ast(), type.sourceName());
        return typeNodeFor(ctx, described.get());
    }

    /** A type node for a curated choice, importing what it names — {@code Point}, or {@code List<Point>}. */
    private static Type typeNodeFor(EditContext ctx, BotType.Choice choice) {
        AST ast = ctx.ast();
        choice.type().sdkType().ifPresent(ctx::addImport);
        if (!choice.isList()) {
            return ProjectAnalyzer.createTypeNode(ast, choice.type().typeName());
        }
        ctx.addImport(LIST_FQN);
        ParameterizedType listType =
                ast.newParameterizedType(ast.newSimpleType(ast.newSimpleName("List")));
        listType.typeArguments().add(ast.newSimpleType(ast.newSimpleName(choice.elementName())));
        return listType;
    }

    /**
     * The value a body is seeded with to produce a {@code type}: the catalogue's own default where there is
     * one, and otherwise whatever the type itself forces — {@code false} for a carried {@code boolean},
     * {@code null} for a carried class.
     *
     * <p>A carried type still has to compile: {@code return null;} is not a legal body for one that gives back
     * an {@code int}, and "the editor cannot describe this type" is not the same statement as "it has no
     * default".
     */
    private static Expression defaultValueFor(EditContext ctx, SignatureType type) {
        Optional<BotType.Choice> described = type.described();
        return described.isPresent() ? defaultValueFor(ctx, described.get())
                : defaultReturnExpression(ctx.ast(), ResolvedType.named(type.sourceName()));
    }

    /** {@code List.of()} for a list, and the type's own catalogue default otherwise. */
    private static Expression defaultValueFor(EditContext ctx, BotType.Choice choice) {
        if (choice.isList()) {
            ctx.addImport(LIST_FQN);
            MethodInvocation of = ctx.ast().newMethodInvocation();
            of.setExpression(ctx.ast().newSimpleName("List"));
            of.setName(ctx.ast().newSimpleName("of"));
            return of;
        }
        return choice.type().defaultValue()
                .map(init -> StatementFactory.buildExpression(ctx, init))
                .orElseGet(() -> ctx.ast().newNullLiteral());
    }

    private static final String LIST_FQN = "java.util.List";

    /** Default {@code return} value for {@code type}: literal for primitives/String/char, {@code null} for objects. */
    private static Expression defaultReturnExpression(AST ast, ResolvedType type) {
        Expression primitive = DefaultValueHelper.createDefaultForPrimitive(ast, type);
        return primitive != null ? primitive : ast.newNullLiteral();
    }

    public static String deleteMethodFromClass(CompilationUnit cu, String originalCode, MethodDeclaration method) {
        return AstRewriteHelper.removeNode(cu, originalCode, method);
    }

    public static String renameMethod(CompilationUnit cu, String originalCode, MethodDeclaration method, String newName) {
        return AstRewriteHelper.renameSimpleName(cu, originalCode, method.getName(), newName);
    }

    public static String moveBodyDeclaration(CompilationUnit cu, String originalCode, BodyDeclaration declToMove,
                                      TypeDeclaration targetType, int targetIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        ListRewrite listRewrite = rewriter.getListRewrite(targetType, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        ASTNode moveTarget = rewriter.createMoveTarget(declToMove);
        listRewrite.insertAt(moveTarget, targetIndex, null);

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String updateMethodInvocation(EditContext ctx, String originalCode,
                                                MethodInvocation mi, String newScope,
                                                String newMethodName, List<ResolvedType> newParamTypes) {
        AST ast = ctx.ast();
        ASTRewrite rewriter = ctx.rewriter();

        // Update scope
        if (newScope == null || newScope.isEmpty() || newScope.equals("Local")) {
            if (mi.getExpression() != null) {
                rewriter.remove(mi.getExpression(), null);
            }
        } else {
            SimpleName newScopeNode = ast.newSimpleName(newScope);
            // Switching to another facade (SDK dropdown / ⚙ overload picker) introduces a type name that
            // was never referenced before, so it needs the same import an inserted call gets. The scope may
            // also be an instance receiver, which must not be imported — unlike createMethodInvocation there
            // is no isStatic() flag here, so go on the capitalisation that tells types from variables.
            if (Character.isUpperCase(newScope.charAt(0))) {
                ctx.addImportForSimpleName(newScope);
            }
            if (mi.getExpression() == null) {
                rewriter.set(mi, MethodInvocation.EXPRESSION_PROPERTY, newScopeNode, null);
            } else {
                rewriter.replace(mi.getExpression(), newScopeNode, null);
            }
        }

        // Update method name
        if (!mi.getName().getIdentifier().equals(newMethodName)) {
            rewriter.replace(mi.getName(), ast.newSimpleName(newMethodName), null);
        }

        syncArguments(ctx, mi, newParamTypes);

        return ctx.applyTo(originalCode);
    }

    /**
     * Makes the method's trailing {@code return} agree with a changed return type, doing exactly what
     * {@link SignatureMigration#returnFate} said would be done.
     *
     * <p>The decision is not taken here on purpose: the user was shown a sentence naming it — "the value it
     * gives back becomes false" — and a body that then did something else would make that sentence a lie. This
     * only carries it out.
     *
     * <p>The value it writes is the <em>catalogue's</em> default, not a null literal. That is the maintainer's
     * "null in the picker": a function retyped from giving nothing back to giving back {@code Text} used to
     * gain {@code return null;}, drawn as an empty {@code null} in the value slot, while the very same function
     * created through the Add Function dialog got {@code return "";}. One seed, one answer — see
     * {@link #defaultValueFor}, which is what that dialog already used.
     */
    private static void updateTrailingReturn(EditContext ctx, MethodDeclaration method,
                                             SignatureType oldType, SignatureType newType) {
        Block body = method.getBody();
        if (body == null) return;
        ListRewrite bodyRewrite = ctx.rewriter().getListRewrite(body, Block.STATEMENTS_PROPERTY);
        ReturnStatement trailing = SignatureMigration.trailingReturnOf(method);

        switch (SignatureMigration.returnFate(method, oldType, newType)) {
            case UNCHANGED -> { }
            case REMOVED -> bodyRewrite.remove(trailing, null);
            case ADDED -> {
                ReturnStatement added = ctx.ast().newReturnStatement();
                added.setExpression(defaultValueFor(ctx, newType));
                bodyRewrite.insertLast(added, null);
            }
            case REPLACED -> {
                Expression seed = defaultValueFor(ctx, newType);
                if (trailing.getExpression() == null) {
                    ctx.rewriter().set(trailing, ReturnStatement.EXPRESSION_PROPERTY, seed, null);
                } else {
                    ctx.rewriter().replace(trailing.getExpression(), seed, null);
                }
            }
        }
    }

    // setMethodReturnType, addParameterToMethod, deleteParameterFromMethod and changeMethodParameterType were
    // here. Each rewrote the declaration alone, and each was reachable from a header control — which is how a
    // signature could change without a single call site being asked about it. Everything they did,
    // applyFunctionSignature above does as part of a scanned, previewed migration, so they are deleted rather
    // than left as a second way in. renameMethodParameter survives because it genuinely is a local edit: no
    // call anywhere names a parameter.

    public static String renameMethodParameter(CompilationUnit cu, String originalCode,
                                        MethodDeclaration method, int index, String newName) {
        List<?> params = method.parameters();
        if (index >= 0 && index < params.size()) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) params.get(index);
            return AstRewriteHelper.renameSimpleName(cu, originalCode, param.getName(), newName);
        }
        return originalCode;
    }

    public static String addConstructorToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        MethodDeclaration newConstructor = ast.newMethodDeclaration();
        newConstructor.setConstructor(true);
        // Constructor name MUST match class name
        newConstructor.setName(ast.newSimpleName(typeDecl.getName().getIdentifier()));
        newConstructor.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        // No return type for constructors

        Block body = ast.newBlock();
        newConstructor.setBody(body);

        ListRewrite listRewrite = rewriter.getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        // Insert at the beginning (index 0) or after fields? Let's default to index 0 for visibility
        listRewrite.insertAt(newConstructor, 0, null);

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String addArgumentToMethodInvocation(EditContext ctx, String originalCode,
                                                       MethodInvocation mi, ExpressionType type) {
        Expression newArg = NodeCreator.createDefaultExpression(ctx, type);
        if (newArg != null) {
            ctx.rewriter().getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY).insertLast(newArg, null);
        }
        return ctx.applyTo(originalCode);
    }

    public static String addArgumentToMethodInvocation(CompilationUnit cu, String originalCode,
                                                MethodInvocation mi, Expression newArgument) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY).insertLast(newArgument, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /**
     * Appends a default-valued argument of {@code elementType} — one more slot in a varargs tail. Same node
     * factory {@link #syncArguments} uses when an overload switch grows the argument list, so a hand-added
     * varargs argument and a generated one look identical.
     */
    public static String addVarargsArgument(EditContext ctx, String originalCode, MethodInvocation mi,
                                            ResolvedType elementType) {
        Expression newArg = InitializerFactory.createDefaultInitializer(ctx, elementType);
        if (newArg != null) {
            ctx.rewriter().getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY).insertLast(newArg, null);
            ctx.addImportForType(elementType);
        }
        return ctx.applyTo(originalCode);
    }

    /** Removes the argument at {@code index}; a no-op when the index is out of range. */
    public static String deleteArgument(CompilationUnit cu, String originalCode, MethodInvocation mi, int index) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        List<?> args = mi.arguments();
        if (index >= 0 && index < args.size()) {
            rewriter.getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY).remove((ASTNode) args.get(index), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static void syncArguments(EditContext ctx, MethodInvocation mi, List<ResolvedType> targetTypes) {
        ListRewrite argsRewrite = ctx.rewriter().getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY);
        List<?> currentArgs = mi.arguments();

        int targetCount = targetTypes.size();
        int currentCount = currentArgs.size();

        // 1. Update/Keep existing arguments
        for (int i = 0; i < Math.min(currentCount, targetCount); i++) {
            Expression currentArg = (Expression) currentArgs.get(i);
            ResolvedType targetType = targetTypes.get(i);

            // Resolve type of current argument
            ResolvedType currentType = ProjectAnalyzer.resolveType(currentArg);

            // If types are NOT compatible, replace the argument
            if (!currentType.isAssignmentCompatible(targetType)) {
                Expression defaultExpr = InitializerFactory.createDefaultInitializer(ctx, targetType);
                argsRewrite.replace(currentArg, defaultExpr, null);
                ctx.addImportForType(targetType);
            }
        }

        // 2. Remove excess arguments
        if (currentCount > targetCount) {
            for (int i = currentCount - 1; i >= targetCount; i--) {
                argsRewrite.remove((ASTNode) currentArgs.get(i), null);
            }
        }
        // 3. Add missing arguments
        else if (currentCount < targetCount) {
            for (int i = currentCount; i < targetCount; i++) {
                ResolvedType type = targetTypes.get(i);
                Expression defaultExpr = InitializerFactory.createDefaultInitializer(ctx, type);
                argsRewrite.insertLast(defaultExpr, null);
                ctx.addImportForType(type);
            }
        }
    }
}
