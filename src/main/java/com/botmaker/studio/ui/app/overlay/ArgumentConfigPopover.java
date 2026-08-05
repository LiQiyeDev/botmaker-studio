package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import com.botmaker.studio.util.MethodSignature;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.ASTNode;

import java.util.List;
import java.util.function.Supplier;

import static com.botmaker.studio.ui.app.overlay.OverlayStyles.PANEL;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.applyThemeClass;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.dimLabel;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.label;

/**
 * The call config popover: an <b>overload selector</b> (when the method has more than one) plus a row per
 * parameter. Each parameter gets its specialized editor when one applies ({@code Rect} → draw a rectangle,
 * {@code ImageTemplate}/{@code ImageTemplateGroup} → pick/capture, {@code CaptureSource}/{@code Window} →
 * chooser — via {@link PickerRegistry}), otherwise a generic expression picker, so <em>every</em> argument is
 * editable — not only the drawable ones. It opens as a small always-on-top window so drawing overlays it while
 * the target app stays visible.
 *
 * <p>It stays open until dismissed: the rows are rebuilt by {@link #refresh()} after each edit rather than the
 * window being closed, so several arguments can be filled in one visit and each shows its new value as it
 * lands. That rebuild is not a nicety — every picker writes through {@code CodeEditor}, which republishes the
 * whole tree, so the {@link MethodInvocationBlock} and every argument node the rows were built from are dead
 * the moment the first argument is set. The popover used to keep them anyway: it showed stale values and
 * dropped every edit after the first. It survives by re-resolving its call from a
 * {@link BlockTree.Position} — coordinates into the tree, not a block reference — so the window itself (and
 * its position) outlives the blocks it is editing.
 */
final class ArgumentConfigPopover {

    private final CodeEditorService context;
    /** The tree index of the moment: re-read on every call, because each edit publishes a new one. */
    private final Supplier<BlockTree.Index> index;
    /** The HUD stage the popover parks beside; a supplier because the HUD builds its stage after this. */
    private final Supplier<Stage> hud;

    private Stage dialog;
    /** The pane holding the rows, so {@link #refresh()} can swap its content without touching the window. */
    private ScrollPane scroll;
    /** Where the open popover's call sits. See the class note on why this is a position and not a block. */
    private BlockTree.Position target;

    ArgumentConfigPopover(CodeEditorService context, Supplier<BlockTree.Index> index, Supplier<Stage> hud) {
        this.context = context;
        this.index = index;
        this.hud = hud;
    }

    /** Whether a popover is currently on screen — the HUD stands down from its re-raise while one is. */
    boolean isOpen() {
        return dialog != null;
    }

    /**
     * Hides / restores the popover around a capture draw surface. It is only made transparent, never hidden:
     * the popover owns the modal capture overlay the user is drawing on, and hiding its owner takes the modal
     * child with it.
     */
    void dim(boolean dim) {
        if (dialog == null) return;
        dialog.setOpacity(dim ? 0 : 1);
        if (!dim) dialog.toFront();
    }

    void close() {
        if (dialog != null) dialog.close();
    }

