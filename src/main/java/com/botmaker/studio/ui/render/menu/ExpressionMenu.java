package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.ui.app.capture.CaptureSourcePicker;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.palette.ExpressionCategory;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.util.MethodSignature;
import com.botmaker.studio.util.VariableScopeVisitor;
import io.github.classgraph.ClassInfo;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The type-aware menus for filling an expression <em>slot</em>: what value can go here, and what type does a
 * declaration have. Split out of the former {@code ExpressionMenuFactory} alongside {@link StatementMenu} (what
 * can be <em>inserted</em> here); their shared plumbing lives in {@link MenuBuilders}.
 *
 * <p>This is the domain-heavy counterpart to the pure widget factories in
 * {@link com.botmaker.studio.ui.render.components.BlockUIComponents}: it reads {@link ProjectAnalyzer} for visible
 * variables, callable methods, constructors and enum constants, and emits the user's pick either as an
 * {@link ExpressionType} or an {@link ExpressionChoice}.
 */
public final class ExpressionMenu {

    private ExpressionMenu() {}

    /**
     * Wires {@code label} as a clickable type selector: hand cursor, a "click to change" {@code tooltip}, and a
     * click that opens {@link #showTypeMenu}. {@code currentType} is resolved lazily at click time, since some
     * callers (parameter / enum blocks) only know the type then. Shared by the variable / field / parameter /
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
        MenuBuilders.withSearch(menu, "Search types…", (m, query) ->
                rebuildTypeItems(m, query, currentType, context, contextNode, allowArrayDims, allowVoid, onTypeSelected));
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /** Rebuilds the type menu body (everything below the search box at index 0) for the current {@code query}. */
    private static void rebuildTypeItems(ContextMenu menu, String query, ResolvedType currentType,
                                         CodeEditorService context, ASTNode contextNode, boolean allowArrayDims,
                                         boolean allowVoid, Consumer<ResolvedType> onPick) {
        MenuBuilders.clearBody(menu);
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
            if (matches.isEmpty()) menu.getItems().add(MenuBuilders.disabledItem("No matches"));
            else menu.getItems().addAll(matches);
            return;
        }

