package com.botmaker.studio.ui.fx;

import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SDK call surface and the argument pill drawn on it, per theme. Both were literals — {@code #f3ecfb} with
 * no on-colour (so its labels took the {@code .root} default {@code white}: white on near-white) and a pill
 * whose light/dark wash was a boolean fixed at construction. These assert what a screenshot would: the fill
 * moves with the theme, and the wash moves with the fill.
 */
class SdkBlockContrastTest extends FxHeadlessTest {

    /** The user's real saved theme, restored after each test — {@link BlockTheme} is static and persisted. */
    private BlockTheme.ThemeType saved;

    private Region sdkBlock;
    private Region pill;

    @BeforeEach
    void rememberTheme() {
        saved = BlockTheme.getCurrentThemeType();
    }

    @AfterEach
    void restoreTheme() {
        interact(() -> BlockTheme.setTheme(saved));
    }

    /** Styles an SDK block with a pill inside it under {@code theme}, and leaves both in the fields above. */
    private void render(BlockTheme.ThemeType theme) {
        BlockTheme.setTheme(theme);
        pill = new Region();
        pill.getStyleClass().add("argument-pill");
        StackPane block = new StackPane(pill);
        block.getStyleClass().add("sdk-call-block");
        sdkBlock = block;
        StackPane root = new StackPane(block);
        ThemedWindows.scene(root, 200, 100);
        root.applyCss();
        root.layout();
    }

    private static Color fillOf(Region region) {
        return (Color) region.getBackground().getFills().get(0).getFill();
    }

    @Test
    void theSdkSurfaceIsAThemedTokenRatherThanTheOldLilacLiteral() {
        interact(() -> {
            render(BlockTheme.ThemeType.DEFAULT);
            assertEquals(Color.web("#f3ecfb"), fillOf(sdkBlock), "light theme keeps the pale lilac");

            render(BlockTheme.ThemeType.DARK);
            assertEquals(Color.web("#3a1c4a"), fillOf(sdkBlock),
                    "a literal fill is what no theme could reach — the dark theme must repaint it");
        });
    }

    /** The pill picks its wash by ladder() on the surface's on-colour; a broken ladder drops the rule outright. */
    @Test
    void theArgumentPillWashFollowsTheSurfaceItLandsOn() {
        interact(() -> {
            render(BlockTheme.ThemeType.DEFAULT);
            Color onLight = fillOf(pill);
            assertTrue(onLight.getBrightness() < 0.5,
                    "a pale SDK surface wants the translucent-black wash, got " + onLight);

            render(BlockTheme.ThemeType.DARK);
            Color onDark = fillOf(pill);
            assertTrue(onDark.getBrightness() > 0.5,
                    "the same pill on a dark surface must flip to the translucent-white wash, got " + onDark);
            assertTrue(onDark.getOpacity() < 0.5, "the wash is a wash, not a panel");
        });
    }
}
