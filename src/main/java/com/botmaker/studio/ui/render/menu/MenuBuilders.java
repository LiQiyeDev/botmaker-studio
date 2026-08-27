package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.SdkSurfaceService;
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
     * Appends, at the top level of the expression menu, one submenu per {@linkplain
     * CodeEditorService#sdkMenuFacades() served menu facade} listing its static members whose return type is
     * compatible with {@code expectedType} — the
     * expression-slot analogue of the statement menu's per-facade submenus. Void-only methods naturally drop
     * out (no return value fits an expression slot), and {@link #buildScopeMenu} returns {@code null} for a
     * facade with nothing compatible, so empty submenus are skipped.
     *
     * <p><b>PRESENCE is gated implicitly by that null return</b> and must stay so: the analyzer resolves
     * against the jar the project pins, so a facade its version doesn't have has no members and no submenu.
     * {@code services/SdkSurfaceService} exists for the surfaces where nothing enumerates members first (the
     * chips, the class dropdowns) — adding a <em>presence</em> filter here would ask the same jar the same
     * question twice.
     *
     * <p><b>CURATION is a different question and does need the explicit filter</b> that {@link #buildScopeMenu}
     * now applies. "Is this member here?" and "should we lead with it?" are not the same, and nothing
     * enumerable answers the second — a member the analyzer resolves perfectly well may still not be one the
     * SDK proposes. This is the exact twin of the note at {@code StatementMenu.rebuildItems}, and until
     * 2026-08-24 it said only the paragraph above, which is why the whole expression menu went on offering
     * everything for a year of curation. The rule that keeps the two apart: <b>filter what is OFFERED, never
     * what is RESOLVED</b> — blocks already in the file resolve through the analyzer, untouched.
     */
    static void appendSdkFacadeExpressionSubmenus(ContextMenu menu, ResolvedType expectedType,
                                                  CodeEditorService context, Consumer<Object> onSelect) {
        if (context == null) return;
        ProjectAnalyzer analyzer = context.getProjectAnalyzer();
        if (analyzer == null) return;
        for (FacadeEntry facade : context.sdkMenuFacades()) {
            String name = facade.simpleName();
            Menu sub = buildScopeMenu(name, name, name, true, expectedType, analyzer,
                    context.getSdkSurface(), onSelect);
            if (sub != null) menu.getItems().add(MenuIcons.decorate(sub, MenuIcons.iconFor(facade)));
        }
    }

    /** Flattens the SDK-facade expression submenus into "Facade.member" leaves for the flat search view. */
    static void collectSdkFacadeLeaves(ResolvedType expectedType, CodeEditorService context,
                                       Consumer<Object> onSelect, List<MenuItem> out) {
        if (context == null) return;
        ProjectAnalyzer analyzer = context.getProjectAnalyzer();
        if (analyzer == null) return;
        for (FacadeEntry facade : context.sdkMenuFacades()) {
            String name = facade.simpleName();
            Menu sub = buildScopeMenu(name, name, name, true, expectedType, analyzer,
                    context.getSdkSurface(), onSelect);
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
     *
     * <p>{@code surface} curates the <b>methods</b> half: on a catalogued SDK type, only the overloads the
     * served catalog names are listed, and a name whose every overload is hidden drops out entirely.
     * {@code null} — a headless edit, an uncurated pin, a type that isn't the SDK's — offers everything, which
     * is what every SDK released before 1.2.0 answers. This is the one place the expression menu and its
     * search view both go through, so filtering here covers both.
     *
     * <p>The <b>fields</b> half is deliberately never curated. A catalog names methods and constructors and
     * gains no field entries: the constant sets are small and closed ({@code BotSettings} 7,
     * {@code Precision} 4, {@code Text} 2, {@code Direction} 4) and each entry is a named anchor a user
     * reaches for — {@code Precision.TIGHT} is the whole point of {@code Precision} having constants. Enum
     * constants also reach the activity-variable pickers by reading the {@code Class<?>} directly (see
     * {@code ValueWire}), never through the index, so curating them here would be half an answer.
     */
    static Menu buildScopeMenu(String label, String scope, String typeName, boolean isStatic,
                               ResolvedType expectedType, ProjectAnalyzer analyzer,
                               SdkSurfaceService surface, Consumer<Object> onSelect) {
        Menu scopeMenu = new Menu(label);

        // Methods (grouped by name; overloads nest one level).
        Map<String, List<MethodSignature>> grouped = analyzer.getMethods(typeName, isStatic).stream()
                .filter(m -> m.returnsCompatibleWith(expectedType))
                .collect(Collectors.groupingBy(MethodSignature::name));
        List<String> names = grouped.keySet().stream().sorted().collect(Collectors.toList());
        // Nothing is currently selected in a menu that is being built from scratch, hence keep = null.
        if (surface != null) names = surface.retainOfferedNames(typeName, names, null);
        names.forEach(mName -> {
            List<MethodSignature> sigs = grouped.get(mName);
            if (surface != null) sigs = surface.retainOffered(typeName, mName, sigs, null);
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
