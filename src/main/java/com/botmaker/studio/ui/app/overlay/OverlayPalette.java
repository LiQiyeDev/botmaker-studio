package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.util.MethodSignature;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.botmaker.studio.ui.app.overlay.OverlayStyles.label;

/**
 * The palette bar: one hover-expanding chip per SDK facade category laid out in a line — hovering a chip lists
 * its methods, and a method with several overloads fans out into its overloads (favourite methods first). A
 * trailing <em>＋ Add block</em> opens the full categorized statement menu for everything else (control flow,
 * variables, print, …).
 *
 * <p>It picks <em>what</em> to insert and nothing more: both outcomes leave through {@link Callbacks}, so the
 * cursor, the read-only guards and the post-re-parse handoff stay with the coordinator. The menus are built in
 * {@code setOnShowing} rather than up front because the SDK index is populated asynchronously — a bar built at
 * open time on a cold project would list nothing and never correct itself.
 */
final class OverlayPalette {

    /** An SDK call the user picked; {@code overload} is {@code null} when they picked the bare method name. */
    @FunctionalInterface
    interface LibraryCallRequest {
        void insert(SdkType facade, String method, MethodSignature overload);
    }

    /**
     * @param onLibraryCall an SDK facade call to insert below the cursor
     * @param onAddBlock    the ＋ Add block button, passed as the anchor the statement menu should pop up from
     */
    record Callbacks(LibraryCallRequest onLibraryCall, Consumer<Node> onAddBlock) {}

    private final CodeEditorService context;
    private final ProjectSettingsService settings;
    private final Callbacks callbacks;

    OverlayPalette(CodeEditorService context, ProjectSettingsService settings, Callbacks callbacks) {
        this.context = context;
        this.settings = settings;
        this.callbacks = callbacks;
    }

    /** The bar itself: a caption over the wrapping row of facade chips and the ＋ Add block button. */
    VBox node() {
        FlowPane chips = new FlowPane(6, 6);
        for (SdkType facade : SdkType.MENU_FACADES) {
            chips.getChildren().add(facadeMenuButton(facade));
        }
        Button addBlock = new Button("＋ Add block");
        addBlock.setTooltip(new Tooltip("Insert any block (control flow, variables, print, …) below the cursor"));
        addBlock.setOnAction(e -> callbacks.onAddBlock().accept(addBlock));
        chips.getChildren().add(addBlock);

        return new VBox(4, label("Blocks:"), chips);
    }

    /** A category chip for one SDK facade; on show it lists its methods → overloads (favourites first). */
    private MenuButton facadeMenuButton(SdkType facade) {
        MenuButton mb = new MenuButton(facade.simpleName());
        mb.setOnShowing(e -> {
            mb.getItems().clear();
            Map<String, List<MethodSignature>> byName =
                    context.getProjectAnalyzer().getMethods(facade.simpleName(), true).stream()
                            .collect(Collectors.groupingBy(MethodSignature::name,
                                    LinkedHashMap::new, Collectors.toList()));
            if (byName.isEmpty()) {
                MenuItem none = new MenuItem("(SDK not indexed yet)");
                none.setDisable(true);
                mb.getItems().add(none);
                return;
            }
            for (String mName : orderedMethods(facade, byName.keySet())) {
                List<MethodSignature> sigs = byName.get(mName);
                if (sigs.size() == 1) {
                    MenuItem it = new MenuItem(mName);
                    it.setOnAction(a -> callbacks.onLibraryCall().insert(facade, mName, null));
                    mb.getItems().add(it);
                } else {
                    Menu sub = new Menu(mName);
                    for (MethodSignature sig : sigs) {
                        MenuItem si = new MenuItem(sig.toString());
                        si.setOnAction(a -> callbacks.onLibraryCall().insert(facade, mName, sig));
                        sub.getItems().add(si);
                    }
                    mb.getItems().add(sub);
                }
            }
        });
        return mb;
    }

    /** The project's favourite methods for this facade (Project Settings) first, then the rest alphabetically. */
    private List<String> orderedMethods(SdkType facade, java.util.Set<String> available) {
        List<String> ordered = new ArrayList<>();
        for (String f : settings.current().favoriteMethodsFor(facade.simpleName())) {
            if (available.contains(f) && !ordered.contains(f)) ordered.add(f);
        }
        available.stream().filter(n -> !ordered.contains(n)).sorted().forEach(ordered::add);
        return ordered;
    }
}
