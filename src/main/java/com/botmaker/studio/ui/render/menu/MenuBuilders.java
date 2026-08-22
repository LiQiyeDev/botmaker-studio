package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The plumbing {@link StatementMenu} and {@link ExpressionMenu} genuinely share: the live search box and its
 * rebuild-on-type wiring, section headers and disabled placeholders, leaf collection, and the type-compatible
 * member submenu ({@link #buildScopeMenu}) both menus build for SDK facades and scopes.
 *
 * <p>Sibling of {@link MenuComponents}, which handles the simpler "turn a list into a flat menu" case with no
 * domain knowledge; this one reads {@link ProjectAnalyzer}.
 */
final class MenuBuilders {

    private MenuBuilders() {}

    /**
     * Installs the standard searchable-menu shape on {@code menu}: a non-hiding {@link TextField} at index 0,
     * an initial body build with an empty query, a rebuild on every keystroke, and focus on the field when the
     * menu shows. {@code rebuild} owns everything below index 0 (it is expected to start by clearing it).
     */
    static void withSearch(ContextMenu menu, String promptText, BiConsumer<ContextMenu, String> rebuild) {
        TextField search = new TextField();
        search.setPromptText(promptText);
        CustomMenuItem searchItem = new CustomMenuItem(search);
        searchItem.setHideOnClick(false);
        menu.getItems().add(searchItem);

        rebuild.accept(menu, "");
        search.textProperty().addListener((obs, old, query) -> rebuild.accept(menu, query));
        menu.setOnShown(e -> search.requestFocus());
    }

    /** Drops everything a {@link #withSearch} rebuild owns, i.e. every item below the search box. */
    static void clearBody(ContextMenu menu) {
        menu.getItems().remove(1, menu.getItems().size());
    }

    static MenuItem sectionHeader(String text) {
        MenuItem header = new MenuItem(text);
        header.setDisable(true);
        header.getStyleClass().add("block-section-header");
        return header;
    }

    static MenuItem disabledItem(String text) {
        MenuItem item = new MenuItem(text);
        item.setDisable(true);
        return item;
    }

    static <T> void addIfNonNull(List<T> list, T item) {
        if (item != null) list.add(item);
    }

    /** Recursively collects the actionable (non-disabled) leaf {@link MenuItem}s under {@code menu}. */
    static void collectMenuLeaves(Menu menu, List<MenuItem> out) {
        if (menu == null) return;
        for (MenuItem mi : menu.getItems()) {
            if (mi instanceof Menu sub) collectMenuLeaves(sub, out);
            else if (!mi.isDisable() && mi.getText() != null) out.add(mi);
        }
    }

    /**
     * Appends, at the top level of the expression menu, one submenu per {@link SdkType#MENU_FACADES}
     * facade listing its static members whose return type is compatible with {@code expectedType} — the
     * expression-slot analogue of the statement menu's per-facade submenus. Void-only methods naturally drop
     * out (no return value fits an expression slot), and {@link #buildScopeMenu} returns {@code null} for a
     * facade with nothing compatible, so empty submenus are skipped.
     *
     * <p>That null return is also what gates this on the bot's <em>own</em> SDK rather than Studio's: the
     * analyzer resolves against the jar the project pins, so a facade its version doesn't have has no members
     * and no submenu. {@code services/SdkSurfaceService} exists for the surfaces where nothing enumerates
     * members first (the chips, the class dropdowns) — adding it here would ask the same jar the same
     * question twice.
     */
    static void appendSdkFacadeExpressionSubmenus(ContextMenu menu, ResolvedType expectedType,
                                                  CodeEditorService context, Consumer<Object> onSelect) {
        if (context == null) return;
        ProjectAnalyzer analyzer = context.getProjectAnalyzer();
        if (analyzer == null) return;
        for (SdkType facade : SdkType.MENU_FACADES) {
            String name = facade.simpleName();
            Menu sub = buildScopeMenu(name, name, name, true, expectedType, analyzer, onSelect);
            if (sub != null) menu.getItems().add(MenuIcons.decorate(sub, MenuIcons.iconFor(facade)));
        }
    }

    /** Flattens the SDK-facade expression submenus into "Facade.member" leaves for the flat search view. */
    static void collectSdkFacadeLeaves(ResolvedType expectedType, CodeEditorService context,
                                       Consumer<Object> onSelect, List<MenuItem> out) {
        if (context == null) return;
        ProjectAnalyzer analyzer = context.getProjectAnalyzer();
        if (analyzer == null) return;
        for (SdkType facade : SdkType.MENU_FACADES) {
            String name = facade.simpleName();
            Menu sub = buildScopeMenu(name, name, name, true, expectedType, analyzer, onSelect);
            if (sub == null) continue;
            List<MenuItem> leaves = new ArrayList<>();
            collectMenuLeaves(sub, leaves);
            for (MenuItem mi : leaves) {
                mi.setText(name + "." + mi.getText());
                mi.setGraphic(MenuIcons.node(MenuIcons.iconFor(facade)));
                out.add(mi);
            }
        }
    }

    /**
     * Submenu of the type-compatible members (methods + readable fields) of {@code typeName}. {@code label} is
     * the menu's display text while {@code scope} is the AST receiver — they differ for the enclosing class
     * ("This (Foo)" labelled, {@code scope=""} so the reference has no receiver; fields are then skipped since
     * a bare receiver-less field isn't offered here). Returns {@code null} when nothing is compatible, so the
     * caller can drop the whole scope/jar entry rather than show an empty submenu.
     */
    static Menu buildScopeMenu(String label, String scope, String typeName, boolean isStatic,
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
}
