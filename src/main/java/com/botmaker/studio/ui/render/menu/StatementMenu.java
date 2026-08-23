package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.parser.StatementPlacement;
import com.botmaker.studio.services.SdkSurfaceService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.util.MethodSignature;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The statement insert menu: what can be added at a given point in a body. Split out of the former
 * {@code ExpressionMenuFactory}, whose other half is {@link ExpressionMenu} (which fills an expression
 * <em>slot</em>); the two shared nothing but plumbing, now in {@link MenuBuilders}.
 *
 * <p>Entries come from two sources: the language/structure blocks of {@link BlockCatalog}, grouped by
 * {@link BlockCategory}, and the SDK facades of {@link SdkType}, whose methods are discovered at runtime through
 * {@link ProjectAnalyzer} rather than mirrored in the palette.
 */
public final class StatementMenu {

    private StatementMenu() {}

    /**
     * Creates the statement insert menu. A search box filters a flat list across every insertable block — the
     * language/structure blocks <em>and</em> every SDK facade method; with no query the menu leads with a submenu
     * per class in {@link SdkType#MENU_FACADES} order (methods discovered at runtime via {@code ProjectAnalyzer}),
     * followed by the language-block category submenus, with the bot-{@code Control} group last.
     *
     * @param analyzer resolves each facade's static methods; may be {@code null} (headless / no project resolved),
     *                 in which case only the language blocks are shown.
     */
    public static ContextMenu create(ProjectAnalyzer analyzer, Consumer<BlockType> onSelection) {
        return create(analyzer, null, null, onSelection);
    }

    /**
     * As {@link #create(ProjectAnalyzer, Consumer)}, but only offering blocks legal in {@code targetBody} — a
     * {@code break} isn't listed where there's no loop or switch to break out of, so an illegal insert can't be
     * chosen in the first place. Pass {@code null} to offer everything.
     *
     * @param surface this project's SDK's curation ({@code @Palette}); {@code null} — headless, or no project
     *                resolved — offers every method the analyzer finds, as an uncurated jar does.
     */
    public static ContextMenu create(ProjectAnalyzer analyzer, SdkSurfaceService surface,
                                     org.eclipse.jdt.core.dom.ASTNode targetBody,
                                     Consumer<BlockType> onSelection) {
        Predicate<BlockType> allowed =
                targetBody == null ? b -> true : b -> StatementPlacement.allows(b, targetBody);
        ContextMenu menu = new ContextMenu();
        MenuBuilders.withSearch(menu, "Search blocks…",
                (m, query) -> rebuildItems(m, query, analyzer, surface, allowed, onSelection));
        return menu;
    }

    /**
     * Language/structure block categories in statement-menu display order — the SDK facade calls are pulled out
     * into their own generated submenus (see {@link #rebuildItems}), so what remains here is the general
     * programming vocabulary. {@link BlockCategory#CONTROL} (enable/disable activity, stop bot, break/continue/
     * return) is placed last as a clearly-separated group.
     */
    private static final List<BlockCategory> LANGUAGE_CATEGORY_ORDER = List.of(
            BlockCategory.FLOW, BlockCategory.LOOPS, BlockCategory.VARIABLES, BlockCategory.BOT_VARIABLE,
            BlockCategory.FUNCTIONS, BlockCategory.OUTPUT, BlockCategory.INPUT, BlockCategory.GAME,
            BlockCategory.UTILITY, BlockCategory.CONTROL);

    /** Rebuilds the menu body (everything below the search box at index 0) for the current search {@code query}. */
    private static void rebuildItems(ContextMenu menu, String query, ProjectAnalyzer analyzer,
                                     SdkSurfaceService surface,
                                     Predicate<BlockType> allowed, Consumer<BlockType> onSelection) {
        MenuBuilders.clearBody(menu);

        String q = query == null ? "" : query.trim().toLowerCase();

        // Active search: flat, filtered list across every block — the language/structure blocks and every SDK
        // facade method — no submenus to dig through.
        if (!q.isEmpty()) {
            List<MenuItem> matches = new ArrayList<>();
            for (BlockType b : languageBlocks(allowed)) {
                if (b.displayName().toLowerCase().contains(q)) matches.add(statementItem(b, onSelection));
            }
            for (SdkCall call : sdkCalls(analyzer, surface)) {
                if (call.block().displayName().toLowerCase().contains(q)) {
                    matches.add(MenuIcons.decorate(statementItem(call.block(), onSelection),
                            MenuIcons.iconFor(call.facade())));
                }
            }
            if (matches.isEmpty()) menu.getItems().add(MenuBuilders.disabledItem("No matching blocks"));
            else menu.getItems().addAll(matches);
            return;
        }

        // Default: one submenu per SDK facade class (in SdkType order), enumerating that class's static methods.
        //
        // PRESENCE is gated implicitly here and must stay so: sdkFacadeSubmenu returns null when the facade
        // resolves no methods, and the analyzer resolves against the bot's own jar — so a class its SDK
        // doesn't have contributes nothing. Do not add an SdkSurfaceService *presence* filter; it would be a
        // second answer to the same question, from the same jar. Do not "optimize away" the null return,
        // which is the gate.
        //
        // CURATION is a different question and does need the explicit filter that arrived with @Palette (see
        // facadeMethodNames). "Is this method here?" and "should we lead with it?" are not the same, and
        // nothing enumerable answers the second — a method the analyzer resolves perfectly well may still not
        // be one we propose. The rule that keeps the two apart: filter what is OFFERED, never what is
        // RESOLVED. Blocks already in the file resolve through the analyzer, untouched.
        for (SdkType facade : SdkType.MENU_FACADES) {
            Menu sub = sdkFacadeSubmenu(facade, analyzer, surface, onSelection);
            if (sub != null) menu.getItems().add(sub);
        }

        // Then the language/structure block categories (SDK-facade calls excluded — reached via the submenus above).
        if (menu.getItems().size() > 1) menu.getItems().add(new SeparatorMenuItem());
        Map<BlockCategory, List<BlockType>> grouped = languageBlocks(allowed).stream()
                .collect(Collectors.groupingBy(BlockType::category, LinkedHashMap::new, Collectors.toList()));
        for (BlockCategory category : LANGUAGE_CATEGORY_ORDER) {
            addCategoryMenu(menu, category, grouped, onSelection);
        }

        if (menu.getItems().size() == 1) menu.getItems().add(MenuBuilders.disabledItem("(No blocks available)"));
    }

