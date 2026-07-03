package com.botmaker.blocks.func;

import com.botmaker.core.AbstractCodeBlock;
import com.botmaker.core.AbstractExpressionBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.core.StatementBlock;
import com.botmaker.events.CoreApplicationEvents;
import com.botmaker.palette.SdkApi;
import com.botmaker.services.CodeEditorService;
import com.botmaker.project.ProjectFile;
import com.botmaker.suggestions.ProjectAnalyzer;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.ui.render.components.ArgumentEditors;
import com.botmaker.ui.render.components.BlockUIComponents;
import com.botmaker.ui.render.menu.MenuComponents;
import com.botmaker.util.MethodSignature;
import com.botmaker.types.ResolvedType;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.util.Callback;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;


public class MethodInvocationBlock extends AbstractExpressionBlock implements StatementBlock {

    protected String scopeName;
    protected String methodName;
    protected final List<ExpressionBlock> arguments = new ArrayList<>();
    protected boolean isStatementContext = false;

    // NEW: Allow subclasses to lock the scope
    protected String fixedScopeName = null;

    public MethodInvocationBlock(String id, ASTNode astNode) {
        super(id, resolveExpressionNode(astNode));

        if (astNode instanceof ExpressionStatement) {
            this.isStatementContext = true;
        }

        MethodInvocation mi = (MethodInvocation) this.astNode;
        this.methodName = mi.getName().getIdentifier();
        if (mi.getExpression() != null) {
            this.scopeName = mi.getExpression().toString();
        } else {
            this.scopeName = ""; // Local
        }
    }

    // NEW: Setter for LibraryCallBlock to use
    public void setFixedScope(String className) {
        this.fixedScopeName = className;
        this.scopeName = className; // Sync internal scope
    }

    private static MethodInvocation resolveExpressionNode(ASTNode node) {
        if (node instanceof ExpressionStatement) {
            return (MethodInvocation) ((ExpressionStatement) node).getExpression();
        }
        return (MethodInvocation) node;
    }

