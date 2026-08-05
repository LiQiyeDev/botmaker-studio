package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.validation.BlockValidator;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * The HUD's <b>compact tree</b>: the scrolling list of one-line rows that <em>is</em> the overlay's view of the
 * program, plus the Show-lines control that sizes it.
 *
 * <p>It renders {@link BlockTree#flatten} and nothing else — it holds no cursor, no tree and no editor. What a
 * row <em>does</em> is a callback the coordinator supplies ({@link Callbacks}), so the view can be reasoned
 * about (and the row model tested) without an editor session behind it.
 *
 * <p>Everything the row shows beyond the code text is there because its absence was silent: an unfilled slot
 * ({@code ⚠ missing value}) before any compile, a compile error the main editor shows on the block but the HUD
 * never did, a lock on generated scaffolding that previously read as "delete is broken here", and the full
 * source text as a tooltip because a row is truncated at 70 characters with no other way to see the rest.
 */
final class OverlayTreeView {

    /** What the coordinator does when a row is used. */
    record Callbacks(Consumer<InsertionCursor> onFocus,
                     Delete onDelete,
                     Consumer<MethodInvocationBlock> onConfig) {

        @FunctionalInterface
        interface Delete {
            void delete(StatementBlock stmt, BodyBlock body, int index);
        }
    }

    /** The pixel height one row (including spacing) costs, used to turn "show N lines" into a pref height. */
    private static final double ROW_HEIGHT_PX = 24;

    /** Longest row text before truncation; the full text stays reachable as the row's tooltip. */
    private static final int MAX_LABEL_CHARS = 70;

    private final Callbacks callbacks;
    private final VBox rows = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Spinner<Integer> visibleLines = new Spinner<>(3, 30, 8);
    private final VBox panel;

    /** Compile diagnostics, so a broken block is marked here as well as in the main editor. May be null. */
    private DiagnosticsManager diagnostics;

    OverlayTreeView(Callbacks callbacks, Runnable onResize) {
        this.callbacks = callbacks;

        rows.setPadding(new Insets(6));
        rows.setStyle("-fx-background-color: transparent;");
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        // How many rows are visible before the pane scrolls internally. This is a *preferred* height, not
        // just a cap: with the HUD's fixed Scene size gone, the window sizes to the sum of its children's
        // preferred heights, so a ScrollPane with no explicit prefHeight falls back to its own tiny default —
        // that's what made only one row visible before this control existed.
        visibleLines.setEditable(true);
        visibleLines.setPrefWidth(60);
        visibleLines.setTooltip(new Tooltip("How many rows are visible at once before the tree scrolls"));
        scroll.setPrefHeight(visibleLines.getValue() * ROW_HEIGHT_PX + 12);
        scroll.setMinHeight(Region.USE_PREF_SIZE);
        visibleLines.valueProperty().addListener((obs, old, val) -> {
            scroll.setPrefHeight(val * ROW_HEIGHT_PX + 12);
            onResize.run();   // the Scene only auto-sizes to content at first show(); later changes need this
        });

        HBox linesRow = new HBox(6, OverlayStyles.label("Show:"), visibleLines, OverlayStyles.label("lines"));
        linesRow.setAlignment(Pos.CENTER_LEFT);

        panel = new VBox(6, linesRow, scroll);
        panel.setStyle(OverlayStyles.PANEL);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(panel, Priority.ALWAYS);
    }

    /** The node to put in the HUD. */
    VBox node() {
        return panel;
    }

    /** How many rows the user asked to see at once — persisted with the rest of the HUD's state. */
    int visibleLineCount() {
        return visibleLines.getValue();
    }

    void setVisibleLineCount(int lines) {
        visibleLines.getValueFactory().setValue(lines);
    }

    void setDiagnostics(DiagnosticsManager diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** Replaces the list with a single message — "No open file.", "Program is empty.". */
    void showMessage(String message) {
        rows.getChildren().setAll(OverlayStyles.dimLabel(message));
    }

    /** Draws {@code bodies} (each a render root) against {@code cursor}, replacing whatever was there. */
    void render(List<BodyBlock> bodies, InsertionCursor cursor) {
        rows.getChildren().clear();
        for (BodyBlock body : bodies) {
            List<BlockTree.Row> flat = BlockTree.flatten(body, 0);
            for (int i = 0; i < flat.size(); i++) {
                BlockTree.Row row = flat.get(i);
                // A caret sitting *before* a body's first statement owns no row, so it gets one of its own —
                // otherwise the HUD shows a tree with no focus anywhere and looks like it lost the cursor. A
                // caption for that same body already carries the highlight, so it doesn't need a second marker.
                if (cursor != null && cursor.index() < 0 && cursor.body() == row.body()
                        && row.kind() == BlockTree.Kind.STATEMENT && row.index() == 0) {
                    rows.getChildren().add(caretRow(row.depth()));
                }
                rows.getChildren().add(rowNode(row, cursor));
            }
        }
    }

    /** The caret's own row, drawn when it sits before a body's first statement. */
    private static HBox caretRow(int depth) {
        Label text = new Label("▸ next block goes here");
        text.setStyle("-fx-font-style: italic; -fx-text-fill: #9fc0ff;");
        HBox node = new HBox(6, indent(depth), text);
        node.setPadding(new Insets(3, 6, 3, 6));
        node.setStyle("-fx-background-color: rgba(74,144,226,0.35); -fx-background-radius: 4;");
        return node;
    }

    private HBox rowNode(BlockTree.Row row, InsertionCursor cursor) {
        return switch (row.kind()) {
            case STATEMENT -> statementRow(row, cursor);
            case CAPTION -> captionRow(row, cursor);
            case EMPTY -> emptyRow(row, cursor);
        };
    }

    private HBox statementRow(BlockTree.Row row, InsertionCursor cursor) {
        StatementBlock stmt = row.stmt();
        boolean incomplete = BlockValidator.hasEmptySlot(stmt);
        boolean broken = diagnostics != null && diagnostics.hasError(stmt);
        boolean locked = stmt.isReadOnly();

        String suffix = incomplete ? "   ⚠ missing value" : (broken ? "   ✖ error" : "");
        Label text = new Label((locked ? "🔒 " : "") + compactLabel(stmt) + suffix);
        // An empty slot or a compile error shows red before the user has to go looking for it; scaffolding is
        // dimmed, because "why is there no ✕ on this row" was the only signal that it wasn't the user's to edit.
        String colour = (incomplete || broken) ? "#ff6b6b;" : (locked ? "#8b93a1;" : "#dfe6f2;");
        text.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-text-fill: " + colour);

        HBox node = shell(row, focused(row, cursor));
        node.getChildren().add(text);
        Tooltip.install(node, new Tooltip(rowTooltip(stmt, locked, broken)));
        node.setOnMouseClicked(e -> callbacks.onFocus().accept(new InsertionCursor(row.body(), row.index())));
        node.setPickOnBounds(true);

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        node.getChildren().add(spring);

        // Config (⚙) for SDK/method calls: draw the rect / pick the template for the call's arguments without
        // leaving the overlay (reuses the standard argument pickers).
        if (stmt instanceof MethodInvocationBlock mib) {
            Button config = OverlayStyles.iconButton(
                    "⚙", "Configure arguments (draw rect / pick template)", () -> callbacks.onConfig().accept(mib));
            config.setMinWidth(26);
            node.getChildren().add(config);
        }
        // Delete (✕), the row-level twin of the main editor's per-block delete. Generated scaffolding is not
        // the user's to remove, so a read-only row simply doesn't offer it.
        if (!locked) {
            Button remove = OverlayStyles.iconButton(
                    "✕", "Delete this block (Del)", () -> callbacks.onDelete().delete(stmt, row.body(), row.index()));
            remove.setMinWidth(26);
            node.getChildren().add(remove);
        }
        return node;
    }

    /**
     * A branch label — {@code else}, {@code case A:}, {@code otherwise}. Clickable like any row: it parks the
     * caret at the top of the branch it introduces, which is the only way to aim at an empty branch.
     */
    private HBox captionRow(BlockTree.Row row, InsertionCursor cursor) {
        Label text = new Label(row.caption());
        text.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-text-fill: #b8a2e0;");
        HBox node = shell(row, focused(row, cursor));
        node.getChildren().add(text);
        if (row.body() != null) {
            node.setOnMouseClicked(e -> callbacks.onFocus().accept(new InsertionCursor(row.body(), -1)));
        }
        return node;
    }

    private HBox emptyRow(BlockTree.Row row, InsertionCursor cursor) {
        Label text = new Label("· (empty) ·");
        text.setStyle("-fx-font-style: italic; -fx-text-fill: #8b93a1;");
        HBox node = shell(row, cursor != null && cursor.body() == row.body());
        node.getChildren().add(text);
        node.setOnMouseClicked(e -> callbacks.onFocus().accept(new InsertionCursor(row.body(), 0)));
        return node;
    }

    /** The row container: indent, padding, and the focus highlight. */
    private static HBox shell(BlockTree.Row row, boolean focused) {
        HBox node = new HBox(6, indent(row.depth()));
        node.setAlignment(Pos.CENTER_LEFT);
        node.setPadding(new Insets(3, 6, 3, 6));
        node.setStyle(focused
                ? "-fx-background-color: rgba(74,144,226,0.35); -fx-background-radius: 4; "
                        + "-fx-border-color: #4a90e2; -fx-border-radius: 4;"
                : "-fx-background-color: transparent;");
        return node;
    }

    private static boolean focused(BlockTree.Row row, InsertionCursor c) {
        return c != null && c.body() == row.body() && c.index() == row.index();
    }

    private static Region indent(int depth) {
        Region r = new Region();
        r.setMinWidth(depth * 16.0);
        r.setPrefWidth(depth * 16.0);
        return r;
    }

    /** The full text a truncated row hides, plus why it can't be edited or won't compile. */
    private String rowTooltip(StatementBlock stmt, boolean locked, boolean broken) {
        StringBuilder sb = new StringBuilder(fullText(stmt));
        if (locked) sb.append("\n\n🔒 Generated code — edit it in the project's activity flow, not here.");
        if (broken) {
            for (var d : diagnostics.getDiagnosticsForBlock(stmt)) sb.append("\n\n✖ ").append(d.getMessage());
        }
        return sb.toString();
    }

    private static String fullText(CodeBlock block) {
        ASTNode n = block.getAstNode();
        return n == null ? block.getClass().getSimpleName() : n.toString().strip();
    }

    /** One-line summary of a block: the first source line of its AST node, trimmed and truncated. */
    static String compactLabel(CodeBlock block) {
        ASTNode n = block.getAstNode();
        if (n == null) return block.getClass().getSimpleName();
        String s = n.toString().strip();
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl).strip();
        if (s.endsWith("{")) s = s.substring(0, s.length() - 1).strip();
        return s.length() > MAX_LABEL_CHARS ? s.substring(0, MAX_LABEL_CHARS - 3) + "…" : s;
    }
}