    /**
     * The insertable language/structure blocks: every {@link BlockCatalog#all()} entry except the SDK-facade
     * calls (a {@link BlockType.LibraryCall} / {@link BlockType.LambdaCall} on a {@link SdkType} facade), which are
     * offered through the generated per-class submenus instead.
     */
    private static List<BlockType> languageBlocks(Predicate<BlockType> allowed) {
        return BlockCatalog.all().stream()
                .filter(b -> !isSdkFacadeCall(b))
                .filter(allowed)
                .collect(Collectors.toList());
    }

    private static boolean isSdkFacadeCall(BlockType block) {
        return switch (block) {
            case BlockType.LibraryCall l -> l.facade().isFacade();
            case BlockType.LambdaCall l -> l.facade().isFacade();
            default -> false;
        };
    }

    /**
     * A submenu of {@code facade}'s static methods (one entry per distinct method name — overloads collapse, and
     * the default overload is chosen at insert time by {@code StatementFactory}). Returns {@code null} when the
     * analyzer is absent or the facade resolves no static methods (e.g. the SDK jar isn't on the classpath yet).
     */
    private static Menu sdkFacadeSubmenu(SdkType facade, ProjectAnalyzer analyzer, SdkSurfaceService surface,
                                         Consumer<BlockType> onSelection) {
        if (analyzer == null) return null;
        Menu sub = new Menu(facade.simpleName());
        String icon = MenuIcons.iconFor(facade);
        for (String method : facadeMethodNames(facade, analyzer, surface)) {
            sub.getItems().add(MenuIcons.decorate(
                    statementItem(sdkCall(facade, method, method), onSelection), icon));
        }
        return sub.getItems().isEmpty() ? null : MenuIcons.decorate(sub, icon);
    }

    /** An SDK facade method as a statement block, paired with its facade so the search view can icon it. */
    private record SdkCall(SdkType facade, BlockType block) {}

    /** Every SDK facade method as a flat list of class-qualified statement blocks, for the search view. */
    private static List<SdkCall> sdkCalls(ProjectAnalyzer analyzer, SdkSurfaceService surface) {
        List<SdkCall> out = new ArrayList<>();
        if (analyzer == null) return out;
        for (SdkType facade : SdkType.MENU_FACADES) {
            for (String method : facadeMethodNames(facade, analyzer, surface)) {
                out.add(new SdkCall(facade, sdkCall(facade, method, facade.simpleName() + "." + method)));
            }
        }
        return out;
    }

    /**
     * Distinct static method names of {@code facade}, sorted — the entries of its statement submenu, and (via
     * {@link #sdkCalls}) of the search view, so both are curated by this one method.
     *
     * <p>A name survives when the SDK offers at least one of its overloads. Which overload the insert then
     * creates is {@code StatementFactory}'s business, and it applies the same set.
     */
    private static List<String> facadeMethodNames(SdkType facade, ProjectAnalyzer analyzer,
                                                  SdkSurfaceService surface) {
        return analyzer.getMethods(facade.simpleName(), true).stream()
                .map(MethodSignature::name)
                .distinct()
                .filter(name -> surface == null || surface.isOffered(facade.simpleName(), name))
                .sorted()
                .collect(Collectors.toList());
    }

    /** A synthetic {@code facade.method(<defaults>)} statement block; args are seeded from the resolved overload. */
    private static BlockType sdkCall(SdkType facade, String method, String displayName) {
        return new BlockType.LibraryCall("SDK_" + facade.simpleName() + "_" + method, displayName,
                BlockCategory.INPUT, facade, method, List.of());
    }

    private static void addCategoryMenu(ContextMenu menu, BlockCategory category,
                                        Map<BlockCategory, List<BlockType>> grouped, Consumer<BlockType> onSelection) {
        List<BlockType> blocks = grouped.get(category);
        if (blocks == null || blocks.isEmpty()) return;
        Menu categoryMenu = MenuIcons.decorate(new Menu(category.getLabel()), MenuIcons.iconFor(category));
        for (BlockType block : blocks) categoryMenu.getItems().add(statementItem(block, onSelection));
        menu.getItems().add(categoryMenu);
    }

    private static MenuItem statementItem(BlockType block, Consumer<BlockType> onSelection) {
        MenuItem item = MenuIcons.decorate(new MenuItem(block.displayName()), MenuIcons.iconFor(block.category()));
        item.setOnAction(e -> onSelection.accept(block));
        return item;
    }
}
