package com.botmaker.studio.ui.app;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * Modal dialog for viewing, adding and removing the current file's import declarations. Reads/writes go
 * through {@link CodeEditorService#getCodeEditor()} (which publishes a code update on every edit), and the
 * add field autocompletes against the curated, whitelisted library surface exposed by {@link ProjectAnalyzer}.
 */
public class ManageImportsDialog {

    private static final int SUGGESTION_LIMIT = 15;

    private final Window owner;
    private final CodeEditorService codeEditorService;

    private final ObservableList<String> imports = FXCollections.observableArrayList();
    private final TextField addField = new TextField();
    private final ContextMenu suggestions = new ContextMenu();
    private final Label statusLabel = new Label();

    private Stage stage;

    public ManageImportsDialog(Window owner, CodeEditorService codeEditorService) {
        this.owner = owner;
        this.codeEditorService = codeEditorService;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Manage Imports");

        refresh();

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(buildList(), buildAddRow(), buildButtonBar());

        stage.setScene(new Scene(root, 520, 420));
        stage.show();
    }

    private void refresh() {
        imports.setAll(codeEditorService.getCodeEditor().getImports());
    }

    // -------------------------------------------------------------------------
    // Current imports list
    // -------------------------------------------------------------------------

    private VBox buildList() {
        ListView<String> list = new ListView<>(imports);
        list.setPlaceholder(new Label("No imports in this file."));

        Button removeBtn = new Button("Remove");
        removeBtn.setDisable(true);
        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> removeBtn.setDisable(sel == null));
        removeBtn.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            // listImports renders on-demand imports as "pkg.*"; strip the suffix for removal.
            String fqn = sel.endsWith(".*") ? sel.substring(0, sel.length() - 2) : sel;
            codeEditorService.getCodeEditor().removeImport(fqn);
            refresh();
        });

        HBox buttons = new HBox(removeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Label heading = new Label("File imports");
        heading.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(6, heading, list, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    // -------------------------------------------------------------------------
    // Add row with whitelisted-type autocomplete
    // -------------------------------------------------------------------------

    private HBox buildAddRow() {
        addField.setPromptText("fully.qualified.ClassName");
        HBox.setHgrow(addField, Priority.ALWAYS);
        addField.textProperty().addListener((obs, old, text) -> showSuggestions(text));

        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> addCurrent());

        HBox row = new HBox(8, addField, addBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void showSuggestions(String query) {
        if (query == null || query.isBlank()) {
            suggestions.hide();
            return;
        }
        String q = query.trim().toLowerCase();
        List<MenuItem> items = codeEditorService.getProjectAnalyzer().getAvailableTypes(null).stream()
                .map(ResolvedType::qualifiedName)
                .filter(fqn -> fqn != null && fqn.contains(".") && fqn.toLowerCase().contains(q))
                .distinct()
                .limit(SUGGESTION_LIMIT)
                .map(fqn -> {
                    MenuItem item = new MenuItem(fqn);
                    item.setOnAction(e -> {
                        suggestions.hide();
                        addField.setText(fqn);
                        addField.positionCaret(addField.getLength());
                    });
                    return item;
                })
                .toList();

        suggestions.getItems().setAll(items);
        if (items.isEmpty()) {
            suggestions.hide();
        } else if (!suggestions.isShowing()) {
            suggestions.show(addField, Side.BOTTOM, 0, 0);
        }
    }

    private void addCurrent() {
        statusLabel.setText("");
        String fqn = addField.getText() == null ? "" : addField.getText().trim();
        if (!fqn.contains(".")) {
            error("Enter a fully-qualified class name (e.g. com.botmaker.sdk.api.Point).");
            return;
        }
        if (imports.contains(fqn)) {
            error(fqn + " is already imported.");
            return;
        }
        codeEditorService.getCodeEditor().addImport(fqn);
        addField.clear();
        suggestions.hide();
        refresh();
    }

    // -------------------------------------------------------------------------
    // Close
    // -------------------------------------------------------------------------

    private HBox buildButtonBar() {
        statusLabel.setStyle("-fx-text-fill: #b00020;");

        Button close = new Button("Close");
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, statusLabel, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void error(String message) {
        statusLabel.setText(message);
    }
}
