package com.botmaker.studio.ui.app;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * A row of controls that is allowed to wrap, but only so far: past {@link #maxRows} rows, what doesn't fit
 * goes into a {@code »} menu at the end instead of onto another row.
 *
 * <p>It replaces the {@link javafx.scene.layout.FlowPane}s the toolbar was built from. A FlowPane's height is
 * unbounded by design — it answers "how tall must I be at this width" with however many rows that takes — so
 * the narrower the window got, the taller the toolbar grew, and since a {@code Region} does not clip its
 * children the extra rows painted upward over the menu bar. Every workaround for that was a way of *asking*
 * the bar to stay short (a wrap length bound to a share of the width, a min height pinned to pref, a min width
 * of zero on every group) while the layout was still free to say no. Capping the row count is the same
 * statement made where it can be enforced: at four rows' worth of buttons in a two-row bar, two rows are what
 * comes back, and the rest is one click away rather than on top of the File menu.
 *
 * <p>The menu is built from the controls themselves — a {@link Labeled}'s text, and for a {@link ButtonBase}
 * an item that fires it — so an overflowed button does the same thing from the menu as from the bar, and a
 * button added to the toolbar tomorrow needs no second registration here to survive being overflowed. A
 * control that is neither (a status label) still appears, greyed, because "the resolution readout is over
 * there" is more useful than its silent disappearance.
 *
 * <p>Width comes from {@code prefWidth} where a caller sets one, because the layout that contains this — a
 * {@code BorderPane} — hands its edge children their <em>preferred</em> width and its centre child whatever is
 * left. That is why {@code UIManager} binds the run cluster's preferred width to a share of the bar.
 */
public class OverflowBar extends Region {

    private final List<Node> items = new ArrayList<>();
    private final MenuButton more = new MenuButton("»");
    private final double hgap;
    private final double vgap;
    private final int maxRows;
    private final HPos alignment;

    /** What is currently in the menu, so it is only rebuilt when the set actually changes. */
    private List<Node> overflowed = List.of();

    public OverflowBar(double hgap, double vgap, int maxRows, HPos alignment, Node... items) {
        this.hgap = hgap;
        this.vgap = vgap;
        this.maxRows = Math.max(1, maxRows);
        this.alignment = alignment;
        this.items.addAll(List.of(items));
        more.getStyleClass().add("toolbar-btn");
        more.setTooltip(new Tooltip("The rest of the toolbar — too narrow to show it all"));
        more.setVisible(false);
        getChildren().addAll(this.items);
        getChildren().add(more);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double width = Math.max(0, getWidth() - in.getLeft() - in.getRight());
        Packing packing = packFor(width);
        applyOverflow(packing.overflow());

        double rowHeight = rowHeight();
        double y = in.getTop();
        for (int r = 0; r < packing.rows().size(); r++) {
            List<Node> row = packing.rows().get(r);
            boolean lastRow = r == packing.rows().size() - 1;
            boolean withMore = lastRow && !packing.overflow().isEmpty();
            double rowWidth = rowWidth(row) + (withMore ? hgap + more.prefWidth(-1) : 0);
            double x = in.getLeft() + switch (alignment) {
                case LEFT -> 0;
                case CENTER -> Math.max(0, (width - rowWidth) / 2);
                case RIGHT -> Math.max(0, width - rowWidth);
            };
            for (Node item : row) {
                double w = itemWidth(item);
                item.resizeRelocate(x, y + (rowHeight - item.prefHeight(-1)) / 2, w, item.prefHeight(-1));
                x += w + hgap;
            }
            if (withMore) {
                double w = more.prefWidth(-1);
                more.resizeRelocate(x, y + (rowHeight - more.prefHeight(-1)) / 2, w, more.prefHeight(-1));
            }
            y += rowHeight + vgap;
        }
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        return in.getLeft() + rowWidth(items) + in.getRight();
    }

    /**
     * Zero, deliberately. The bar's own preferred width must never become a floor under the window's: a
     * toolbar is the one part of a window that is allowed to become less useful rather than stop it shrinking.
     */
    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        // A width of -1 means "answer without one", which a wrapping layout cannot honestly do. The bar's own
        // current width is the best available answer and is what it will in fact be laid out at.
        double w = width >= 0 ? width : getWidth();
        int rows = packFor(Math.max(0, w - in.getLeft() - in.getRight())).rows().size();
        return in.getTop() + rows * rowHeight() + Math.max(0, rows - 1) * vgap + in.getBottom();
    }

    /** The same as the preferred height: the rows the bar has decided on are not negotiable, and there are
     *  at most {@link #maxRows} of them, so this is a bound rather than the open-ended demand a FlowPane makes. */
    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    private double rowHeight() {
        double h = more.prefHeight(-1);
        for (Node item : items) h = Math.max(h, item.prefHeight(-1));
        return h;
    }

    private double rowWidth(List<Node> row) {
        double w = 0;
        for (Node item : row) w += itemWidth(item) + hgap;
        return Math.max(0, w - hgap);
    }

    /**
     * How wide an item is laid out. The cap matters: the two buttons whose label tracks project state are
     * given a {@code maxWidth} so a long game title ellipsizes instead of widening the bar, and a packing that
     * read only {@code prefWidth} would go on reserving the room the title asked for.
     */
    private static double itemWidth(Node item) {
        double pref = item.prefWidth(-1);
        return item instanceof Region region ? Math.min(pref, region.maxWidth(-1)) : pref;
    }

    /** The rows and the overflow at {@code width}, as nodes — the arithmetic is {@link ToolbarPacking}. */
    private Packing packFor(double width) {
        double[] widths = new double[items.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = itemWidth(items.get(i));
        ToolbarPacking.Packing packing = ToolbarPacking.pack(widths, width, hgap, maxRows, more.prefWidth(-1));
        List<List<Node>> rows = new ArrayList<>();
        for (List<Integer> row : packing.rows()) rows.add(row.stream().map(items::get).toList());
        return new Packing(rows, packing.overflow().stream().map(items::get).toList());
    }

    private void applyOverflow(List<Node> overflow) {
        for (Node item : items) item.setVisible(!overflow.contains(item));
        more.setVisible(!overflow.isEmpty());
        if (overflow.equals(overflowed)) return;
        overflowed = List.copyOf(overflow);
        more.getItems().setAll(overflow.stream().map(OverflowBar::menuItemFor).toList());
    }

    /** The menu stand-in for a control that didn't fit. See the class note for why it is derived, not declared. */
    private static MenuItem menuItemFor(Node node) {
        String text = node instanceof Labeled labeled ? labeled.getText() : node.getId();
        MenuItem item = new MenuItem(text == null || text.isBlank() ? "…" : text);
        if (node instanceof ButtonBase button) {
            item.setOnAction(e -> button.fire());
            item.disableProperty().bind(button.disabledProperty());
        } else {
            // A label has nothing to fire. It is here to be read, which a disabled item still allows.
            item.setDisable(true);
        }
        return item;
    }

    private record Packing(List<List<Node>> rows, List<Node> overflow) {}
}
