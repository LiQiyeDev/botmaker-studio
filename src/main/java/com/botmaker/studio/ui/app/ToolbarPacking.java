package com.botmaker.studio.ui.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Where an {@link OverflowBar}'s controls go: which row each lands on, and which don't fit at all.
 *
 * <p>Pure arithmetic over widths — no JavaFX — for the same reason {@code FlowRules} is: this is the part with
 * the edge cases (the last row has to give up space for the {@code »} button, but only once something is
 * actually going in it) and a rule that decides what the user can see is worth being able to test without a
 * toolkit and a window.
 */
final class ToolbarPacking {

    private ToolbarPacking() {}

    /**
     * @param rows     item indices per row, in order; never more than {@code maxRows} rows
     * @param overflow item indices that did not fit, in order — what the {@code »} menu holds
     */
    record Packing(List<List<Integer>> rows, List<Integer> overflow) {}

    /**
     * Packs {@code widths} into at most {@code maxRows} rows of {@code width}, putting what doesn't fit into
     * {@link Packing#overflow}.
     *
     * <p>Two passes, because the {@code »} button's own width may only be charged once it is known to be
     * needed: reserving it up front would push an item into the menu to make room for the menu.
     */
    static Packing pack(double[] widths, double width, double hgap, int maxRows, double moreWidth) {
        Packing free = packReserving(widths, width, hgap, maxRows, 0.0);
        return free.overflow().isEmpty() ? free : packReserving(widths, width, hgap, maxRows, moreWidth + hgap);
    }

    private static Packing packReserving(double[] widths, double width, double hgap, int maxRows,
                                         double reserve) {
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> overflow = new ArrayList<>();
        List<Integer> row = new ArrayList<>();
        double x = 0;
        for (int i = 0; i < widths.length; i++) {
            // Order is kept: once one item has overflowed, everything after it does too, rather than a narrow
            // button hopping over a wide one into the last gap and reading as a shuffled toolbar.
            if (!overflow.isEmpty()) {
                overflow.add(i);
                continue;
            }
            double w = widths[i];
            boolean lastRow = rows.size() == Math.max(1, maxRows) - 1;
            double limit = width - (lastRow ? reserve : 0);
            double needed = row.isEmpty() ? w : x + hgap + w;
            if (needed > limit && !row.isEmpty()) {
                if (lastRow) {
                    overflow.add(i);
                    continue;
                }
                rows.add(row);
                row = new ArrayList<>();
                needed = w;
            }
            row.add(i);
            x = needed;
        }
        if (!row.isEmpty()) rows.add(row);
        return new Packing(rows, overflow);
    }
}
