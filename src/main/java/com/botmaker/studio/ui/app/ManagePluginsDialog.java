package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * <b>Project ▸ Manage Plugins…</b> — the browser over the plugin registry, and the install button.
 *
 * <p>Installing is deliberately not special: it adds an ordinary dependency through {@link LibraryService},
 * exactly as <b>Manage Libraries</b> does, so a plugin is on the project's classpath and
 * {@code PluginHost.bind} finds it through the same {@code ServiceLoader} pass that finds the SDK. A
 * bespoke install path would be a privilege the first-party plugin has and a third party's does not —
 * which is the back door the whole platform exists to close. The corollary is that <b>this dialog can be
 * undone from Manage Libraries</b>: a plugin is a row there like any other, which is where its version is
 * changed.
 *
 * <p><b>The version installed is the registry's {@code verifiedVersion}, not the newest tag.</b> That is
 * the version the registry's gate actually loaded and checked; a newer tag may be one nothing has ever
 * run. Only an entry with no verified version falls back to asking JitPack for the newest.
 *
 * <p>Everything about the network here degrades to a sentence: an unreachable registry shows a message in
 * the empty list and nothing else changes, matching how {@code JitPackSearch} already treats a failure. A
 * catalog nobody can fetch must never stop somebody editing their bot.
 */
public final class ManagePluginsDialog {

    private final Window owner;
    private final LibraryService libraryService;
    private final PluginRegistry registry;
    private final JitPackSearch jitpack;

    private final ObservableList<PluginRegistry.Plugin> shown = FXCollections.observableArrayList();
    private final List<PluginRegistry.Plugin> all = new ArrayList<>();
    private final TextField searchField = new TextField();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final ListView<PluginRegistry.Plugin> list = new ListView<>(shown);

    /** The project's libraries as they stand, re-read after every change so the badges stay honest. */
    private List<UserLibrary> installed = List.of();

    /** The coordinates a dev build in {@code ~/.m2} answers for, so a row can say where its version came from. */
    private final Set<String> localCoordinates = new HashSet<>();

    private Stage stage;

