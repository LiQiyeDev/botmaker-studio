package com.botmaker.studio.blocks.func;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.palette.SdkDocs;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.ui.render.components.ArgumentEditors;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import com.botmaker.studio.ui.render.components.PickAllSession;
import com.botmaker.studio.ui.render.components.pickers.ImageTemplateGroupPicker;
import com.botmaker.studio.ui.render.menu.MenuComponents;
import com.botmaker.studio.util.MethodSignature;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import javafx.util.Callback;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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

    /** This call's argument expression blocks (read-only) — used by the overlay editor's per-arg config popover. */
    public List<ExpressionBlock> getArgumentBlocks() {
        return List.copyOf(arguments);
    }

    /** The call's scope: the SDK facade class for a fixed-scope SDK call, else the current dynamic scope name. */
    public String getScope() {
        return fixedScopeName != null ? fixedScopeName : scopeName;
    }

    public String getMethodName() {
        return methodName;
    }

    /**
     * The parameter types of the overload this call currently matches, aligned to {@link #getArgumentBlocks()}
     * — so the overlay can hand each argument to {@code PickerRegistry} with the right expected type. Empty
     * when the method can't be resolved (e.g. SDK jar not indexed).
     */
    public List<ResolvedType> resolveParamTypes(CodeEditorService context) {
        String targetType = resolveTargetType(getScope(), context);
        MethodSignature sig = determineCurrentSignature(context, targetType, methodName);
        return sig != null ? sig.paramTypes() : List.of();
    }

    /** The overloads available for this call's current scope + method (for the overlay config popover). */
    public List<MethodSignature> overloadSignatures(CodeEditorService context) {
        return findSignatures(context, resolveTargetType(getScope(), context), getMethodName());
    }

    /** The overload this call currently matches by its actual argument types, or {@code null}. */
    public MethodSignature currentSignature(CodeEditorService context) {
        return determineCurrentSignature(context, resolveTargetType(getScope(), context), getMethodName());
    }

    /** Rewrites this call to {@code sig}'s parameter list (arguments are smart-merged by the AST rewrite). */
    public void switchToOverload(CodeEditorService context, MethodSignature sig) {
        if (sig == null) return;
        String scope = getScope();
        String currentFileClass = (context.getState() != null && context.getState().getActiveFile() != null)
                ? context.getState().getActiveFile().getClassName() : "";
        String scopeForAST = (fixedScopeName != null) ? fixedScopeName
                : (scope.equals(currentFileClass) && !isVariableScope(context, scope) ? "" : scope);
        context.getCodeEditor().updateMethodInvocation(
                (MethodInvocation) this.astNode, scopeForAST, getMethodName(), sig.paramTypes());
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
            // This bot's SDK, not Studio's — see CodeEditorService.sdkFacadeNames(). The call's own class is
            // re-added just below when it isn't in the list, so switching away from a facade the jar no longer
            // has is possible while switching *to* one it never had is not.
            classSelector.getItems().addAll(context.sdkFacadeNames());
            if (!classSelector.getItems().contains(fixedScopeName)) {
                classSelector.getItems().add(0, fixedScopeName);
            }
            classSelector.setValue(fixedScopeName);
            classSelector.getStyleClass().add("block-selector");
            // Read-only: the selector still backs the value lookups below, but never reaches the scene — the
            // user sees what the call says, with no control to change it.
            scopeNode = isReadOnly() ? staticValueLabel(fixedScopeName) : classSelector;

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
            scopeNode = isReadOnly() ? staticValueLabel(fileSelector.getValue()) : fileSelector;

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
        methodSelector.getStyleClass().add("block-selector");
        methodSelector.setPrefWidth(120);

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

        Node methodNode = isReadOnly() ? staticValueLabel(methodName) : methodSelector;
        markIfDeprecated(methodNode, context, currentScopeGetter);

        sentenceBuilder
                .addNode(scopeNode)
                .addLabel(".")
                .addNode(methodNode);

        if (!isReadOnly()) {
            // Both of these rewrite the call, so a locked block gets neither. The info button below stays: it
            // explains the call rather than changing it, and reading is not editing.

            // Signature Button (explicit overload picker) — argument sync is automatic on method change.
            addSignatureButton(sentenceBuilder, context, currentScopeGetter, methodSelector, finalCurrentFileClass);

            // "Pick all on screen" — one overlay pass over every on-screen-pickable argument of this call.
            addPickAllButton(sentenceBuilder, context, currentScopeGetter);
        }

        sentenceBuilder.addLabel("(");

        // Arguments
        renderArguments(sentenceBuilder, context, currentScopeGetter, methodSelector);

        sentenceBuilder.addLabel(")");

        // Return-type badge AFTER the arguments (SDK calls): the vision API returns boolean/int (find/click →
        // boolean, findAll/clickAll → int, Vision.lastMatch → MatchResult, lastMatches → Matches),
        // so surface what the call yields — placed after the argument list per the block layout.
        addReturnTypeBadge(sentenceBuilder, context, currentScopeGetter);

        // Explanation (?) button — far right of all elements — SDK method help from the sources-jar Javadoc.
        addInfoButton(sentenceBuilder, context, currentScopeGetter);

        HBox container = sentenceBuilder.build();
        styleContainer(container);

        // Add delete button if statement
        if (isStatementContext && !isReadOnly()) {
            container.getChildren().addAll(
                    BlockUIComponents.createSpacer(),
                    BlockUIComponents.createDeleteButton(() -> {
                        // enclosingStatement(), not astNode.getParent(): this block's node is the
                        // MethodInvocation, and the wrapping ExpressionStatement is not always its direct parent.
                        Statement statement = enclosingStatement();
                        if (statement != null) context.getCodeEditor().deleteStatement(statement);
                    })
            );
        }

        return container;
    }

    /**
     * A locked stand-in for a {@link ComboBox}: shows the value the call actually has, with nothing to click.
     * The selector it replaces is still constructed — it backs the scope/method lookups — it just never
     * reaches the scene, so there is no control for the user to change and no edit for the write layer to
     * refuse.
     */
    private static Label staticValueLabel(String value) {
        Label label = new Label(value == null ? "" : value);
        label.getStyleClass().add("static-value-label");
        return label;
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

        // External-library static-utility classes (same source as ExpressionMenu's "Library (static)").
        // SDK facades are excluded here: they're reached only via the curated Vision palette blocks (+ this
        // block's own SDK class/method/⚙ selectors once placed), so there's a single way in.
        List<String> libraryClassItems = new ArrayList<>();
        if (context.getProjectAnalyzer().getLibraryIndex() != null) {
            context.getProjectAnalyzer().getLibraryIndex().getStaticUtilityTypes().stream()
                    .filter(ci -> !SdkType.isFacadeClass(ci.getSimpleName()))
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
                if (empty || item == null) { setText(null); setDisable(false); getStyleClass().remove("block-section-header"); }
                else {
                    setText(item);
                    // The popup is its own scene graph and never sees the block's -bm-text-on-color, so the
                    // ordinary rows say nothing about colour at all and take the theme's list-cell default.
                    // Pinning "black" here is what made classes and libraries unreadable in Dark and Black.
                    boolean separator = item.startsWith("---");
                    setDisable(separator);
                    if (separator && !getStyleClass().contains("block-section-header")) {
                        getStyleClass().add("block-section-header");
                    } else if (!separator) {
                        getStyleClass().remove("block-section-header");
                    }
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
        fileSelector.getStyleClass().add("block-selector");
        fileSelector.setPrefWidth(120);

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

        // OFFERED, not resolved — the same rule the ⚙ picker applies, one level up at the method *name*.
        // The name this call is already on is kept whatever the SDK says, so switching scope back and forth
        // can never leave the block pointing at a method its own dropdown denies; and an uncurated jar, an
        // uncurated type or a headless edit is the identity function here.
        if (context.getSdkSurface() != null) {
            availableMethods = context.getSdkSurface()
                    .retainOfferedNames(targetClassName, availableMethods, this.methodName);
        }

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
        container.getStyleClass().add(isStatementContext ? "block-call--statement" : "block-call--expression");
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

    /**
     * The overloads to <em>offer</em> the user for {@code className.methodName} — the SDK's {@code @Palette}
     * set, plus whichever overload this call is already on.
     *
     * <p>Deliberately not {@link #findSignatures}, and deliberately not a replacement for it: that one is what
     * <em>resolves</em> a call (argument types, the current overload, {@code switchToOverload}) and must never
     * be filtered, or a block sitting on an overload the SDK no longer proposes would stop rendering the
     * arguments it actually has. Curation is about what to propose; resolution is about what is there.
     *
     * <p>An unindexed or uncurated SDK — and a headless {@code CodeEditorService} with no surface at all —
     * offers everything, so this is the identity function everywhere except a curated facade.
     */
    private List<MethodSignature> offeredSignatures(CodeEditorService context, String className, String methodName) {
        List<MethodSignature> all = findSignatures(context, className, methodName);
        if (context.getSdkSurface() == null) return all;
        return context.getSdkSurface().retainOffered(className, methodName, all,
                MethodSignature.bestForArgs(all, currentArgTypes()));
    }

    private MethodSignature determineCurrentSignature(CodeEditorService context, String className, String methodName){
        // Resolve by the ACTUAL argument types, not just their count — so same-arity overloads that differ only
        // in a parameter's type (e.g. find(t, CaptureSource) vs find(t, Rect) vs find(t, double), all arity-2)
        // pick the overload the arguments really match, giving each slot the correct picker. Falls back to
        // count-only when argument types are unresolved.
        return MethodSignature.bestForArgs(findSignatures(context, className, methodName), currentArgTypes());
    }

    /** The resolved (binding-backed) types of this call's current arguments, for type-aware overload selection. */
    private List<ResolvedType> currentArgTypes() {
        List<ResolvedType> types = new ArrayList<>();
        for (ExpressionBlock arg : arguments) {
            ASTNode node = arg.getAstNode();
            types.add(node instanceof Expression expr ? ProjectAnalyzer.resolveType(expr) : ResolvedType.UNKNOWN);
        }
        return types;
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
        signatureBtn.getStyleClass().addAll("block-action-button", "block-action-button--mini");
        signatureBtn.setTooltip(new Tooltip("Select Method Signature"));

        signatureBtn.setOnShowing(e -> {
            signatureBtn.getItems().clear();
            String currentScope = scopeGetter.get();
            if (currentScope == null || currentScope.startsWith("---")) return;

            // Resolve actual target (variable type or class name)
            String targetType = resolveTargetType(currentScope, context);
            String currentMethod = methodSelector.getValue();

            // OFFERED, not resolved. findSignatures stays unfiltered everywhere else — it backs argument
            // typing and the current-overload lookup, and a block sitting on a hidden overload must keep
            // rendering and compiling. Here the user is *choosing*, so the SDK's curation applies — with the
            // overload this call is already on kept regardless, so the picker never denies where the block is.
            List<MethodSignature> signatures = offeredSignatures(context, targetType, currentMethod);

            // Overload selection: switch this call to the picked overload now (arg sync is automatic).
            MenuComponents.populate(signatureBtn.getItems(), signatures, MethodSignature::toString,
                    sig -> {
                        String scopeForAST = (fixedScopeName != null) ? fixedScopeName : (currentScope.equals(currentFileClass) && !isVariableScope(context, currentScope) ? "" : currentScope);
                        context.getCodeEditor().updateMethodInvocation((MethodInvocation) this.astNode, scopeForAST, currentMethod, sig.paramTypes());
                    },
                    "No signatures found");

            // Favorite overload: the one created by default for this method in fresh palette blocks. Only
            // meaningful when there's more than one overload to choose between.
            if (signatures.size() > 1) {
                addFavoriteOverloadMenu(signatureBtn.getItems(), context, targetType, currentMethod, signatures);
            }
        });
        builder.addNode(signatureBtn);
    }

    /**
     * The project-settings key for a method's favorite overload: {@code targetType#methodName}. Must match the
     * key used at creation in {@code StatementFactory.buildLibraryCall} so the favorite set here is the one
     * applied to fresh palette blocks.
     */
    static String favoriteMethodKey(String targetType, String methodName) {
        return targetType + "#" + methodName;
    }

    /**
     * Appends a "★ Default overload" submenu: a check-item per overload that marks/sets the project's favorite
     * overload for this method (persisted in {@code settings.json}). The favorite is the overload created by
     * default when a fresh block for this method is inserted; selecting the current favorite again clears it.
     */
    private void addFavoriteOverloadMenu(List<MenuItem> items, CodeEditorService context, String targetType,
                                         String methodName, List<MethodSignature> signatures) {
        ProjectSettingsService settingsService = ProjectSettingsService.forProject(context);
        String methodKey = favoriteMethodKey(targetType, methodName);
        String currentFavKey = settingsService.current().favoriteSignature(methodKey);

        items.add(new SeparatorMenuItem());
        Menu favMenu = new Menu("★ Default overload");
        for (MethodSignature sig : signatures) {
            String sigKey = sig.signatureKey();
            CheckMenuItem item = new CheckMenuItem(sig.toString());
            item.setSelected(sigKey.equals(currentFavKey));
            item.setOnAction(ev -> {
                // Toggle: clicking the current favorite clears it; otherwise set this overload as favorite.
                String newFav = sigKey.equals(currentFavKey) ? null : sigKey;
                settingsService.update(settingsService.current().withFavoriteOverload(methodKey, newFav));
            });
            favMenu.getItems().add(item);
        }
        items.add(favMenu);
    }

    /**
     * "📸 Pick all" button — captures the target once and walks every on-screen-pickable argument
     * ({@code ImageTemplate}/{@code Rect}/{@code Point}) of this call through a single overlay, applying them
     * in one rewrite (see {@link PickAllSession}). Added only when at least one such argument exists, so
     * ordinary calls stay uncluttered. The overload is resolved the same way {@code renderArguments} resolves
     * per-argument types, so the button and the typed pickers agree.
     */
    private void addPickAllButton(SentenceLayoutBuilder builder, CodeEditorService context,
                                  java.util.function.Supplier<String> scopeGetter) {
        String currentScope = scopeGetter.get();
        String targetType = (currentScope != null) ? resolveTargetType(currentScope, context) : "";
        MethodSignature signature = determineCurrentSignature(context, targetType, methodName);
        if (!PickAllSession.hasPickableArgs(signature, arguments.size())) return;

        Button pickAll = new Button("📸 Pick all");
        pickAll.setTooltip(new Tooltip("Capture every image & region argument of this call in one screen selection"));
        pickAll.getStyleClass().addAll("block-action-button", "block-action-button--mini");
        pickAll.setOnAction(e -> {
            Window owner = pickAll.getScene() != null ? pickAll.getScene().getWindow() : null;
            PickAllSession.run(context, (MethodInvocation) this.astNode, arguments, signature, owner);
        });
        builder.addNode(pickAll);
    }

    private void renderArguments(SentenceLayoutBuilder builder, CodeEditorService context, java.util.function.Supplier<String> scopeGetter, ComboBox<String> methodSelector) {
        String currentScope = scopeGetter.get();
        String targetType = (currentScope != null) ? resolveTargetType(currentScope, context) : "";
        MethodSignature currentSignature = determineCurrentSignature(context, targetType, methodName);

        // SDK facade calls carry real parameter names + @param docs (from the sources jar); use them to label
        // the pills (e.g. findCompare(good, bad)) instead of the bare type. Empty for non-SDK calls.
        List<SdkDocs.Param> docParams = sdkDocParams(context, targetType, currentSignature);

        int imageVarargsFrom = imageVarargsStart(currentSignature);
        // Everything the image chip row doesn't claim falls back to the generic grow/shrink affordance.
        int varargsFrom = imageVarargsFrom >= 0 ? -1 : varargsTailStart(currentSignature);

        for (int i = 0; i < arguments.size(); i++) {
            if (i == imageVarargsFrom) {
                builder.addNode(imageVarargsRow(context, imageVarargsFrom));
                break;
            }
            ExpressionBlock arg = arguments.get(i);

            // paramTypeAt stretches a trailing varargs parameter over every trailing argument, so e.g. every
            // ImageTemplate in findAny(a, b, c) gets the image picker — not just the first.
            ResolvedType paramType = currentSignature != null ? currentSignature.paramTypeAt(i) : null;
            if (paramType == null) paramType = ResolvedType.UNKNOWN;

            final ResolvedType finalParamType = paramType;
            SdkDocs.Param doc = (i < docParams.size()) ? docParams.get(i) : null;
            String argName = (doc != null && doc.name() != null && !doc.name().isBlank()) ? doc.name() : null;
            String argDesc = (doc != null) ? doc.desc() : null;

            Node editor = ArgumentEditors.editorFor(context, arg, paramType, targetType, methodName, i);
            if (editor != null) {
                // Specialized picker (image/region/enum). Wrap it in the standard pill so it *also* gets the
                // "+" change button (open the expression menu to swap in a variable/other expression) — the
                // control some SDK arg slots were missing — plus its parameter name/description.
                Label pickerLabel = argName != null ? argLabel(argName, argDesc) : null;
                Button changeBtn = createChangeButton(e ->
                        showExpressionMenuAndReplace((Button) e.getSource(), context, finalParamType, (Expression) arg.getAstNode()));
                builder.addNode(BlockUIComponents.createArgumentPill(pickerLabel, editor, changeBtn));
                addRemoveVarargsButton(builder, context, varargsFrom, i);
                continue;
            }

            Label typeLabel = argLabel(argName != null ? argName : paramType.simpleName(), argDesc);
            builder.addNode(createArgumentPill(context, arg, paramType, typeLabel));
            addRemoveVarargsButton(builder, context, varargsFrom, i);
        }

        if (varargsFrom >= 0) addVarargsButton(builder, context, currentSignature);

        // An image varargs call that has no templates yet — hasAny(), findAll() — renders nothing above,
        // because the loop walks the arguments that exist. The row still has to appear, or the slot is
        // unfillable.
        if (imageVarargsFrom == arguments.size()) {
            builder.addNode(imageVarargsRow(context, imageVarargsFrom));
        }
    }

    /**
     * The argument index at which this call's trailing varargs begin, or {@code -1} when the resolved overload
     * isn't varargs. Unlike {@link #imageVarargsStart} this makes no demand on what the arguments hold — the
     * generic affordance adds and removes whole slots, it never reads or rewrites their contents.
     */
    private int varargsTailStart(MethodSignature signature) {
        if (signature == null || !signature.varargs() || signature.paramTypes().isEmpty()) return -1;
        return signature.paramTypes().size() - 1;
    }

    /**
     * The {@code ✕} that drops argument {@code index}, rendered only on arguments inside the varargs tail —
     * a fixed parameter can't be removed without breaking the call.
     */
    private void addRemoveVarargsButton(SentenceLayoutBuilder builder, CodeEditorService context, int varargsFrom, int index) {
        if (varargsFrom < 0 || index < varargsFrom || isReadOnly) return;
        Button remove = new Button("✕");
        remove.getStyleClass().add("icon-button");
        remove.setTooltip(new Tooltip("Remove this argument"));
        remove.setOnAction(e ->
                context.getCodeEditor().deleteArgumentFromMethodInvocation((MethodInvocation) this.astNode, index));
        builder.addNode(remove);
    }

    /**
     * The trailing {@code ＋} that appends one more varargs argument. Its absence is why
     * {@code MethodSignature} could model varargs correctly since the beginning and a call still never grew
     * past the arguments it was created with: {@code CodeEditor.addVarargsArgument} had no caller.
     */
    private void addVarargsButton(SentenceLayoutBuilder builder, CodeEditorService context, MethodSignature signature) {
        if (isReadOnly) return;
        ResolvedType element = signature.paramTypes().get(signature.paramTypes().size() - 1);
        Button add = new Button("＋");
        add.getStyleClass().addAll("icon-button", "expression-add-button");
        add.setTooltip(new Tooltip("Add another " + (element != null ? element.simpleName() : "argument")));
        add.setOnAction(e ->
                context.getCodeEditor().addVarargsArgument((MethodInvocation) this.astNode, element));
        builder.addNode(add);
    }

    /**
     * The argument index at which this call's trailing {@code ImageTemplate...} varargs begin, or {@code -1}
     * when it has none — or when the templates already there are not all plain
     * {@code new ImageTemplate("…")} literals.
     *
     * <p>That last condition is the guard: the chip row edits the tail as a list of paths, so an argument
     * holding a variable, a field or a call has no path to show and would be overwritten by the next chip
     * edit. Those calls keep the ordinary one-picker-per-argument rendering, which represents them
     * faithfully.
     */
    private int imageVarargsStart(MethodSignature signature) {
        if (signature == null || !signature.varargs() || signature.paramTypes().isEmpty()) return -1;
        int from = signature.paramTypes().size() - 1;
        ResolvedType element = signature.paramTypes().get(from);
        if (element == null || !ImageTemplatePicker.isImageTemplateType(element)) return -1;
        if (from > arguments.size()) return -1;
        for (int i = from; i < arguments.size(); i++) {
            if (ImageTemplateGroupPicker.templatePath(arguments.get(i).getAstNode()).isEmpty()) return -1;
        }
        return from;
    }

    /**
     * The multi-image chip row standing in for the whole varargs tail — the same control an
     * {@code ImageTemplateGroup} slot gets. A varargs slot used to render one single-image picker per
     * argument that already existed, so {@code found.hasAny(coin)} could never become
     * {@code found.hasAny(coin, gem)}: the picker was fine, there was simply no affordance that added a
     * second slot. The row's add/remove hand back the whole list, which
     * {@code CodeEditor.setImageTemplateArgs} writes over the tail.
     */
    private Node imageVarargsRow(CodeEditorService context, int from) {
        List<String> paths = new ArrayList<>();
        for (int i = from; i < arguments.size(); i++) {
            ImageTemplateGroupPicker.templatePath(arguments.get(i).getAstNode()).ifPresent(paths::add);
        }
        return ImageTemplateGroupPicker.chipRow(context, paths,
                updated -> context.getCodeEditor()
                        .setImageTemplateArgs((MethodInvocation) this.astNode, from, updated));
    }

    /**
     * Shows a small {@code → ReturnType} badge on SDK call blocks (skipped for {@code void} and non-SDK
     * calls). Driven by the resolved current overload's return type, so it tracks method/overload switches —
     * e.g. flips {@code → boolean} to {@code → int} when the user switches {@code find} to {@code findAll}.
     */
    private void addReturnTypeBadge(SentenceLayoutBuilder builder, CodeEditorService context,
                                    java.util.function.Supplier<String> scopeGetter) {
        if (fixedScopeName == null) return;
        String currentScope = scopeGetter.get();
        String targetType = (currentScope != null) ? resolveTargetType(currentScope, context) : fixedScopeName;
        MethodSignature sig = determineCurrentSignature(context, targetType, methodName);
        if (sig == null || sig.returnType() == null || sig.returnType().isVoid()) return;
        String returnName = sig.returnType().simpleName();
        Label badge = new Label("→ " + returnName);
        badge.getStyleClass().add("return-type-badge");
        badge.setTooltip(new Tooltip("This call returns " + returnName));
        builder.addNode(badge);
    }

    /** A small arg-slot label (parameter name or type) carrying the {@code @param} description as a tooltip. */
    private Label argLabel(String text, String desc) {
        Label label = new Label(text);
        label.getStyleClass().add("arg-type-label");
        if (desc != null && !desc.isBlank()) label.setTooltip(new Tooltip(desc));
        return label;
    }

    /** The documented parameters of the best-matching SDK overload for this call (empty for non-SDK calls). */
    private List<SdkDocs.Param> sdkDocParams(CodeEditorService context, String targetType, MethodSignature sig) {
        if (fixedScopeName == null || sig == null) return List.of();
        List<String> typeNames = sig.paramTypes().stream().map(ResolvedType::simpleName).collect(Collectors.toList());
        return context.getSdkDocs().lookup(targetType, methodName, typeNames)
                .map(SdkDocs.Overload::params).orElse(List.of());
    }

    /**
     * Adds the "learn about it" (ⓘ) button when this is an SDK call and the sources-jar Javadoc documents the
     * current method — a click-open popover with the method summary and per-parameter descriptions. SDK-only
     * for now (the generic call path has no doc source); no-op when nothing is documented.
     */
    /**
     * Strikes {@code methodNode} through and explains why, when the call names a method this project's SDK
     * marks {@code @Deprecated}. Nothing is blocked: the call still compiles and still runs — this is the one
     * release of notice the API contract owes the user before the method goes
     * ({@code docs/refactor/21-api-compat.md}), and it is the only place they would ever see it.
     *
     * <p>The <em>fact</em> comes from bytecode ({@code SdkSurfaceService}); the <em>replacement</em> from the
     * {@code @deprecated} Javadoc of the bot's own sources jar ({@code SdkDocs}). Either can be missing — an
     * annotated method with no note still strikes through with a generic tooltip, and a note with no
     * annotation says nothing at all, because the compiler is what the user will ultimately answer to.
     */
    private void markIfDeprecated(Node methodNode, CodeEditorService context,
                                  java.util.function.Supplier<String> scopeGetter) {
        if (fixedScopeName == null) return; // only SDK facade calls have a surface to check against
        String currentScope = scopeGetter.get();
        String targetType = (currentScope != null) ? resolveTargetType(currentScope, context) : fixedScopeName;
        if (!context.isSdkMemberDeprecated(targetType, methodName)) return;

        methodNode.getStyleClass().add("deprecated-member");
        String note = context.getSdkDocs().overloads(targetType, methodName).stream()
                .map(SdkDocs.Overload::deprecated)
                .filter(d -> d != null && !d.isBlank())
                .findFirst()
                .orElse("");
        Tooltip.install(methodNode, new Tooltip(note.isBlank()
                ? methodName + "() is deprecated and will be removed in a future SDK version."
                : methodName + "() is deprecated — " + note.trim()));
    }

    private void addInfoButton(SentenceLayoutBuilder builder, CodeEditorService context,
                               java.util.function.Supplier<String> scopeGetter) {
        if (fixedScopeName == null) return;
        String currentScope = scopeGetter.get();
        String targetType = (currentScope != null) ? resolveTargetType(currentScope, context) : fixedScopeName;
        MethodSignature sig = determineCurrentSignature(context, targetType, methodName);
        List<String> typeNames = (sig != null)
                ? sig.paramTypes().stream().map(ResolvedType::simpleName).collect(Collectors.toList())
                : List.of();

        Optional<SdkDocs.Overload> overload = context.getSdkDocs().lookup(targetType, methodName, typeNames);
        if (overload.isEmpty()) return;
        SdkDocs.Overload o = overload.get();

        StringBuilder body = new StringBuilder();
        // First, because it changes what the rest of the explanation is worth reading for.
        if (o.deprecated() != null && !o.deprecated().isBlank()) {
            body.append("⚠ Deprecated — ").append(o.deprecated().trim());
        }
        if (o.summary() != null && !o.summary().isBlank()) {
            if (body.length() > 0) body.append("\n\n");
            body.append(o.summary().trim());
        }
        for (SdkDocs.Param p : o.params()) {
            if (p.desc() != null && !p.desc().isBlank()) {
                if (body.length() > 0) body.append("\n\n");
                body.append("• ").append(p.name()).append(" — ").append(p.desc().trim());
            }
        }
        if (body.length() == 0) return;
        builder.addNode(BlockUIComponents.createInfoButton(methodName + "()", body.toString()));
    }

}