    /** Opens the popover on {@code mib}, replacing any popover already up. */
    void open(MethodInvocationBlock mib) {
        // One popover at a time; a second would orphan the first (its onHidden clears the tracking fields, so
        // it must close before the new target is recorded).
        close();
        target = index.get().locate(mib);

        // Capped in a ScrollPane so a call with many parameters (e.g. Fill) scrolls instead of growing the
        // popover taller than the screen, with the bottom rows landing off-screen and unreachable.
        ScrollPane pane = new ScrollPane(content(mib));
        pane.setFitToWidth(true);
        pane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        pane.setMaxHeight(Screen.getPrimary().getVisualBounds().getHeight() * 0.7);
        scroll = pane;

        Stage dlg = new Stage();
        dlg.setTitle("Configure arguments");
        dlg.setAlwaysOnTop(true);
        Scene sc = new Scene(pane);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) sc.getStylesheets().add(css.toExternalForm());
        applyThemeClass(sc.getRoot());
        dlg.setScene(sc);
        dialog = dlg;
        // Only the popover's own tracking is cleared here. It used to also clear the HUD's capture-hide flag,
        // which is not this window's state to write: closing the popover while a capture surface was up
        // disarmed the guard, and the next real close of the HUD then skipped its teardown entirely.
        dlg.setOnHidden(e -> {
            if (dialog == dlg) { dialog = null; scroll = null; target = null; }
        });
        dlg.show();
        // After show(): the dialog has no width/height to place against until it has been sized to its scene.
        placeBesideHud(dlg);
        // The HUD stands down from its own re-raise while this is open, so promoting the popover is what
        // actually keeps it above both the HUD and a fullscreen game.
        OverlayToolbars.promoteAboveFullscreen(dlg);
        dlg.toFront();
    }

    /**
     * Rebuilds the open popover against the re-parsed tree, or closes it when its call is gone (deleted, or its
     * body edited out from under it). Same {@link Stage} either way — the window keeps its position, so filling
     * a second argument doesn't move the popover out from under the pointer.
     */
    void refresh() {
        if (dialog == null || scroll == null || target == null) return;
        if (index.get().statementAt(target) instanceof MethodInvocationBlock mib) scroll.setContent(content(mib));
        else dialog.close();
    }

    /**
     * The popover's rows for {@code mib}: the header, the overload selector, one editor per argument and the
     * Done button. Built fresh on open and again after every re-parse, because each picker's write replaces the
     * block and all of its argument nodes.
     */
    private VBox content(MethodInvocationBlock mib) {
        List<ExpressionBlock> args = mib.getArgumentBlocks();
        List<ResolvedType> paramTypes = mib.resolveParamTypes(context);

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));
        content.setStyle(PANEL);
        content.getChildren().add(label("Configure  " + mib.getScope() + "." + mib.getMethodName() + "(…)"));

        // Overload selector: switch this call to a different overload. The re-parse that follows replaces the
        // block, and the rebuild redraws these rows against the new overload's parameters.
        List<MethodSignature> overloads = mib.overloadSignatures(context);
        if (overloads.size() > 1) {
            ComboBox<MethodSignature> overloadBox =
                    new ComboBox<>(javafx.collections.FXCollections.observableArrayList(overloads));
            overloadBox.setValue(mib.currentSignature(context));
            overloadBox.setMaxWidth(Double.MAX_VALUE);
            overloadBox.setOnAction(e -> {
                MethodSignature sel = overloadBox.getValue();
                if (sel != null && !sel.equals(mib.currentSignature(context))) mib.switchToOverload(context, sel);
            });
            HBox line = new HBox(8, label("Overload:"), overloadBox);
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(overloadBox, Priority.ALWAYS);
            content.getChildren().add(line);
        }

        for (int i = 0; i < args.size(); i++) {
            ResolvedType pt = i < paramTypes.size() ? paramTypes.get(i) : ResolvedType.UNKNOWN;
            ExpressionBlock arg = args.get(i);
            PickerContext ctx = new PickerContext(context, arg, pt, mib.getScope(), mib.getMethodName(), i);
            Node editor = PickerRegistry.pickerNodeFor(ctx);
            if (editor == null) editor = genericArgEditor(mib, arg, pt);   // every arg editable, not just drawable ones
            HBox line = new HBox(8, label(paramLabel(mib, i, pt) + ":"), editor);
            line.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().add(line);
        }
        if (args.isEmpty()) {
            content.getChildren().add(dimLabel("This call takes no arguments."));
        }

        // An explicit dismissal. The window's own title bar is the only other way out, and between
        // setAlwaysOnTop and promoteAboveFullscreen there are window managers that don't leave one.
        Button done = new Button("Done");
        done.setDefaultButton(true);
        done.setOnAction(e -> close());
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox actions = new HBox(8, spring, done);
        actions.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().add(actions);
        return content;
    }

    /**
     * Puts the config popover immediately to the <b>right</b> of the HUD, top-aligned with it — the HUD is
     * tucked into the target window's top-left corner, so the space to its right is the one place a second
     * window neither covers the HUD nor the region the user is about to draw on. Falls back to the HUD's left
     * when there isn't room, and finally clamps into the screen so no row lands off-display.
     */
    private void placeBesideHud(Stage dlg) {
        Stage owner = hud.get();
        if (owner == null) return;
        Rectangle2D screen = Screen.getScreensForRectangle(
                        owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight()).stream()
                .findFirst().orElse(Screen.getPrimary()).getVisualBounds();

        double x = owner.getX() + owner.getWidth() + 12;
        if (x + dlg.getWidth() > screen.getMaxX()) {
            double left = owner.getX() - dlg.getWidth() - 12;
            x = (left >= screen.getMinX()) ? left : screen.getMaxX() - dlg.getWidth();
        }
        double y = Math.min(owner.getY(), screen.getMaxY() - dlg.getHeight());
        dlg.setX(Math.max(screen.getMinX(), x));
        dlg.setY(Math.max(screen.getMinY(), y));
    }

    /**
     * A generic editor for a parameter that has no specialized picker: a button showing the current expression
     * that opens the type-aware expression menu and rewrites the argument via {@link ExpressionMenu}. The
     * re-parse a pick triggers replaces the argument node; {@link #refresh()} redraws this row against the new
     * one rather than the popover closing.
     */
    private Node genericArgEditor(MethodInvocationBlock mib, ExpressionBlock arg, ResolvedType paramType) {
        ASTNode node = arg.getAstNode();
        String current = (node != null) ? node.toString() : "";
        boolean empty = current == null || current.isBlank() || "null".equals(current);
        Button b = new Button(empty ? "Set…" : current);
        b.setMaxWidth(240);
        b.setOnAction(e -> {
            if (!(arg.getAstNode() instanceof org.eclipse.jdt.core.dom.Expression expr)) return;
            var menu = ExpressionMenu.create(
                    paramType == null ? ResolvedType.UNKNOWN : paramType, false, context, mib.getAstNode(), null,
                    sel -> ExpressionMenu.applySelection(context, expr, sel));
            menu.show(b, Side.BOTTOM, 0, 0);
        });
        return b;
    }

    /** A "{@code Type name}" label for parameter {@code i}, from the current overload's names when available. */
    private String paramLabel(MethodInvocationBlock mib, int i, ResolvedType pt) {
        MethodSignature sig = mib.currentSignature(context);
        if (sig != null && i < sig.paramNames().size()) {
            String typeName = (pt != null && pt.simpleName() != null) ? pt.simpleName()
                    : (i < sig.paramTypes().size() ? sig.paramTypes().get(i).simpleName() : "arg");
            return typeName + " " + sig.paramNames().get(i);
        }
        return (pt != null && pt.simpleName() != null) ? pt.simpleName() : ("arg " + i);
    }
}
