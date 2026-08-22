package com.botmaker.studio.ui.app;

import com.botmaker.studio.services.SdkUpgradeService;
import com.botmaker.studio.services.SdkUpgradeService.Break;
import com.botmaker.studio.services.SdkUpgradeService.Deprecation;
import com.botmaker.studio.services.SdkUpgradeService.Note;
import com.botmaker.studio.services.SdkUpgradeService.Report;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * "What happens to my bot if I move to SDK x.y.z" — asked before anything is changed.
 *
 * <p>Changing the SDK version used to be a cell edit in <b>Manage Libraries</b>: it rewrote the pom and the
 * user found out what that cost by opening their project afterwards. This is the same operation with the
 * answer shown first — {@link SdkUpgradeService} does the reading, this only lays it out.
 *
 * <p><b>The two steps are ordered and the dialog says so.</b> The migration recipes ship in the <em>new</em>
 * jar but have to run against source the <em>old</em> SDK still explains, so the source rewrite comes first,
 * with the pom still pinned to the old version, and the pom bump second. Presenting them as one button would
 * be simpler and would produce a project that neither version can parse.
 *
 * <p>Studio does not run the rewrite itself, by design (see {@link SdkUpgradeService}); it hands over the
 * exact command. That is a Maven feature rather than a Studio one, which is why it works on bots generated
 * long before this dialog existed.
 */
public final class SdkUpgradeDialog {

    private final Window owner;
    private final SdkUpgradeService upgrades;

    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final Button checkButton = new Button("Check");
    private final Button applyButton = new Button("Snapshot & switch");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final VBox reportBox = new VBox(14);

    private Stage stage;
    private Report report;

    public SdkUpgradeDialog(Window owner, SdkUpgradeService upgrades) {
        this.owner = owner;
        this.upgrades = upgrades;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Upgrade SDK");

        progress.setVisible(false);
        progress.setPrefSize(18, 18);
        applyButton.setDisable(true);
        reportBox.setPadding(new Insets(4, 2, 4, 2));

        ScrollPane scroll = new ScrollPane(reportBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(buildTopRow(), scroll, statusLabel, buildButtonBar());

        placeholder("Pick a version and press Check. Nothing is changed until you say so.");
        loadVersions();

        stage.setScene(ThemedWindows.scene(root, 700, 560));
        stage.show();
    }

    // -------------------------------------------------------------------------
    // Chrome
    // -------------------------------------------------------------------------

    private Node buildTopRow() {
        Label current = new Label("This bot is on SDK " + upgrades.currentVersion() + ".");
        current.setStyle("-fx-font-weight: bold;");

        versionCombo.setPrefWidth(180);
        versionCombo.setPromptText("loading versions…");
        versionCombo.setDisable(true);

        checkButton.setDefaultButton(true);
        checkButton.setDisable(true);
        checkButton.setOnAction(e -> runCheck());

        HBox row = new HBox(8, new Label("Upgrade to:"), versionCombo, checkButton, progress);
        row.setAlignment(Pos.CENTER_LEFT);

        return new VBox(8, current, row);
    }

    private Node buildButtonBar() {
        applyButton.setOnAction(e -> runApply());

        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, spacer, applyButton, close);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }

    private void loadVersions() {
        upgrades.availableVersions().thenAccept(versions -> Platform.runLater(() -> {
            if (versions.isEmpty()) {
                versionCombo.setPromptText("no versions found — offline?");
                status("Could not reach JitPack, so the list of SDK versions is unavailable.");
                return;
            }
            versionCombo.getItems().setAll(versions);
            versionCombo.setPromptText(null);
            versionCombo.getSelectionModel().selectFirst();
            versionCombo.setDisable(false);
            checkButton.setDisable(false);
        }));
    }

    // -------------------------------------------------------------------------
    // Check
    // -------------------------------------------------------------------------

