package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The project file tree, split into two top-level groups:
 *
 * <ul>
 *   <li><b>My code</b> — {@link FileRole#EDITABLE} files, shown as a <em>flat</em> list. Yours to change;
 *       edits persist. Flat on purpose: the package arborescence they sit in ({@code com → <bot> → …}) is
 *       three rows of Java ceremony between the user and the only thing they ever open. The sweep is still
 *       recursive, so a file anywhere under {@code src/main/java} shows — it just shows at the top level.</li>
 *   <li><b>Library</b> — {@link FileRole#LIBRARY} source, shown flat. Not the project's: bundled code the
 *       user can read and not change.</li>
 * </ul>
 *
 * <p><b>Every file the walk finds is now listed.</b> There used to be a third state — files BotMaker wrote
 * and rewrote ({@code Activities}, {@code ActivityRegistry}, {@code FlowDriver}, {@code Templates} and the
 * entry point) which were kept out of the tree entirely, because they were the Activity Flow, the parameters
 * and the image library rendered as Java and listing them offered a file the user could read but never
 * change. Nothing is rendered as Java since 2026-08-29, so nothing is hidden from its owner.
 *
 * <p>Roles come from {@link FileRole#of} — this class must not re-derive them from paths.
 */
public class FileExplorerManager {

    private static final Logger LOGGER = Logger.getLogger(FileExplorerManager.class.getName());

    private static final String GROUP_USER = "My code";
    private static final String GROUP_LIBRARY = "Library";

    private final ProjectConfig config;
    private final CodeEditorService codeEditorService;
    private final ProjectState state;
    private final ActivityService activityService;
    private final TreeView<ExplorerNode> fileTree;

    /**
     * A tree row: either a synthetic group header or a real file/directory.
     *
     * <p>The tree used to be a {@code TreeView<Path>}, which left no room for a group header that isn't a
     * path — and the cell factory / selection listener both branch on {@code Files.isDirectory(...)}, which a
     * sentinel path would have to lie about.
     */
    public record ExplorerNode(String label, Path path, FileRole role, boolean group) {

        static ExplorerNode group(String label) {
            return new ExplorerNode(label, null, null, true);
        }

        static ExplorerNode of(Path path, FileRole role) {
            return new ExplorerNode(path.getFileName().toString(), path, role, false);
        }

        boolean isDirectory() {
            return path != null && Files.isDirectory(path);
        }

        /** Stable key for save/restore of expansion state. */
        String key() {
            return group ? "group:" + label : path.toAbsolutePath().toString();
        }
    }

    public FileExplorerManager(StudioContext ctx) {
        this.config = ctx.config();
        this.codeEditorService = ctx.codeEditorService();
        this.state = ctx.state();
        this.activityService = ctx.activityService();
        this.fileTree = new TreeView<>();

        // Manage Activities / New Activity write new files; without this the tree wouldn't show them until
        // some unrelated refresh happened to run.
        EventBus eventBus = ctx.eventBus();
        if (eventBus != null) {
            eventBus.subscribe(CoreApplicationEvents.ActivitiesChangedEvent.class,
                    e -> Platform.runLater(this::refreshTree), false);
        }
    }

    public VBox createView() {
        VBox container = new VBox();
        container.getStyleClass().add("file-explorer");

        Label header = new Label("Project Files");
        header.getStyleClass().add("sidebar-header");
        header.setMaxWidth(Double.MAX_VALUE);

        Button newActivityBtn = new Button("New Activity");
        newActivityBtn.getStyleClass().add("sidebar-button");
        newActivityBtn.setMaxWidth(Double.MAX_VALUE);
        newActivityBtn.setOnAction(e -> showCreateActivityDialog());

        configureTree();
        refreshTree();

        // The tree fills all remaining space (both axes) so the panel never shows dead area below it.
        fileTree.getStyleClass().add("file-tree");
        fileTree.setMaxWidth(Double.MAX_VALUE);
        fileTree.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(fileTree, javafx.scene.layout.Priority.ALWAYS);

        container.getChildren().addAll(header, newActivityBtn, fileTree);
        container.setFillWidth(true);
        return container;
    }

    private void configureTree() {
        fileTree.setShowRoot(false);

        fileTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(ExplorerNode item, boolean empty) {
                super.updateItem(item, empty);
                // Reset cross-cutting state every render (cells are recycled).
                getStyleClass().removeAll("tree-dir", "tree-lib", "tree-active", "tree-group", "tree-generated");
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }

                if (item.group()) {
                    setText(item.label());
                    setGraphic(null);
                    getStyleClass().add("tree-group");
                    setContextMenu(null);
                    return;
                }

                setText(item.label());
                if (item.isDirectory()) {
                    setGraphic(icon("📁"));
                    getStyleClass().add("tree-dir");
                    setContextMenu(null);
                    return;
                }

                setGraphic(icon(glyphFor(item.role())));
                setContextMenu(null);
                if (item.role() == FileRole.LIBRARY) getStyleClass().add("tree-lib");

                boolean active = state.getActiveFile() != null
                        && item.path().equals(state.getActiveFile().getPath());
                if (active) getStyleClass().add("tree-active");

            }

            private Label icon(String glyph) {
                Label l = new Label(glyph);
                l.getStyleClass().add("tree-icon");
                return l;
            }
        });

        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.getValue() == null) return;
            ExplorerNode node = newVal.getValue();
            if (node.group() || node.path() == null) return;
            if (!Files.isRegularFile(node.path())) return;
            if (state.getActiveFile() == null || !state.getActiveFile().getPath().equals(node.path())) {
                codeEditorService.switchToFile(node.path());
                fileTree.refresh();
            }
        });
    }

    private static String glyphFor(FileRole role) {
        return switch (role) {
            case LIBRARY -> "📦";
            case EDITABLE -> "📄";
        };
    }

    // The tree has no context menu at all any more. It carried one entry, "Delete File", removed back when
    // deleting a file could leave generated code pointing at a class that was gone. Nothing is generated now,
    // so the reason has expired rather than the entry being restored — deleting a file is what a file manager
    // and the IDE the user already has are for, and an explorer that can delete but not create, rename or move
    // is the worst of both.

    // ------------------------------------------------------------------
    // tree construction
    // ------------------------------------------------------------------

    public void refreshTree() {
        Set<String> expandedState = saveExpansionState();

        Path javaRoot = config.projectPath().resolve("src").resolve("main").resolve("java");
        if (!Files.exists(javaRoot)) javaRoot = config.mainSourceFile().getParent();

        TreeItem<ExplorerNode> root = new TreeItem<>(ExplorerNode.group("root"));
        root.setExpanded(true);

        TreeItem<ExplorerNode> userGroup = new TreeItem<>(ExplorerNode.group(GROUP_USER));
        userGroup.setExpanded(true);
        TreeItem<ExplorerNode> libraryGroup = new TreeItem<>(ExplorerNode.group(GROUP_LIBRARY));

        // Two groups, and since 2026-08-29 the second one is bundled library source rather than "Generated by
        // BotMaker" — nothing generates a project's Java, so the group that used to hold it would be empty in
        // every project, and every file the walk finds is the user's unless it is library code.
        List<Path> editable = new ArrayList<>();
        collectByRole(javaRoot, FileRole.EDITABLE, editable);
        editable.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path p : editable) {
            userGroup.getChildren().add(new TreeItem<>(ExplorerNode.of(p, FileRole.EDITABLE)));
        }

        List<Path> library = new ArrayList<>();
        collectByRole(javaRoot, FileRole.LIBRARY, library);
        library.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path p : library) {
            libraryGroup.getChildren().add(new TreeItem<>(ExplorerNode.of(p, FileRole.LIBRARY)));
        }

        root.getChildren().add(userGroup);
        if (!libraryGroup.getChildren().isEmpty()) root.getChildren().add(libraryGroup);

        restoreExpansionState(root, expandedState);
        fileTree.setRoot(root);
    }

    /**
     * Collects every file under {@code dir} whose role is {@code role}. Recursive, and it never yields a
     * directory: both groups are flat lists, so directories exist only as something to walk through.
     */
    private void collectByRole(Path dir, FileRole role, List<Path> out) {
        // A project whose sources aren't on disk yet is an empty tree, not an error: refreshTree() falls back to
        // the main source file's parent precisely when src/main/java is missing, and that parent is missing too.
        // Without this the walk threw NoSuchFileException on every refresh and printed a stack trace for it.
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.toList()) {
                if (Files.isDirectory(path)) {
                    collectByRole(path, role, out);
                    continue;
                }
                // Every file the walk finds is listed. Some used to be hidden outright — the flow drawn as
                // Java, the image library drawn as Java — on the grounds that every line in them answered to
                // a dialog. Nothing is drawn as Java now, so nothing is hidden from its owner.
                if (FileRole.of(path) == role) out.add(path);
            }
        } catch (IOException unreadable) {
            // Past the guard above this is a real I/O fault (permissions, a vanishing dir mid-walk), not the
            // routine "not created yet" — worth a line in the log, still not worth failing the tree over.
            LOGGER.log(Level.WARNING, "could not list " + dir + " while building the file tree", unreadable);
        }
    }

    private Set<String> saveExpansionState() {
        Set<String> expanded = new HashSet<>();
        if (fileTree.getRoot() != null) saveExpansionStateRecursive(fileTree.getRoot(), expanded);
        return expanded;
    }

    private void saveExpansionStateRecursive(TreeItem<ExplorerNode> item, Set<String> expanded) {
        if (item.isExpanded() && item.getValue() != null) expanded.add(item.getValue().key());
        for (TreeItem<ExplorerNode> child : item.getChildren()) saveExpansionStateRecursive(child, expanded);
    }

    private void restoreExpansionState(TreeItem<ExplorerNode> item, Set<String> expanded) {
        if (item.getValue() != null && expanded.contains(item.getValue().key())) item.setExpanded(true);
        for (TreeItem<ExplorerNode> child : item.getChildren()) restoreExpansionState(child, expanded);
    }

    // expandPathTo(...) lived here: it walked the package tree open down to the active file. Both groups are
    // now one level deep and the user group starts expanded, so there is no branch left to reveal.

    // ------------------------------------------------------------------
    // New Activity
    // ------------------------------------------------------------------

    /**
     * Creates a new activity. Delegates to {@link ActivityService#update} — the same path Manage Activities
     * drives — so the registry is regenerated and the {@code Activity} subclass stub is created for us.
     * (This replaces the old "New Function Library", which wrote a bare {@code static void action()} class
     * into the main package and registered nothing.)
     */
    private void showCreateActivityDialog() {
        TextInputDialog dialog = new TextInputDialog();
        ThemedWindows.apply(dialog);
        dialog.setTitle("New Activity");
        dialog.setHeaderText("Create a new activity");
        dialog.setContentText("Name (e.g. Mining):");
        Optional<String> result = dialog.showAndWait();

        result.ifPresent(name -> {
            String className = sanitizeActivityName(name);
            if (className.isEmpty()) return;

            ActivitiesConfig current = activityService.current();
            boolean exists = current.activities().stream()
                    .anyMatch(a -> a.name().equalsIgnoreCase(className));
            if (exists) {
                Alert alert = ThemedWindows.alert(Alert.AlertType.WARNING);
                alert.setTitle("Activity exists");
                alert.setHeaderText("There is already an activity called " + className + ".");
                alert.showAndWait();
                return;
            }

            List<ActivityDefinition> updated = new ArrayList<>(current.activities());
            updated.add(ActivityDefinition.create(className, "").withEnabled(true));
            // withActivities, never a fresh ActivitiesConfig: rebuilding one from two fields is how this
            // path used to drop the flow, the presets and every variable on the way to adding an activity.
            activityService.update(current.withActivities(updated))
                    .thenRun(() -> Platform.runLater(() -> {
                        refreshTree();
                        Path stub = config.activitiesPackageDir().resolve(className + ".java");
                        if (Files.exists(stub)) codeEditorService.switchToFile(stub);
                    }));
        });
    }

    /**
     * An activity name becomes a Java class name and a field on the generated {@code Activities} class, so it
     * must be a valid identifier: strip non-alphanumerics, then drop any leading digits.
     */
    static String sanitizeActivityName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replaceAll("[^a-zA-Z0-9]", "");
        return cleaned.replaceFirst("^[0-9]+", "");
    }
}
