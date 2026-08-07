package com.botmaker.studio.ui.fx;

import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secondary windows follow the theme. Each JavaFX window owns its own {@link Scene} and inherits nothing from
 * its owner, so a dialog needs the stylesheet <em>and</em> the theme's style class or it shows a white Modena
 * pane in a dark app — which is what every Studio dialog did until {@link ThemedWindows} existed.
 */
class ThemedWindowsTest extends FxHeadlessTest {

    /** The user's real saved theme, restored after each test — {@link BlockTheme} is static and persisted. */
    private BlockTheme.ThemeType saved;

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    @BeforeEach
    void rememberTheme() {
        saved = BlockTheme.getCurrentThemeType();
    }

    @AfterEach
    void restoreTheme() {
        interact(() -> BlockTheme.setTheme(saved));
    }

    @Test
    void aThemedSceneCarriesTheStylesheetAndTheCurrentThemeClass() {
        interact(() -> {
            BlockTheme.setTheme(BlockTheme.ThemeType.DARK);
            Scene scene = ThemedWindows.scene(new VBox(), 200, 100);

            assertTrue(scene.getStylesheets().stream().anyMatch(s -> s.endsWith("blocks.css")),
                    "a dialog without blocks.css has no theme tokens to resolve");
            assertTrue(scene.getRoot().getStyleClass().contains("dark-theme"));
        });
    }

    @Test
    void aThemeSwitchedWhileTheWindowIsOpenReachesIt() {
        interact(() -> {
            BlockTheme.setTheme(BlockTheme.ThemeType.DEFAULT);
            VBox root = new VBox();
            stage.setScene(ThemedWindows.scene(root, 200, 100));
            stage.show();

            BlockTheme.setTheme(BlockTheme.ThemeType.BLACK);

            assertTrue(root.getStyleClass().contains("black-theme"));
            assertFalse(root.getStyleClass().contains("default-theme"), "exactly one theme class at a time");
        });
    }

    /** The other half of following the theme: a closed dialog must not stay in the static listener list. */
    @Test
    void aHiddenWindowStopsFollowingTheTheme() {
        interact(() -> {
            BlockTheme.setTheme(BlockTheme.ThemeType.DEFAULT);
            VBox root = new VBox();
            stage.setScene(ThemedWindows.scene(root, 200, 100));
            stage.show();
            stage.hide();

            BlockTheme.setTheme(BlockTheme.ThemeType.DARK);

            assertTrue(root.getStyleClass().contains("default-theme"),
                    "a hidden window's listener must have been dropped, not left holding its scene graph");
        });
    }
}
