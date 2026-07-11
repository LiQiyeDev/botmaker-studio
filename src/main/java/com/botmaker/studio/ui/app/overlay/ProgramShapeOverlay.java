package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.events.CoreApplicationEvents.UIBlocksUpdatedEvent;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.SdkApi;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.CursorNavigator;
import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ASTNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The <b>overlay authoring surface</b> (Phase 2A/B): a small, always-on-top, independently-minimizable window
 * that mirrors <em>the current shape of the program</em> as a compact, clickable, scrollable list of one-line
 * rows — one per block — reusing the live {@link CodeBlock} tree (not a second renderer). It is the companion
 * to the capture/record overlays: while you work in the target app, this shows where you are in the bot and
 * lets you grow it.
 *
 * <p>An {@link InsertionCursor} (kept on {@link ProjectState}) marks the <em>focused</em> block; the toolbar's
 * <b>step</b> / <b>step-into</b> / <b>step-out</b> buttons move it and <b>+ Add below</b> inserts a new block
 * just beneath it (always below — the user's requested "know where the next block goes" model). Clicking a row
 * focuses it. The list re-renders whenever the editor republishes its block tree
 * ({@link UIBlocksUpdatedEvent}), so overlay and editor never drift.
 *
 * <p>Phase 2C (per-row config/overload buttons) and 2D (searchable method palette) hang off the same rows and
 * toolbar; this class owns the tree/cursor/step plumbing they build on.
 */
public final class ProgramShapeOverlay {

    /** Single live instance — pressing the toolbar button again focuses it instead of opening another. */
    private static ProgramShapeOverlay active;

    private final CodeEditorService context;
    private final ProjectState state;

    private Stage stage;
    private final VBox rows = new VBox(2);
    private CodeBlock root;

    /** All insertable methods (curated bot-actions + every SDK facade method), built once on open. */
    private final List<PaletteItem> allPaletteItems = new ArrayList<>();
    private final ObservableList<PaletteItem> paletteView = FXCollections.observableArrayList();

    /** One insertable method: a display label and the {@link BlockType} that materializes it below the cursor. */
    private record PaletteItem(String label, BlockType type) {
        @Override public String toString() { return label; }
    }

    private ProgramShapeOverlay(CodeEditorService context) {
        this.context = context;
        this.state = context.getState();
    }

    /** Opens (or focuses) the overlay editor for the active file. Must be called on the FX thread. */
    public static void open(Window owner, CodeEditorService context) {
        if (active != null && active.stage != null && active.stage.isShowing()) {
            active.stage.toFront();
            return;
        }
        ProgramShapeOverlay overlay = new ProgramShapeOverlay(context);
        overlay.show(owner);
        active = overlay;
    }

    private void show(Window owner) {
        BorderPane layout = buildLayout();

        Scene scene = new Scene(layout, 340, 460);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage = new Stage();
        stage.setTitle("Overlay Editor");
        stage.setAlwaysOnTop(true);              // stays above the target app
        stage.setScene(scene);
        // Deliberately NOT owned by the Studio window, so Studio can be minimized independently (matches the
        // capture/record overlays). Placed near the top-left of the owner if we have one.
        if (owner != null) {
            stage.setX(owner.getX() + 20);
            stage.setY(owner.getY() + 60);
        }
        stage.setOnHidden(e -> { if (active == this) active = null; });
        stage.show();

        // Re-render on every editor update; guard so a stale subscription (no unsubscribe API) no-ops.
        context.getEventBus().subscribe(UIBlocksUpdatedEvent.class, e -> {
            if (stage != null && stage.isShowing()) {
                root = e.rootBlock();
                Platform.runLater(this::render);
            }
        });

        root = context.getRootBlock().orElse(null);
        buildPaletteItems();
        ensureCursor();
        render();
    }

    /** The toolbar (two compact rows so buttons stay reachable when the window is narrow) + the scroll list. */
    private BorderPane buildLayout() {
        Button into  = iconButton("⤵", "Step into", () -> move(CursorNavigator.stepInto(cursor())));
        Button out   = iconButton("⤴", "Step out",  () -> move(CursorNavigator.stepOut(cursor(), root)));
        Button up    = iconButton("▲", "Step up",    () -> move(CursorNavigator.stepBack(cursor())));
        Button down  = iconButton("▼", "Step down",  () -> move(CursorNavigator.stepOver(cursor())));
        HBox stepRow = new HBox(6, new Label("Step:"), up, down, into, out);
        stepRow.setAlignment(Pos.CENTER_LEFT);

        Button add = new Button("＋ Add below");
        add.setTooltip(new Tooltip("Insert a new block just below the focused one"));
        add.setOnAction(e -> addBelow(add));
        Button refresh = iconButton("⟳", "Refresh", this::render);
        HBox actionRow = new HBox(6, add, refresh);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox toolbar = new VBox(4, stepRow, actionRow);
        toolbar.setPadding(new Insets(8));
        toolbar.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #dcdcdc; -fx-border-width: 0 0 1 0;");

        rows.setPadding(new Insets(6));
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox center = new VBox(scroll, buildPalette());
        BorderPane pane = new BorderPane();
        pane.setTop(toolbar);
        pane.setCenter(center);
        return pane;
    }

    /**
     * The always-visible, searchable method palette (Phase 2D/E): a filter box over every insertable SDK method
     * — curated bot-actions plus all facade static methods (vision included). Enter or double-click inserts the
     * selection below the cursor via the same path as "Add below".
     */
    private VBox buildPalette() {
        TextField search = new TextField();
        search.setPromptText("Search methods to insert…");

        ListView<PaletteItem> list = new ListView<>(paletteView);
        list.setPrefHeight(150);
        list.setPlaceholder(new Label("No matching methods"));

        search.textProperty().addListener((o, old, text) -> filterPalette(text));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !paletteView.isEmpty()) {
                insertPalette(paletteView.get(0));
            } else if (e.getCode() == KeyCode.DOWN) {
                list.requestFocus();
                list.getSelectionModel().selectFirst();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                insertPalette(list.getSelectionModel().getSelectedItem());
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && list.getSelectionModel().getSelectedItem() != null) {
                insertPalette(list.getSelectionModel().getSelectedItem());
            }
        });

        VBox box = new VBox(4, new Label("Insert method below cursor:"), search, list);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-border-color: #dcdcdc; -fx-border-width: 1 0 0 0;");
        return box;
    }

    /** Builds the master palette list once (curated bot-actions first, then all facade methods, deduped). */
    private void buildPaletteItems() {
        allPaletteItems.clear();
        Map<String, PaletteItem> byLabel = new LinkedHashMap<>();
        for (BlockType t : BlockCatalog.botActions()) {
            byLabel.putIfAbsent(t.displayName(), new PaletteItem(t.displayName(), t));
        }
        var analyzer = context.getProjectAnalyzer();
        for (String facade : SdkApi.FACADE_CLASSES) {
            try {
                analyzer.getMethods(facade, true).stream()
                        .map(com.botmaker.studio.util.MethodSignature::name)
                        .distinct()
                        .forEach(name -> {
                            String label = facade + "." + name;
                            byLabel.putIfAbsent(label, new PaletteItem(label,
                                    new BlockType.LibraryCall("SDK_" + facade + "_" + name, label,
                                            BlockCategory.INPUT, facade, name, List.of())));
                        });
            } catch (Exception ignored) {
                // A facade the analyzer can't resolve (SDK jar not yet indexed) simply contributes nothing.
            }
        }
        allPaletteItems.addAll(byLabel.values());
        filterPalette("");
    }

    private void filterPalette(String text) {
        String q = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            paletteView.setAll(allPaletteItems);
            return;
        }
        List<PaletteItem> matches = new ArrayList<>();
        for (PaletteItem it : allPaletteItems) {
            if (it.label().toLowerCase(Locale.ROOT).contains(q)) matches.add(it);
        }
        paletteView.setAll(matches);
    }

    /**
     * The per-argument config popover (Phase 2C): for each of the call's arguments that has a specialized
     * editor ({@code Rect} → draw a rectangle, {@code ImageTemplate}/{@code ImageTemplateGroup} → pick/capture,
     * {@code CaptureSource}/{@code Window} → chooser), show that exact editor — reusing {@link PickerRegistry}
     * so the AST-rewrite path is identical to editing the block inline. Opens as a small always-on-top window
     * so drawing overlays it while the target app stays visible.
     */
    private void openConfig(MethodInvocationBlock mib) {
        List<ExpressionBlock> args = mib.getArgumentBlocks();
        List<ResolvedType> paramTypes = mib.resolveParamTypes(context);

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));
        content.getChildren().add(new Label("Configure  " + mib.getScope() + "." + mib.getMethodName() + "(…)"));

        int configurable = 0;
        for (int i = 0; i < args.size(); i++) {
            ResolvedType pt = i < paramTypes.size() ? paramTypes.get(i) : ResolvedType.UNKNOWN;
            PickerContext ctx = new PickerContext(context, args.get(i), pt, mib.getScope(), mib.getMethodName(), i);
            var picker = PickerRegistry.pickerNodeFor(ctx);
            if (picker == null) continue;
            String name = pt != null && pt.simpleName() != null ? pt.simpleName() : ("arg " + i);
            HBox line = new HBox(8, new Label(name + ":"), picker);
            line.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().add(line);
            configurable++;
        }
        if (configurable == 0) {
            Label none = new Label("This call has no drawable / pickable arguments.");
            none.setStyle("-fx-text-fill: #888;");
            content.getChildren().add(none);
        }

        Stage dlg = new Stage();
        dlg.setTitle("Configure arguments");
        dlg.setAlwaysOnTop(true);
        Scene sc = new Scene(content);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) sc.getStylesheets().add(css.toExternalForm());
        dlg.setScene(sc);
        if (stage != null) { dlg.setX(stage.getX() + 40); dlg.setY(stage.getY() + 80); }
        dlg.show();
    }

    private void insertPalette(PaletteItem item) {
        InsertionCursor c = cursor();
        if (c == null || item == null) return;
        int insertIndex = Math.min(c.index() + 1, c.body().getStatements().size());
        context.getCodeEditor().addStatement(c.body(), item.type(), insertIndex);
    }

    private static Button iconButton(String glyph, String tip, Runnable action) {
        Button b = new Button(glyph);
        b.setTooltip(new Tooltip(tip));
        b.setMinWidth(30);
        b.setOnAction(e -> action.run());
        return b;
    }

    // ── cursor ───────────────────────────────────────────────────────────────────────────────────────────

    private InsertionCursor cursor() {
        return state.getInsertionCursor().orElse(null);
    }

    /** Seeds the cursor from the tree if none is set (or the current one dangles after a re-parse). */
    private void ensureCursor() {
        InsertionCursor c = cursor();
        if (c == null || !CursorNavigator.collectAll(root).contains(c.body())) {
            state.setInsertionCursor(CursorNavigator.defaultCursor(root));
        }
    }

    private void move(InsertionCursor next) {
        if (next != null) state.setInsertionCursor(next);
        render();
    }

    // ── insertion ────────────────────────────────────────────────────────────────────────────────────────

    private void addBelow(javafx.scene.Node anchor) {
        InsertionCursor c = cursor();
        if (c == null) return;
        int insertIndex = Math.min(c.index() + 1, c.body().getStatements().size());
        var menu = ExpressionMenuFactory.createStatementMenu(type ->
                context.getCodeEditor().addStatement(c.body(), type, insertIndex));
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────────────────

    private void render() {
        rows.getChildren().clear();
        ensureCursor();
        if (root == null) {
            rows.getChildren().add(new Label("No open file."));
            return;
        }
        // Render each top-level body found in the tree; nested bodies are drawn indented by renderBody.
        boolean any = false;
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b instanceof BodyBlock body && !isNested(body)) {
                renderBody(body, 0);
                any = true;
            }
        }
        if (!any) rows.getChildren().add(new Label("Program is empty."));
    }

    /** True when {@code body} is the child of some block (i.e. not a top-level render root). */
    private boolean isNested(BodyBlock body) {
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b != body && b instanceof BlockWithChildren bwc && bwc.getChildren().contains(body)) return true;
        }
        return false;
    }

    private void renderBody(BodyBlock body, int depth) {
        InsertionCursor c = cursor();
        var statements = body.getStatements();
        if (statements.isEmpty()) {
            rows.getChildren().add(emptyRow(body, depth));
            return;
        }
        for (int i = 0; i < statements.size(); i++) {
            StatementBlock stmt = statements.get(i);
            boolean focused = c != null && c.body() == body && c.index() == i;
            rows.getChildren().add(statementRow(stmt, body, i, depth, focused));
            // Draw child bodies (if/while/for/lambda) indented under their owner row.
            if (stmt instanceof BlockWithChildren bwc) {
                for (CodeBlock child : bwc.getChildren()) {
                    if (child instanceof BodyBlock childBody) renderBody(childBody, depth + 1);
                }
            }
        }
    }

    private HBox statementRow(StatementBlock stmt, BodyBlock body, int index, int depth, boolean focused) {
        Label text = new Label(compactLabel(stmt));
        text.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        HBox row = new HBox(6, indent(depth), text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3, 6, 3, 6));
        row.setStyle(focused
                ? "-fx-background-color: #d7e6ff; -fx-background-radius: 4; -fx-border-color: #4a90e2; -fx-border-radius: 4;"
                : "-fx-background-color: transparent;");
        row.setOnMouseClicked(e -> move(new InsertionCursor(body, index)));
        row.setPickOnBounds(true);

        // Config (⚙) button for SDK/method calls: lets the user draw the rect / pick the template for the
        // call's arguments without leaving the overlay (reuses the standard argument pickers, Phase 2C).
        if (stmt instanceof MethodInvocationBlock mib) {
            Region spring = new Region();
            HBox.setHgrow(spring, Priority.ALWAYS);
            Button config = iconButton("⚙", "Configure arguments (draw rect / pick template)", () -> openConfig(mib));
            config.setMinWidth(26);
            row.getChildren().addAll(spring, config);
        }
        return row;
    }

    private HBox emptyRow(BodyBlock body, int depth) {
        InsertionCursor c = cursor();
        boolean focused = c != null && c.body() == body;
        Label text = new Label("· (empty) ·");
        text.setStyle("-fx-font-style: italic; -fx-text-fill: #888;");
        HBox row = new HBox(6, indent(depth), text);
        row.setPadding(new Insets(3, 6, 3, 6));
        if (focused) row.setStyle("-fx-background-color: #d7e6ff; -fx-background-radius: 4;");
        row.setOnMouseClicked(e -> move(new InsertionCursor(body, 0)));
        return row;
    }

    private static Region indent(int depth) {
        Region r = new Region();
        r.setMinWidth(depth * 16.0);
        r.setPrefWidth(depth * 16.0);
        return r;
    }

    /** One-line summary of a block: the first source line of its AST node, trimmed and truncated. */
    private static String compactLabel(CodeBlock block) {
        ASTNode n = block.getAstNode();
        if (n == null) return block.getClass().getSimpleName();
        String s = n.toString().strip();
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl).strip();
        if (s.endsWith("{")) s = s.substring(0, s.length() - 1).strip();
        return s.length() > 70 ? s.substring(0, 67) + "…" : s;
    }
}
