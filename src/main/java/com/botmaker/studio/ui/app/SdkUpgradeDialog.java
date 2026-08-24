package com.botmaker.studio.ui.app;

import com.botmaker.studio.services.SdkUpgradeService;
import com.botmaker.studio.services.SdkUpgradeService.Break;
import com.botmaker.studio.services.SdkUpgradeService.CallSite;
import com.botmaker.studio.services.SdkUpgradeService.Choice;
import com.botmaker.studio.services.SdkUpgradeService.Deprecation;
import com.botmaker.studio.services.SdkUpgradeService.Highlight;
import com.botmaker.studio.services.SdkUpgradeService.Report;
import com.botmaker.studio.services.SdkUpgradeService.Site;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
 *
 * <h2>Two windows, one class</h2>
 *
 * <p>{@link #showModernise()} opens the same dialog on the question that has no version in it: move this bot
 * off what the SDK it <em>already</em> pins has deprecated. There is no combo box and no pom bump; the report
 * is the deprecation list with the SDK's own {@code @ReplacedBy} answers beside it, and the button is the
 * same snapshot-then-rewrite. It shares this class rather than getting one of its own because everything
 * below the top row is the same layout of the same records — a second dialog would be a second place for the
 * repair sentence to drift.
 */
public final class SdkUpgradeDialog {

    private final Window owner;
    private final SdkUpgradeService upgrades;

    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final Button checkButton = new Button("Check");
    private final Button applyButton = new Button("Snapshot & switch");
    private final CheckBox moderniseBox = new CheckBox("Also move off members deprecated on that version");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final VBox reportBox = new VBox(14);

    /**
     * The one thing this dialog collects rather than displays: which candidate of a split each call site
     * meant, as an index into <em>that site's</em> own list. Every entry is filled in as the card is built,
     * so the map is complete before the user has touched anything and closing the dialog without a click
     * produces exactly the upgrade an empty map would.
     */
    private final Map<CallSite, Integer> picks = new LinkedHashMap<>();

    private Stage stage;
    private Report report;
    private boolean modernising;

    public SdkUpgradeDialog(Window owner, SdkUpgradeService upgrades) {
        this.owner = owner;
        this.upgrades = upgrades;
    }

    /** The upgrade: pick a version, read what it costs, switch. */
    public void show() {
        open(false);
    }

    /** The same window with no version in it — move off what the SDK this bot already pins has deprecated. */
    public void showModernise() {
        open(true);
    }

    private void open(boolean moderniseOnly) {
        this.modernising = moderniseOnly;

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(moderniseOnly ? "Modernise" : "Upgrade SDK");

        progress.setVisible(false);
        progress.setPrefSize(18, 18);
        applyButton.setDisable(true);
        applyButton.setText(moderniseOnly ? "Snapshot & modernise" : "Snapshot & switch");
        reportBox.setPadding(new Insets(4, 2, 4, 2));

        ScrollPane scroll = new ScrollPane(reportBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(moderniseOnly ? buildModerniseTopRow() : buildTopRow(), scroll, statusLabel,
                buildButtonBar());

        if (moderniseOnly) {
            placeholder("Reading SDK " + upgrades.currentVersion() + "…");
            runReport(upgrades::modernisations,
                    "Reading what SDK " + upgrades.currentVersion() + " deprecates…");
        } else {
            placeholder("Pick a version and press Check. Nothing is changed until you say so.");
            loadVersions();
        }

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

        // Re-checks rather than only changing what Apply does: the box changes the report as well as the
        // repair, and a checkbox that silently makes the list on screen wrong is worse than a second wait.
        moderniseBox.setOnAction(e -> {
            if (report != null) runCheck();
        });

        return new VBox(8, current, row, moderniseBox);
    }

    /**
     * The modernise header. No version is offered because none is involved — the point of this window is
     * that it moves the bot forward without moving the bot's SDK.
     */
    private Node buildModerniseTopRow() {
        Label current = new Label("This bot is on SDK " + upgrades.currentVersion() + ".");
        current.setStyle("-fx-font-weight: bold;");

        Label what = new Label("Some of what this bot calls is marked deprecated on that same version, and "
                + "the SDK says what to use instead. This moves those calls, without changing the version "
                + "this bot pins.");
        what.setWrapText(true);

        HBox row = new HBox(8, what, progress);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(what, Priority.ALWAYS);

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
        boolean alsoModernise = moderniseBox.isSelected() && !moderniseBox.isDisabled();
        runReport(() -> upgrades.compare(target, alsoModernise),
                "Resolving and scanning SDK " + target + "…");
    }

    /**
     * Runs one blocking read of the jars off the FX thread and lays the answer out. Both entry points come
     * through here, so a failure says the same thing and leaves the same untouched project either way.
     */
    private void runReport(Supplier<Report> work, String busyText) {
        busy(true, busyText);
        applyButton.setDisable(true);
        report = null;

        Thread worker = new Thread(() -> {
            Report result;
            try {
                result = work.get();
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
                applyButton.setText(applyText(done));
                // The switch is always available; modernising is only available when there is something to
                // move, since it is not offered against a version change the user came here to make anyway.
                applyButton.setDisable(modernising && !done.canModernise());
            });
        }, modernising ? "sdk-modernise-check" : "sdk-upgrade-check");
        worker.setDaemon(true);
        worker.start();
    }

    private String applyText(Report r) {
        if (modernising) return "Snapshot & modernise";
        return (r.canMigrate() || (moderniseBox.isSelected() && r.canModernise()))
                ? "Snapshot, repair & switch to " + r.to()
                : "Snapshot & switch to " + r.to();
    }

    private void render(Report r) {
        reportBox.getChildren().clear();
        picks.clear();

        if (r.isIncomplete()) {
            reportBox.getChildren().add(section("⚠ What this check could not determine", r.problems()));
        }
        // Above every cost section, and above the scaffolding warning, because it is the only thing on this
        // dialog that answers "why would I". Everything below it answers "what will this take", which is a
        // question the user is only asking because they have already decided the first one is worth it.
        Node highlights = highlightsSection(r);
        if (highlights != null) reportBox.getChildren().add(highlights);
        // Before anything else, including the modernise layout: this is the one thing that can stop the
        // upgrade for a reason the user cannot act on, and learning it after pressing the button is the
        // failure this section exists to prevent.
        if (!r.scaffolding().isEmpty()) {
            List<String> scaffold = new ArrayList<>();
            for (String element : r.scaffolding()) {
                scaffold.add(element + " — Studio writes this into your generated files, which are rendered "
                        + "from Studio's own templates rather than migrated.");
            }
            reportBox.getChildren().add(section("⚠ What this release moves that Studio writes for you",
                    scaffold));
        }
        if (modernising) {
            renderModernise(r);
            return;
        }

        // A break list that could not be repaired disables the extra hop too — the migration is one
        // all-or-nothing pass, so offering to modernise inside a pass that will not run is offering nothing.
        moderniseBox.setDisable(r.isIncomplete() || !r.unrepairable().isEmpty());
        if (moderniseBox.isDisabled()) moderniseBox.setSelected(false);

        List<String> breaks = new ArrayList<>();
        for (Break b : r.breaks()) {
            breaks.add(b.display() + describe(b));
            for (var site : b.sites()) breaks.add("        " + site);
        }
        reportBox.getChildren().add(section("What breaks in this bot", breaks,
                r.isIncomplete()
                        ? "Nothing in the files that could be read."
                        : "Nothing — every SDK call in this bot still exists on " + r.to() + "."));

        if (!r.unrepairable().isEmpty()) {
            List<String> byHand = new ArrayList<>();
            for (Break b : r.unrepairable()) {
                byHand.add(b.display() + " — no type in " + r.to() + " takes its place, and this bot writes "
                        + "the name itself, so there is nothing to stand in for it.");
                for (var site : b.sites()) byHand.add("        " + site);
            }
            reportBox.getChildren().add(section("What you have to change yourself", byHand));
        }

        List<String> deprecated = new ArrayList<>();
        for (Deprecation d : r.deprecated()) {
            deprecated.add(d.display() + " — deprecated on " + r.to()
                    + (d.isMovable() ? "; use " + d.becomes() : ""));
            for (var site : d.sites()) deprecated.add("        " + site);
        }
        reportBox.getChildren().add(section("What this bot uses that is now deprecated", deprecated,
                "Nothing this bot calls is deprecated on " + r.to() + "."));

        // Grouped by the release each thing arrived in, newest first — a bot several versions behind is
        // reading a span, not a single release, and which one a thing came from is most of what makes the
        // list worth reading. A jar with no @Since has one unlabelled group, which is the old flat list.
        List<String> added = new ArrayList<>();
        r.addedBySince().forEach((era, entries) -> {
            if (!era.isBlank()) added.add("new in " + era);
            for (String entry : entries) added.add(era.isBlank() ? entry : "        " + entry);
        });
        // Kept, and kept exhaustive — but retitled, because it is no longer the only "what's new" on the
        // dialog and the two answer different questions: the section above is the release talking, this one
        // is the diff. Under a jar with no changelog it is still the only answer there is.
        reportBox.getChildren().add(section("What's new in the API", added,
                "No new public API between " + r.from() + " and " + r.to() + "."));

        // Last before the button, because it is the only thing on this dialog addressed *to* the user.
        for (Choice choice : r.splits()) reportBox.getChildren().add(splitCard(choice));

        if (!r.repairable().isEmpty()) {
            reportBox.getChildren().add(repairCard(r));
        }
    }

    /**
     * One member that became two, and a row per call of it — the only question this dialog asks.
     *
     * <p>Which candidate a call meant is a property of the call, not of the member, so there is no
     * project-wide answer to offer: {@code scroll(3)} and {@code scroll(-3)} want different ones and a single
     * pick would be wrong in half of them by construction. Hence a row per site, each showing the call
     * <em>as written</em> — the line number alone cannot tell those two apart, which is exactly the
     * distinction being asked about.
     *
     * <p><b>Nothing is required.</b> Every combo arrives on the author's preferred candidate, and a site
     * where none of the candidates fits gets no combo at all: that is today's default value and review mark,
     * and offering an empty menu would imply a choice that does not exist.
     *
     * <p>Modernise does not render these — it takes the preferred candidate everywhere. Moving off a
     * deprecation is not a change the user came here to make, and it is the one path where declining to
     * answer must not cost anything.
     */
    private Node splitCard(Choice choice) {
        Label heading = new Label(choice.display() + " became " + choice.candidates().size()
                + " members — pick one per call");
        heading.setStyle("-fx-font-weight: bold;");

        VBox card = new VBox(6, heading);
        if (!choice.note().isBlank()) {
            // The SDK author's own sentence, verbatim. Nobody here is entitled to paraphrase it.
            Label note = new Label(choice.note());
            note.setWrapText(true);
            card.getChildren().add(note);
        }
        Label why = new Label("Each call is already answered with the first choice. Change one only where "
                + "that is not what the call meant.");
        why.setWrapText(true);
        why.getStyleClass().add("sdk-upgrade-empty");
        card.getChildren().add(why);

        for (Site site : choice.sites()) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Label where = new Label(site.site() + "   " + site.site().text());
            where.getStyleClass().add("sdk-upgrade-detail");
            row.getChildren().add(where);

            if (site.candidates().isEmpty()) {
                Label none = new Label("— nothing that fits here, so a default value is written and the "
                        + "function is marked for review");
                none.setWrapText(true);
                none.getStyleClass().add("sdk-upgrade-empty");
                row.getChildren().add(none);
            } else {
                ComboBox<String> combo = new ComboBox<>();
                site.candidates().forEach(c -> combo.getItems().add(c.display()));
                combo.getSelectionModel().select(0);
                picks.put(site.site(), 0);
                combo.getSelectionModel().selectedIndexProperty().addListener(
                        (o, was, now) -> picks.put(site.site(), now.intValue()));
                row.getChildren().add(combo);
            }
            card.getChildren().add(row);
        }
        card.getStyleClass().add("sdk-upgrade-card");
        return card;
    }

    /**
     * The modernise layout: what will move, and what is deprecated with nowhere named to move it to.
     *
     * <p>Nothing here is a break — every one of these calls compiles today and would go on compiling — so
     * there is no "what breaks" list and no "what's new". The split that matters instead is between what
     * the SDK answered and what it only warned about, because the second list is the one addressed to the
     * user rather than to the button.
     */
    private void renderModernise(Report r) {
        List<String> moving = new ArrayList<>();
        for (Deprecation d : r.movable()) {
            moving.add(d.display() + " — deprecated");
            moving.add("        → " + d.repair());
            for (var site : d.sites()) moving.add("        " + site);
        }
        reportBox.getChildren().add(section("What Studio will move for you", moving,
                r.isIncomplete()
                        ? "Nothing in the files that could be read."
                        : "Nothing — this bot calls nothing that SDK " + r.to() + " both deprecates and "
                        + "points somewhere else."));

        List<String> byHand = new ArrayList<>();
        for (Deprecation d : r.deprecated()) {
            if (d.isMovable()) continue;
            byHand.add(d.display() + " — deprecated, with nothing on SDK " + r.to() + " named to take its "
                    + "place, so what it becomes is your call.");
            for (var site : d.sites()) byHand.add("        " + site);
        }
        if (!byHand.isEmpty()) {
            reportBox.getChildren().add(section("What you have to decide yourself", byHand));
        }

        Label note = new Label(r.canModernise()
                ? "\"Snapshot & modernise\" commits your project to Project History first, so all of this is "
                + "one revert away. The version this bot pins does not change, and nothing that could not "
                + "be moved cleanly is touched — a deprecated call still compiles, so it is left as it is "
                + "rather than replaced by a default. Any function whose calls did not come through "
                + "unchanged is marked for you to review."
                : r.isIncomplete()
                ? "Some of this project could not be read, so nothing will be rewritten."
                : "There is nothing to do here.");
        note.setWrapText(true);
        note.getStyleClass().add("sdk-upgrade-empty");
        reportBox.getChildren().add(note);
    }

    private static String describe(Break b) {
        return switch (b.kind()) {
            case TYPE_REMOVED -> " — the whole class is gone";
            case TYPE_RENAMED -> " — " + b.detail();
            case MEMBER_REMOVED -> " — removed";
            case FIELD_REMOVED -> " — the constant is gone";
            case SIGNATURE_CHANGED -> " — " + b.detail();
        };
    }

    /**
     * What Studio will write in place of each break, and whether the button below will actually do it.
     *
     * <p>It says the model out loud, because the model is the surprising part: the repair makes the bot
     * <em>compile</em>, not behave the same. Where the SDK says what a member became and the target jar
     * confirms the shapes line up, the call is pointed there; where it does not, the call becomes a default
     * value, or is deleted if it stood on its own. Either way the function around it is marked for the user
     * to go through afterwards. Promising more would be guessing — a redirect nobody checked is a bot that
     * compiles and behaves differently.
     *
     * <p>There is deliberately no second Apply button here. The repair is not a separate operation the user
     * could run on its own — it happens between the snapshot and the pom bump, and running it without either
     * would leave a project rewritten for an SDK it does not yet pin. One button, one revert away.
     */
    private Node repairCard(Report r) {
        Label heading = new Label("What Studio will change for you");
        heading.setStyle("-fx-font-weight: bold;");

        Label why = new Label("These are repaired so the bot compiles again — a renamed class is renamed "
                + "everywhere, something that moved is pointed at where it went, and anything with nowhere "
                + "to go is replaced by a default value (or deleted, where the call was a line of its own). "
                + "That can leave the bot doing something different, so every function whose calls did not "
                + "come through unchanged is marked for you to review afterwards.");
        why.setWrapText(true);

        List<String> lines = new ArrayList<>();
        for (Break b : r.repairable()) {
            lines.add(b.display() + describe(b));
            lines.add("        → " + b.repair());
        }
        VBox card = new VBox(6, heading, why);
        card.getChildren().add(section("", lines));

        Label note = new Label(r.canMigrate()
                ? "\"Snapshot, repair & switch\" below does all of it: your project is committed to Project "
                + "History first, so the whole upgrade is one revert away."
                : reasonApplyIsOff(r));
        note.setWrapText(true);
        note.getStyleClass().add("sdk-upgrade-empty");
        card.getChildren().add(note);

        card.getStyleClass().add("sdk-upgrade-card");
        return card;
    }

    /**
     * Why the whole span is off, not just the break that caused it. One unrepairable change disables the
     * lot: rewriting some of the call sites and leaving the rest would produce a project in neither shape,
     * with nothing telling the user which half was touched.
     */
    private static String reasonApplyIsOff(Report r) {
        if (r.isIncomplete()) {
            return "Some of this project could not be read, so nothing will be rewritten automatically.";
        }
        return "A class this bot uses is gone with nothing to take its place, so none of these will be "
                + "applied automatically. Change those uses by hand first.";
    }

    // -------------------------------------------------------------------------
    // Apply
    // -------------------------------------------------------------------------

    private void runApply() {
        if (report == null) return;
        if (modernising) {
            runModernise();
            return;
        }
        String target = report.to();
        boolean repair = report.canMigrate();
        boolean alsoModernise = moderniseBox.isSelected() && !moderniseBox.isDisabled();

        busy(true, repair || alsoModernise
                ? "Committing a snapshot, repairing your call sites and switching to " + target + "…"
                : "Committing a snapshot and switching to " + target + "…");
        applyButton.setDisable(true);

        upgrades.apply(target, repair, alsoModernise, Map.copyOf(picks)).whenComplete((ignored, error) ->
                Platform.runLater(() -> {
                    if (error != null) {
                        failed("Could not switch to SDK " + target, error);
                        return;
                    }
                    busy(false, (repair || alsoModernise
                            ? "Now on SDK " + target + ", with your call sites repaired. "
                            : "Now on SDK " + target + ". ")
                            + "The previous state is one revert away in Project History.");
                    stage.close();
                }));
    }

    private void runModernise() {
        busy(true, "Committing a snapshot and moving your calls off what is deprecated…");
        applyButton.setDisable(true);

        upgrades.modernise().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                failed("Could not modernise this bot", error);
                return;
            }
            busy(false, "Your calls have been moved. The SDK version has not changed, and the previous "
                    + "state is one revert away in Project History.");
            stage.close();
        }));
    }

    private void failed(String what, Throwable error) {
        busy(false, "");
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        ThemedWindows.alert(Alert.AlertType.ERROR, what + ":\n\n" + cause.getMessage()).showAndWait();
        applyButton.setDisable(false);
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    /**
     * The target release's own changelog for the span being crossed, or {@code null} when it carries none.
     *
     * <p>{@code null} rather than an empty section, and this is the one place on the dialog where absence
     * must not be stated: every SDK up to v1.0.26 ships no changelog, so "this release says nothing about
     * itself" would be the *usual* message and would read as a defect in the release rather than in the
     * reader. The rest of the dialog says what an empty list means because there an empty list is a finding.
     *
     * <p>A bot several versions behind sees each release it is moving through, newest first, so the span is
     * legible as a span rather than flattened into one undifferentiated list.
     */
    private Node highlightsSection(Report r) {
        if (r.highlights().isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (Highlight h : r.highlights()) {
            lines.add(h.version() + (h.date().isBlank() ? "" : "  ·  " + h.date()));
            for (String line : h.lines()) lines.add("        " + line);
        }
        return section("What this release gives you", lines);
    }

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