    public void addArgument(ExpressionBlock arg) {
        arguments.add(arg);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        String currentFileClass = "";
        if (context.getState() != null && context.getState().getActiveFile() != null) {
            currentFileClass = context.getState().getActiveFile().getClassName();
        }

        // --- 1. Configure Scope UI (Selector OR Label) ---
        Node scopeNode;
        Runnable refreshMethodsAction;

        // Context variables for method population logic
        final ComboBox<String> methodSelector = new ComboBox<>();
        // We need a way to get the current scope text dynamically
        final java.util.function.Supplier<String> currentScopeGetter;

        if (fixedScopeName != null) {
            // --- SDK CALL MODE (LibraryCallBlock) ---
            // The class is switchable inline: a dropdown of the SDK facade classes. Picking a different
            // class rewrites the call to that class (keeping the method name when it still exists, else the
            // class's first method) — the AST rewrite then re-renders the block.
            ComboBox<String> classSelector = new ComboBox<>();
            classSelector.getStyleClass().add("sdk-class-selector");
            classSelector.getItems().addAll(SdkApi.FACADE_CLASSES);
            if (!classSelector.getItems().contains(fixedScopeName)) {
                classSelector.getItems().add(0, fixedScopeName);
            }
            classSelector.setValue(fixedScopeName);
            classSelector.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
            scopeNode = classSelector;

            currentScopeGetter = classSelector::getValue;

            refreshMethodsAction = () -> populateMethodList(context, classSelector.getValue(), methodSelector);

            classSelector.setOnAction(e -> {
                String newClass = classSelector.getValue();
                if (newClass == null || newClass.equals(fixedScopeName)) return;
                switchSdkClass(context, newClass);
            });
        } else {
            // --- DYNAMIC SCOPE MODE (Standard) ---
            ComboBox<String> fileSelector = createScopeSelector(context, currentFileClass);
            scopeNode = fileSelector;

            currentScopeGetter = fileSelector::getValue;

            // Logic to populate based on selection
            refreshMethodsAction = () -> populateMethodList(context, fileSelector.getValue(), methodSelector);

            fileSelector.setOnAction(e -> {
                refreshMethodsAction.run();
                methodSelector.show();
            });
        }

        // --- 2. Configure Method Selector ---
        methodSelector.setValue(methodName);
        methodSelector.setEditable(false);
        methodSelector.setStyle("-fx-font-size: 11px; -fx-pref-width: 120px; -fx-font-weight: bold;");

        // Populate initially
        refreshMethodsAction.run();

        // --- 3. Handle Method Selection Updates ---
        final String finalCurrentFileClass = currentFileClass;
        methodSelector.setOnAction(e -> {
            String newMethodName = methodSelector.getValue();
            if (newMethodName == null) return;

            String currentScopeVal = currentScopeGetter.get();
            if (currentScopeVal == null || currentScopeVal.startsWith("---")) return;

            // Resolve target type for validation
            String targetTypeForValidation = resolveTargetType(currentScopeVal, context);

            // Validation logic (dry run to check existence)
            if (!isValidMethod(context, targetTypeForValidation, newMethodName)) {
                return;
            }

            // Update AST
            String scopeForAST = "";
            if (fixedScopeName != null) {
                scopeForAST = fixedScopeName;
            } else {
                boolean isVariable = isVariableScope(context, currentScopeVal);
                scopeForAST = currentScopeVal.equals(finalCurrentFileClass) && !isVariable ? "" : currentScopeVal;
            }

            // Auto-select the overload that matches the current argument count (else the first); the AST
            // rewrite then smart-merges arguments (keeps compatible ones, fills/drops the rest).
            List<ResolvedType> paramTypes = bestOverloadParams(context, targetTypeForValidation, newMethodName, arguments.size());

            context.getCodeEditor().updateMethodInvocation(
                    (MethodInvocation) this.astNode,
                    scopeForAST,
                    newMethodName,
                    paramTypes
            );
        });

        // --- 4. Build Layout ---
        var sentenceBuilder = BlockLayout.sentence();

        if (fixedScopeName == null) {
            sentenceBuilder.addLabel("Call"); // Only show "Call" for generic blocks
        } else {
            Label sdkBadge = new Label("🤖 SDK");
            sdkBadge.getStyleClass().add("sdk-badge");
            sentenceBuilder.addNode(sdkBadge);
        }

        sentenceBuilder
                .addNode(scopeNode)
                .addLabel(".")
                .addNode(methodSelector);

        // Signature Button (explicit overload picker) — argument sync is automatic on method change.
        addSignatureButton(sentenceBuilder, context, currentScopeGetter, methodSelector, finalCurrentFileClass);

        sentenceBuilder.addLabel("(");

        // Arguments
        renderArguments(sentenceBuilder, context, currentScopeGetter, methodSelector);

        sentenceBuilder.addLabel(")");

        HBox container = sentenceBuilder.build();
        styleContainer(container);

        // Add delete button if statement
        if (isStatementContext) {
            container.getChildren().addAll(
                    BlockUIComponents.createSpacer(),
                    BlockUIComponents.createDeleteButton(() ->
                            context.getCodeEditor().deleteStatement((Statement) this.astNode.getParent()))
            );
        }

        return container;
    }

    // --- REFACTORED HELPER METHODS ---

