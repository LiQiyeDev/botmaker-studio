package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The row-and-overflow rule behind the toolbar. No JavaFX: the packing was pulled out of {@link OverflowBar}
 * precisely so the thing that decides which buttons the user can see is answerable at a width, on a list of
 * numbers, without a window.
 *
 * <p>Widths are round numbers with a gap of 10, so each expectation can be read off by arithmetic.
 */
public class ToolbarPackingTest {

    private static final double GAP = 10;
    private static final double MORE = 30;

    private static ToolbarPacking.Packing pack(double width, int maxRows, double... widths) {
        return ToolbarPacking.pack(widths, width, GAP, maxRows, MORE);
    }

    @Test
    void everythingOnOneRowWhenThereIsRoom() {
        ToolbarPacking.Packing packing = pack(500, 2, 100, 100, 100);
        assertEquals(List.of(List.of(0, 1, 2)), packing.rows());
        assertTrue(packing.overflow().isEmpty(), "nothing hidden while it fits");
    }

    @Test
    void whatDoesNotFitWrapsWhileThereIsAnotherRow() {
        // 100+10+100 = 210 fits in 250; a third would need 320.
        ToolbarPacking.Packing packing = pack(250, 2, 100, 100, 100);
        assertEquals(List.of(List.of(0, 1), List.of(2)), packing.rows());
        assertTrue(packing.overflow().isEmpty());
    }

    @Test
    void pastTheLastRowItOverflowsInsteadOfWrapping() {
        // Four buttons, two rows of 250: two per row, and the fifth has nowhere to go.
        ToolbarPacking.Packing packing = pack(250, 2, 100, 100, 100, 100, 100);
        assertEquals(2, packing.rows().size(), "the cap is the cap");
        assertEquals(List.of(4), packing.overflow());
    }

    @Test
    void theOverflowButtonIsChargedOnlyOnceItIsNeeded() {
        // 210 of buttons in a 220 bar: it fits, and it must not be pushed into a menu to make room for the
        // menu button. Widen nothing, add one more item, and the reserve appears — 100+10+100 no longer fits
        // beside a 30px » in 220, so the second row keeps one button and two go to the menu.
        assertTrue(pack(220, 1, 100, 100).overflow().isEmpty());
        ToolbarPacking.Packing tight = pack(220, 1, 100, 100, 100);
        assertEquals(List.of(List.of(0)), tight.rows());
        assertEquals(List.of(1, 2), tight.overflow());
    }

    @Test
    void orderIsKeptRatherThanFillingTheLastGap() {
        // The narrow item at the end would fit beside the first two. Letting it jump the wide one would
        // reorder the toolbar under the user, so once something overflows, everything after it does.
        // One row of 250 less the 40 the » costs: two 100s fit, the 200 does not, and the 20 that would have
        // slipped in after it follows it into the menu.
        ToolbarPacking.Packing packing = pack(250, 1, 100, 100, 200, 20);
        assertEquals(List.of(List.of(0, 1)), packing.rows());
        assertEquals(List.of(2, 3), packing.overflow());
    }

    @Test
    void anItemWiderThanTheBarStillGetsItsRow() {
        // Otherwise a single over-wide control would overflow at every width and the bar would look empty.
        ToolbarPacking.Packing packing = pack(100, 2, 400, 50);
        assertEquals(List.of(0), packing.rows().getFirst());
    }

    @Test
    void aRowCapBelowOneIsTreatedAsOne() {
        ToolbarPacking.Packing packing = pack(250, 0, 100, 100, 100);
        assertEquals(1, packing.rows().size());
    }
}
