package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.ui.app.capture.CaptureSourcePicker;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.palette.ExpressionCategory;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.palette.SdkApi;
import com.botmaker.studio.util.MethodSignature;
import com.botmaker.studio.util.VariableScopeVisitor;
import io.github.classgraph.ClassInfo;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Builds the type-aware context menus for inserting and replacing blocks. This is the domain-heavy
 * counterpart to the pure widget factories in {@link com.botmaker.studio.ui.render.components.BlockUIComponents}:
 * it reads {@link ProjectAnalyzer} for visible variables, callable methods, constructors and enum
 * constants, and emits the user's pick either as an {@link ExpressionType} or an
 * {@link ExpressionChoice}.
 */
public final class ExpressionMenuFactory {

    private ExpressionMenuFactory() {}

    /**
     * Wires {@code label} as a clickable type selector: hand cursor, a "click to change" {@code tooltip}, and a
     * click that opens {@link #showTypeSelectorMenu}. {@code currentType} is resolved lazily at click time, since
     * some callers (parameter / enum blocks) only know the type then. Shared by the variable / field / parameter /
     * enum blocks so the cursor + tooltip + menu wiring lives in one place.
     */
    public static void installTypeSelector(
            javafx.scene.control.Label label,
            String tooltip,
            java.util.function.Supplier<ResolvedType> currentType,
            CodeEditorService context,
            ASTNode contextNode,
            Consumer<ResolvedType> onTypeSelected) {
        installTypeSelector(label, tooltip, currentType, context, contextNode, false, onTypeSelected);
    }

    /** As {@link #installTypeSelector}, but {@code allowVoid} offers a {@code void} pick (method return types). */
    public static void installTypeSelector(
            javafx.scene.control.Label label,
            String tooltip,
            java.util.function.Supplier<ResolvedType> currentType,
            CodeEditorService context,
            ASTNode contextNode,
            boolean allowVoid,
            Consumer<ResolvedType> onTypeSelected) {
        label.setCursor(javafx.scene.Cursor.HAND);
        javafx.scene.control.Tooltip.install(label, new javafx.scene.control.Tooltip(tooltip));
        label.setOnMouseClicked(e -> showTypeMenu(label, currentType.get(), context, contextNode, true, allowVoid, onTypeSelected));
    }

    /** Back-compat entry: the type-change selector for params/vars/fields (array dims on, no {@code void}). */
    public static void showTypeSelectorMenu(
            Node anchor,
            ResolvedType currentType,
            CodeEditorService context,
            ASTNode contextNode,
            Consumer<ResolvedType> onTypeSelected) {
        showTypeMenu(anchor, currentType, context, contextNode, true, false, onTypeSelected);
    }

