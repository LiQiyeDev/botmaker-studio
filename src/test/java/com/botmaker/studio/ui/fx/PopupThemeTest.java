package com.botmaker.studio.ui.fx;

import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Popups follow the theme. A {@link ContextMenu} lives in its own {@link javafx.stage.Window} with its own
 * {@link Scene}, created by the skin when it is shown — it inherits neither {@code blocks.css} nor the theme's
 * style class from the window that opened it, which is why every block menu, right-click menu and tooltip in
 * Studio stayed stock light Modena in a dark app. {@link ThemedWindows#install()} is the hook that fixes it.
 *
 * <p><b>The thing that is easy to get wrong, and that these tests pin down.</b> The two halves of theming a
 * popup arrive by <em>different</em> routes. The stylesheet has to be on the popup's own scene — that is what
 * makes the {@code .context-menu} rules exist at all. But the {@code -bm-*} tokens those rules look up are
 * <em>not</em> resolved through the popup's scene root: {@code PopupControl}'s CSS bridge reports the popup's
 * <em>owner node</em> as its styleable parent, so the lookup walks into the window that opened the menu. A
 * popup whose owner chain is unthemed renders light no matter what class its own root carries — which is why
 * {@link #showMenuUnder} themes the owner, and why the class on the popup root is the fallback, not the fix.
 */
class PopupThemeTest extends FxHeadlessTest {

    /** The user's real saved theme, restored after each test — {@link BlockTheme} is static and persisted. */
    private BlockTheme.ThemeType saved;

    private Stage owner;

    @Override
    public void start(Stage stage) {
        ThemedWindows.install();
        stage.setScene(ThemedWindows.scene(new StackPane(), 200, 100));
        stage.show();
        owner = stage;
    }

    @BeforeEach
    void rememberTheme() {
        saved = BlockTheme.getCurrentThemeType();
    }

    @AfterEach
    void restoreTheme() {
        interact(() -> BlockTheme.setTheme(saved));
    }

    /** Shows a one-item menu on the themed owner window under {@code theme} and returns its popup scene. */
    private Scene showMenuUnder(BlockTheme.ThemeType theme) {
        BlockTheme.setTheme(theme);
        // The owner chain is what the popup's lookups resolve through (see the class javadoc). In Studio the
        // main window carries the class already; here it is applied explicitly so the test does not depend on
        // whichever theme the previous test left on this stage.
        ThemedWindows.applyThemeClass(owner.getScene().getRoot());
        ContextMenu menu = new ContextMenu(new MenuItem("Anything"));
        menu.show(owner, 0, 0);
        menu.getScene().getRoot().applyCss();
        menu.getScene().getRoot().layout();
        return menu.getScene();
    }

    /**
     * The node the {@code .context-menu} rules actually paint. A shown ContextMenu carries that style class
     * <em>twice</em> — on the PopupControl's own CSS bridge and again on the skin's {@code ContextMenuContent}
     * inside it — and only the inner one is a painted Region; the bridge's background stays null. A plain
     * {@code lookup(".context-menu")} finds the bridge and asserts nothing, so the selector is the pair.
     */
    private static Region menuPaneOf(Scene popupScene) {
        return (Region) popupScene.getRoot().lookup(".context-menu .context-menu");
    }

    /**
     * The popup's rules are the ones this stylesheet added, not Modena's — asserted through the padding
     * rather than the background colour, and the reason is worth recording.
     *
     * <p>Asserting the <em>colour</em> is what this test wanted to do and cannot do honestly here. The fill
     * comes from a looked-up token, and a popup resolves its lookups through the styleable parent its CSS
     * bridge reports — under a headless TestFX primary stage that chain leads to a scene root the test does
     * not control (it reports the theme a previous test method left on it), so the assertion would be
     * measuring TestFX's stage reuse, not the theming. The padding travels the same rule with no lookup in
     * it, so it proves the rule applied; the colour is left to the manual check in the plan's verification
     * ("open a block's expression menu, a right-click menu, a combo box and hover a tooltip in Dark and
     * Black"). A headless assertion on the resolved fill is worth revisiting with an explicitly-owned stage.
     */
    @Test
    void aContextMenusChromeComesFromThisStylesheetRatherThanModena() {
        interact(() -> {
            Region pane = menuPaneOf(showMenuUnder(BlockTheme.ThemeType.DARK));

            assertNotNull(pane, "a shown ContextMenu must expose its content pane");
            assertEquals(new Insets(4, 0, 4, 0), pane.getPadding(),
                    "Modena pads a context menu on all four sides; this stylesheet pads only top and bottom");
        });
    }

    /** The other half of install(): the popup scene's own stylesheet, without which the rules don't exist. */
    @Test
    void aPopupSceneCarriesTheBlocksStylesheet() {
        interact(() -> {
            Scene popup = showMenuUnder(BlockTheme.ThemeType.DARK);

            assertTrue(popup.getStylesheets().stream().anyMatch(s -> s.endsWith("/css/blocks.css")),
                    "got " + popup.getStylesheets());
            assertTrue(popup.getRoot().getStyleClass().contains("dark-theme"),
                    "the popup's own root is themed too, for the popups with no owner node to inherit from");
        });
    }

    /**
     * The opt-out survives. A transparent capture surface marks its root {@link ThemedWindows#UNTHEMED} so the
     * global hook leaves it alone — giving it the shell's chrome is exactly the regression this guards.
     */
    @Test
    void aWindowMarkedUnthemedIsLeftAlone() {
        interact(() -> {
            BlockTheme.setTheme(BlockTheme.ThemeType.DARK);
            StackPane root = new StackPane();
            root.getStyleClass().add(ThemedWindows.UNTHEMED);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 100, 100));
            stage.show();
            try {
                assertTrue(stage.getScene().getStylesheets().isEmpty(), "no stylesheet was pushed onto it");
                assertFalse(root.getStyleClass().contains("dark-theme"), "and no theme class either");
            } finally {
                stage.close();
            }
        });
    }
}