    public ManagePluginsDialog(Window owner, LibraryService libraryService, PluginRegistry registry,
                               JitPackSearch jitpack) {
        this.owner = owner;
        this.libraryService = libraryService;
        this.registry = registry;
        this.jitpack = jitpack;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Manage Plugins");

        installed = libraryService.currentLibraries();

        searchField.setPromptText("Search plugins");
        searchField.textProperty().addListener((obs, old, text) -> refilter(text));

        list.setCellFactory(view -> new PluginCell());
        list.setPlaceholder(new Label("Loading…"));
        VBox.setVgrow(list, Priority.ALWAYS);

        Label hint = new Label("A plugin is an ordinary dependency: it is added to this project's pom, and"
                + " its version can be changed in Manage Libraries.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        // Said plainly, in the dialog, because the registry's README says it and a user installing from
        // here never reads that: the checks ask whether a plugin WORKS, never whether it is safe.
        Label caveat = new Label("Registry plugins are curated and checked for loading, not reviewed for"
                + " safety — a plugin runs with Studio's own permissions.");
        caveat.setWrapText(true);
        caveat.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        HBox bar = new HBox(10, progress, statusLabel, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, searchField, list, hint, caveat, bar);
        root.setPadding(new Insets(16));

        stage.setScene(ThemedWindows.scene(root, 620, 520));
        stage.show();

        load();
    }

    private void load() {
        // The ~/.m2 scan opens jars, so it is not the FX thread's work; it is also the half that must not
        // wait on the network, since a plugin author testing an unpublished build may have no registry at
        // all. Both halves are joined before anything is shown so the rows never re-order under the mouse.
        CompletableFuture<List<MavenService.LocalPluginBuild>> local =
                CompletableFuture.supplyAsync(MavenService::localPluginBuilds);
        registry.browse().thenCombine(local, (plugins, builds) -> merge(plugins, builds))
                .thenAccept(rows -> Platform.runLater(() -> {
                    all.clear();
                    all.addAll(rows);
                    refilter(searchField.getText());
                    list.setPlaceholder(new Label(rows.isEmpty()
                            ? "No plugins listed — the registry is empty or could not be reached."
                            : "No plugin matches that search."));
                }));
    }

    /**
     * The registry's entries, with what is built locally taking precedence over what is published.
     *
     * <p>A local build of a coordinate the registry also lists <b>replaces that entry's version</b> rather
     * than adding a second row: two rows for one artifact would offer to install two versions of it, and a
     * developer who has just built one wants the one they built. A local build nobody has published yet
     * becomes a row of its own, at the top, because it is the row they came here for.
     *
     * <p>Only ever populated in a dev build — {@link MavenService#localPluginBuilds()} answers empty
     * otherwise — so a released Studio shows exactly the registry and nothing else.
     */
    private List<PluginRegistry.Plugin> merge(List<PluginRegistry.Plugin> published,
                                              List<MavenService.LocalPluginBuild> builds) {
        localCoordinates.clear();
        List<PluginRegistry.Plugin> rows = new ArrayList<>(published);
        for (MavenService.LocalPluginBuild build : builds) {
            localCoordinates.add(build.coordinate());
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).coordinate().equals(build.coordinate())) at = i;
            }
            if (at >= 0) {
                PluginRegistry.Plugin entry = rows.get(at);
                rows.set(at, new PluginRegistry.Plugin(entry.id(), entry.name(), entry.coordinate(),
                        entry.repo(), entry.description(), entry.tags(), entry.minContractVersion(),
                        entry.valueTypeIds(), build.version(), entry.verifiedAt()));
            } else {
                rows.add(0, new PluginRegistry.Plugin(build.coordinate(), build.artifactId(),
                        build.coordinate(), "", "Built locally into ~/.m2 — not published.", List.of(), "",
                        List.of(), build.version(), ""));
            }
        }
        return rows;
    }

    private void refilter(String query) {
        shown.setAll(all.stream().filter(plugin -> plugin.matches(query)).toList());
    }

    // -------------------------------------------------------------------------
    // Install / remove
    // -------------------------------------------------------------------------

    /**
     * Adds the plugin's coordinate to the project's libraries.
     *
     * <p>Idempotent by coordinate: re-installing replaces the row rather than adding a second one, since
     * two versions of one artifact on a classpath is the state that produces the least diagnosable failure
     * this platform has.
     */
    private void install(PluginRegistry.Plugin plugin) {
        if (!plugin.isInstallable()) {
            error("This entry has no resolvable coordinate — nothing to install.");
            return;
        }
        busy(true);
        version(plugin).thenAccept(version -> {
            if (version == null || version.isBlank()) {
                Platform.runLater(() -> {
                    busy(false);
                    error("Could not resolve a version for " + plugin.coordinate() + ".");
                });
                return;
            }
            List<UserLibrary> next = new ArrayList<>(libraryService.currentLibraries());
            next.removeIf(lib -> lib.groupId().equals(plugin.groupId())
                    && lib.artifactId().equals(plugin.artifactId()));
            next.add(new UserLibrary(plugin.groupId(), plugin.artifactId(), version));
            apply(next, plugin.name() + " " + version + " installed.");
        });
    }

    private void remove(PluginRegistry.Plugin plugin) {
        busy(true);
        List<UserLibrary> next = new ArrayList<>(libraryService.currentLibraries());
        next.removeIf(lib -> lib.groupId().equals(plugin.groupId())
                && lib.artifactId().equals(plugin.artifactId()));
        apply(next, plugin.name() + " removed.");
    }

    /**
     * Writes the pom and re-resolves, then re-reads what is installed.
     *
     * <p>The SDK version is passed straight back through: this dialog has no business moving it, and
     * {@code updateLibraries} takes it because the pom is written whole.
     */
    private void apply(List<UserLibrary> libraries, String done) {
        libraryService.updateLibraries(libraries, libraryService.currentSdkVersion())
                .whenComplete((ok, err) -> Platform.runLater(() -> {
                    busy(false);
                    installed = libraryService.currentLibraries();
                    // The badges are per-row, so the rows are what have to be redrawn.
                    list.refresh();
                    if (err != null) {
                        error(rootMessage(err));
                    } else {
                        statusLabel.setStyle("-fx-text-fill: gray;");
                        statusLabel.setText(done);
                    }
                }));
    }

    /** The verified version, or — only when the entry carries none — JitPack's newest. */
    private CompletableFuture<String> version(PluginRegistry.Plugin plugin) {
        if (!plugin.verifiedVersion().isBlank()) {
            return CompletableFuture.completedFuture(plugin.verifiedVersion());
        }
        return jitpack.fetchLatestVersion(plugin.groupId(), plugin.artifactId());
    }

    private void busy(boolean busy) {
        progress.setVisible(busy);
        list.setDisable(busy);
        if (busy) {
            statusLabel.setText("");
        }
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

    // -------------------------------------------------------------------------
    // One row
    // -------------------------------------------------------------------------

    private final class PluginCell extends ListCell<PluginRegistry.Plugin> {

        @Override
        protected void updateItem(PluginRegistry.Plugin plugin, boolean empty) {
            super.updateItem(plugin, empty);
            if (empty || plugin == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(plugin.name().isBlank() ? plugin.id() : plugin.name());
            name.setStyle("-fx-font-weight: bold;");
            // The same wording the SDK dropdown uses for the same thing, because it is the same thing: a
            // build in ~/.m2 that no repository has ever served.
            boolean localBuild = localCoordinates.contains(plugin.coordinate());
            Label coordinate = new Label(plugin.coordinate()
                    + (localBuild ? "  " + plugin.verifiedVersion() + " (local build)" : ""));
            coordinate.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            Label description = new Label(plugin.description());
            description.setWrapText(true);

            VBox text = new VBox(2, name, coordinate, description);
            if (!plugin.repo().isBlank()) {
                Hyperlink source = new Hyperlink(plugin.repo());
                source.setStyle("-fx-font-size: 11px;");
                source.setOnAction(e -> BrowserLauncher.open(plugin.htmlUrl()));
                text.getChildren().add(source);
            }
            HBox.setHgrow(text, Priority.ALWAYS);

            boolean here = plugin.isInstalledIn(installed);
            Button action = new Button(here ? "Remove" : "Install");
            action.setOnAction(e -> {
                if (here) {
                    remove(plugin);
                } else {
                    install(plugin);
                }
            });

            HBox row = new HBox(10, text, action);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 2, 6, 2));
            setGraphic(row);
        }
    }
}
