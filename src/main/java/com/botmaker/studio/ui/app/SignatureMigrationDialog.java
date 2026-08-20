package com.botmaker.studio.ui.app;

import com.botmaker.studio.parser.refactor.SignatureMigration;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * What a signature change is about to do to the rest of the project, shown before it happens.
 *
 * <p>Changing a function's name or inputs used to rewrite the declaration alone: the calls to it — in this file
 * and in the four others a generated project has — were left spelling a signature that no longer existed, and
 * the user found out from the compiler, in files they were not looking at. The change now carries them, and
 * carrying them silently would be its own surprise, so this is the sentence in between.
 *
 * <p>It says what changed and where it lands, not how. Listing every call site was tried on paper and is worse:
 * four lines of {@code Bot.java:41} tell the user nothing they can act on, while "Bot — 3 calls" tells them the
 * blast radius, which is the only thing Cancel is a decision about.
 *
 * <p><b>Not shown when there is nothing to say.</b> A function nothing calls yet — the common case while a bot
 * is being written — saves exactly as it did before, with no window in the way.
 */
public final class SignatureMigrationDialog {

    private SignatureMigrationDialog() {}

    /**
     * Shows the plan and waits. True when the user pressed Apply.
     *
     * @param name the function as it is called today — what the user still recognises it by
     */
    public static boolean confirm(Window owner, String name, SignatureMigration.Plan plan) {
        if (plan == null || plan.isEmpty()) return true;

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Change Function");

        Label headline = new Label(headlineFor(name, plan));
        headline.getStyleClass().add("dialog-headline");
        headline.setWrapText(true);

        VBox body = new VBox(6);
        for (String change : plan.changes()) body.getChildren().add(bullet(change));
        if (!plan.calls().isEmpty()) {
            body.getChildren().add(section("Where the calls are:"));
            for (String line : plan.perFileLines()) body.getChildren().add(bullet(line));
        }
        for (SignatureMigration.RescuedParameter rescued : plan.rescued()) {
            body.getChildren().add(bullet("\"" + rescued.name() + "\" is still used inside the function, so it "
                    + "becomes a variable there, starting at its default"));
        }

        boolean[] applied = {false};
        Button apply = new Button("Apply");
        apply.getStyleClass().add("primary-button");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> {
            applied[0] = true;
            stage.close();
        });
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spacer, cancel, apply);
        bar.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14, headline, body, bar);
        root.setPadding(new Insets(18));
        stage.setScene(ThemedWindows.scene(root, 560, 300));
        stage.setMinWidth(480);
        stage.setMinHeight(260);
        stage.showAndWait();
        return applied[0];
    }

    /** {@code "clickAt" is called 4 times in 2 files.} — or the body-only line when nothing calls it. */
    private static String headlineFor(String name, SignatureMigration.Plan plan) {
        int calls = plan.calls().size();
        if (calls == 0) return "\"" + name + "\" isn't called anywhere yet.";
        int files = plan.perFileLines().size();
        return "\"" + name + "\" is called " + calls + (calls == 1 ? " time in " : " times in ")
                + files + (files == 1 ? " file." : " files.");
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-section-label");
        return label;
    }

    private static Label bullet(String text) {
        Label label = new Label("• " + text);
        label.setWrapText(true);
        return label;
    }
}