    private void runCheck() {
        String target = versionCombo.getValue();
        if (target == null || target.isBlank()) return;

        busy(true, "Resolving and scanning SDK " + target + "…");
        applyButton.setDisable(true);
        report = null;

        Thread worker = new Thread(() -> {
            Report result;
            try {
                result = upgrades.compare(target);
            } catch (RuntimeException e) {
                result = null;
                String message = e.getMessage();
                Platform.runLater(() -> {
                    busy(false, "The check failed: " + message);
                    placeholder("Nothing was changed.");
                });
            }
            if (result == null) return;
            Report done = result;
            Platform.runLater(() -> {
                report = done;
                busy(false, "");
                render(done);
                applyButton.setText("Snapshot & switch to " + done.to());
                applyButton.setDisable(false);
            });
        }, "sdk-upgrade-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(Report r) {
        reportBox.getChildren().clear();

        if (r.isIncomplete()) {
            reportBox.getChildren().add(section("⚠ What this check could not determine", r.problems()));
        }

        List<String> breaks = new ArrayList<>();
        for (Break b : r.breaks()) {
            breaks.add(b.display() + describe(b));
            for (var site : b.sites()) breaks.add("        " + site);
        }
        reportBox.getChildren().add(section("What breaks in this bot", breaks,
                r.isIncomplete()
                        ? "Nothing in the files that could be read."
                        : "Nothing — every SDK call in this bot still exists on " + r.to() + "."));

        if (!r.notes().isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Note n : r.notes()) {
                lines.add(n.member().isBlank() ? n.version() : n.version() + " — " + n.member());
                if (!n.summary().isBlank()) lines.add("        " + n.summary());
                if (!n.action().isBlank()) lines.add("        → " + n.action());
            }
            reportBox.getChildren().add(section("What cannot be migrated automatically", lines));
        }

        List<String> deprecated = new ArrayList<>();
        for (Deprecation d : r.deprecated()) {
            deprecated.add(d.display() + " — deprecated on " + r.to());
            for (var site : d.sites()) deprecated.add("        " + site);
        }
        reportBox.getChildren().add(section("What this bot uses that is now deprecated", deprecated,
                "Nothing this bot calls is deprecated on " + r.to() + "."));

        reportBox.getChildren().add(section("What's new", r.added(),
                "No new public API between " + r.from() + " and " + r.to() + "."));

        if (!r.rewriteCommand().isBlank()) {
            reportBox.getChildren().add(rewriteCard(r));
        }
    }

    private static String describe(Break b) {
        return switch (b.kind()) {
            case TYPE_REMOVED -> " — the whole class is gone";
            case MEMBER_REMOVED -> " — removed";
            case SIGNATURE_CHANGED -> " — " + b.detail();
        };
    }

    /**
     * Step 1, and the reason the dialog has two steps at all. Shown before the apply button is used, because
     * the command must run while the pom still pins the old version.
     */
    private Node rewriteCard(Report r) {
        Label heading = new Label("Step 1 — migrate your source, before switching");
        heading.setStyle("-fx-font-weight: bold;");

        Label why = new Label("Run this in the project folder while the pom still says " + r.from()
                + ". It reads the migration recipes out of the " + r.to()
                + " jar and rewrites your call sites; it changes nothing in your pom.");
        why.setWrapText(true);

        TextArea command = new TextArea(r.rewriteCommand());
        command.setEditable(false);
        command.setWrapText(true);
        command.setPrefRowCount(3);

        Button copy = new Button("Copy command");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(r.rewriteCommand());
            Clipboard.getSystemClipboard().setContent(content);
            status("Command copied to the clipboard.");
        });

        HBox actions = new HBox(8, copy);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(6, heading, why, command, actions);
        card.getStyleClass().add("sdk-upgrade-card");
        return card;
    }

    // -------------------------------------------------------------------------
    // Apply
    // -------------------------------------------------------------------------

    private void runApply() {
        if (report == null) return;
        String target = report.to();

        busy(true, "Committing a snapshot and switching to " + target + "…");
        applyButton.setDisable(true);

        upgrades.apply(target).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                busy(false, "");
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                ThemedWindows.alert(Alert.AlertType.ERROR,
                        "Could not switch to SDK " + target + ":\n\n" + cause.getMessage()).showAndWait();
                applyButton.setDisable(false);
                return;
            }
            busy(false, "Now on SDK " + target + ". The previous state is one revert away in Project History.");
            stage.close();
        }));
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    private Node section(String title, List<String> lines) {
        return section(title, lines, "");
    }

    /** A heading and its lines; {@code emptyText} is shown in place of an empty list (and says why it is ok). */
    private Node section(String title, List<String> lines, String emptyText) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");

        VBox box = new VBox(2, heading);
        if (lines.isEmpty()) {
            Label empty = new Label(emptyText);
            empty.setWrapText(true);
            empty.getStyleClass().add("sdk-upgrade-empty");
            box.getChildren().add(empty);
        } else {
            for (String line : lines) {
                Label label = new Label(line);
                label.setWrapText(true);
                if (line.startsWith("    ")) label.getStyleClass().add("sdk-upgrade-detail");
                box.getChildren().add(label);
            }
        }
        return box;
    }

    private void placeholder(String text) {
        reportBox.getChildren().setAll(new Label(text));
    }

    private void busy(boolean running, String message) {
        progress.setVisible(running);
        checkButton.setDisable(running || versionCombo.getValue() == null);
        status(message);
    }

    private void status(String message) {
        statusLabel.setText(message == null ? "" : message);
    }
}
