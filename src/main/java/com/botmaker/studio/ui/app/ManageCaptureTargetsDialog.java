package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor-side dialog to manage the project's {@link CaptureTarget}s (monitors and application windows)
 * and mark one as the <em>default</em> used by every on-screen picker (image / region / point). Applying
 * delegates to {@link ProjectSettingsService}, which writes {@code settings.json} off the FX thread.
 *
 * <p>Once a default is set the pickers stop asking which screen to use; a window default is also brought
 * to the front and captured directly. Modeled on {@link ManageActivitiesDialog}.
 */
public class ManageCaptureTargetsDialog {

    private final Window owner;
    private final ProjectSettingsService settingsService;

    private final ObservableList<CaptureTarget> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    /** Window titles remembered across sessions, so a window can be re-picked without the app running. */
    private final java.util.LinkedHashSet<String> knownWindowTitles = new java.util.LinkedHashSet<>();

    /** The row currently marked default, or {@code null} for none. Tracked by identity into {@link #rows}. */
    private CaptureTarget defaultTarget;
    private ListView<CaptureTarget> list;
    private Stage stage;

    public ManageCaptureTargetsDialog(Window owner, ProjectSettingsService settingsService) {
        this.owner = owner;
        this.settingsService = settingsService;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Capture Targets");

        StudioProjectSettings current = settingsService.current();
        rows.setAll(current.captureTargets());
        defaultTarget = current.defaultTarget();
        knownWindowTitles.addAll(current.knownWindowTitles());
        // Titles from already-saved window targets are known too (older projects predate the stored list).
        for (CaptureTarget t : current.captureTargets()) {
            if (t instanceof WindowTarget w && w.titleSubstring() != null && !w.titleSubstring().isBlank()) {
                knownWindowTitles.add(w.titleSubstring());
            }
        }

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(buildList(), buildAddScreenRow(), buildAddWindowRow(), buildButtonBar());

        stage.setScene(new Scene(root, 520, 460));
        stage.show();
    }

    private VBox buildList() {
        list = new ListView<>(rows);
        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.setPlaceholder(new Label("No capture targets yet. Add a screen or window below."));
        list.setCellFactory(v -> new TargetCell());

        Button setDefaultBtn = new Button("Set as default");
        setDefaultBtn.setDisable(true);
        Button removeBtn = new Button("Remove");
        removeBtn.setDisable(true);
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            setDefaultBtn.setDisable(sel == null);
            removeBtn.setDisable(sel == null);
        });
        setDefaultBtn.setOnAction(e -> {
            CaptureTarget sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) { defaultTarget = sel; list.refresh(); }
        });
        removeBtn.setOnAction(e -> {
            CaptureTarget sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) {
                rows.remove(sel);
                if (sel.equals(defaultTarget)) defaultTarget = null;
            }
        });

        HBox buttons = new HBox(8, setDefaultBtn, removeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Label heading = new Label("Capture targets");
        heading.setStyle("-fx-font-weight: bold;");
        Label hint = new Label("The default target is used by all on-screen pickers. A window is brought "
                + "to the front and captured directly.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        VBox box = new VBox(6, heading, list, hint, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    /** Renders a target's label, tagging the one currently marked default. */
    private final class TargetCell extends ListCell<CaptureTarget> {
        @Override protected void updateItem(CaptureTarget item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setText(null); return; }
            setText(item.equals(defaultTarget) ? item.label() + "   ✓ default" : item.label());
        }
    }

    private HBox buildAddScreenRow() {
        List<Screen> screens = Screen.getScreens();
        ObservableList<Integer> indices = FXCollections.observableArrayList();
        for (int i = 0; i < screens.size(); i++) indices.add(i);

        ComboBox<Integer> combo = new ComboBox<>(indices);
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(Integer i) {
                if (i == null) return "";
                javafx.geometry.Rectangle2D b = screens.get(i).getBounds();
                return String.format("Screen %d — %d×%d", i + 1, (int) b.getWidth(), (int) b.getHeight());
            }
            @Override public Integer fromString(String s) { return null; }
        });
        if (!indices.isEmpty()) combo.getSelectionModel().selectFirst();
        HBox.setHgrow(combo, Priority.ALWAYS);
        combo.setMaxWidth(Double.MAX_VALUE);

        Button add = new Button("Add screen");
        add.setOnAction(e -> {
            Integer idx = combo.getValue();
            if (idx == null) return;
            addTarget(new ScreenTarget(idx));
        });

        HBox row = new HBox(8, combo, add);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox buildAddWindowRow() {
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(windowTitleOptions()));
        combo.setEditable(true);
        combo.setPromptText("window title (substring)");
        HBox.setHgrow(combo, Priority.ALWAYS);
        combo.setMaxWidth(Double.MAX_VALUE);

        Button refresh = new Button("↻");
        refresh.setOnAction(e -> combo.setItems(FXCollections.observableArrayList(windowTitleOptions())));

        Button add = new Button("Add window");
        add.setOnAction(e -> {
            String title = combo.getEditor().getText();
            if (title == null || title.isBlank()) { error("Enter or pick a window title."); return; }
            String trimmed = title.trim();
            knownWindowTitles.add(trimmed);
            addTarget(new WindowTarget(trimmed));
            combo.getEditor().clear();
        });

        HBox row = new HBox(8, combo, refresh, add);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Window titles for the add-window dropdown: currently-open windows first, then remembered ones. */
    private List<String> windowTitleOptions() {
        java.util.LinkedHashSet<String> options = new java.util.LinkedHashSet<>(ScreenCaptureService.listWindowTitles());
        options.addAll(knownWindowTitles);
        return new ArrayList<>(options);
    }

    private void addTarget(CaptureTarget target) {
        if (!rows.contains(target)) rows.add(target);
        if (defaultTarget == null) defaultTarget = target; // first target becomes default
        statusLabel.setText("");
        list.refresh();
        list.getSelectionModel().select(target);
    }

    private HBox buildButtonBar() {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);

        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());
        Button apply = new Button("Apply");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> apply(apply, cancel));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, statusLabel, spacer, cancel, apply);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void apply(Button apply, Button cancel) {
        List<CaptureTarget> result = new ArrayList<>(rows);
        Integer defaultIndex = (defaultTarget == null) ? null : result.indexOf(defaultTarget);
        if (defaultIndex != null && defaultIndex < 0) defaultIndex = null;

        setBusy(apply, cancel, true);
        settingsService.update(new StudioProjectSettings(result, defaultIndex, new ArrayList<>(knownWindowTitles)))
                .whenComplete((ok, err) -> Platform.runLater(() -> {
                    setBusy(apply, cancel, false);
                    if (err != null) error(rootMessage(err));
                    else stage.close();
                }));
    }

    private void setBusy(Button apply, Button cancel, boolean busy) {
        progress.setVisible(busy);
        apply.setDisable(busy);
        cancel.setDisable(busy);
    }

    private void error(String message) {
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText(message);
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
