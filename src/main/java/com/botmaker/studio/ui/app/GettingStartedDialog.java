package com.botmaker.studio.ui.app;

import com.botmaker.studio.docs.RuntimeDiagram;
import com.botmaker.studio.docs.StudioAction;
import com.botmaker.studio.docs.Workflow;
import com.botmaker.studio.docs.WorkflowStep;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Help ▸ Getting Started — the BotMaker workflow as a walkthrough, with a jump button on each step that opens
 * the matching feature.
 *
 * <p>The words are <b>not</b> here: every heading and paragraph comes from {@link Workflow}, which
 * {@code WORKFLOW.md} is also rendered from, so the file and this dialog cannot say different things. What
 * belongs here is only the JavaFX — and the {@link Actions} map, which is the one thing a Markdown file has no
 * use for.
 */
public final class GettingStartedDialog {

    /**
     * The runnable behind each {@link StudioAction} a step can point at, supplied by {@link StudioActions}.
     *
     * <p>A destination with no runnable simply renders without a button, so a dialog opened before a project
     * is loaded degrades to plain text rather than throwing.
     */
    public record Actions(Map<StudioAction, Runnable> byDestination) {

        public Actions {
            byDestination = byDestination == null ? Map.of() : new EnumMap<>(byDestination);
        }

        public static Builder builder() {
            return new Builder();
        }

        Runnable get(StudioAction action) {
            return action == null ? null : byDestination.get(action);
        }

        public static final class Builder {
            private final Map<StudioAction, Runnable> map = new EnumMap<>(StudioAction.class);

            public Builder on(StudioAction action, Runnable run) {
                map.put(action, run);
                return this;
            }

            public Actions build() {
                return new Actions(map);
            }
        }
    }

    private final Stage owner;
    private final Actions actions;

    public GettingStartedDialog(Stage owner, Actions actions) {
        this.owner = owner;
        this.actions = actions;
    }

    public void show() {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Getting Started");

        Label heading = new Label(Workflow.TITLE);
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
        Label intro = new Label(Workflow.INTRO);
        intro.setWrapText(true);
        intro.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        VBox steps = new VBox(14);
        steps.getChildren().add(runtimeDiagram());
        steps.getChildren().add(new Separator());
        int number = 1;
        for (WorkflowStep step : Workflow.steps()) {
            steps.getChildren().add(render(number++, step));
        }

        ScrollPane scroll = new ScrollPane(steps);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(4));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button close = new Button("Close");
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, heading, intro, new Separator(), scroll, new Separator(), bar);
        root.setPadding(new Insets(18));
        stage.setScene(ThemedWindows.scene(root, 580, 660));
        stage.show();
    }

    /**
     * {@link RuntimeDiagram} as a column of boxes with arrows between them — the numbered steps say what you
     * do, this says what happens when you press Run.
     *
     * <p>It sits <em>above</em> step 1 rather than next to the "Run and debug" step, because the thing it
     * corrects (that activities run top to bottom, once each) is a belief people form while drawing the flow
     * graph, long before they run anything.
     */
    private VBox runtimeDiagram() {
        Label title = new Label(RuntimeDiagram.TITLE);
        title.getStyleClass().add("dialog-heading");
        Label intro = new Label(RuntimeDiagram.INTRO);
        intro.setWrapText(true);
        intro.getStyleClass().add("dialog-hint");

        VBox box = new VBox(4, title, intro);
        List<RuntimeDiagram.Node> chain = RuntimeDiagram.chain();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) box.getChildren().add(arrow("↓"));
            box.getChildren().add(node(chain.get(i)));
        }
        // The loop back to the driver, drawn as the arrow it is rather than left to the caption to assert.
        box.getChildren().add(arrow("↺  " + RuntimeDiagram.LOOP_NOTE));

        Label asideEdge = new Label("⇢  " + RuntimeDiagram.GUARD_EDGE_LABEL
                + ", whichever activity is running:");
        asideEdge.setWrapText(true);
        asideEdge.getStyleClass().add("runtime-arrow");
        box.getChildren().addAll(asideEdge, node(RuntimeDiagram.guard()));
        return box;
    }

    /** One box of the diagram: its title, and the sentence that says what happens there. */
    private static VBox node(RuntimeDiagram.Node node) {
        Label title = new Label(node.title());
        title.setWrapText(true);
        title.getStyleClass().add("runtime-node-title");
        Label detail = new Label(node.detail());
        detail.setWrapText(true);
        detail.getStyleClass().add("dialog-hint");

        VBox box = new VBox(2, title, detail);
        box.setMaxWidth(Double.MAX_VALUE);
        box.getStyleClass().addAll("runtime-node", shapeClass(node));
        return box;
    }

    /** The style class for a node's shape — the guard is the one node drawn as an aside rather than a step. */
    private static String shapeClass(RuntimeDiagram.Node node) {
        if (node.id().equals(RuntimeDiagram.guard().id())) return "runtime-node--aside";
        return switch (node.shape()) {
            case TERMINAL -> "runtime-node--terminal";
            case DECISION -> "runtime-node--decision";
            case STEP -> "runtime-node--step";
        };
    }

    private static Label arrow(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("runtime-arrow");
        return label;
    }

    /** One numbered step: heading, summary, paragraphs, and the jump button when we can open its destination. */
    private VBox render(int number, WorkflowStep step) {
        Label title = new Label(number + " · " + step.title());
        title.setWrapText(true);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label summary = new Label(step.summary());
        summary.setWrapText(true);
        summary.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        VBox box = new VBox(4, title, summary);
        for (String paragraph : step.body()) {
            Label text = new Label(paragraph);
            text.setWrapText(true);
            text.setStyle("-fx-font-size: 12px;");
            box.getChildren().add(text);
        }

        Runnable action = actions.get(step.action());
        if (action != null) {
            Button open = new Button(step.action().buttonLabel());
            open.setOnAction(e -> action.run());
            box.getChildren().add(open);
        }
        return box;
    }
}
