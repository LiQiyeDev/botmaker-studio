package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.validation.DiagnosticsManager;
import com.botmaker.studio.validation.ErrorTranslator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The <b>Errors</b> bottom tab: the severity filter bar and the diagnostic list, with a click on a row
 * revealing the offending block on the canvas.
 *
 * <p>The filtering and the counts are {@code static} and free of JavaFX ({@link #matches}, {@link #counts})
 * so the one piece of logic here that can be wrong is testable headlessly.
 */
final class DiagnosticsPanel {

    /** What the filter bar reports, and the only arithmetic in this class. */
    record Counts(long errors, long warnings, long infos) { }

    private final DiagnosticsManager diagnosticsManager;
    private final Consumer<CodeBlock> onRevealBlock;
    /** Raises this tab. Called only when a compile produced at least one error. */
    private final Runnable onErrorsFound;

    private final ListView<Diagnostic> list = new ListView<>();
    private final ToggleButton errorFilter = severityFilter("Errors", "#E74C3C");
    private final ToggleButton warningFilter = severityFilter("Warnings", "#F39C12");
    private final ToggleButton infoFilter = severityFilter("Infos/Hints", "#3498DB");
    private final VBox node;

    private List<Diagnostic> all = new ArrayList<>();

    DiagnosticsPanel(DiagnosticsManager diagnosticsManager,
                     Consumer<CodeBlock> onRevealBlock,
                     Runnable onErrorsFound) {
        this.diagnosticsManager = diagnosticsManager;
        this.onRevealBlock = onRevealBlock;
        this.onErrorsFound = onErrorsFound;

        configureList();
        VBox.setVgrow(list, Priority.ALWAYS);

        HBox filterBar = new HBox(10, new Label("Filter: "), errorFilter, warningFilter, infoFilter);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(5));
        filterBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");

        this.node = new VBox(filterBar, list);
    }

    VBox node() {
        return node;
    }

    /** Replaces the diagnostic set, re-filters, re-counts, and raises this tab if anything is an error. */
    void update(List<Diagnostic> diagnostics) {
        this.all = diagnostics != null ? new ArrayList<>(diagnostics) : new ArrayList<>();
        applyFilters();

        Counts counts = counts(all);
        errorFilter.setText("Errors (%d)".formatted(counts.errors()));
        warningFilter.setText("Warnings (%d)".formatted(counts.warnings()));
        infoFilter.setText("Infos (%d)".formatted(counts.infos()));

        if (counts.errors() > 0) onErrorsFound.run();
    }

    /**
     * Whether {@code d} survives the three severity toggles. A diagnostic with an unrecognised (or absent)
     * severity always passes — hiding something nobody asked to hide is the worse failure.
     */
    static boolean matches(Diagnostic d, boolean showErrors, boolean showWarnings, boolean showInfos) {
        DiagnosticSeverity severity = d.getSeverity();
        if (severity == null) return true;
        return switch (severity) {
            case Error -> showErrors;
            case Warning -> showWarnings;
            case Information, Hint -> showInfos;
        };
    }

    /** Per-severity totals over the unfiltered set; {@code Hint} counts as an info, as the filter treats it. */
    static Counts counts(List<Diagnostic> diagnostics) {
        long errors = 0;
        long warnings = 0;
        long infos = 0;
        for (Diagnostic d : diagnostics) {
            DiagnosticSeverity severity = d.getSeverity();
            if (severity == null) continue;
            switch (severity) {
                case Error -> errors++;
                case Warning -> warnings++;
                case Information, Hint -> infos++;
            }
        }
        return new Counts(errors, warnings, infos);
    }

    private void applyFilters() {
        list.getItems().setAll(all.stream()
                .filter(d -> matches(d, errorFilter.isSelected(), warningFilter.isSelected(), infoFilter.isSelected()))
                .toList());
    }

    private ToggleButton severityFilter(String label, String colour) {
        ToggleButton b = new ToggleButton(label);
        b.setSelected(true);
        b.setStyle("-fx-text-fill: " + colour + "; -fx-font-weight: bold;");
        b.setOnAction(e -> applyFilters());
        return b;
    }

    private void configureList() {
        list.setPlaceholder(new Label("No issues found."));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Diagnostic diagnostic, boolean empty) {
                super.updateItem(diagnostic, empty);
                if (empty || diagnostic == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    setOnMouseClicked(null);
                    return;
                }
                render(this, diagnostic);
            }
        });

        ContextMenu cm = new ContextMenu();
        MenuItem copy = new MenuItem("Copy Selection");
        copy.setOnAction(e -> {
            Diagnostic selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(selected.getMessage());
            Clipboard.getSystemClipboard().setContent(content);
        });
        cm.getItems().add(copy);
        list.setContextMenu(cm);
    }

    private void render(ListCell<Diagnostic> cell, Diagnostic diagnostic) {
        String message = ErrorTranslator.getShortSummary(diagnostic);
        int line = diagnostic.getRange().getStart().getLine() + 1;

        String icon;
        String colorStyle;
        String iconColorStyle;
        if (diagnostic.getSeverity() == DiagnosticSeverity.Error) {
            icon = "❌";
            colorStyle = "-fx-text-fill: #C0392B;";
            iconColorStyle = "-fx-text-fill: #E74C3C;";
        } else if (diagnostic.getSeverity() == DiagnosticSeverity.Warning) {
            icon = "⚠️";
            colorStyle = "-fx-text-fill: #D35400;";
            iconColorStyle = "-fx-text-fill: #F39C12;";
        } else {
            icon = "ℹ️";
            colorStyle = "-fx-text-fill: #2980B9;";
            iconColorStyle = "-fx-text-fill: #3498DB;";
        }

        Label iconLabel = new Label(icon);
        iconLabel.setStyle(iconColorStyle + "-fx-font-size: 14px; -fx-padding: 0 8 0 0;");

        cell.setText("%sLine %d: %s".formatted(fileNamePrefix(diagnostic), line, message));
        cell.setStyle(colorStyle + "-fx-font-family: 'Segoe UI', sans-serif; -fx-font-weight: normal;");
        cell.setGraphic(iconLabel);
        cell.setOnMouseClicked(event ->
                diagnosticsManager.findBlockForDiagnostic(diagnostic).ifPresent(onRevealBlock));
    }

    /** {@code "[Main.java] "} when the diagnostic carries its source URI, empty otherwise. */
    private static String fileNamePrefix(Diagnostic diagnostic) {
        if (!(diagnostic.getData() instanceof String uri)) return "";
        try {
            return "[" + Path.of(new URI(uri)).getFileName() + "] ";
        } catch (Exception e) {
            // Fallback for non-standard URIs.
            return "[" + (uri.contains("/") ? uri.substring(uri.lastIndexOf('/') + 1) : uri) + "] ";
        }
    }
}
