package com.botmaker.studio.ui.fx;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the headless TestFX + Monocle harness works end-to-end, independent of any Studio code:
 * a self-contained scene with one {@link Button} is clicked by the robot and its state is asserted.
 *
 * <p>Kept deliberately app-agnostic so that if it fails, the harness (not Studio) is at fault —
 * this isolates "the headless stack is broken" from "a Studio screen regressed".
 */
class FxHarnessSmokeTest extends FxHeadlessTest {

    private Button button;
    private int clicks;

    @Override
    public void start(Stage stage) {
        button = new Button("click me");
        button.setId("smoke-button");
        button.setOnAction(e -> clicks++);
        stage.setScene(new Scene(new StackPane(button), 200, 120));
        stage.show();
    }

    @Test
    void robotClicksRealButtonHeadless() {
        assertEquals(0, clicks, "precondition: not yet clicked");

        clickOn("#smoke-button");

        assertEquals(1, clicks, "the Monocle/TestFX robot should have delivered a real click");
    }
}
