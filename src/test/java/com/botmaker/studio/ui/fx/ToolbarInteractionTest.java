package com.botmaker.studio.ui.fx;

import com.botmaker.studio.events.ApplicationEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.ui.app.ToolbarManager;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the editor toolbar's two UI contracts ({@link ToolbarManager}): clicking a button publishes the right
 * request event on the {@link EventBus}, and the Run/Stop enablement tracks the app's run state as
 * {@code ProgramStarted/Stopped} events arrive. These are the UI-event-wiring assertions the AST-level tests
 * can't make.
 */
class ToolbarInteractionTest extends FxHeadlessTest {

    private EventBus bus;
    private final List<ApplicationEvent> published = new ArrayList<>();
    private Button runButton;
    private Button stopButton;

    @Override
    public void start(Stage stage) {
        bus = new EventBus(false);
        // A subscriber on the ApplicationEvent supertype receives every event the toolbar emits.
        bus.subscribe(ApplicationEvent.class, published::add);

        // No project settings needed for these edit/execution-group assertions (only the capture group reads them).
        ToolbarManager toolbar = new ToolbarManager(bus, null);
        HBox edit = toolbar.createEditGroup();
        HBox exec = toolbar.createExecutionGroup();

        runButton = (Button) exec.lookup(".btn-run");
        stopButton = (Button) exec.lookup(".btn-stop");

        stage.setScene(new Scene(new VBox(edit, exec), 600, 80));
        stage.show();
    }

    @Test
    void runButtonPublishesExecutionRequest() {
        assertNotNull(runButton, "the Run button should be locatable by its .btn-run style class");
        interact(runButton::fire);
        assertTrue(published.stream().anyMatch(e -> e instanceof CoreApplicationEvents.ExecutionRequestedEvent),
                "clicking Run should publish an ExecutionRequestedEvent");
    }

    @Test
    void compileButtonPublishesCompilationRequest() {
        // The "⚙ Compile" button is the only .toolbar-btn in the edit group.
        Button compile = editGroupCompileButton();
        interact(compile::fire);
        assertTrue(published.stream().anyMatch(e -> e instanceof CoreApplicationEvents.CompilationRequestedEvent),
                "clicking Compile should publish a CompilationRequestedEvent");
    }

    @Test
    void stopIsDisabledUntilRunningThenReEnablesRunOnStop() {
        assertFalse(runButton.isDisable(), "Run is enabled while idle");
        assertTrue(stopButton.isDisable(), "Stop is disabled while idle");

        publishAndFlush(new CoreApplicationEvents.ProgramStartedEvent());
        assertFalse(stopButton.isDisable(), "Stop enables once the program is running");
        assertTrue(runButton.isDisable(), "Run disables while running");

        publishAndFlush(new CoreApplicationEvents.ProgramStoppedEvent());
        assertFalse(runButton.isDisable(), "Run re-enables once the program stops");
        assertTrue(stopButton.isDisable(), "Stop disables again once idle");
    }

    // --- helpers ---

    private Button editGroupCompileButton() {
        // createEditGroup() returns Undo, Redo, then the styled Compile button; find it by its label.
        return (Button) runButton.getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && "⚙ Compile".equals(b.getText()))
                .findFirst().orElseThrow();
    }

    /** The toolbar subscribes to state events on the FX thread; publish then flush that queue before asserting. */
    private void publishAndFlush(ApplicationEvent event) {
        interact(() -> bus.publish(event));
        WaitForAsyncUtils.waitForFxEvents();
    }
}
