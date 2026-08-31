package com.botmaker.studio.ui.app;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.util.MethodSignature;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.util.StringConverter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Editor-side dialog for per-project settings backed by {@link StudioProjectSettings}: the capture
 * <b>reference (standard) resolution</b>, the project's <b>favourite methods per class</b> (surfaced
 * first in the overlay palette), and a read-only view of the <b>favourite overload per method</b>
 * (written by the block ⚙ menu). Applying delegates to {@link ProjectSettingsService#update}, which
 * rewrites {@code settings.json} off the FX thread.
 */
public class ProjectSettingsDialog {

    private final Window owner;
    private final ProjectSettingsService settingsService;
    /** SDK API surface for populating the favourite method/overload dropdowns (never manual typing). */
    private final ProjectAnalyzer projectAnalyzer;

    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private Stage stage;

    // The Reference Resolution pane was here until 2026-09-01 — a dropdown of standard sizes and a
    // landscape/portrait toggle. It set the size image templates are captured at, which is the capturing
    // plugin's own setting; its toolbar item owns it now, and this dialog no longer names a capture concept.

    // Favourite methods per class: className -> mutable ordered method list.
    private final ObservableList<FavMethodRow> favMethodRows = FXCollections.observableArrayList();

    // Favourite overloads (read-only view): methodKey -> signatureKey.
    private final ObservableList<FavOverloadRow> favOverloadRows = FXCollections.observableArrayList();

    /** One class → comma-separated favourite methods row (edited as text for simplicity). */
    public record FavMethodRow(String className, String methods) {}

    /** One method → chosen overload signature row (read-only). */
    public record FavOverloadRow(String methodKey, String signatureKey) {}

    public ProjectSettingsDialog(Window owner, ProjectSettingsService settingsService,
                                 ProjectAnalyzer projectAnalyzer) {
        this.owner = owner;
        this.settingsService = settingsService;
        this.projectAnalyzer = projectAnalyzer;
    }

    public void show() {
        StudioWindow window = StudioWindow.modal("project-settings", "Project Settings", owner)
                .size(560, 560).minSize(480, 400);
        stage = window.stage();

        StudioProjectSettings s = settingsService.current();

        favMethodRows.setAll(s.favoriteMethods().entrySet().stream()
                .map(e -> new FavMethodRow(e.getKey(), String.join(", ", e.getValue())))
                .collect(Collectors.toList()));

        favOverloadRows.setAll(s.favoriteOverloads().entrySet().stream()
                .map(e -> new FavOverloadRow(e.getKey(), e.getValue()))
                .collect(Collectors.toList()));

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(buildFavMethodsPane(), buildFavOverloadsPane(), buildButtonBar());

        window.show(root);
    }

    private TitledPane buildFavMethodsPane() {
        TableView<FavMethodRow> table = new TableView<>(favMethodRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No favourite methods yet. Pick a class and its methods below."));
        table.setPrefHeight(120);

        TableColumn<FavMethodRow, String> classCol = new TableColumn<>("Class");
        classCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().className()));
        TableColumn<FavMethodRow, String> methodsCol = new TableColumn<>("Favourite methods (in order)");
        methodsCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().methods()));
        table.getColumns().addAll(List.of(classCol, methodsCol));

        Button removeBtn = new Button("Remove");
        removeBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, was, sel) -> removeBtn.setDisable(sel == null));
        removeBtn.setOnAction(e -> {
            FavMethodRow sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) favMethodRows.remove(sel);
        });

        // No manual typing: class from the SDK facade list, methods multi-selected from that class's methods.
        ComboBox<String> classCombo = new ComboBox<>(FXCollections.observableArrayList(availableFacades()));
        classCombo.setPromptText("class");
        ListView<String> methodsList = new ListView<>();
        methodsList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        methodsList.setPrefHeight(120);
        HBox.setHgrow(methodsList, Priority.ALWAYS);

        classCombo.getSelectionModel().selectedItemProperty().addListener((o, was, cls) -> {
            methodsList.getSelectionModel().clearSelection();
            methodsList.setItems(FXCollections.observableArrayList(methodNames(cls)));
            // Pre-select the class's currently-favourited methods for easy editing.
            favMethodRows.stream().filter(r -> r.className().equals(cls)).findFirst().ifPresent(row -> {
                Set<String> current = splitMethods(row.methods());
                for (String m : methodsList.getItems()) {
                    if (current.contains(m)) methodsList.getSelectionModel().select(m);
                }
            });
        });

        Button setBtn = new Button("Set favourites");
        setBtn.setOnAction(e -> {
            String cls = classCombo.getValue();
            if (cls == null) { error("Pick a class first."); return; }
            List<String> chosen = new ArrayList<>(methodsList.getSelectionModel().getSelectedItems());
            favMethodRows.removeIf(r -> r.className().equals(cls));
            if (!chosen.isEmpty()) favMethodRows.add(new FavMethodRow(cls, String.join(", ", chosen)));
            statusLabel.setText("");
        });

        Label notIndexed = new Label("(SDK not indexed yet — reopen after the project finishes loading)");
        notIndexed.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        notIndexed.setVisible(!sdkIndexed());
        notIndexed.setManaged(!sdkIndexed());
        classCombo.setDisable(!sdkIndexed());
        setBtn.setDisable(!sdkIndexed());

        HBox editor = new HBox(8, new VBox(4, new Label("Class:"), classCombo),
                new VBox(4, new Label("Methods (Ctrl/Shift-click for several):"), methodsList));
        editor.setAlignment(Pos.TOP_LEFT);
        HBox buttons = new HBox(8, setBtn, removeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(6, table, editor, notIndexed, buttons);
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

        // Add row: class -> method -> overload, all from the SDK API surface (no manual typing).
        ComboBox<String> classCombo = new ComboBox<>(FXCollections.observableArrayList(availableFacades()));
        classCombo.setPromptText("class");
        ComboBox<String> methodCombo = new ComboBox<>();
        methodCombo.setPromptText("method");
        ComboBox<MethodSignature> overloadCombo = new ComboBox<>();
        overloadCombo.setPromptText("overload");
        overloadCombo.setConverter(new StringConverter<>() {
            @Override public String toString(MethodSignature m) { return m == null ? "" : m.toString(); }
            @Override public MethodSignature fromString(String s) { return null; }
        });

        classCombo.getSelectionModel().selectedItemProperty().addListener((o, was, cls) -> {
            methodCombo.setItems(FXCollections.observableArrayList(methodNames(cls)));
            methodCombo.getSelectionModel().clearSelection();
            overloadCombo.getItems().clear();
        });
        methodCombo.getSelectionModel().selectedItemProperty().addListener((o, was, m) ->
                overloadCombo.setItems(FXCollections.observableArrayList(overloads(classCombo.getValue(), m))));

        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> {
            String cls = classCombo.getValue();
            String method = methodCombo.getValue();
            MethodSignature sig = overloadCombo.getValue();
            if (cls == null || method == null || sig == null) { error("Pick a class, method and overload."); return; }
            String methodKey = cls + "#" + method;
            favOverloadRows.removeIf(r -> r.methodKey().equals(methodKey));
            favOverloadRows.add(new FavOverloadRow(methodKey, sig.signatureKey()));
            statusLabel.setText("");
        });

        boolean indexed = sdkIndexed();
        classCombo.setDisable(!indexed);
        methodCombo.setDisable(!indexed);
        overloadCombo.setDisable(!indexed);
        addBtn.setDisable(!indexed);

        HBox addRow = new HBox(8, classCombo, methodCombo, overloadCombo, addBtn);
        addRow.setAlignment(Pos.CENTER_LEFT);
        HBox tableButtons = new HBox(removeBtn);
        tableButtons.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(6, table, addRow, tableButtons);
        VBox.setVgrow(table, Priority.ALWAYS);
        TitledPane pane = new TitledPane("Favourite Overload per Method", box);
        pane.setCollapsible(false);
        return pane;
    }

    // ── SDK-surface helpers (populate the dropdowns; never manual typing) ────────────────────────────────

    /** Distinct method names of {@code className}'s SDK facade (static-facade methods), alphabetical. */
    private List<String> methodNames(String className) {
        if (className == null || projectAnalyzer == null) return List.of();
        return projectAnalyzer.getMethods(className, true).stream()
                .map(MethodSignature::name).distinct().sorted().collect(Collectors.toList());
    }

    /** All overloads of {@code className#methodName}. */
    private List<MethodSignature> overloads(String className, String methodName) {
        if (className == null || methodName == null || projectAnalyzer == null) return List.of();
        return projectAnalyzer.getMethods(className, true).stream()
                .filter(m -> m.name().equals(methodName)).collect(Collectors.toList());
    }

    /**
     * The facades <em>this project's</em> SDK actually has, in the bundled catalog's order — a facade that resolves
     * no static methods is not in the bot's jar (the analyzer reads that jar, not the one Studio compiled
     * against), and favouriting a method on a class the bot doesn't have would store a setting that can only
     * ever fail. Falls back to the full list while the jar is still being indexed, so the dropdown is never
     * mysteriously empty — {@link #sdkIndexed()} is what gates the controls until then.
     */
    private List<String> availableFacades() {
        if (!sdkIndexed()) return PluginHost.facadeNames();
        return PluginHost.facadeNames().stream()
                .filter(c -> !projectAnalyzer.getMethods(c, true).isEmpty())
                .collect(Collectors.toList());
    }

    /** True once the SDK jar is indexed so the facades resolve to methods (else the dropdowns stay disabled). */
    private boolean sdkIndexed() {
        if (projectAnalyzer == null) return false;
        return PluginHost.facadeNames().stream().anyMatch(c -> !projectAnalyzer.getMethods(c, true).isEmpty());
    }

    /** Splits a stored comma-separated method list back into a set (order-insensitive membership test). */
    private static Set<String> splitMethods(String methods) {
        Set<String> out = new LinkedHashSet<>();
        for (String m : methods.split(",")) {
            String t = m.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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

        StudioProjectSettings s = settingsService.current();

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
