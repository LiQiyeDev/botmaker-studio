package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.ReviewService;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * The <b>Review</b> bottom tab: everything BotMaker changed for the user and could not finish on its own.
 *
 * <p>This is the half of the promise the marks exist for. A refactor that rewrites files the user is not
 * looking at — an SDK upgrade, a signature edit, a template repoint — leaves {@code @NeedsReview} entries
 * behind ({@code parser/refactor/ReviewMarks}); without somewhere to see them the user would have to open
 * every file in the bot to find out what happened. Here each entry is one row: what changed, where, and a
 * click that takes them to it.
 *
 * <p><b>Nothing is cached.</b> The list is re-scanned from the sources on every {@link #refresh}, and the tab
 * refreshes when it is opened. Four different code paths write marks, two of them without the editor
 * involved, so a panel holding its own copy would be wrong more often than right — see
 * {@link ReviewService}.
 */
final class ReviewPanel {

    private final ProjectConfig config;
    private final ProjectState state;
    /** Opens the marked function on the canvas. */
    private final Consumer<ReviewService.Item> onReveal;

    private final ListView<ReviewService.Item> list = new ListView<>();
    private final Label summary = new Label();
    private final Button markReviewed = new Button("Mark Reviewed");
    private final VBox node;

    ReviewPanel(ProjectConfig config, ProjectState state, Consumer<ReviewService.Item> onReveal) {
        this.config = config;
        this.state = state;
        this.onReveal = onReveal;

        configureList();
        VBox.setVgrow(list, Priority.ALWAYS);

        summary.getStyleClass().add("review-summary");
        markReviewed.getStyleClass().add("review-action");
        markReviewed.setDisable(true);
        markReviewed.setOnAction(e -> markSelectedReviewed());
        list.getSelectionModel().selectedItemProperty().addListener(
                (o, was, now) -> markReviewed.setDisable(now == null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        // Same band as the Errors tab's filter bar, over the same tokens — see blocks.css.
        HBox bar = new HBox(summary, spacer, markReviewed, refresh);
        bar.getStyleClass().add("diagnostics-filter-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        this.node = new VBox(bar, list);
    }

    VBox node() {
        return node;
    }

    /** Re-reads the marks from the project's sources. Cheap enough to call whenever the tab is opened. */
    void refresh() {
        List<ReviewService.Item> items = ReviewService.scan(config, state);
        list.getItems().setAll(items);
        long files = items.stream().map(ReviewService.Item::file).distinct().count();
        summary.setText(items.isEmpty()
                ? "Nothing to review."
                : items.size() + (items.size() == 1 ? " thing" : " things") + " to look at in "
                        + files + (files == 1 ? " file." : " files."));
    }

    /** True when the project holds at least one mark — what decides whether the tab raises itself. */
    boolean hasItems() {
        return !list.getItems().isEmpty();
    }

    /**
     * Strips the selected entry and re-scans. Re-scanning rather than removing the row is deliberate: the
     * strip rewrote a file, and the file is the truth.
     */
    private void markSelectedReviewed() {
        ReviewService.Item selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        ReviewService.markReviewed(config, state, selected);
        refresh();
    }

    private void configureList() {
        list.setPlaceholder(new Label("Nothing to review — BotMaker finished everything it changed."));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ReviewService.Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setOnMouseClicked(null);
                    return;
                }
                setText(null);
                setGraphic(row(item));
                setOnMouseClicked(e -> {
                    if (onReveal != null) onReveal.accept(item);
                });
            }
        });
    }

    /**
     * Two lines: where it is, and what happened. The entry is the sentence the refactor wrote — it is meant to
     * be read as prose, so it wraps rather than being clipped to the panel's width.
     */
    private static VBox row(ReviewService.Item item) {
        Label where = new Label(item.where());
        where.getStyleClass().add("review-cell-where");

        Label what = new Label(item.entry());
        what.getStyleClass().add("review-cell-what");
        what.setWrapText(true);

        VBox box = new VBox(2, where, what);
        box.getStyleClass().add("review-cell");
        return box;
    }
}
