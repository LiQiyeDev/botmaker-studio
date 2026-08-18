package com.botmaker.studio.ui.fx;

import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.ui.app.flow.ActivityDraft;
import com.botmaker.studio.ui.app.flow.FlowCanvas;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.CubicCurve;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Activity Flow canvas is painted by the stylesheet, not by {@code setStyle}.
 *
 * <p>The report was "in dark mode the activity node is completely white and unreadable", and the cause was
 * mechanical: {@code NodeCard.restyle} wrote {@code -fx-background-color: white} inline, which in JavaFX beats
 * every author stylesheet — so no theme could have reached it. The labels on the card kept following the
 * theme, which is what made it white-on-white rather than merely light.
 *
 * <p>Two things are asserted per surface: that the colour <em>changes</em> with the theme (nothing is pinned
 * to a literal any more) and that the dark one is actually dark. The grid gets its own test because it is the
 * one surface CSS cannot paint — a {@link javafx.scene.canvas.Canvas} takes a {@link Color} — so the canvas
 * keeps an invisible {@code .flow-grid-ink} probe and reads the colour off that. If the probe stops resolving,
 * the grid silently freezes at its fallback and nothing else notices.
 */
class FlowCanvasThemingTest extends FxHeadlessTest {

    private FlowCanvas canvas;
    private Scene scene;

    /** {@link BlockTheme} is static and persisted, so the user's real theme is put back after each test. */
    private BlockTheme.ThemeType saved;

    @Override
    public void start(Stage stage) {
        canvas = new FlowCanvas();
        scene = ThemedWindows.scene(canvas, 900, 600);
        stage.setScene(scene);
        stage.show();
    }

    @BeforeEach
    void rememberTheme() {
        saved = BlockTheme.getCurrentThemeType();
    }

    @AfterEach
    void restoreTheme() {
        interact(() -> BlockTheme.setTheme(saved));
    }

    /** The resolved fill of the first node matching {@code selector}, under {@code theme}. */
    private Paint fillUnder(BlockTheme.ThemeType theme, String selector) {
        BlockTheme.setTheme(theme);
        ThemedWindows.applyThemeClass(scene.getRoot());
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        Region region = (Region) scene.getRoot().lookup(selector);
        assertNotNull(region, selector + " must exist on the canvas");
        assertNotNull(region.getBackground(), selector + " must be painted by a rule in blocks.css");
        assertTrue(!region.getBackground().getFills().isEmpty(), selector + " has no background fill");
        return region.getBackground().getFills().getFirst().getFill();
    }

    private void addCard(String name, double x) {
        interact(() -> canvas.add(new ActivityDraft(name, "", true, List.of(), List.of(), true, true, x, 60)));
    }

    @Test
    void anActivityCardTakesItsColourFromTheTheme() {
        addCard("Mining", 60);

        interact(() -> {
            Paint light = fillUnder(BlockTheme.ThemeType.DEFAULT, ".flow-card");
            Paint dark = fillUnder(BlockTheme.ThemeType.DARK, ".flow-card");

            assertNotEquals(light, dark, "the card was a hard-coded white — it has to follow the theme now");
            assertNotEquals(Color.WHITE, dark, "and a white card in the Dark theme is the reported bug");
            assertTrue(((Color) dark).getBrightness() < 0.5, "the dark card is dark: " + dark);
        });
    }

    /**
     * The grid's colour comes back through the probe. Nothing draws this Region — it exists so that the
     * painter has a stylesheet to ask, and a rule that stopped resolving would leave the grid stuck at the
     * light theme's fallback with no visible failure.
     */
    @Test
    void theGridInkProbeResolvesPerTheme() {
        interact(() -> {
            Paint light = fillUnder(BlockTheme.ThemeType.DEFAULT, ".flow-grid-ink");
            Paint dark = fillUnder(BlockTheme.ThemeType.DARK, ".flow-grid-ink");

            assertNotEquals(light, dark, "the grid dot follows -bm-flow-grid, which each theme redefines");
            assertTrue(((Color) dark).getBrightness() < ((Color) light).getBrightness(),
                    "the dark theme's grid is the darker of the two: " + dark + " vs " + light);
        });
    }

    /**
     * Every wire carries a transparent companion curve at six times the width. That is the hitbox: the visible
     * stroke is 2.5px, and picking a 2.5px curve at a zoom of 0.4 is pixel-hunting — which is what made a
     * mis-drawn wire feel permanent.
     */
    @Test
    void aWireIsPickedThroughAFatTransparentCompanion() {
        addCard("Mining", 60);
        addCard("Smelt", 400);
        interact(() -> {
            canvas.edges().add(new FlowEdge("Mining", "Smelt", ""));
            canvas.refresh();
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            CubicCurve visible = (CubicCurve) scene.getRoot().lookup(".flow-wire");
            CubicCurve hit = (CubicCurve) scene.getRoot().lookup(".flow-wire-hit");
            assertNotNull(visible, "the wire itself");
            assertNotNull(hit, "and the hitbox behind it");

            assertEquals(Color.TRANSPARENT, hit.getStroke(), "the hitbox is never seen");
            assertTrue(hit.getStrokeWidth() >= 4 * visible.getStrokeWidth(),
                    "hitbox " + hit.getStrokeWidth() + " vs visible " + visible.getStrokeWidth());
            assertEquals(visible.getStartX(), hit.getStartX(), "same geometry, or it is a hitbox for nothing");
            assertEquals(visible.getEndY(), hit.getEndY());
        });
    }
}
