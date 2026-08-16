package com.botmaker.studio.ui.app;

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