    /**
     * The single, searchable type picker used everywhere a type is chosen (method return type, parameter type,
     * variable / field type). A live search box filters a flat list; with no query the types are grouped into
     * PRIMITIVES and CLASSES (de-duped by simple name, sorted). When {@code allowArrayDims} and {@code currentType}
     * is non-null, "Add/Remove Dimension []" items are offered and every pick preserves the current array depth.
     * When {@code allowVoid}, a {@code void} entry is offered (return types only).
     */
    public static void showTypeMenu(
            Node anchor,
            ResolvedType currentType,
            CodeEditorService context,
            ASTNode contextNode,
            boolean allowArrayDims,
            boolean allowVoid,
            Consumer<ResolvedType> onTypeSelected) {
        ContextMenu menu = new ContextMenu();

        TextField search = new TextField();
        search.setPromptText("Search types…");
        CustomMenuItem searchItem = new CustomMenuItem(search);
        searchItem.setHideOnClick(false);
        menu.getItems().add(searchItem);

        rebuildTypeItems(menu, "", currentType, context, contextNode, allowArrayDims, allowVoid, onTypeSelected);
        search.textProperty().addListener((obs, old, query) ->
                rebuildTypeItems(menu, query, currentType, context, contextNode, allowArrayDims, allowVoid, onTypeSelected));
        menu.setOnShown(e -> search.requestFocus());
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /** Rebuilds the type menu body (everything below the search box at index 0) for the current {@code query}. */
    private static void rebuildTypeItems(ContextMenu menu, String query, ResolvedType currentType,
                                         CodeEditorService context, ASTNode contextNode, boolean allowArrayDims,
                                         boolean allowVoid, Consumer<ResolvedType> onPick) {
        menu.getItems().remove(1, menu.getItems().size());
        String q = query == null ? "" : query.trim().toLowerCase();

        int dims = (allowArrayDims && currentType != null) ? currentType.arrayDimensions() : 0;
        ResolvedType leaf = currentType != null ? currentType.leafType() : null;

        // Array-dimension controls only make sense in the no-query, editing-an-existing-type view.
        if (allowArrayDims && leaf != null && q.isEmpty()) {
            MenuItem addDim = new MenuItem("Add Dimension []");
            addDim.setOnAction(e -> onPick.accept(leaf.asArray(dims + 1)));
            menu.getItems().add(addDim);
            if (dims > 0) {
                MenuItem removeDim = new MenuItem("Remove Dimension []");
                removeDim.setOnAction(e -> onPick.accept(leaf.asArray(dims - 1)));
                menu.getItems().add(removeDim);
            }
            menu.getItems().add(new SeparatorMenuItem());
        }

        // Collect primitives + classes, de-duped by simple name.
        Set<String> fundamentals = new HashSet<>(ProjectAnalyzer.getFundamentalTypeNames());
        Set<String> seen = new HashSet<>();
        List<ResolvedType> primitives = new ArrayList<>();
        List<ResolvedType> classes = new ArrayList<>();

        if (allowVoid && seen.add("void")) primitives.add(ResolvedType.named("void"));
        for (String name : ProjectAnalyzer.getFundamentalTypeNames()) {
            if (seen.add(name)) primitives.add(ResolvedType.named(name));
        }
        for (ResolvedType type : context.getProjectAnalyzer().getAvailableTypes(contextNode)) {
            if (type.isVoid()) continue;
            if (!seen.add(type.simpleName())) continue;
            (fundamentals.contains(type.simpleName()) ? primitives : classes).add(type);
        }
        primitives.sort(Comparator.comparing(ResolvedType::simpleName));
        classes.sort(Comparator.comparing(ResolvedType::simpleName));

        // Active search: a flat, filtered list — no sections to scan.
        if (!q.isEmpty()) {
            List<ResolvedType> all = new ArrayList<>(primitives);
            all.addAll(classes);
            List<MenuItem> matches = all.stream()
                    .filter(t -> t.simpleName().toLowerCase().contains(q))
                    .map(t -> typeMenuItem(t, dims, onPick))
                    .collect(Collectors.toList());
            if (matches.isEmpty()) menu.getItems().add(disabledItem("No matches"));
            else menu.getItems().addAll(matches);
            return;
        }

        if (!primitives.isEmpty()) {
            menu.getItems().add(sectionHeader("PRIMITIVES"));
            for (ResolvedType type : primitives) menu.getItems().add(typeMenuItem(type, dims, onPick));
        }
        if (!classes.isEmpty()) {
            if (menu.getItems().size() > 1) menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(sectionHeader("CLASSES"));
            for (ResolvedType type : classes) menu.getItems().add(typeMenuItem(type, dims, onPick));
        }
        if (menu.getItems().size() == 1) menu.getItems().add(disabledItem("(No types available)"));
    }

    /** A type entry that preserves the current array depth ({@code dims}) when picked. */
    private static MenuItem typeMenuItem(ResolvedType baseType, int dims, Consumer<ResolvedType> onSelect) {
        MenuItem item = new MenuItem(baseType.simpleName());
        item.setOnAction(e -> onSelect.accept(dims > 0 ? baseType.asArray(dims) : baseType));
        return item;
    }

    private static final String TYPE_SECTION_HEADER_STYLE =
            "-fx-font-weight: bold; -fx-opacity: 1.0; -fx-text-fill: #666;";

    private static MenuItem sectionHeader(String text) {
        MenuItem header = new MenuItem(text);
        header.setDisable(true);
        header.setStyle(TYPE_SECTION_HEADER_STYLE);
        return header;
    }

    /**
     * Applies a pick from {@link #createExpressionTypeMenu} to {@code toReplace}: a plain {@link ExpressionType}
     * swaps in a fresh expression block, an {@link ExpressionChoice} drives the richer rewrite (method call,
     * instantiation, enum constant, variable/field reference, new-variable, raw expression). Shared by every
     * block ({@code AbstractCodeBlock.applyExpressionSelection}) and the overlay config popover so the rewrite
     * path is identical everywhere.
     */
    public static void applySelection(CodeEditorService context, org.eclipse.jdt.core.dom.Expression toReplace, Object selection) {
        if (toReplace == null || selection == null) return;
        if (selection instanceof com.botmaker.studio.palette.ExpressionType expr) {
            context.getCodeEditor().replaceExpression(toReplace, expr);
            return;
        }
        if (selection instanceof ExpressionChoice choice) {
            switch (choice) {
                case ExpressionChoice.Method m -> context.getCodeEditor().replaceWithMethodCall(toReplace, m);
                case ExpressionChoice.Constructor c ->
                        context.getCodeEditor().replaceWithInstantiation(toReplace, c.typeName(), c.paramTypes());
                case ExpressionChoice.EnumConstant en ->
                        context.getCodeEditor().replaceWithEnumConstant(toReplace, en.typeName(), en.constantName());
                case ExpressionChoice.Variable v -> context.getCodeEditor().replaceWithVariable(toReplace, v.variableName());
                case ExpressionChoice.Field f ->
                        context.getCodeEditor().replaceWithFieldReference(toReplace, f.scope(), f.fieldName());
                case ExpressionChoice.NewVariable nv ->
                        context.getCodeEditor().declareVariableBeforeAndReference(toReplace, nv.type(), nv.name());
                case ExpressionChoice.RawExpression rx ->
                        context.getCodeEditor().replaceWithRawExpression(toReplace, rx.code());
            }
        }
    }

    public static ContextMenu createExpressionTypeMenu(
            ResolvedType expectedType,
            boolean constantOnly,
            CodeEditorService context,
            ASTNode contextNode,
            Predicate<ExpressionType> filter,
            Consumer<Object> onSelect) {

        // Bot-first, searchable menu that mirrors the statement menu: a live search box filters a flat list
        // of quick picks (values, variables, enum constants, "new X"); with no query the categorized
        // submenus are shown as before.
        ContextMenu menu = new ContextMenu();

        TextField search = new TextField();
        search.setPromptText("Search…");
        CustomMenuItem searchItem = new CustomMenuItem(search);
        searchItem.setHideOnClick(false);
        menu.getItems().add(searchItem);

        rebuildExpressionItems(menu, "", expectedType, constantOnly, context, contextNode, filter, onSelect);
        search.textProperty().addListener((obs, old, query) ->
                rebuildExpressionItems(menu, query, expectedType, constantOnly, context, contextNode, filter, onSelect));
        menu.setOnShown(e -> search.requestFocus());
        return menu;
    }

    /** Rebuilds the menu body (everything below the search box at index 0) for the current {@code query}. */
    private static void rebuildExpressionItems(ContextMenu menu, String query, ResolvedType expectedType,
                                               boolean constantOnly, CodeEditorService context, ASTNode contextNode,
                                               Predicate<ExpressionType> filter, Consumer<Object> onSelect) {
        menu.getItems().remove(1, menu.getItems().size());

        ProjectState state = (context != null) ? context.getState() : null;
        List<ExpressionType> available = ExpressionCatalog.getForType(expectedType, constantOnly, state);
        if (filter != null) available = available.stream().filter(filter).collect(Collectors.toList());

        String q = query == null ? "" : query.trim().toLowerCase();

        // A CaptureSource / Window slot is picked visually (the SDK CaptureSource is an interface — it must not
        // be offered a `new` constructor). Surface the picker at the top of both the search and categorized views.
        boolean captureSlot = isCaptureSourceType(expectedType);

        // The name argument of Activity.enable("…")/disable("…"): offer the project's defined activity names as a
        // dropdown instead of a free-typed string, so the name always matches a real activity.
        boolean activitySlot = context != null && isActivityNameSlot(contextNode);

        // Active search: a flat, filtered list of the leaf quick picks — no submenus to dig through.
        if (!q.isEmpty()) {
            List<MenuItem> matches = collectSearchableLeaves(available, expectedType, context, contextNode, state, onSelect)
                    .stream()
                    .filter(mi -> mi.getText() != null && mi.getText().toLowerCase().contains(q))
                    .collect(Collectors.toList());
            if (activitySlot) {
                for (String name : context.getProjectAnalyzer().getActivityNames()) {
                    if (name.toLowerCase().contains(q)) matches.add(0, activityNameItem(name, onSelect));
                }
            }
            if (captureSlot && "choose capture source".contains(q)) matches.add(0, captureSourceItem(context, onSelect));
            if (matches.isEmpty()) menu.getItems().add(disabledItem("No matches"));
            else menu.getItems().addAll(matches);
            return;
        }

        if (activitySlot) menu.getItems().add(activityNameSubmenu(context, onSelect));
        if (captureSlot) menu.getItems().add(captureSourceItem(context, onSelect));

        // Parity with the statement menu: lead with a submenu per SDK facade (in SdkApi order), each listing
        // that facade's static members whose return type fits this slot (buildScopeMenu drops empty facades).
        appendSdkFacadeExpressionSubmenus(menu, expectedType, context, onSelect);

        // Default: the categorized view (declaration order of ExpressionCategory is the display order).
        Map<ExpressionCategory, List<ExpressionType>> grouped = available.stream()
                .collect(Collectors.groupingBy(ExpressionType::category));
        for (ExpressionCategory cat : ExpressionCategory.values()) {
            List<ExpressionType> items = grouped.get(cat);
            if (items == null || items.isEmpty()) continue;
            switch (cat) {
                case LITERAL, REFERENCE -> appendReferenceSection(menu, items, expectedType, context, contextNode, onSelect);
                case STRUCTURE -> appendStructureSection(menu, cat, items, expectedType, context, state, onSelect);
                default -> appendOperatorSection(menu, cat, items, onSelect);
            }
        }

        if (menu.getItems().size() == 1) menu.getItems().add(disabledItem("(No options available)"));
    }

    /**
     * Flattens the slot's quick picks into searchable leaf items: plain literals/operators, the visible
     * variables (plus "New &lt;Type&gt; variable…"), enum constants, activities and constructors. Deep
     * "Call Function" scopes are intentionally left to the categorized view (too large to flatten).
     */
    private static List<MenuItem> collectSearchableLeaves(List<ExpressionType> available, ResolvedType expectedType,
                                                          CodeEditorService context, ASTNode contextNode,
                                                          ProjectState state, Consumer<Object> onSelect) {
        List<MenuItem> leaves = new ArrayList<>();
        for (ExpressionType expr : available) {
            if (expr == ExpressionCatalog.VARIABLE) {
                if (contextNode != null) collectMenuLeaves(variableSubmenu(expectedType, context, contextNode, onSelect), leaves);
            } else if (expr == ExpressionCatalog.ACTIVITY) {
                collectMenuLeaves(activitiesSubmenu(expectedType, context, onSelect), leaves);
            } else if (expr == ExpressionCatalog.ENUM_CONSTANT && expectedType.isEnum()) {
                Menu sub = specificEnumSubmenu(expectedType, onSelect);
                if (sub != null) collectMenuLeaves(sub, leaves);
            } else if (expr == ExpressionCatalog.INSTANTIATION && !expectedType.isUnknown() && !isCaptureSourceType(expectedType) && state != null) {
                for (MethodSignature sig : context.getProjectAnalyzer().getConstructors(expectedType.simpleName())) {
                    MenuItem item = new MenuItem("New " + sig);
                    item.setOnAction(e -> onSelect.accept(new ExpressionChoice.Constructor(expectedType.simpleName(), sig.paramTypes())));
                    leaves.add(item);
                }
            } else if (expr == ExpressionCatalog.ENUM_CONSTANT || expr == ExpressionCatalog.FUNCTION_CALL) {
                // Global-enum / function-call fan-outs are left to the categorized view.
            } else {
                leaves.add(createItem(expr, onSelect));
            }
        }
        // Parity with the statement-menu search: flatten every SDK facade's slot-compatible members as
        // "Facade.member" leaves so they are reachable from the flat search list too.
        collectSdkFacadeLeaves(expectedType, context, onSelect, leaves);
        return leaves;
    }

    /**
     * Appends, at the top level of the expression menu, one submenu per {@link SdkApi#MENU_FACADE_CLASSES}
     * facade listing its static members whose return type is compatible with {@code expectedType} — the
     * expression-slot analogue of the statement menu's per-facade submenus. Void-only methods naturally drop
     * out (no return value fits an expression slot), and {@link #buildScopeMenu} returns {@code null} for a
     * facade with nothing compatible, so empty submenus are skipped.
     */
    private static void appendSdkFacadeExpressionSubmenus(ContextMenu menu, ResolvedType expectedType,
                                                          CodeEditorService context, Consumer<Object> onSelect) {
        if (context == null) return;
        ProjectAnalyzer analyzer = context.getProjectAnalyzer();
        if (analyzer == null) return;
        for (String facade : SdkApi.MENU_FACADE_CLASSES) {
            Menu sub = buildScopeMenu(facade, facade, facade, true, expectedType, analyzer, onSelect);
            if (sub != null) menu.getItems().add(sub);
        }
    }

    /** Flattens the SDK-facade expression submenus into "Facade.member" leaves for the flat search view. */
    private static void collectSdkFacadeLeaves(ResolvedType expectedType, CodeEditorService context,
                                               Consumer<Object> onSelect, List<MenuItem> out) {
        if (context == null) return;
        ProjectAnalyzer analyzer = context.getProjectAnalyzer();
        if (analyzer == null) return;
        for (String facade : SdkApi.MENU_FACADE_CLASSES) {
            Menu sub = buildScopeMenu(facade, facade, facade, true, expectedType, analyzer, onSelect);
            if (sub == null) continue;
            List<MenuItem> leaves = new ArrayList<>();
            collectMenuLeaves(sub, leaves);
            for (MenuItem mi : leaves) {
                mi.setText(facade + "." + mi.getText());
                out.add(mi);
            }
        }
    }

    /** Recursively collects the actionable (non-disabled) leaf {@link MenuItem}s under {@code menu}. */
    private static void collectMenuLeaves(Menu menu, List<MenuItem> out) {
        if (menu == null) return;
        for (MenuItem mi : menu.getItems()) {
            if (mi instanceof Menu sub) collectMenuLeaves(sub, out);
            else if (!mi.isDisable() && mi.getText() != null) out.add(mi);
        }
    }

    /** Literals + references: plain items, except VARIABLE / ENUM_CONSTANT / FUNCTION_CALL which fan out into submenus. */
    private static void appendReferenceSection(ContextMenu menu, List<ExpressionType> items, ResolvedType expectedType,
                                               CodeEditorService context, ASTNode contextNode, Consumer<Object> onSelect) {
        if (!menu.getItems().isEmpty()) menu.getItems().add(new SeparatorMenuItem());
        for (ExpressionType expr : items) {
            if (expr == ExpressionCatalog.VARIABLE) {
                if (contextNode != null) menu.getItems().add(variableSubmenu(expectedType, context, contextNode, onSelect));
            } else if (expr == ExpressionCatalog.ACTIVITY) {
                menu.getItems().add(activitiesSubmenu(expectedType, context, onSelect));
            } else if (expr == ExpressionCatalog.ENUM_CONSTANT && expectedType.isEnum()) {
                Menu sub = specificEnumSubmenu(expectedType, onSelect);
                if (sub != null) menu.getItems().add(sub);
            } else if (expr == ExpressionCatalog.ENUM_CONSTANT && (expectedType.isUnknown() || expectedType.simpleName().equals("Object"))) {
                menu.getItems().add(globalEnumSubmenu(context, contextNode, onSelect));
            } else if (expr == ExpressionCatalog.FUNCTION_CALL) {
                menu.getItems().add(functionCallSubmenu(expectedType, context, contextNode, onSelect));
            } else {
                menu.getItems().add(createItem(expr, onSelect));
            }
        }
    }

    /** Structure category: INSTANTIATION expands to the target type's constructors when the type is known. */
    private static void appendStructureSection(ContextMenu menu, ExpressionCategory cat, List<ExpressionType> items,
                                               ResolvedType expectedType, CodeEditorService context, ProjectState state,
                                               Consumer<Object> onSelect) {
        Menu subMenu = new Menu(cat.getLabel());
        subMenu.setGraphic(iconNode(cat.icon()));
        for (ExpressionType expr : items) {
            if (expr == ExpressionCatalog.INSTANTIATION && !expectedType.isUnknown() && !isCaptureSourceType(expectedType) && state != null) {
                for (MethodSignature sig : context.getProjectAnalyzer().getConstructors(expectedType.simpleName())) {
                    MenuItem item = new MenuItem("New " + sig);
                    item.setOnAction(e -> onSelect.accept(new ExpressionChoice.Constructor(expectedType.simpleName(), sig.paramTypes())));
                    subMenu.getItems().add(item);
                }
            } else {
                subMenu.getItems().add(createItem(expr, onSelect));
            }
        }
        if (!subMenu.getItems().isEmpty()) menu.getItems().add(subMenu);
    }

    /** Math / comparison / logic: a flat submenu of plain items. */
    private static void appendOperatorSection(ContextMenu menu, ExpressionCategory cat, List<ExpressionType> items, Consumer<Object> onSelect) {
        Menu subMenu = new Menu(cat.getLabel());
        subMenu.setGraphic(iconNode(cat.icon()));
        for (ExpressionType expr : items) subMenu.getItems().add(createItem(expr, onSelect));
        menu.getItems().add(subMenu);
    }

    private static Menu variableSubmenu(ResolvedType expectedType, CodeEditorService context, ASTNode contextNode, Consumer<Object> onSelect) {
        Menu varMenu = new Menu("Variables");
        List<ProjectAnalyzer.VariableOption> vars = context.getProjectAnalyzer().getVisibleVariables(contextNode, expectedType);

        // "New <Type> variable…": declare a fresh local of the slot's type and reference it. This is the
        // generic way to create (e.g.) a Direction variable inline — no per-type catalog entry required.
        if (expectedType != null && !expectedType.isUnknown()) {
            String name = freshVariableName(expectedType, vars);
            MenuItem create = new MenuItem("New " + expectedType.simpleName() + " variable…");
            create.setOnAction(e -> onSelect.accept(new ExpressionChoice.NewVariable(expectedType, name)));
            varMenu.getItems().add(create);
            varMenu.getItems().add(new SeparatorMenuItem());
        }

        if (vars.isEmpty()) {
            varMenu.getItems().add(disabledItem("(No existing variables)"));
        } else {
            for (ProjectAnalyzer.VariableOption var : vars) {
                MenuItem item = new MenuItem(var.name() + (var.isField() ? " (Field)" : ""));
                item.setOnAction(e -> onSelect.accept(new ExpressionChoice.Variable(var.name())));
                varMenu.getItems().add(item);
            }
        }
        return varMenu;
    }

    /** A lowercase-simple-name-based variable name (e.g. {@code direction}), suffixed to avoid clashing with
     *  an existing visible variable ({@code direction2}, …). */
    private static String freshVariableName(ResolvedType type, List<ProjectAnalyzer.VariableOption> existing) {
        String simple = type.leafType().simpleName();
        String base = simple.isEmpty() ? "value" : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        Set<String> taken = existing.stream().map(ProjectAnalyzer.VariableOption::name).collect(Collectors.toSet());
        if (!taken.contains(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + i;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    /** "Activities": the project's global config variables whose type is assignment-compatible with the slot. */
    private static Menu activitiesSubmenu(ResolvedType expectedType, CodeEditorService context, Consumer<Object> onSelect) {
        Menu menu = new Menu("Activities");
        List<ActivityVariable> activities = context.getProjectAnalyzer().getActivityVariables(expectedType);
        if (activities.isEmpty()) {
            menu.getItems().add(disabledItem("(No activities)"));
        } else {
            for (ActivityVariable a : activities) {
                MenuItem item = new MenuItem(a.name() + " (" + a.type().displayName() + ")");
                item.setOnAction(e -> onSelect.accept(new ExpressionChoice.Field("Activities", a.name())));
                menu.getItems().add(item);
            }
        }
        return menu;
    }

    /**
     * Whether {@code contextNode} is the first (name) argument of an {@code Activity.enable(...)} /
     * {@code Activity.disable(...)} call — the slot the activity-name dropdown targets. Matches on the AST
     * (receiver {@code Activity} + method name + arg position), independent of type resolution, so it works even
     * before the SDK jar's parameter types resolve. Facades are referenced by simple name (not import), as
     * elsewhere in the palette.
     */
    private static boolean isActivityNameSlot(ASTNode contextNode) {
        if (contextNode == null || !(contextNode.getParent() instanceof MethodInvocation call)) {
            return false;
        }
        String method = call.getName().getIdentifier();
        if (!method.equals("enable") && !method.equals("disable")) {
            return false;
        }
        if (!(call.getExpression() instanceof SimpleName receiver) || !receiver.getIdentifier().equals("Activity")) {
            return false;
        }
        List<?> args = call.arguments();
        return !args.isEmpty() && args.get(0) == contextNode;
    }

    /** "Activity name": the project's defined activity names, each inserted as a string literal. */
    private static Menu activityNameSubmenu(CodeEditorService context, Consumer<Object> onSelect) {
        Menu menu = new Menu("Activity name");
        List<String> names = context.getProjectAnalyzer().getActivityNames();
        if (names.isEmpty()) {
            menu.getItems().add(disabledItem("(No activities defined)"));
        } else {
            for (String name : names) {
                menu.getItems().add(activityNameItem(name, onSelect));
            }
        }
        return menu;
    }

    /** A menu item that inserts {@code name} as a quoted string literal (activity names are valid identifiers). */
    private static MenuItem activityNameItem(String name, Consumer<Object> onSelect) {
        MenuItem item = new MenuItem(name);
        item.setOnAction(e -> onSelect.accept(new ExpressionChoice.RawExpression("\"" + name + "\"")));
        return item;
    }

    private static Menu specificEnumSubmenu(ResolvedType enumType, Consumer<Object> onSelect) {
        List<String> constants = enumType.enumConstants();
        if (constants.isEmpty()) return null;
        Menu enumMenu = new Menu("Enum Values");
        for (String constName : constants) {
            MenuItem item = new MenuItem(constName);
            item.setOnAction(e -> onSelect.accept(new ExpressionChoice.EnumConstant(enumType.simpleName(), constName)));
            enumMenu.getItems().add(item);
        }
        return enumMenu;
    }

    private static Menu globalEnumSubmenu(CodeEditorService context, ASTNode contextNode, Consumer<Object> onSelect) {
        Menu enumRoot = new Menu("Enums");
        List<ResolvedType> enums = context.getProjectAnalyzer().getAvailableTypes(contextNode)
                .stream().filter(ResolvedType::isEnum).toList();
        for (ResolvedType enumType : enums) {
            Menu enumSub = new Menu(enumType.simpleName());
            for (String c : enumType.enumConstants()) {
                MenuItem item = new MenuItem(c);
                item.setOnAction(e -> onSelect.accept(new ExpressionChoice.EnumConstant(enumType.simpleName(), c)));
                enumSub.getItems().add(item);
            }
            enumRoot.getItems().add(enumSub);
        }
        return enumRoot;
    }

    /**
     * "Call Function": the enclosing class's own methods (local call), one submenu per visible non-primitive
     * variable (instance methods) and per in-scope static class, plus a lazily-built "Library (static)" group
     * covering every external-jar class with public static returning methods.
     */
    private static Menu functionCallSubmenu(ResolvedType expectedType, CodeEditorService context, ASTNode contextNode, Consumer<Object> onSelect) {
        Menu functionMenu = new Menu("Call Function");
        ProjectAnalyzer analyzer = (context != null) ? context.getProjectAnalyzer() : null;
        VariableScopeVisitor.NodeScope scope = (analyzer != null && contextNode != null)
                ? analyzer.getAvailableScopes(contextNode) : null;

        List<Menu> scopeMenus = new ArrayList<>();

        // 1. Enclosing class's own methods — local call (no receiver).
        String enclosingClass = enclosingClassName(contextNode);
        if (analyzer != null && enclosingClass != null) {
            addIfNonNull(scopeMenus, buildScopeMenu("This (" + enclosingClass + ")", "", enclosingClass, false, expectedType, analyzer, onSelect));
        }

        // 2. Visible variables (instance members) + 3. in-scope static classes. Each submenu is dropped when
        // it has no members compatible with the slot type (buildScopeMenu returns null).
        if (scope != null) {
            for (IVariableBinding var : scope.variables()) {
                if (!ProjectAnalyzer.isUserVariable(var.getName())) continue; // hide args/this/super/scanner/…
                ResolvedType varType = ResolvedType.of(var.getType());
                if (varType.isArray()) continue;                  // arrays have no meaningful instance members
                if (varType.isPrimitive() && !varType.isString()) continue;
                addIfNonNull(scopeMenus, buildScopeMenu(var.getName(), var.getName(), var.getType().getQualifiedName(), false, expectedType, analyzer, onSelect));
            }
            for (ITypeBinding type : scope.types()) {
                if (type.getName().equals(enclosingClass)) continue; // already covered as "This (...)"
                addIfNonNull(scopeMenus, buildScopeMenu(type.getName(), type.getName(), type.getQualifiedName(), true, expectedType, analyzer, onSelect));
            }
        }

        if (scopeMenus.isEmpty()) functionMenu.getItems().add(disabledItem("(No visible objects/classes)"));
        else functionMenu.getItems().addAll(scopeMenus);

        // 4. External-jar statics, grouped by package — populated lazily (the list can be very large).
        if (analyzer != null && analyzer.getLibraryIndex() != null) {
            Menu libMenu = new Menu("Library (static)");
            libMenu.getItems().add(disabledItem("Loading…"));
            libMenu.setOnShowing(ev -> populateLibraryStatics(libMenu, expectedType, analyzer, onSelect));
            functionMenu.getItems().add(libMenu);
        }
        return functionMenu;
    }

    /** Simple name of the {@link TypeDeclaration} enclosing {@code node}, or {@code null}. */
    private static String enclosingClassName(ASTNode node) {
        for (ASTNode cur = node; cur != null; cur = cur.getParent()) {
            if (cur instanceof TypeDeclaration td) return td.getName().getIdentifier();
        }
        return null;
    }

    /** Lazily fills the "Library (static)" submenu, grouped by package; idempotent. */
    private static void populateLibraryStatics(Menu libMenu, ResolvedType expectedType, ProjectAnalyzer analyzer, Consumer<Object> onSelect) {
        if (Boolean.TRUE.equals(libMenu.getProperties().get("loaded"))) return;
        libMenu.getProperties().put("loaded", true);
        libMenu.getItems().clear();

        Map<String, List<ClassInfo>> byPackage = new TreeMap<>();
        for (ClassInfo ci : analyzer.getLibraryIndex().getStaticUtilityTypes()) {
            // SDK facades are intentionally omitted — they're reached only through the curated Vision
            // palette blocks, so there's a single access path (see BlockCatalog / MethodInvocationBlock).
            if (SdkApi.isFacadeClass(ci.getSimpleName())) continue;
            byPackage.computeIfAbsent(ci.getPackageName() == null ? "" : ci.getPackageName(),
                    k -> new ArrayList<>()).add(ci);
        }
        byPackage.forEach((pkg, classes) -> {
            Menu pkgMenu = new Menu(pkg.isEmpty() ? "(default)" : pkg);
            classes.stream()
                    .sorted(java.util.Comparator.comparing(ClassInfo::getSimpleName))
                    .forEach(ci -> addIfNonNull(pkgMenu.getItems(),
                            buildScopeMenu(ci.getSimpleName(), ci.getSimpleName(), ci.getName(), true, expectedType, analyzer, onSelect)));
            if (!pkgMenu.getItems().isEmpty()) libMenu.getItems().add(pkgMenu); // skip jars/packages with nothing compatible
        });
        if (libMenu.getItems().isEmpty()) libMenu.getItems().add(disabledItem("(None compatible)"));
    }

    private static <T> void addIfNonNull(List<T> list, T item) {
        if (item != null) list.add(item);
    }

    private static MenuItem disabledItem(String text) {
        MenuItem item = new MenuItem(text);
        item.setDisable(true);
        return item;
    }

    /** True when a slot expects the SDK's {@code CaptureSource} (or a {@code Window} used as one). */
    private static boolean isCaptureSourceType(ResolvedType expectedType) {
        if (expectedType == null || expectedType.isUnknown()) return false;
        String name = expectedType.leafType().simpleName();
        return "CaptureSource".equals(name) || "Window".equals(name);
    }

    /** The "Choose capture source…" entry: opens the visual picker and emits the helper-call snippet. */
    private static MenuItem captureSourceItem(CodeEditorService context, Consumer<Object> onSelect) {
        MenuItem item = new MenuItem("🎯 Choose capture source…");
        item.setOnAction(e -> new CaptureSourcePicker(null, true).showAndWait()
                .ifPresent(sel -> onSelect.accept(new ExpressionChoice.RawExpression(captureSourceCode(context, sel)))));
        return item;
    }

    /**
     * Maps a picker {@link CaptureSourcePicker.Selection} to an inline, fully-qualified capture-source
     * expression (see {@link com.botmaker.studio.project.capture.CaptureExpr}) that resolves from any file
     * without import management: {@code …CaptureSource.desktop()} / {@code …CaptureSource.monitor(i)} /
     * {@code …CaptureSource.window("t")}, optionally narrowed with a trailing {@code .region(new Rect(...))}.
     * "Project default" is snapshotted to the current default target.
     */
    private static String captureSourceCode(CodeEditorService context, CaptureSourcePicker.Selection sel) {
        return switch (sel) {
            case CaptureSourcePicker.Selection.ProjectDefault ignored -> com.botmaker.studio.project.capture.CaptureExpr.of(
                    com.botmaker.studio.services.ProjectSettingsService.forProject(context).defaultTarget());
            case CaptureSourcePicker.Selection.Concrete c ->
                    com.botmaker.studio.project.capture.CaptureExpr.of(c.target(), c.region());
        };
    }

    /**
     * Creates the statement insert menu. A search box filters a flat list across every insertable block — the
     * language/structure blocks <em>and</em> every SDK facade method; with no query the menu leads with a submenu
     * per class in {@link SdkApi#FACADE_CLASSES} order (methods discovered at runtime via {@code ProjectAnalyzer}),
     * followed by the language-block category submenus, with the bot-{@code Control} group last.
     *
     * @param analyzer resolves each facade's static methods; may be {@code null} (headless / no project resolved),
     *                 in which case only the language blocks are shown.
     */
    public static ContextMenu createStatementMenu(ProjectAnalyzer analyzer, Consumer<BlockType> onSelection) {
        ContextMenu menu = new ContextMenu();

        TextField search = new TextField();
        search.setPromptText("Search blocks…");
        CustomMenuItem searchItem = new CustomMenuItem(search);
        searchItem.setHideOnClick(false);
        menu.getItems().add(searchItem);

        rebuildStatementItems(menu, "", analyzer, onSelection);
        search.textProperty().addListener((obs, old, query) -> rebuildStatementItems(menu, query, analyzer, onSelection));
        menu.setOnShown(e -> search.requestFocus());

        return menu;
    }

    /**
     * Language/structure block categories in statement-menu display order — the SDK facade calls are pulled out
     * into their own generated submenus (see {@link #rebuildStatementItems}), so what remains here is the general
     * programming vocabulary. {@link BlockCategory#CONTROL} (enable/disable activity, stop bot, break/continue/
     * return) is placed last as a clearly-separated group.
     */
    private static final List<BlockCategory> LANGUAGE_CATEGORY_ORDER = List.of(
            BlockCategory.FLOW, BlockCategory.LOOPS, BlockCategory.VARIABLES, BlockCategory.BOT_VARIABLE,
            BlockCategory.FUNCTIONS, BlockCategory.OUTPUT, BlockCategory.INPUT, BlockCategory.GAME,
            BlockCategory.UTILITY, BlockCategory.CONTROL);

    /** Rebuilds the menu body (everything below the search box at index 0) for the current search {@code query}. */
    private static void rebuildStatementItems(ContextMenu menu, String query, ProjectAnalyzer analyzer,
                                              Consumer<BlockType> onSelection) {
        menu.getItems().remove(1, menu.getItems().size());

        String q = query == null ? "" : query.trim().toLowerCase();

        // Active search: flat, filtered list across every block — the language/structure blocks and every SDK
        // facade method — no submenus to dig through.
        if (!q.isEmpty()) {
            List<MenuItem> matches = new ArrayList<>();
            for (BlockType b : languageBlocks()) {
                if (b.displayName().toLowerCase().contains(q)) matches.add(statementItem(b, onSelection));
            }
            for (BlockType b : sdkCallBlocks(analyzer)) {
                if (b.displayName().toLowerCase().contains(q)) matches.add(statementItem(b, onSelection));
            }
            if (matches.isEmpty()) menu.getItems().add(disabledItem("No matching blocks"));
            else menu.getItems().addAll(matches);
            return;
        }

        // Default: one submenu per SDK facade class (in SdkApi order), enumerating that class's static methods.
        for (String facade : SdkApi.MENU_FACADE_CLASSES) {
            Menu sub = sdkFacadeSubmenu(facade, analyzer, onSelection);
            if (sub != null) menu.getItems().add(sub);
        }

        // Then the language/structure block categories (SDK-facade calls excluded — reached via the submenus above).
        if (menu.getItems().size() > 1) menu.getItems().add(new SeparatorMenuItem());
        Map<BlockCategory, List<BlockType>> grouped = languageBlocks().stream()
                .collect(Collectors.groupingBy(BlockType::category, LinkedHashMap::new, Collectors.toList()));
        for (BlockCategory category : LANGUAGE_CATEGORY_ORDER) {
            addCategoryMenu(menu, category, grouped, onSelection);
        }

        if (menu.getItems().size() == 1) menu.getItems().add(disabledItem("(No blocks available)"));
    }

    /**
     * The insertable language/structure blocks: every {@link BlockCatalog#all()} entry except the SDK-facade
     * calls (a {@link BlockType.LibraryCall} / {@link BlockType.LambdaCall} on a {@link SdkApi} facade), which are
     * offered through the generated per-class submenus instead.
     */
    private static List<BlockType> languageBlocks() {
        return BlockCatalog.all().stream().filter(b -> !isSdkFacadeCall(b)).collect(Collectors.toList());
    }

    private static boolean isSdkFacadeCall(BlockType block) {
        return switch (block) {
            case BlockType.LibraryCall l -> SdkApi.isFacadeClass(l.className());
            case BlockType.LambdaCall l -> SdkApi.isFacadeClass(l.className());
            default -> false;
        };
    }

    /**
     * A submenu of {@code facade}'s static methods (one entry per distinct method name — overloads collapse, and
     * the default overload is chosen at insert time by {@code StatementFactory}). Returns {@code null} when the
     * analyzer is absent or the facade resolves no static methods (e.g. the SDK jar isn't on the classpath yet).
     */
    private static Menu sdkFacadeSubmenu(String facade, ProjectAnalyzer analyzer, Consumer<BlockType> onSelection) {
        if (analyzer == null) return null;
        Menu sub = new Menu(facade);
        for (String method : facadeMethodNames(facade, analyzer)) {
            sub.getItems().add(statementItem(sdkCall(facade, method, method), onSelection));
        }
        return sub.getItems().isEmpty() ? null : sub;
    }

    /** Every SDK facade method as a flat list of class-qualified statement blocks, for the search view. */
    private static List<BlockType> sdkCallBlocks(ProjectAnalyzer analyzer) {
        List<BlockType> out = new ArrayList<>();
        if (analyzer == null) return out;
        for (String facade : SdkApi.MENU_FACADE_CLASSES) {
            for (String method : facadeMethodNames(facade, analyzer)) {
                out.add(sdkCall(facade, method, facade + "." + method));
            }
        }
        return out;
    }

    /** Distinct static method names of {@code facade}, sorted — the entries of its statement submenu. */
    private static List<String> facadeMethodNames(String facade, ProjectAnalyzer analyzer) {
        return analyzer.getMethods(facade, true).stream()
                .map(MethodSignature::name)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /** A synthetic {@code facade.method(<defaults>)} statement block; args are seeded from the resolved overload. */
    private static BlockType sdkCall(String facade, String method, String displayName) {
        return new BlockType.LibraryCall("SDK_" + facade + "_" + method, displayName, BlockCategory.INPUT,
                facade, method, List.of());
    }

    private static void addCategoryMenu(ContextMenu menu, BlockCategory category,
                                        Map<BlockCategory, List<BlockType>> grouped, Consumer<BlockType> onSelection) {
        List<BlockType> blocks = grouped.get(category);
        if (blocks == null || blocks.isEmpty()) return;
        Menu categoryMenu = new Menu(category.getLabel());
        categoryMenu.setGraphic(iconNode(category.icon()));
        for (BlockType block : blocks) categoryMenu.getItems().add(statementItem(block, onSelection));
        menu.getItems().add(categoryMenu);
    }

    private static MenuItem statementItem(BlockType block, Consumer<BlockType> onSelection) {
        MenuItem item = new MenuItem(block.displayName());
        item.setGraphic(iconNode(block.category().icon()));
        item.setOnAction(e -> onSelection.accept(block));
        return item;
    }

    /**
     * Submenu of the type-compatible members (methods + readable fields) of {@code typeName}. {@code label} is
     * the menu's display text while {@code scope} is the AST receiver — they differ for the enclosing class
     * ("This (Foo)" labelled, {@code scope=""} so the reference has no receiver; fields are then skipped since
     * a bare receiver-less field isn't offered here). Returns {@code null} when nothing is compatible, so the
     * caller can drop the whole scope/jar entry rather than show an empty submenu.
     */
    private static Menu buildScopeMenu(String label, String scope, String typeName, boolean isStatic,
                                       ResolvedType expectedType, ProjectAnalyzer analyzer, Consumer<Object> onSelect) {
        Menu scopeMenu = new Menu(label);

        // Methods (grouped by name; overloads nest one level).
        Map<String, List<MethodSignature>> grouped = analyzer.getMethods(typeName, isStatic).stream()
                .filter(m -> m.returnsCompatibleWith(expectedType))
                .collect(Collectors.groupingBy(MethodSignature::name));
        grouped.keySet().stream().sorted().forEach(mName -> {
            List<MethodSignature> sigs = grouped.get(mName);
            if (sigs.size() == 1) {
                MethodSignature sig = sigs.getFirst();
                MenuItem item = new MenuItem(mName);
                item.setOnAction(e -> onSelect.accept(new ExpressionChoice.Method(scope, mName, sig.paramTypes(), isStatic)));
                scopeMenu.getItems().add(item);
            } else {
                Menu overloadMenu = new Menu(mName);
                for (MethodSignature sig : sigs) {
                    MenuItem sigItem = new MenuItem(sig.toString());
                    sigItem.setOnAction(e -> onSelect.accept(new ExpressionChoice.Method(scope, mName, sig.paramTypes(), isStatic)));
                    overloadMenu.getItems().add(sigItem);
                }
                scopeMenu.getItems().add(overloadMenu);
            }
        });

        // Fields (static constants for class scopes, instance members for variable scopes). Needs a receiver.
        if (!scope.isEmpty()) {
            List<ProjectAnalyzer.FieldOption> fields = analyzer.getFields(typeName, isStatic).stream()
                    .filter(f -> MethodSignature.typeSatisfies(f.type(), expectedType))
                    .toList();
            if (!fields.isEmpty() && !scopeMenu.getItems().isEmpty()) scopeMenu.getItems().add(new SeparatorMenuItem());
            for (ProjectAnalyzer.FieldOption f : fields) {
                MenuItem item = new MenuItem(f.name() + " : " + f.type().simpleName());
                item.setOnAction(e -> onSelect.accept(new ExpressionChoice.Field(scope, f.name())));
                scopeMenu.getItems().add(item);
            }
        }

        return scopeMenu.getItems().isEmpty() ? null : scopeMenu;
    }

    private static MenuItem createItem(ExpressionType expr, Consumer<Object> onSelect) {
        MenuItem item = new MenuItem(expr.displayName());
        item.setGraphic(iconNode(expr.icon()));
        item.setOnAction(e -> onSelect.accept(expr));
        return item;
    }

    /**
     * A menu item's icon.
     *
     * <p>Set as the item's <b>graphic</b>, never folded into its text: both this menu and the statement menu
     * filter their search on {@code getText()}, so an icon in the label would be searchable noise ("+" would
     * match every arithmetic entry by its glyph rather than its name). Fixed-width and centred so the labels
     * beside them still line up into a column.
     */
    private static Node iconNode(String glyph) {
        Label label = new Label(glyph);
        label.setStyle("-fx-font-family: 'Segoe UI Symbol'; -fx-text-fill: #555;");
        label.setMinWidth(16);
        label.setAlignment(javafx.geometry.Pos.CENTER);
        return label;
    }
}
