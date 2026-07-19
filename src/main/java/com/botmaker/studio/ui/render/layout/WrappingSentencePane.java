package com.botmaker.studio.ui.render.layout;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.List;

/**
 * An {@link HBox} that flows its children like words in a paragraph: when a row runs out of horizontal
 * space the next child wraps onto a new line, and every continuation line is hung-indented so a long call
 * reads as one wrapped sentence rather than a flat overflow. Children are never split or squeezed below
 * their preferred width — the only exception is a lone child wider than the whole row, which is clamped so
 * it can wrap <em>internally</em> — so no label is ever ellipsized and nothing is hidden at any nesting
 * depth. A single {@link Priority#ALWAYS} spacer on a row still soaks up the leftover width (so e.g. a
 * trailing delete button stays pushed to the right edge).
 *
 * <p>It subclasses {@code HBox} rather than a bare {@code Pane} so it is a drop-in for the many block
 * factories that build rows through {@code SentenceLayoutBuilder.build()} and keep the result typed as
 * {@code HBox} (style classes, {@code getChildren()}, {@code styleContainer(HBox)}); only the layout math
 * is overridden. Horizontal content bias is inherited from {@code HBox}, so height tracks the allotted
 * width. The wrap width comes from the surrounding {@code fitToWidth} canvas: a block whose natural width
 * exceeds the viewport is shrunk to it (max width stays at pref, so the box never grows past its content)
 * and wraps; a narrow block keeps its content width and never stretches.
 */
public class WrappingSentencePane extends HBox {

    private final double vgap;
    private final double indent;

    public WrappingSentencePane(double hgap) {
        this(hgap, 4, 18);
    }

    public WrappingSentencePane(double hgap, double vgap, double indent) {
        super(hgap);
        this.vgap = vgap;
        this.indent = indent;
    }

    private double hgap() {
        return getSpacing();
    }

    @Override
    protected double computeMinWidth(double height) {
        // Narrowest useful width: the widest single child, so a row can wrap down to one item per line.
        Insets in = getInsets();
        double widest = 0;
        for (Node c : getManagedChildren()) widest = Math.max(widest, c.prefWidth(-1));
        return in.getLeft() + widest + in.getRight();
    }

    @Override
    protected double computePrefWidth(double height) {
        // Natural single-line width, so an unconstrained parent doesn't force wrapping.
        Insets in = getInsets();
        double w = 0;
        boolean first = true;
        for (Node c : getManagedChildren()) {
            if (!first) w += hgap();
            w += c.prefWidth(-1);
            first = false;
        }
        return in.getLeft() + w + in.getRight();
    }

    @Override
    protected double computePrefHeight(double width) {
        return layoutRows(width < 0 ? getWidth() : width, false);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        layoutRows(getWidth(), true);
    }

    /**
     * The single source of truth for both measuring ({@code place=false}) and placing ({@code place=true}):
     * greedily packs children into rows within {@code width}, hang-indents continuation rows, vertically
     * centres each child in its row, and returns the total height.
     */
    private double layoutRows(double width, boolean place) {
        Insets in = getInsets();
        List<Node> children = getManagedChildren();
        // width <= 0 means "not laid out yet" — treat as unbounded so we report a single-row height estimate.
        double contentRight = (width <= 0 ? Double.MAX_VALUE : width - in.getRight());
        double y = in.getTop();
        boolean firstRow = true;
        int i = 0;

        while (i < children.size()) {
            double startX = in.getLeft() + (firstRow ? 0 : indent);
            int rowStart = i;
            double x = startX;
            double rowHeight = 0;

            while (i < children.size()) {
                Node c = children.get(i);
                double cw = clampWidth(c.prefWidth(-1), contentRight, startX);
                if (i != rowStart && x + cw > contentRight + 0.5) break;
                rowHeight = Math.max(rowHeight, c.prefHeight(cw));
                x += cw + hgap();
                i++;
            }

            if (place) placeRow(children, rowStart, i, startX, y, rowHeight, contentRight);

            y += rowHeight + vgap;
            firstRow = false;
        }
        return (children.isEmpty() ? y : y - vgap) + in.getBottom();
    }

    private void placeRow(List<Node> children, int from, int to, double startX, double y,
                          double rowHeight, double contentRight) {
        // Leftover width on this row is handed to any Hgrow=ALWAYS children (spacers), so trailing controls
        // pushed by a spacer keep hugging the right edge exactly as they did under a plain HBox.
        double used = 0;
        int growCount = 0;
        for (int j = from; j < to; j++) {
            Node c = children.get(j);
            used += clampWidth(c.prefWidth(-1), contentRight, startX) + (j > from ? hgap() : 0);
            if (Priority.ALWAYS.equals(HBox.getHgrow(c))) growCount++;
        }
        double leftover = (contentRight == Double.MAX_VALUE) ? 0 : Math.max(0, contentRight - startX - used);
        double extra = growCount > 0 ? leftover / growCount : 0;

        double px = startX;
        for (int j = from; j < to; j++) {
            Node c = children.get(j);
            double cw = clampWidth(c.prefWidth(-1), contentRight, startX);
            if (extra > 0 && Priority.ALWAYS.equals(HBox.getHgrow(c))) cw += extra;
            double ch = c.prefHeight(cw);
            double cy = y + (rowHeight - ch) / 2;
            c.resizeRelocate(snapPositionX(px), snapPositionY(cy), snapSizeX(cw), snapSizeY(ch));
            px += cw + hgap();
        }
    }

    /** A child wider than a full row is clamped to the row span so it wraps internally instead of overflowing. */
    private static double clampWidth(double prefWidth, double contentRight, double startX) {
        double span = contentRight - startX;
        return prefWidth > span ? Math.max(1, span) : prefWidth;
    }
}
