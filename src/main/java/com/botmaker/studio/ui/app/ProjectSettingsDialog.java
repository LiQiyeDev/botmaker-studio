package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.StudioProjectSettings.Resolution;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Editor-side dialog for per-project settings backed by {@link StudioProjectSettings}: the capture
 * <b>reference (standard) resolution</b>, the project's <b>favourite methods per class</b> (surfaced
 * first in the overlay palette), and a read-only view of the <b>favourite overload per method</b>
 * (written by the block ⚙ menu). Applying delegates to {@link ProjectSettingsService#update}, which
 * rewrites {@code settings.json} off the FX thread. Modeled on {@link ManageActivitiesDialog}.
 */
public class ProjectSettingsDialog {

    private final Window owner;
    private final ProjectSettingsService settingsService;

    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private Stage stage;

    // Reference resolution.
    private final TextField widthField = new TextField();
    private final TextField heightField = new TextField();

    // Favourite methods per class: className -> mutable ordered method list.
    private final ObservableList<FavMethodRow> favMethodRows = FXCollections.observableArrayList();

    // Favourite overloads (read-only view): methodKey -> signatureKey.
    private final ObservableList<FavOverloadRow> favOverloadRows = FXCollections.observableArrayList();

    /** One class → comma-separated favourite methods row (edited as text for simplicity). */
    public record FavMethodRow(String className, String methods) {}

    /** One method → chosen overload signature row (read-only). */
    public record FavOverloadRow(String methodKey, String signatureKey) {}

    public ProjectSettingsDialog(Window owner, ProjectSettingsService settingsService) {
        this.owner = owner;
        this.settingsService = settingsService;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Project Settings");

        StudioProjectSettings s = settingsService.current();

        Resolution ref = s.referenceResolution();
        widthField.setText(ref == null ? "" : Integer.toString(ref.width()));
        heightField.setText(ref == null ? "" : Integer.toString(ref.height()));

        favMethodRows.setAll(s.favoriteMethods().entrySet().stream()
                .map(e -> new FavMethodRow(e.getKey(), String.join(", ", e.getValue())))
                .collect(Collectors.toList()));

        favOverloadRows.setAll(s.favoriteOverloads().entrySet().stream()
                .map(e -> new FavOverloadRow(e.getKey(), e.getValue()))
                .collect(Collectors.toList()));

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(buildResolutionPane(), buildFavMethodsPane(), buildFavOverloadsPane(), buildButtonBar());

        stage.setScene(new Scene(root, 560, 560));
        stage.show();
    }

    private TitledPane buildResolutionPane() {
        widthField.setPromptText("width");
        heightField.setPromptText("height");
        widthField.setPrefColumnCount(6);
        heightField.setPrefColumnCount(6);
        Button clear = new Button("Clear");
        clear.setOnAction(e -> { widthField.clear(); heightField.clear(); });
        HBox row = new HBox(8, new Label("Standard resolution:"), widthField, new Label("×"), heightField, clear);
        row.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("The canonical target-window size image templates are captured at. "
                + "Leave empty to auto-seed from the window's size on first capture.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        hint.setWrapText(true);
        TitledPane pane = new TitledPane("Reference Resolution", new VBox(6, row, hint));
        pane.setCollapsible(false);
        return pane;
    }

    private TitledPane buildFavMethodsPane() {
        TableView<FavMethodRow> table = new TableView<>(favMethodRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No favourite methods yet. Add one below."));

        TableColumn<FavMethodRow, String> classCol = new TableColumn<>("Class");
        classCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().className()));
        TableColumn<FavMethodRow, String> methodsCol = new TableColumn<>("Favourite methods (comma-separated, in order)");
        methodsCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().methods()));
        table.getColumns().addAll(List.of(classCol, methodsCol));

        Button removeBtn = new Button("Remove");
        removeBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, was, sel) -> removeBtn.setDisable(sel == null));
        removeBtn.setOnAction(e -> {
            FavMethodRow sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) favMethodRows.remove(sel);
        });

        TextField classField = new TextField();
        classField.setPromptText("class (e.g. ImageFinder)");
        HBox.setHgrow(classField, Priority.ALWAYS);
        TextField methodsField = new TextField();
        methodsField.setPromptText("methods (e.g. find, findAll)");
        HBox.setHgrow(methodsField, Priority.ALWAYS);
        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> {
            String cls = classField.getText() == null ? "" : classField.getText().trim();
            String methods = methodsField.getText() == null ? "" : methodsField.getText().trim();
            if (cls.isEmpty() || methods.isEmpty()) { error("Enter both a class and at least one method."); return; }
            favMethodRows.removeIf(r -> r.className().equals(cls));
            favMethodRows.add(new FavMethodRow(cls, methods));
            classField.clear();
            methodsField.clear();
            statusLabel.setText("");
        });
        HBox addRow = new HBox(8, classField, methodsField, addBtn);
        addRow.setAlignment(Pos.CENTER_LEFT);
        HBox tableButtons = new HBox(removeBtn);
        tableButtons.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(6, table, addRow, tableButtons);
        VBox.setVgrow(table, Priority.ALWAYS);
        TitledPane pane = new TitledPane("Favourite Methods per Class", box);
        pane.setCollapsible(false);
        return pane;
    }

    private TitledPane buildFavOverloadsPane() {
        TableView<FavOverloadRow> table = new TableView<>(favOverloadRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No favourite overloads set. Choose one from a block's ⚙ menu."));

        TableColumn<FavOverloadRow, String> keyCol = new TableColumn<>("Method");
        keyCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().methodKey()));
        TableColumn<FavOverloadRow, String> sigCol = new TableColumn<>("Chosen overload");
        sigCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().signatureKey()));
        table.getColumns().addAll(List.of(keyCol, sigCol));

        Button removeBtn = new Button("Remove");
        removeBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, was, sel) -> removeBtn.setDisable(sel == null));
        removeBtn.setOnAction(e -> {
            FavOverloadRow sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) favOverloadRows.remove(sel);
        });
        HBox tableButtons = new HBox(removeBtn);
        tableButtons.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(6, table, tableButtons);
        VBox.setVgrow(table, Priority.ALWAYS);
        TitledPane pane = new TitledPane("Favourite Overload per Method", box);
        pane.setCollapsible(false);
        return pane;
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
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText("");

        Resolution resolution;
        String w = widthField.getText() == null ? "" : widthField.getText().trim();
        String h = heightField.getText() == null ? "" : heightField.getText().trim();
        if (w.isEmpty() && h.isEmpty()) {
            resolution = null;
        } else {
            try {
                int wi = Integer.parseInt(w);
                int hi = Integer.parseInt(h);
                if (wi <= 0 || hi <= 0) { error("Resolution must be positive."); return; }
                resolution = new Resolution(wi, hi);
            } catch (NumberFormatException ex) {
                error("Resolution width/height must be whole numbers (or both empty)."); return;
            }
        }

        StudioProjectSettings s = settingsService.current().withReferenceResolution(resolution);

        // Rebuild favourite methods: drop any classes removed in the UI, then apply the current rows.
        for (String existing : new ArrayList<>(s.favoriteMethods().keySet())) {
            s = s.withFavoriteMethods(existing, null);
        }
        for (FavMethodRow r : favMethodRows) {
            List<String> methods = Arrays.stream(r.methods().split(","))
                    .map(String::trim).filter(m -> !m.isEmpty()).collect(Collectors.toList());
            s = s.withFavoriteMethods(r.className(), methods);
        }

        // Rebuild favourite overloads from the (possibly pruned) table.
        Map<String, String> keptOverloads = new LinkedHashMap<>();
        for (FavOverloadRow r : favOverloadRows) keptOverloads.put(r.methodKey(), r.signatureKey());
        for (String existing : new ArrayList<>(s.favoriteOverloads().keySet())) {
            if (!keptOverloads.containsKey(existing)) s = s.withFavoriteOverload(existing, null);
        }

        setBusy(apply, cancel, true);
        settingsService.update(s).whenComplete((ok, err) -> Platform.runLater(() -> {
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
