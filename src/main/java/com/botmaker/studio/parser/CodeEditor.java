package com.botmaker.studio.parser;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.parser.handlers.EnumManipulationHandler;
import com.botmaker.studio.parser.handlers.InstantiationHandler;
import com.botmaker.studio.parser.handlers.ListHandler;
import com.botmaker.studio.parser.handlers.MethodHandler;
import com.botmaker.studio.parser.handlers.OperatorReplacementHandler;
import com.botmaker.studio.parser.handlers.TypeHandler;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The single stateful layer of the write pipeline: every public method is a per-edit API call guarded by
 * {@link #canModify()} that publishes a {@code CodeUpdatedEvent} when it lands. The pure {@code (cu, code) -> code}
 * rewrites are delegated straight to the stateless {@code parser/handlers/*} (signature-shaped operations) or to the
 * {@code private static} transforms at the bottom of this file (bespoke AST shapes). The former {@code AstRewriter}
 * pass-through façade is gone — its real-logic methods live here as those transforms.
 */
public class CodeEditor {

    private final ProjectState state;
    private final EventBus eventBus;
    private final ProjectAnalyzer analyzer;

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

    public CodeEditor(ProjectState state, EventBus eventBus, ProjectAnalyzer analyzer) {
        this.state = state;
        this.eventBus = eventBus;
        this.analyzer = analyzer;
    }

    private String getCurrentCode() { return state.getCurrentCode(); }
    private CompilationUnit getCompilationUnit() { return state.getCompilationUnit().orElse(null); }

    private boolean canModify() {
        if (state.getActiveFile() != null) {
            String path = state.getActiveFile().getPath().toString().replace("\\", "/");
            if (path.contains("com/botmaker/sdk")) {
                return false;
            }
        }
        return true;
    }

    private void triggerUpdate(String newCode) {
        triggerUpdate(newCode, false);
    }

    private void triggerUpdate(String newCode, boolean markNewIdentifiersAsUnedited) {
        String previousCode = getCurrentCode();
        eventBus.publish(new CoreApplicationEvents.CodeUpdatedEvent(newCode, previousCode, markNewIdentifiersAsUnedited));
    }

    /**
     * Runs a pure rewrite under the modify-guard and publishes the result. {@code op} is a
     * {@code (CompilationUnit, originalCode) -> newCode} transform — a handler call or a local static transform.
     */
    private void edit(boolean markUnedited, BiFunction<CompilationUnit, String, String> op) {
        if (!canModify()) return;
        CompilationUnit cu = getCompilationUnit();
        if (cu == null) return;
        String newCode = op.apply(cu, getCurrentCode());
        if (newCode != null) triggerUpdate(newCode, markUnedited);
    }

    // =================================================================================
    // TYPE / INSTANTIATION
    // =================================================================================

    public void replaceVariableType(VariableDeclarationStatement toReplace, ResolvedType newType) {
        edit(false, (cu, code) -> TypeHandler.replaceVariableType(cu, code, toReplace, newType, state));
    }

    public void replaceFieldType(FieldDeclaration fieldDecl, String newTypeName) {
        edit(false, (cu, code) -> TypeHandler.replaceFieldType(cu, code, fieldDecl, ResolvedType.named(newTypeName), state));
    }

    public void updateInstantiation(ClassInstanceCreation node, String newTypeName, List<ResolvedType> newParamTypes) {
        edit(true, (cu, code) -> InstantiationHandler.updateInstantiation(cu, code, node, ResolvedType.named(newTypeName), newParamTypes, state));
    }

    public void replaceWithInstantiation(Expression toReplace, String typeName, List<ResolvedType> paramTypes) {
        edit(true, (cu, code) -> InstantiationHandler.replaceWithInstantiation(cu, code, toReplace, ResolvedType.named(typeName), paramTypes, state));
    }

    public void replaceWithVariable(Expression toReplace, String variableName) {
        edit(false, (cu, code) -> replaceNode(cu, code, toReplace, cu.getAST().newSimpleName(variableName)));
    }

    /**
     * Declares a new local variable {@code type name = <default>;} just before the statement enclosing
     * {@code toReplace}, then references it in that slot — a single atomic rewrite. Lets the user create a
     * typed variable (e.g. a {@code Direction}) inline from the Variables submenu. Falls back to a plain
     * reference when there is no enclosing block to host the declaration.
     */
    public void declareVariableBeforeAndReference(Expression toReplace, ResolvedType type, String name) {
        edit(true, (cu, code) -> {
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
        edit(true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            ClassInstanceCreation cic = ast.newClassInstanceCreation();
            cic.setType(ast.newSimpleType(ast.newSimpleName("ImageTemplate")));
            StringLiteral lit = ast.newStringLiteral();
            lit.setLiteralValue(path);
            cic.arguments().add(lit);
            ImportManager.addImportForSimpleName(cu, rewriter, "ImageTemplate", analyzer, null);
            rewriter.replace(toReplace, cic, null);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /**
     * Replaces {@code toReplace} with {@code ImageTemplateGroup.of(new ImageTemplate("p1"), …)} — the
     * multi-template group picker. Passing the full desired path list each time (rather than mutating
     * in place) keeps the picker's add/remove/change operations a single, uniform rewrite.
     */
    public void setImageTemplateGroup(Expression toReplace, java.util.List<String> paths) {
        edit(true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            MethodInvocation call = ast.newMethodInvocation();
            call.setExpression(ast.newSimpleName("ImageTemplateGroup"));
            call.setName(ast.newSimpleName("of"));
            for (String path : paths) {
                ClassInstanceCreation cic = ast.newClassInstanceCreation();
                cic.setType(ast.newSimpleType(ast.newSimpleName("ImageTemplate")));
                StringLiteral lit = ast.newStringLiteral();
                lit.setLiteralValue(path == null ? "" : path);
                cic.arguments().add(lit);
                call.arguments().add(cic);
            }
            ImportManager.addImportForSimpleName(cu, rewriter, "ImageTemplate", analyzer, null);
            ImportManager.addImportForSimpleName(cu, rewriter, "ImageTemplateGroup", analyzer, null);
            rewriter.replace(toReplace, call, null);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    /** Replaces {@code toReplace} with {@code new Rect(x, y, w, h)} — the screen-region arg picker. */
    public void setRect(Expression toReplace, int x, int y, int w, int h) {
        replaceWithIntCtor(toReplace, "Rect", x, y, w, h);
    }

    /** Replaces {@code toReplace} with {@code new Point(x, y)} — the cursor-position arg picker. */
    public void setPoint(Expression toReplace, int x, int y) {
        replaceWithIntCtor(toReplace, "Point", x, y);
    }

    /** Replaces {@code toReplace} with {@code new <typeName>(a, b, …)} using int-literal arguments. */
    private void replaceWithIntCtor(Expression toReplace, String typeName, int... args) {
        edit(true, (cu, code) -> {
            AST ast = cu.getAST();
            ASTRewrite rewriter = ASTRewrite.create(ast);
            ClassInstanceCreation cic = ast.newClassInstanceCreation();
            cic.setType(ast.newSimpleType(ast.newSimpleName(typeName)));
            for (int v : args) {
                cic.arguments().add(ast.newNumberLiteral(Integer.toString(v)));
            }
            ImportManager.addImportForSimpleName(cu, rewriter, typeName, analyzer, null);
            rewriter.replace(toReplace, cic, null);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    // =================================================================================
    // METHODS
    // =================================================================================

    public void changeMethodParameterType(MethodDeclaration method, int index, ResolvedType newType) {
        edit(false, (cu, code) -> MethodHandler.changeMethodParameterType(cu, code, method, index, newType, analyzer));
    }

    public void addConstructorToClass(TypeDeclaration typeDecl) {
        edit(true, (cu, code) -> MethodHandler.addConstructorToClass(cu, code, typeDecl));
    }

    public void updateMethodInvocation(MethodInvocation mi, String newScope, String newMethodName, List<ResolvedType> newParamTypes) {
        edit(true, (cu, code) -> MethodHandler.updateMethodInvocation(cu, code, mi, newScope, newMethodName, newParamTypes));
    }

    public void addArgumentToMethodInvocation(MethodInvocation mi, ExpressionType type) {
        edit(true, (cu, code) -> MethodHandler.addArgumentToMethodInvocation(cu, code, mi, type));
    }

    public void addArgumentToMethodInvocation(MethodInvocation mi, Expression expr) {
        edit(false, (cu, code) -> MethodHandler.addArgumentToMethodInvocation(cu, code, mi, expr));
    }

    public void addStringArgumentToMethodInvocation(MethodInvocation mi, String text) {
        edit(false, (cu, code) -> {
            StringLiteral newArg = cu.getAST().newStringLiteral();
            newArg.setLiteralValue(text);
            return MethodHandler.addArgumentToMethodInvocation(cu, code, mi, newArg);
        });
    }

    public void replaceWithMethodCall(Expression toReplace, ExpressionChoice.Method choice) {
        // If we're inside an ArrayCreation (new int[]{...}) replace the whole creation, not just the initializer.
        ASTNode targetNode = toReplace;
        if (toReplace instanceof ArrayInitializer && toReplace.getParent() instanceof ArrayCreation) {
            targetNode = toReplace.getParent();
        }
        Expression target = (Expression) targetNode;
        edit(false, (cu, code) -> MethodHandler.replaceWithMethodCall(cu, code, target, choice, state, analyzer));
    }

    public void addMethodCallStatement(BodyBlock targetBody, ExpressionChoice.Method choice, int index) {
        edit(false, (cu, code) -> MethodHandler.addMethodCallStatement(cu, code, targetBody, choice, index, analyzer));
    }

    public void addMethodToClass(TypeDeclaration typeDecl, String methodName, String returnType, int index) {
        edit(true, (cu, code) -> MethodHandler.addMethodToClass(cu, code, typeDecl, methodName, ResolvedType.named(returnType), index));
    }

    public void deleteMethod(MethodDeclaration method) {
        edit(false, (cu, code) -> MethodHandler.deleteMethodFromClass(cu, code, method));
    }

    public void renameMethodParameter(MethodDeclaration method, int index, String newName) {
        edit(false, (cu, code) -> MethodHandler.renameMethodParameter(cu, code, method, index, newName));
    }

    public void setMethodReturnType(MethodDeclaration method, ResolvedType newType) {
        edit(false, (cu, code) -> MethodHandler.setMethodReturnType(cu, code, method, newType, analyzer));
    }

    public void addParameterToMethod(MethodDeclaration method, ResolvedType type, String paramName) {
        edit(false, (cu, code) -> MethodHandler.addParameterToMethod(cu, code, method, type, paramName, analyzer));
    }

    public void deleteParameterFromMethod(MethodDeclaration method, int index) {
        edit(false, (cu, code) -> MethodHandler.deleteParameterFromMethod(cu, code, method, index));
    }

    public void renameMethod(MethodDeclaration method, String newName) {
        edit(false, (cu, code) -> MethodHandler.renameMethod(cu, code, method, newName));
    }

    public void moveBodyDeclaration(BodyDeclaration decl, TypeDeclaration targetType, int index) {
        edit(true, (cu, code) -> MethodHandler.moveBodyDeclaration(cu, code, decl, targetType, index));
    }

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setReturnExpression(ReturnStatement returnStmt, Object selection) {
        edit(true, (cu, code) -> setReturnExpression(cu, code, returnStmt, selection, analyzer));
    }

    // =================================================================================
    // ENUMS
    // =================================================================================

    public void replaceWithEnumConstant(Expression toReplace, String enumType, String constantName) {
        edit(false, (cu, code) -> EnumManipulationHandler.replaceWithEnumConstant(cu, code, toReplace, enumType, constantName));
    }

    /** Replaces with a field reference {@code scope.fieldName} (same {@code QualifiedName} shape as an enum constant). */
    public void replaceWithFieldReference(Expression toReplace, String scope, String fieldName) {
        edit(false, (cu, code) -> EnumManipulationHandler.replaceWithEnumConstant(cu, code, toReplace, scope, fieldName));
    }

    public void renameEnum(EnumDeclaration enumNode, String newName) {
        edit(false, (cu, code) -> EnumManipulationHandler.renameEnum(cu, code, enumNode, newName));
    }

    public void addEnumConstant(EnumDeclaration enumNode, String constantName) {
        edit(false, (cu, code) -> EnumManipulationHandler.addEnumConstant(cu, code, enumNode, constantName));
    }

    public void deleteEnumConstant(EnumDeclaration enumNode, int index) {
        edit(false, (cu, code) -> EnumManipulationHandler.deleteEnumConstant(cu, code, enumNode, index));
    }

    public void renameEnumConstant(EnumDeclaration enumNode, int index, String newName) {
        edit(false, (cu, code) -> EnumManipulationHandler.renameEnumConstant(cu, code, enumNode, index, newName));
    }

    public void addEnumToClass(TypeDeclaration typeDecl, String enumName, int index) {
        edit(true, (cu, code) -> EnumManipulationHandler.addEnumToClass(cu, code, typeDecl, enumName, index));
    }

    public void deleteEnumFromClass(EnumDeclaration enumDecl) {
        edit(false, (cu, code) -> EnumManipulationHandler.deleteEnumFromClass(cu, code, enumDecl));
    }

    // =================================================================================
    // IMPORTS
    // =================================================================================

    /** The fully-qualified names of the current file's imports (read-only; no edit). */
    public List<String> getImports() {
        return ImportManager.listImports(getCompilationUnit());
    }

    public void addImport(String qualifiedName) {
        edit(false, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            ImportManager.addImport(cu, rewriter, qualifiedName);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    public void removeImport(String qualifiedName) {
        edit(false, (cu, code) -> {
            ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
            ImportManager.removeImport(cu, rewriter, qualifiedName);
            return AstRewriteHelper.applyRewrite(rewriter, code);
        });
    }

    // =================================================================================
    // LISTS
    // =================================================================================

    public void addElementToList(ASTNode listNode, ExpressionType type, int insertIndex) {
        edit(true, (cu, code) -> ListHandler.addElementToList(cu, code, listNode, type, insertIndex));
    }

    /**
     * Inserts an element built from an expression-menu {@code selection} (an {@link ExpressionType} or an
     * {@link com.botmaker.studio.parser.ExpressionChoice}) at {@code insertIndex}. Powers the type-aware list "+" menu.
     * {@code elementType} is the list's inferred element type, used to build sensible default arguments.
     */
    public void insertIntoList(ASTNode listNode, int insertIndex, Object selection, ResolvedType elementType) {
        edit(true, (cu, code) -> ListHandler.insertChoiceIntoList(cu, code, listNode, insertIndex, selection, elementType, analyzer));
    }

    /** Moves the list element at {@code fromIndex} to {@code toIndex} (used by the per-row up/down buttons). */
    public void moveListElement(ASTNode listNode, int fromIndex, int toIndex) {
        edit(false, (cu, code) -> ListHandler.moveElement(cu, code, listNode, fromIndex, toIndex));
    }

    /** Adds a {@code new ImageTemplate("")} element to the list — drives the per-element image picker. */
    public void addImageTemplateToList(ASTNode listNode, int insertIndex) {
        edit(true, (cu, code) -> ListHandler.addImageTemplateElement(cu, code, listNode, insertIndex, analyzer));
    }

    public void deleteElementFromList(ASTNode listNode, int elementIndex) {
        edit(false, (cu, code) -> ListHandler.deleteElementFromList(cu, code, listNode, elementIndex));
    }

    // =================================================================================
    // INITIALIZERS / EXPRESSIONS
    // =================================================================================

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setVariableInitializer(VariableDeclarationStatement varDecl, Object selection) {
        edit(true, (cu, code) -> setVariableInitializer(cu, code, varDecl, selection, analyzer));
    }

    /** {@code selection} is an {@link ExpressionType} or an {@link ExpressionChoice} (variable/method/…). */
    public void setFieldInitializer(FieldDeclaration fieldDecl, Object selection) {
        edit(true, (cu, code) -> setFieldInitializer(cu, code, fieldDecl, selection, analyzer));
    }

    public void setFieldInitializerToDefault(FieldDeclaration fieldDecl, ResolvedType fieldType) {
        ExpressionType defaultType = mapTypeToExpressionType(fieldType.simpleName());
        edit(true, (cu, code) -> setFieldInitializer(cu, code, fieldDecl, defaultType, analyzer));
    }

    public void replaceExpression(Expression toReplace, ExpressionType type) {
        edit(true, (cu, code) -> replaceExpression(cu, code, toReplace, type, analyzer));
    }

    public void replaceLiteralValue(Expression toReplace, String newLiteralValue) {
        edit(false, (cu, code) -> replaceLiteral(cu, code, toReplace, newLiteralValue));
    }

    public void replaceSimpleName(SimpleName toReplace, String newName) {
        edit(false, (cu, code) -> AstRewriteHelper.renameSimpleName(cu, code, toReplace, newName));
    }

    // =================================================================================
    // STATEMENTS / FLOW
    // =================================================================================

    public void addStatement(BodyBlock targetBody, BlockType type, int index) {
        if (!canModify()) return;
        CompilationUnit cu = getCompilationUnit();
        if (cu == null) return;
        String newCode = addStatement(cu, getCurrentCode(), targetBody, type, index, state, analyzer);
        if (newCode == null) return;
        triggerUpdate(newCode, true);
        eventBus.publish(new CoreApplicationEvents.BlockAddedEvent(type));
    }

    public void deleteStatement(Statement toDelete) {
        edit(false, (cu, code) -> deleteStatement(cu, code, toDelete));
    }

    public void pasteCode(BodyBlock targetBody, int index, String codeToPaste) {
        edit(false, (cu, code) -> pasteCodeString(cu, code, targetBody, index, codeToPaste));
    }

    public void moveStatement(StatementBlock blockToMove, BodyBlock sourceBody, BodyBlock targetBody, int targetIndex) {
        edit(false, (cu, code) -> moveStatement(cu, code, blockToMove, sourceBody, targetBody, targetIndex));
    }

    public void deleteElseFromIfStatement(IfStatement ifStmt) {
        edit(false, (cu, code) -> deleteElseFromIfStatement(cu, code, ifStmt));
    }

    public void convertElseToElseIf(IfStatement ifStmt) {
        edit(false, (cu, code) -> convertElseToElseIf(cu, code, ifStmt));
    }

    public void addElseToIfStatement(IfStatement ifStmt) {
        edit(false, (cu, code) -> addElseToIfStatement(cu, code, ifStmt));
    }

    public void addCaseToSwitch(SwitchStatement switchStmt) {
        edit(true, (cu, code) -> addCaseToSwitch(cu, code, switchStmt));
    }

    public void moveSwitchCase(SwitchCase caseNode, boolean moveUp) {
        edit(false, (cu, code) -> moveSwitchCase(cu, code, caseNode, moveUp));
    }

    // =================================================================================
    // COMMENTS / OPERATORS
    // =================================================================================

    public void updateComment(Comment commentNode, String newText) {
        edit(false, (cu, code) -> updateComment(code, commentNode, newText));
    }

    public void deleteComment(Comment commentNode) {
        edit(false, (cu, code) -> deleteComment(code, commentNode));
    }

    public void updateAssignmentOperator(ASTNode node, String newOperatorSymbol) {
        if (!canModify()) return;
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
        if (newCode != null) triggerUpdate(newCode);
    }

    public void updateBinaryOperator(ASTNode node, String newOperatorSymbol) {
        if (!canModify()) return;
        if (node instanceof InfixExpression) {
            InfixExpression.Operator op = INFIX_OPS.get(newOperatorSymbol);
            if (op != null) {
                String newCode = OperatorReplacementHandler.replaceInfixOperator(getCompilationUnit(), getCurrentCode(), (InfixExpression) node, op);
                triggerUpdate(newCode);
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

    private static String setVariableInitializer(CompilationUnit cu, String originalCode, VariableDeclarationStatement varDecl, Object selection, ProjectAnalyzer analyzer) {
        return setFragmentInitializer(cu, originalCode,
                (VariableDeclarationFragment) varDecl.fragments().getFirst(), varDecl.getType().toString(), selection, analyzer);
    }

    private static String setFieldInitializer(CompilationUnit cu, String originalCode, FieldDeclaration fieldDecl, Object selection, ProjectAnalyzer analyzer) {
        return setFragmentInitializer(cu, originalCode,
                (VariableDeclarationFragment) fieldDecl.fragments().getFirst(), fieldDecl.getType().toString(), selection, analyzer);
    }

    private static String setFragmentInitializer(CompilationUnit cu, String originalCode,
                                                 VariableDeclarationFragment fragment, String declaredType, Object selection, ProjectAnalyzer analyzer) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ResolvedType contextType = ResolvedType.named(ProjectAnalyzer.unwrapCollectionType(declaredType));
        Expression newExpr = NodeCreator.createExpression(ast, selection, cu, rewriter, contextType, analyzer);
        if (newExpr == null) return originalCode;
        if (fragment.getInitializer() == null) {
            rewriter.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, newExpr, null);
        } else {
            rewriter.replace(fragment.getInitializer(), newExpr, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String setReturnExpression(CompilationUnit cu, String originalCode, ReturnStatement returnStmt, Object selection, ProjectAnalyzer analyzer) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ResolvedType contextType = ProjectAnalyzer.inferExpectedType(returnStmt.getExpression() != null
                ? returnStmt.getExpression() : returnStmt);
        Expression newExpr = NodeCreator.createExpression(ast, selection, cu, rewriter, contextType, analyzer);
        if (newExpr == null) return originalCode;
        if (returnStmt.getExpression() == null) {
            rewriter.set(returnStmt, ReturnStatement.EXPRESSION_PROPERTY, newExpr, null);
        } else {
            rewriter.replace(returnStmt.getExpression(), newExpr, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String replaceExpression(CompilationUnit cu, String originalCode, Expression toReplace, ExpressionType type, ProjectAnalyzer analyzer) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        String contextType = ProjectAnalyzer.inferExpectedType(toReplace).simpleName();
        Expression newExpression = NodeCreator.createDefaultExpression(ast, type, cu, rewriter, contextType, analyzer);
        if (newExpression == null) return originalCode;
        rewriter.replace(toReplace, newExpression, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
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

    private static String addStatement(CompilationUnit cu, String originalCode, BodyBlock targetBody, BlockType type, int index, ProjectState state, ProjectAnalyzer analyzer) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Statement newStatement = NodeCreator.createDefaultStatement(ast, type, cu, rewriter, state, analyzer);
        if (newStatement == null) return originalCode;
        ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        insertIntoList(listRewrite, targetBody, newStatement, index);
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

    private static String pasteCodeString(CompilationUnit cu, String originalCode, BodyBlock targetBody, int index, String codeToPaste) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Statement placeHolder = (Statement) rewriter.createStringPlaceholder(codeToPaste, ASTNode.EMPTY_STATEMENT);
        ListRewrite listRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        insertIntoList(listRewrite, targetBody, placeHolder, index);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static String moveStatement(CompilationUnit cu, String originalCode, StatementBlock blockToMove, BodyBlock sourceBody, BodyBlock targetBody, int targetIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Statement statement = (Statement) blockToMove.getAstNode();
        ListRewrite sourceListRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, sourceBody);
        ListRewrite targetListRewrite = AstRewriteHelper.getListRewriteForBody(rewriter, targetBody);
        Statement copiedStatement = (Statement) ASTNode.copySubtree(ast, statement);
        sourceListRewrite.remove(statement, null);
        insertIntoList(targetListRewrite, targetBody, copiedStatement, targetIndex);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
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

    private static String addCaseToSwitch(CompilationUnit cu, String originalCode, SwitchStatement switchStmt) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite listRewrite = rewriter.getListRewrite(switchStmt, SwitchStatement.STATEMENTS_PROPERTY);
        SwitchCase newCase = ast.newSwitchCase();
        int count = 0;
        for (Object o : switchStmt.statements()) {
            if (o instanceof SwitchCase) count++;
        }
        try {
            newCase.expressions().add(ast.newNumberLiteral(String.valueOf(count)));
        } catch (Exception ignored) {}
        listRewrite.insertLast(newCase, null);
        listRewrite.insertLast(ast.newBreakStatement(), null);
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

    private static void insertIntoList(ListRewrite listRewrite, BodyBlock body, Statement newStatement, int relativeIndex) {
        ASTNode node = body.getAstNode();
        if (node instanceof Block) {
            listRewrite.insertAt(newStatement, relativeIndex, null);
        } else if (node instanceof SwitchCase caseNode) {
            SwitchStatement parent = (SwitchStatement) caseNode.getParent();
            List<?> allStatements = parent.statements();
            int caseIndex = allStatements.indexOf(caseNode);
            int absoluteIndex = caseIndex + 1 + relativeIndex;
            listRewrite.insertAt(newStatement, absoluteIndex, null);
        }
    }
}
