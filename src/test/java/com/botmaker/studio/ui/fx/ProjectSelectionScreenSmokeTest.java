package com.botmaker.studio.ui.fx;

import com.botmaker.studio.ui.app.ProjectSelectionScreen;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the real {@link ProjectSelectionScreen} — the screen {@code BotMakerStudio.start()} shows on
 * launch — headlessly and asserts its core controls are present. This exercises the actual Studio
 * JavaFX layer (scene construction + rendering) that the AST-level logic tests deliberately skip.
 *
 * <p>Assertions stay at the presence/label level (not on the project rows, which are backed by the
 * filesystem/network) so the test is deterministic on any machine.
 */
class ProjectSelectionScreenSmokeTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // The second arg is the "project selected" callback (name, clearCache, freshlyCreated); a no-op is
        // fine — we never open a project.
        ProjectSelectionScreen screen = new ProjectSelectionScreen(stage, (name, clearCache, freshlyCreated) -> {});
        stage.setScene(screen.createScene());
        stage.show();
    }

    @Test
    void selectionScreenRendersItsCoreControls() {
        // lookup(String) matches Labeled nodes (Label/Button) by their text.
        assertTrue(lookup("Select a Project").tryQuery().isPresent(),
                "title label should render");

        assertButtonPresent("Open Project");
        assertButtonPresent("Create New Project");
        assertButtonPresent("Browse Gallery");
    }

    private void assertButtonPresent(String text) {
        Button button = lookup(text).queryButton();
        assertNotNull(button, "button should render: " + text);
        assertEquals(text, button.getText());
    }
}
