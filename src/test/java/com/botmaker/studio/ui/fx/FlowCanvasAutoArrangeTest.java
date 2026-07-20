package com.botmaker.studio.ui.fx;

import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.ui.app.flow.ActivityDraft;
import com.botmaker.studio.ui.app.flow.FlowCanvas;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Auto-arrange must be <b>idempotent</b>: clicking it twice on an unchanged flow has to leave every card where
 * the first click put it.
 *
 * <p>The reported bug was the opposite. With no wires drawn, the layer walk positioned only the start card
 * (the reachability walk from the start reaches nothing else) and the orphan pass placed nothing at all
 * ({@code FlowRules.orphans} returns empty when there are no edges), so every other card kept its previous
 * coordinates. {@code centreOnCanvas} then translated <em>all</em> cards by a delta derived from their current
 * bounding box — feeding those stale coordinates straight back in. Each click widened the box and pushed the
 * unlinked cards further out, so they crept apart indefinitely.
 *
 * <p>The fix is that every placed card is given a fresh position on each run, so these tests assert the
 * fixed-point property rather than any particular layout.
 */
class FlowCanvasAutoArrangeTest extends FxHeadlessTest {

    private FlowCanvas canvas;

    @Override
    public void start(Stage stage) {
        canvas = new FlowCanvas();
        stage.setScene(new Scene(canvas, 900, 600));
        stage.show();
    }

    /** Adds {@code count} activities at the staggered spots the canvas itself hands out for new cards. */
    private void addActivities(int count) {
        interact(() -> {
            for (int i = 0; i < count; i++) {
                canvas.add(new ActivityDraft("Act" + i, "", true, List.of(), List.of(), true,
                        60 + i * 20, 60 + i * 20));
            }
        });
    }

    private void autoArrange() {
        interact(canvas::autoArrange);
    }

    /** Every card's position, in a stable order, as the "layout" to compare across runs. */
    private List<String> positions() {
        List<String> out = new ArrayList<>();
        for (ActivityDraft d : canvas.drafts()) {
            out.add(d.name() + "@" + Math.round(d.x()) + "," + Math.round(d.y()));
        }
        return out;
    }

    @Test
    void repeatedAutoArrangeOfAnUnwiredFlowIsAFixedPoint() {
        addActivities(5);

        autoArrange();
        List<String> first = positions();

        autoArrange();
        autoArrange();

        assertEquals(first, positions(),
                "with nothing wired, re-arranging must not move anything — this is the drift bug");
    }

    @Test
    void anUnwiredFlowIsLaidOutRatherThanLeftScattered() {
        addActivities(4);
        List<String> before = positions();

        autoArrange();

        assertNotEquals(before, positions(), "auto-arrange must actually lay the unwired cards out");
        // Nothing may be left at a negative coordinate, and the cards must share a common grid: with the bug,
        // one card sat at the origin while the rest were flung far to the right.
        for (ActivityDraft d : canvas.drafts()) {
            org.junit.jupiter.api.Assertions.assertTrue(d.x() >= 0 && d.y() >= 0, d.name() + " went off-canvas");
        }
    }

    @Test
    void repeatedAutoArrangeOfAPartiallyWiredFlowIsAlsoAFixedPoint() {
        // The orphan case: a wired chain plus cards nothing reaches. The orphans used to be the ones that drifted.
        addActivities(5);
        interact(() -> {
            canvas.edges().add(new FlowEdge("Act0", "Act1", FlowEdge.NEXT_OUTCOME));
            canvas.edges().add(new FlowEdge("Act1", "Act2", FlowEdge.NEXT_OUTCOME));
            canvas.setStart("Act0");
        });

        autoArrange();
        List<String> first = positions();

        autoArrange();
        autoArrange();

        assertEquals(first, positions(), "orphaned cards must settle too, not drift on every click");
    }
}