    private ComboBox<String> createScopeSelector(CodeEditorService context, String initialValue) {
        ComboBox<String> fileSelector = new ComboBox<>();

        // Data gathering logic moved here
        List<String> instanceItems = new ArrayList<>();
        List<ProjectAnalyzer.VariableOption> vars = context.getProjectAnalyzer().getVisibleVariables(this.astNode, ResolvedType.UNKNOWN);
        for (ProjectAnalyzer.VariableOption var : vars) {
            ResolvedType type = ResolvedType.named(var.typeName());
            if (!type.isPrimitive() || type.isString()) {
                instanceItems.add(var.name());
            }
        }
        Collections.sort(instanceItems);

        List<String> staticClassItems = new ArrayList<>();
        if (context.getState() != null) {
            for (ProjectFile file : context.getState().getAllFiles()) {
                if (file.getPath().toString().replace("\\", "/").contains("com/botmaker/sdk")) continue;
                if (hasPublicStaticMethods(context, file)) staticClassItems.add(file.getClassName());
            }
        }
        Collections.sort(staticClassItems);

        // External-library static-utility classes (same source as ExpressionMenuFactory's "Library (static)").
        List<String> libraryClassItems = new ArrayList<>();
        if (context.getProjectAnalyzer().getLibraryIndex() != null) {
            context.getProjectAnalyzer().getLibraryIndex().getStaticUtilityTypes()
                    .forEach(ci -> libraryClassItems.add(ci.getSimpleName()));
        }
        Collections.sort(libraryClassItems);

        List<String> selectorItems = new ArrayList<>();
        if (!instanceItems.isEmpty()) { selectorItems.add("--- INSTANCES ---"); selectorItems.addAll(instanceItems); }
        if (!staticClassItems.isEmpty()) { selectorItems.add("--- CLASSES ---"); selectorItems.addAll(staticClassItems); }
        if (!libraryClassItems.isEmpty()) { selectorItems.add("--- LIBRARIES ---"); selectorItems.addAll(libraryClassItems); }

        fileSelector.getItems().addAll(selectorItems);

        // Setup Cell Factory
        Callback<ListView<String>, ListCell<String>> cellFactory = lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setDisable(false); setStyle(""); }
                else {
                    setText(item);
                    if (item.startsWith("---")) { setDisable(true); setStyle("-fx-font-weight: bold; -fx-text-fill: #888; -fx-alignment: center; -fx-background-color: #f4f4f4;"); }
                    else { setDisable(false); setStyle("-fx-text-fill: black; -fx-padding: 3 0 3 10;"); }
                }
            }
        };
        fileSelector.setCellFactory(cellFactory);
        fileSelector.setButtonCell(cellFactory.call(null));

        String displayValue = scopeName.isEmpty() ? initialValue : scopeName;
        if (!displayValue.isEmpty() && !fileSelector.getItems().contains(displayValue)) {
            fileSelector.getItems().add(0, displayValue);
        }
        fileSelector.setValue(displayValue);
        fileSelector.setStyle("-fx-font-size: 11px; -fx-pref-width: 120px;");

        return fileSelector;
    }

    private void populateMethodList(CodeEditorService context, String selectedScope, ComboBox<String> methodSelector) {
        methodSelector.getItems().clear();
        if (selectedScope == null || selectedScope.startsWith("---")) return;

        // Resolve target class + static-ness, then ask ProjectAnalyzer — this resolves BOTH project source
        // (live bindings) and external-library types (ClassGraph index), so library calls populate too.
        String targetClassName = resolveTargetType(selectedScope, context);
        boolean lookingForStatic = !isVariableScope(context, selectedScope);

        // When this call produces a value (expression context), only offer methods whose return type fits
        // the slot. As a bare statement there's no expected type, so everything (incl. void) is shown.
        ResolvedType expectedType = isStatementContext
                ? ResolvedType.UNKNOWN
                : ProjectAnalyzer.inferExpectedType(this.astNode);

        List<String> availableMethods = context.getProjectAnalyzer()
                .getMethods(targetClassName, lookingForStatic).stream()
                .filter(sig -> isStatementContext || sig.returnsCompatibleWith(expectedType))
                .map(MethodSignature::name)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        methodSelector.getItems().addAll(availableMethods);
    }

    // Helper to determine if a scope string is a variable or class name
    private boolean isVariableScope(CodeEditorService context, String scope) {
        List<ProjectAnalyzer.VariableOption> vars = context.getProjectAnalyzer().getVisibleVariables(this.astNode, ResolvedType.UNKNOWN);
        for(ProjectAnalyzer.VariableOption v : vars) {
            if(v.name().equals(scope)) return true;
        }
        return false;
    }

    private String resolveTargetType(String scope, CodeEditorService context) {
        // Check if variable
        List<ProjectAnalyzer.VariableOption> vars = context.getProjectAnalyzer().getVisibleVariables(this.astNode, ResolvedType.UNKNOWN);
        for(ProjectAnalyzer.VariableOption v : vars) {
            if(v.name().equals(scope)) return v.typeName();
        }
        // Assume Class Name
        return scope;
    }

    private void styleContainer(HBox container) {
        container.setStyle(" -fx-background-radius: " + (isStatementContext ? "4" : "12") + "; -fx-padding: " + (isStatementContext ? "5 10 5 10" : "3 8 3 8") + ";");
        // Distinct look for SDK calls — colour/border live in blocks.css (sdk-call-block).
        if (fixedScopeName != null) {
            container.getStyleClass().add("sdk-call-block");
        }
    }

    private boolean hasPublicStaticMethods(CodeEditorService context, ProjectFile file) {
        if (file == null) return false;
        return !context.getProjectAnalyzer()
                .getMethods(file.getClassName(), true).isEmpty();
    }

    private boolean isValidMethod(CodeEditorService context, String targetType, String methodName) {
        return !findSignatures(context, targetType, methodName).isEmpty();
    }

    /**
     * Overload signatures for {@code className.methodName}, resolved via {@link ProjectAnalyzer#getMethods}
     * — so this works for both project source types and external-library types (ClassGraph index), not just
     * project files.
     */
    private List<MethodSignature> findSignatures(CodeEditorService context, String className, String methodName) {
        return context.getProjectAnalyzer()
                .getMethods(className, false).stream()
                .filter(s -> s.name().equals(methodName))
                .collect(Collectors.toList());
    }

    private MethodSignature determineCurrentSignature(CodeEditorService context, String className, String methodName){
        return MethodSignature.bestForArity(findSignatures(context, className, methodName), arguments.size());
    }

    /** Parameter types of the overload best matching {@code argCount} (exact match preferred, else first). */
    private List<ResolvedType> bestOverloadParams(CodeEditorService context, String className, String methodName, int argCount) {
        MethodSignature best = MethodSignature.bestForArity(findSignatures(context, className, methodName), argCount);
        return best != null ? best.paramTypes() : new ArrayList<>();
    }

    /**
     * Rewrites this SDK call to {@code newClass}: keeps the current method name when the new class still
     * declares it, otherwise falls back to that class's first static method. The AST rewrite re-renders the
     * block (so the dropdowns and typed argument editors rebuild against the new class).
     */
    private void switchSdkClass(CodeEditorService context, String newClass) {
        List<MethodSignature> methods = context.getProjectAnalyzer().getMethods(newClass, true);
        String targetMethod = methods.stream().map(MethodSignature::name).anyMatch(n -> n.equals(methodName))
                ? methodName
                : methods.stream().map(MethodSignature::name).sorted().findFirst().orElse(methodName);

        List<ResolvedType> paramTypes = bestOverloadParams(context, newClass, targetMethod, arguments.size());
        this.fixedScopeName = newClass;
        this.scopeName = newClass;
        context.getCodeEditor().updateMethodInvocation((MethodInvocation) this.astNode, newClass, targetMethod, paramTypes);
    }

    /** Explicit overload picker (⚙): lets the user choose a specific overload; arg sync is automatic. */
    private void addSignatureButton(SentenceLayoutBuilder builder, CodeEditorService context, java.util.function.Supplier<String> scopeGetter, ComboBox<String> methodSelector, String currentFileClass) {
        MenuButton signatureBtn = new MenuButton("⚙");
        signatureBtn.setStyle("-fx-font-size: 9px; -fx-padding: 2 4 2 4; -fx-background-radius: 10;");
        signatureBtn.setTooltip(new Tooltip("Select Method Signature"));

        signatureBtn.setOnShowing(e -> {
            signatureBtn.getItems().clear();
            String currentScope = scopeGetter.get();
            if (currentScope == null || currentScope.startsWith("---")) return;

            // Resolve actual target (variable type or class name)
            String targetType = resolveTargetType(currentScope, context);
            String currentMethod = methodSelector.getValue();

            List<MethodSignature> signatures = findSignatures(context, targetType, currentMethod);

            MenuComponents.populate(signatureBtn.getItems(), signatures, MethodSignature::toString,
                    sig -> {
                        String scopeForAST = (fixedScopeName != null) ? fixedScopeName : (currentScope.equals(currentFileClass) && !isVariableScope(context, currentScope) ? "" : currentScope);
                        context.getCodeEditor().updateMethodInvocation((MethodInvocation) this.astNode, scopeForAST, currentMethod, sig.paramTypes());
                    },
                    "No signatures found");
        });
        builder.addNode(signatureBtn);
    }

    private void renderArguments(SentenceLayoutBuilder builder, CodeEditorService context, java.util.function.Supplier<String> scopeGetter, ComboBox<String> methodSelector) {
        String currentScope = scopeGetter.get();
        String targetType = (currentScope != null) ? resolveTargetType(currentScope, context) : "";
        MethodSignature currentSignature = determineCurrentSignature(context, targetType, methodName);

        for (int i = 0; i < arguments.size(); i++) {
            ExpressionBlock arg = arguments.get(i);

            // paramTypeAt stretches a trailing varargs parameter over every trailing argument, so e.g. every
            // ImageTemplate in findAny(a, b, c) gets the image picker — not just the first.
            ResolvedType paramType = currentSignature != null ? currentSignature.paramTypeAt(i) : null;
            if (paramType == null) paramType = ResolvedType.UNKNOWN;

            Node editor = ArgumentEditors.editorFor(context, arg, paramType, targetType, methodName);
            if (editor != null) {
                builder.addNode(editor);
                continue;
            }

            Label typeLabel = new Label(paramType.simpleName());
            typeLabel.getStyleClass().add("arg-type-label");

            builder.addNode(createArgumentPill(context, arg, paramType, typeLabel, true));
        }
    }

}