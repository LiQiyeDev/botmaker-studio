package com.botmaker.studio.ui.app;

import com.botmaker.studio.services.SdkUpgradeService;
import com.botmaker.studio.services.SdkUpgradeService.Break;
import com.botmaker.studio.services.SdkUpgradeService.Deprecation;
import com.botmaker.studio.services.SdkUpgradeService.Migration;
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
 * <p><b>The span is split into what Studio can repair and what needs you</b>, because those two lists ask
 * completely different things. The first is a button; the second is reading. Mixing them into one "what
 * changed" list is how a user comes to believe an upgrade was handled when half of it was addressed to them.
 *
 * <p>This replaced a card that printed an {@code mvn rewrite:run} command to paste. The ordering that card
 * had to teach — rewrite first, with the pom still on the old version, then bump — was imposed by
 * OpenRewrite type-attributing against the old SDK, and went away with it (see {@link SdkUpgradeService}).
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

        if (!r.manual().isEmpty()) {
            reportBox.getChildren().add(section("What you have to change yourself", lines(r.manual(), true)));
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

        if (!r.automatic().isEmpty()) {
            reportBox.getChildren().add(automaticCard(r));
        }
    }

    /** One line per entry, its summary indented under it; {@code withAction} adds the "what to do" line. */
    private static List<String> lines(List<Migration> migrations, boolean withAction) {
        List<String> out = new ArrayList<>();
        for (Migration m : migrations) {
            out.add(m.member().isBlank() ? m.version() : m.version() + " — " + m.member());
            if (!m.summary().isBlank()) out.add("        " + m.summary());
            if (withAction && !m.manual().isBlank()) out.add("        → " + m.manual());
        }
        return out;
    }

    private static String describe(Break b) {
        return switch (b.kind()) {
            case TYPE_REMOVED -> " — the whole class is gone";
            case MEMBER_REMOVED -> " — removed";
            case FIELD_REMOVED -> " — the constant is gone";
            case SIGNATURE_CHANGED -> " — " + b.detail();
        };
    }

    /**
     * What the SDK says Studio can repair for you. The Apply button is deliberately inert for now — the
     * rewriter that carries these out lands in a later phase — and says so rather than being merely greyed:
     * a disabled control with no explanation reads as a bug.
     */
    private Node automaticCard(Report r) {
        Label heading = new Label("What Studio can change for you");
        heading.setStyle("-fx-font-weight: bold;");

        Label why = new Label("SDK " + r.to() + " ships a repair for each of these, so they can be applied to "
                + "your call sites automatically.");
        why.setWrapText(true);

        VBox card = new VBox(6, heading, why);
        card.getChildren().add(section("", lines(r.automatic(), false)));

        Button apply = new Button("Apply these changes");
        apply.setDisable(true);
        Label note = new Label(r.canMigrate()
                ? "Not available yet — this build reports the repairs but does not carry them out."
                : reasonApplyIsOff(r));
        note.setWrapText(true);
        note.getStyleClass().add("sdk-upgrade-empty");

        HBox actions = new HBox(8, apply);
        actions.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(actions, note);

        card.getStyleClass().add("sdk-upgrade-card");
        return card;
    }

    /**
     * Why the whole span is off, not just the entry that caused it. One unrepairable change disables the
     * lot: rewriting some of the call sites and leaving the rest would produce a project in neither shape,
     * with nothing telling the user which half was touched.
     */
    private static String reasonApplyIsOff(Report r) {
        if (r.isIncomplete()) {
            return "Some of this project could not be read, so nothing will be rewritten automatically.";
        }
        boolean degraded = r.manual().stream().anyMatch(Migration::degraded);
        return degraded
                ? "Some of these changes need a newer Studio, so none of them will be applied automatically."
                : "Some changes in this range have to be made by hand, so none will be applied automatically.";
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
        VBox box = new VBox(2);
        if (!title.isBlank()) {
            Label heading = new Label(title);
            heading.setStyle("-fx-font-weight: bold;");
            box.getChildren().add(heading);
        }
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
