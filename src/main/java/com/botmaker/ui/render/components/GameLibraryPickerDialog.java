package com.botmaker.ui.render.components;

import com.botmaker.game.GameLibraryProvider;
import com.botmaker.game.InstalledGame;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reusable "pick a game to launch" popup: a searchable grid of cover art fed by any
 * {@link GameLibraryProvider} (Steam today, Epic/GOG later). Cover images are loaded lazily from local
 * files (no network); a placeholder tile is shown when a game has no cached art.
 *
 * <p>A bottom manual-entry field lets the user type a raw launch id (e.g. a Steam appId that isn't
 * installed locally), so the picker degrades gracefully — this fallback lives here so every launcher that
 * reuses the dialog inherits it.
 */
public final class GameLibraryPickerDialog {

    private static final double COVER_W = 120;
    private static final double COVER_H = 160;
    private static final double TILE_W = 132;

    private GameLibraryPickerDialog() {}

    /** Shows the picker for {@code provider}; resolves to the chosen game, or empty if cancelled. */
    public static Optional<InstalledGame> show(Window owner, GameLibraryProvider provider) {
        Dialog<InstalledGame> dialog = new Dialog<>();
        dialog.setTitle("Choose a " + provider.displayName() + " game");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        TextField search = new TextField();
        search.setPromptText("Search " + provider.displayName() + " games…");

        FlowPane grid = new FlowPane(12, 12);
        grid.setPadding(new Insets(8));
        grid.setPrefWrapLength(4 * TILE_W + 5 * 12);

        Label status = new Label("Scanning " + provider.displayName() + " library…");
        status.setPadding(new Insets(8));

        ScrollPane scroll = new ScrollPane(status);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(440);

        TextField manual = new TextField();
        manual.setPromptText("…or enter a " + provider.displayName() + " id manually");
        HBox.setHgrow(manual, Priority.ALWAYS);
        Button useManual = new Button("Use id");
        Runnable applyManual = () -> {
            String id = manual.getText() == null ? "" : manual.getText().trim();
            if (id.isEmpty()) return;
            dialog.setResult(new InstalledGame(provider.platform(), id, id, null));
            dialog.close();
        };
        useManual.setOnAction(e -> applyManual.run());
        manual.setOnAction(e -> applyManual.run());
        HBox manualRow = new HBox(8, manual, useManual);
        manualRow.setAlignment(Pos.CENTER_LEFT);
        manualRow.setPadding(new Insets(4, 8, 0, 8));

        VBox content = new VBox(8, search, scroll, manualRow);
        content.setPadding(new Insets(10));
        content.setPrefWidth(4 * TILE_W + 5 * 12 + 36);
        dialog.getDialogPane().setContent(content);

        // Scan off the FX thread; build tiles once games are known.
        List<TileEntry> tiles = new ArrayList<>();
        new Thread(() -> {
            List<InstalledGame> games = provider.installedGames();
            Platform.runLater(() -> {
                if (games.isEmpty()) {
                    status.setText("No installed " + provider.displayName()
                            + " games found. Enter an id manually below.");
                    return;
                }
                for (InstalledGame g : games) {
                    TileEntry tile = createTile(g, dialog);
                    tiles.add(tile);
                    grid.getChildren().add(tile.node());
                }
                scroll.setContent(grid);
            });
        }, provider.platform() + "-scan").start();

        search.textProperty().addListener((obs, old, q) -> {
            String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            for (TileEntry t : tiles) {
                boolean match = needle.isEmpty() || t.game().name().toLowerCase(Locale.ROOT).contains(needle);
                t.node().setVisible(match);
                t.node().setManaged(match);
            }
        });

        dialog.setResultConverter(bt -> bt == ButtonType.CANCEL ? null : dialog.getResult());
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    private static TileEntry createTile(InstalledGame game, Dialog<InstalledGame> dialog) {
        StackPane cover = new StackPane();
        cover.getStyleClass().add("game-picker-cover");
        cover.setPrefSize(COVER_W, COVER_H);
        cover.setMinSize(COVER_W, COVER_H);
        cover.setMaxSize(COVER_W, COVER_H);

        Path art = game.artwork();
        if (art != null) {
            ImageView iv = new ImageView(new Image(art.toUri().toString(), COVER_W, COVER_H, false, true, true));
            iv.setFitWidth(COVER_W);
            iv.setFitHeight(COVER_H);
            cover.getChildren().add(iv);
        } else {
            Label initials = new Label(initials(game.name()));
            initials.getStyleClass().add("game-picker-cover-placeholder");
            cover.getChildren().add(initials);
        }

        Label name = new Label(game.name());
        name.getStyleClass().add("game-picker-name");
        name.setWrapText(true);
        name.setMaxWidth(TILE_W);
        name.setAlignment(Pos.CENTER);

        VBox tile = new VBox(6, cover, name);
        tile.getStyleClass().add("game-picker-tile");
        tile.setAlignment(Pos.TOP_CENTER);
        tile.setPrefWidth(TILE_W);
        tile.setPadding(new Insets(6));
        tile.setOnMouseClicked(e -> {
            dialog.setResult(game);
            dialog.close();
        });
        return new TileEntry(game, tile);
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] words = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0)));
            if (sb.length() >= 3) break;
        }
        return sb.toString();
    }

    private record TileEntry(InstalledGame game, VBox node) {}
}
