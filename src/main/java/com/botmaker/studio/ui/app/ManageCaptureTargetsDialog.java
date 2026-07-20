package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.ui.app.capture.CaptureSourcePicker;
import com.botmaker.studio.ui.app.capture.TargetThumbnail;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Editor-side dialog to manage the project's {@link CaptureTarget}s (monitors and application windows)
 * and mark one as the <em>default</em> used by every on-screen picker (image / region / point). Applying
 * delegates to {@link ProjectSettingsService}, which writes {@code settings.json} off the FX thread.
 *
 * <p>Once a default is set the pickers stop asking which screen to use; a window default is also brought
 * to the front and captured directly.
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

    /** Cached live-preview probes per target, so cell recycling doesn't re-grab. */
    private final Map<CaptureTarget, ThumbEntry> thumbs = new HashMap<>();
    /** Single background thread for the (blocking) native/desktop grabs. */
    private final ExecutorService thumbExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "target-thumb");
        t.setDaemon(true);
        return t;
    });

    /** A cached probe: the FX preview image (may be null) and whether the target currently exists. */
    private record ThumbEntry(Image image, boolean exists) {}

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
        root.getChildren().addAll(buildList(), buildAddRow(), buildButtonBar());

        stage.setScene(new Scene(root, 560, 520));
        stage.setOnHidden(e -> thumbExec.shutdownNow());
        stage.show();
    }

    private VBox buildList() {
        list = new ListView<>(rows);
        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.setPlaceholder(new Label("No capture targets yet. Add a screen or window below."));
        list.setCellFactory(v -> new TargetCell());
        // Double-click a row to make it the default (mirrors "Set as default").
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                CaptureTarget sel = list.getSelectionModel().getSelectedItem();
                if (sel != null) { defaultTarget = sel; list.refresh(); }
            }
        });

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

    /**
     * Renders a target as a live-preview thumbnail + label, an "exists / not found" badge, and a default
     * marker. The (blocking) preview grab runs off the FX thread and is cached per target, so scrolling /
     * cell recycling doesn't re-probe.
     */
    private final class TargetCell extends ListCell<CaptureTarget> {
        private final ImageView thumb = new ImageView();
        private final Label name = new Label();
        private final Label status = new Label();
        private final HBox box;

        TargetCell() {
            thumb.setFitWidth(128);
            thumb.setFitHeight(76);
            thumb.setPreserveRatio(true);
            name.setStyle("-fx-font-weight: bold;");
            status.setStyle("-fx-font-size: 11px;");
            Region pad = new Region();
            pad.setMinSize(128, 76);
            pad.setStyle("-fx-background-color: rgba(0,0,0,0.08); -fx-background-radius: 4;");
            javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(pad, thumb);
            box = new HBox(10, holder, new VBox(3, name, status));
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override protected void updateItem(CaptureTarget item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setText(null); setGraphic(null); return; }
            setText(null);
            setGraphic(box);
            name.setText(item.equals(defaultTarget) ? item.label() + "   ✓ default" : item.label());
            renderThumb(item);
        }

        private void renderThumb(CaptureTarget item) {
            ThumbEntry cached = thumbs.get(item);
            if (cached != null) {
                thumb.setImage(cached.image());
                if (cached.exists()) { status.setText("● available"); status.setTextFill(javafx.scene.paint.Color.web("#2e7d32")); }
                else { status.setText("○ not found"); status.setTextFill(javafx.scene.paint.Color.web("#b00020")); }
                return;
            }
            // Not probed yet: show a loading state and grab off-thread, then cache + refresh.
            thumb.setImage(null);
            status.setText("probing…");
            status.setTextFill(javafx.scene.paint.Color.GRAY);
            thumbs.put(item, new ThumbEntry(null, false));   // sentinel to avoid duplicate submits
            thumbExec.submit(() -> {
                TargetThumbnail.Result r = TargetThumbnail.grab(item);
                Image fx = (r.image() != null) ? ScreenCaptureService.toFxImage(r.image()) : null;
                Platform.runLater(() -> {
                    thumbs.put(item, new ThumbEntry(fx, r.exists()));
                    if (stage != null && stage.isShowing()) list.refresh();
                });
            });
        }
    }

    /**
     * A single visual "add" action: opens the {@link CaptureSourcePicker} (screens + windows with live
     * thumbnails) and adds the chosen concrete source to the list, remembering any window title.
     */
    private HBox buildAddRow() {
        Button choose = new Button("＋ Add capture source…");
        choose.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(choose, Priority.ALWAYS);
        choose.setOnAction(e -> new CaptureSourcePicker(stage, false).showAndWait().ifPresent(sel -> {
            if (sel instanceof CaptureSourcePicker.Selection.Concrete c) {
                if (c.target() instanceof WindowTarget wt && wt.titleSubstring() != null) {
                    knownWindowTitles.add(wt.titleSubstring());
                }
                addTarget(c.target());
            }
        }));

        HBox row = new HBox(8, choose);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void addTarget(CaptureTarget target) {
        if (!rows.contains(target)) rows.add(target);
        defaultTarget = target; // a newly added source becomes the default
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

        // Preserve the settings this dialog doesn't own (favorite overloads / methods, reference resolution)
        // by deriving from the current settings rather than constructing a bare record.
        StudioProjectSettings updated = settingsService.current()
                .withTargets(result)
                .withKnownWindowTitles(new ArrayList<>(knownWindowTitles))
                .withDefaultIndex(defaultIndex);

        setBusy(apply, cancel, true);
        settingsService.update(updated)
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