        if (!primitives.isEmpty()) {
            menu.getItems().add(MenuBuilders.sectionHeader("PRIMITIVES"));
            for (ResolvedType type : primitives) menu.getItems().add(typeMenuItem(type, dims, onPick));
        }
        if (!classes.isEmpty()) {
            if (menu.getItems().size() > 1) menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(MenuBuilders.sectionHeader("CLASSES"));
            for (ResolvedType type : classes) menu.getItems().add(typeMenuItem(type, dims, onPick));
        }
        if (menu.getItems().size() == 1) menu.getItems().add(MenuBuilders.disabledItem("(No types available)"));
    }

    /** A type entry that preserves the current array depth ({@code dims}) when picked. */
    private static MenuItem typeMenuItem(ResolvedType baseType, int dims, Consumer<ResolvedType> onSelect) {
        MenuItem item = new MenuItem(baseType.simpleName());
        item.setOnAction(e -> onSelect.accept(dims > 0 ? baseType.asArray(dims) : baseType));
        return item;
    }

    /**
     * Applies a pick from {@link #create} to {@code toReplace}: a plain {@link ExpressionType} swaps in a fresh
     * expression block, an {@link ExpressionChoice} drives the richer rewrite (method call, instantiation, enum
     * constant, variable/field reference, new-variable, raw expression). Shared by every block
     * ({@code AbstractCodeBlock.applyExpressionSelection}) and the overlay config popover so the rewrite path is
     * identical everywhere.
     */
    public static void applySelection(CodeEditorService context, org.eclipse.jdt.core.dom.Expression toReplace, Object selection) {
        if (toReplace == null || selection == null) return;
        if (selection instanceof ExpressionType expr) {
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

    /**
     * The bot-first, searchable menu for an expression slot: a live search box filters a flat list of quick picks
     * (values, variables, enum constants, "new X", SDK facade members); with no query the categorized submenus
     * are shown.
     */
    public static ContextMenu create(
            ResolvedType expectedType,
            boolean constantOnly,
            CodeEditorService context,
            ASTNode contextNode,
            Predicate<ExpressionType> filter,
            Consumer<Object> onSelect) {

        ContextMenu menu = new ContextMenu();
        MenuBuilders.withSearch(menu, "Search…", (m, query) ->
                rebuildExpressionItems(m, query, expectedType, constantOnly, context, contextNode, filter, onSelect));
        return menu;
    }

    /** Rebuilds the menu body (everything below the search box at index 0) for the current {@code query}. */
    private static void rebuildExpressionItems(ContextMenu menu, String query, ResolvedType expectedType,
                                               boolean constantOnly, CodeEditorService context, ASTNode contextNode,
                                               Predicate<ExpressionType> filter, Consumer<Object> onSelect) {
        MenuBuilders.clearBody(menu);

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
            if (matches.isEmpty()) menu.getItems().add(MenuBuilders.disabledItem("No matches"));
            else menu.getItems().addAll(matches);
            return;
        }

        if (activitySlot) menu.getItems().add(activityNameSubmenu(context, onSelect));
        if (captureSlot) menu.getItems().add(captureSourceItem(context, onSelect));

        // Parity with the statement menu: lead with a submenu per SDK facade (in SdkApi order), each listing
        // that facade's static members whose return type fits this slot (buildScopeMenu drops empty facades).
        MenuBuilders.appendSdkFacadeExpressionSubmenus(menu, expectedType, context, onSelect);

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

        if (menu.getItems().size() == 1) menu.getItems().add(MenuBuilders.disabledItem("(No options available)"));
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
                if (contextNode != null) MenuBuilders.collectMenuLeaves(variableSubmenu(expectedType, context, contextNode, onSelect), leaves);
            } else if (expr == ExpressionCatalog.ACTIVITY) {
                MenuBuilders.collectMenuLeaves(activitiesSubmenu(expectedType, context, onSelect), leaves);
            } else if (expr == ExpressionCatalog.ENUM_CONSTANT && expectedType.isEnum()) {
                Menu sub = specificEnumSubmenu(expectedType, onSelect);
                if (sub != null) MenuBuilders.collectMenuLeaves(sub, leaves);
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
        MenuBuilders.collectSdkFacadeLeaves(expectedType, context, onSelect, leaves);
        return leaves;
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
        Menu subMenu = MenuIcons.decorate(new Menu(cat.getLabel()), MenuIcons.iconFor(cat));
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
        Menu subMenu = MenuIcons.decorate(new Menu(cat.getLabel()), MenuIcons.iconFor(cat));
        for (ExpressionType expr : items) subMenu.getItems().add(createItem(expr, onSelect));
        menu.getItems().add(subMenu);
    }

    private static Menu variableSubmenu(ResolvedType expectedType, CodeEditorService context, ASTNode contextNode, Consumer<Object> onSelect) {
        Menu varMenu = MenuIcons.decorate(new Menu("Variables"), MenuIcons.VARIABLES);
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
            varMenu.getItems().add(MenuBuilders.disabledItem("(No existing variables)"));
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
        Menu menu = MenuIcons.decorate(new Menu("Activities"), MenuIcons.ACTIVITIES);
        List<ActivityVariable> activities = context.getProjectAnalyzer().getActivityVariables(expectedType);
        if (activities.isEmpty()) {
            menu.getItems().add(MenuBuilders.disabledItem("(No activities)"));
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
        Menu menu = MenuIcons.decorate(new Menu("Activity name"), MenuIcons.ACTIVITY_NAME);
        List<String> names = context.getProjectAnalyzer().getActivityNames();
        if (names.isEmpty()) {
            menu.getItems().add(MenuBuilders.disabledItem("(No activities defined)"));
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
        Menu enumMenu = MenuIcons.decorate(new Menu("Enum Values"), MenuIcons.ENUM);
        for (String constName : constants) {
            MenuItem item = new MenuItem(constName);
            item.setOnAction(e -> onSelect.accept(new ExpressionChoice.EnumConstant(enumType.simpleName(), constName)));
            enumMenu.getItems().add(item);
        }
        return enumMenu;
    }

    private static Menu globalEnumSubmenu(CodeEditorService context, ASTNode contextNode, Consumer<Object> onSelect) {
        Menu enumRoot = MenuIcons.decorate(new Menu("Enums"), MenuIcons.ENUM);
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
        Menu functionMenu = MenuIcons.decorate(new Menu("Call Function"), MenuIcons.FUNCTION_CALL);
        ProjectAnalyzer analyzer = (context != null) ? context.getProjectAnalyzer() : null;
        VariableScopeVisitor.NodeScope scope = (analyzer != null && contextNode != null)
                ? analyzer.getAvailableScopes(contextNode) : null;

        List<Menu> scopeMenus = new ArrayList<>();

        // 1. Enclosing class's own methods — local call (no receiver).
        String enclosingClass = enclosingClassName(contextNode);
        if (analyzer != null && enclosingClass != null) {
            MenuBuilders.addIfNonNull(scopeMenus, MenuBuilders.buildScopeMenu(
                    "This (" + enclosingClass + ")", "", enclosingClass, false, expectedType, analyzer, onSelect));
        }

        // 2. Visible variables (instance members) + 3. in-scope static classes. Each submenu is dropped when
        // it has no members compatible with the slot type (buildScopeMenu returns null).
        if (scope != null) {
            for (IVariableBinding var : scope.variables()) {
                if (!ProjectAnalyzer.isUserVariable(var.getName())) continue; // hide args/this/super/scanner/…
                ResolvedType varType = ResolvedType.of(var.getType());
                if (varType.isArray()) continue;                  // arrays have no meaningful instance members
                if (varType.isPrimitive() && !varType.isString()) continue;
                MenuBuilders.addIfNonNull(scopeMenus, MenuBuilders.buildScopeMenu(
                        var.getName(), var.getName(), var.getType().getQualifiedName(), false, expectedType, analyzer, onSelect));
            }
            for (ITypeBinding type : scope.types()) {
                if (type.getName().equals(enclosingClass)) continue; // already covered as "This (...)"
                MenuBuilders.addIfNonNull(scopeMenus, MenuBuilders.buildScopeMenu(
                        type.getName(), type.getName(), type.getQualifiedName(), true, expectedType, analyzer, onSelect));
            }
        }

        if (scopeMenus.isEmpty()) functionMenu.getItems().add(MenuBuilders.disabledItem("(No visible objects/classes)"));
        else functionMenu.getItems().addAll(scopeMenus);

        // 4. External-jar statics, grouped by package — populated lazily (the list can be very large).
        if (analyzer != null && analyzer.getLibraryIndex() != null) {
            Menu libMenu = MenuIcons.decorate(new Menu("Library (static)"), MenuIcons.LIBRARY);
            libMenu.getItems().add(MenuBuilders.disabledItem("Loading…"));
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
            if (com.botmaker.studio.palette.SdkApi.isFacadeClass(ci.getSimpleName())) continue;
            byPackage.computeIfAbsent(ci.getPackageName() == null ? "" : ci.getPackageName(),
                    k -> new ArrayList<>()).add(ci);
        }
        byPackage.forEach((pkg, classes) -> {
            Menu pkgMenu = new Menu(pkg.isEmpty() ? "(default)" : pkg);
            classes.stream()
                    .sorted(Comparator.comparing(ClassInfo::getSimpleName))
                    .forEach(ci -> MenuBuilders.addIfNonNull(pkgMenu.getItems(), MenuBuilders.buildScopeMenu(
                            ci.getSimpleName(), ci.getSimpleName(), ci.getName(), true, expectedType, analyzer, onSelect)));
            if (!pkgMenu.getItems().isEmpty()) libMenu.getItems().add(pkgMenu); // skip jars/packages with nothing compatible
        });
        if (libMenu.getItems().isEmpty()) libMenu.getItems().add(MenuBuilders.disabledItem("(None compatible)"));
    }

    /** True when a slot expects the SDK's {@code CaptureSource} (or a {@code Window} used as one). */
    private static boolean isCaptureSourceType(ResolvedType expectedType) {
        if (expectedType == null || expectedType.isUnknown()) return false;
        String name = expectedType.leafType().simpleName();
        return "CaptureSource".equals(name) || "Window".equals(name);
    }

    /** The "Choose capture source…" entry: opens the visual picker and emits the helper-call snippet. */
    private static MenuItem captureSourceItem(CodeEditorService context, Consumer<Object> onSelect) {
        MenuItem item = MenuIcons.decorate(new MenuItem("Choose capture source…"), MenuIcons.CAPTURE);
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

    private static MenuItem createItem(ExpressionType expr, Consumer<Object> onSelect) {
        MenuItem item = MenuIcons.decorate(new MenuItem(expr.displayName()), expr.icon());
        item.setOnAction(e -> onSelect.accept(expr));
        return item;
    }
}
