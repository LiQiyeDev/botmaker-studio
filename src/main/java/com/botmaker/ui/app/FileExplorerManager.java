package com.botmaker.ui.app;

import com.botmaker.project.ProjectConfig;
import com.botmaker.services.CodeEditorService;
import com.botmaker.project.ProjectState;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class FileExplorerManager {

    private final ProjectConfig config;
    private final CodeEditorService codeEditorService;
    private final ProjectState state;
    private final TreeView<Path> fileTree;

    public FileExplorerManager(ProjectConfig config, CodeEditorService codeEditorService, ProjectState state) {
        this.config = config;
        this.codeEditorService = codeEditorService;
        this.state = state;
        this.fileTree = new TreeView<>();
    }

    public VBox createView() {
        VBox container = new VBox();
        container.getStyleClass().add("file-explorer");

        Label header = new Label("Project Files");
        header.getStyleClass().add("sidebar-header");

        Button newFileBtn = new Button("New Function Library");
        newFileBtn.setMaxWidth(Double.MAX_VALUE);
        newFileBtn.setOnAction(e -> showCreateFileDialog());

        configureTree();
        refreshTree();

        container.getChildren().addAll(header, newFileBtn, fileTree);
        return container;
    }

    private void configureTree() {
        fileTree.setShowRoot(false);

        fileTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    setContextMenu(null);
                } else {
                    String fileName = item.getFileName().toString();
                    boolean isDirectory = Files.isDirectory(item);

                    // Simple logic to show packages cleanly (optional)
                    setText(fileName);

                    String pathStr = item.toString().replace("\\", "/");
                    boolean isLibrary = pathStr.contains("com/botmaker/library");

                    if (isDirectory) {
                        setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
                    }
                    else if (isLibrary) {
                        setText(fileName + " [Lib]");
                        setStyle("-fx-text-fill: #888; -fx-font-style: italic;");
                    }
                    else {
                        if (state.getActiveFile() != null && item.equals(state.getActiveFile().getPath())) {
                            setStyle("-fx-font-weight: bold; -fx-text-fill: #007bff;");
                        } else {
                            setStyle("-fx-text-fill: black;");
                        }

                        // Context menu for delete...
                        ContextMenu cm = new ContextMenu();
                        MenuItem deleteItem = new MenuItem("Delete File");
                        deleteItem.setOnAction(e -> {codeEditorService.deleteFile(item); refreshTree(); });
                        cm.getItems().add(deleteItem);
                        setContextMenu(cm);
                    }
                }
            }
        });

        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                Path selectedPath = newVal.getValue();
                if (Files.isRegularFile(selectedPath)) {
                    if (state.getActiveFile() == null || !state.getActiveFile().getPath().equals(selectedPath)) {
                        codeEditorService.switchToFile(selectedPath);
                        fileTree.refresh();
                    }
                }
            }
        });
    }

    private Set<String> saveExpansionState() {
        Set<String> expanded = new HashSet<>();
        if (fileTree.getRoot() != null) saveExpansionStateRecursive(fileTree.getRoot(), expanded);
        return expanded;
    }

    private void saveExpansionStateRecursive(TreeItem<Path> item, Set<String> expanded) {
        if (item.isExpanded()) expanded.add(item.getValue().toAbsolutePath().toString());
        for (TreeItem<Path> child : item.getChildren()) saveExpansionStateRecursive(child, expanded);
    }

    private void restoreExpansionState(TreeItem<Path> item, Set<String> expanded) {
        if (expanded.contains(item.getValue().toAbsolutePath().toString())) item.setExpanded(true);
        for (TreeItem<Path> child : item.getChildren()) restoreExpansionState(child, expanded);
    }

    public void refreshTree() {
        Set<String> expandedState = saveExpansionState();

        // FIXED: Robust root finding
        // Start from src/main/java
        Path projectRoot = config.projectPath();
        Path javaRoot = projectRoot.resolve("src").resolve("main").resolve("java");

        if (!Files.exists(javaRoot)) {
            // Fallback to source file parent's parent...
            javaRoot = config.mainSourceFile().getParent();
        }

        TreeItem<Path> root = new TreeItem<>(javaRoot);
        root.setExpanded(true);

        buildFileTree(root, javaRoot);
        restoreExpansionState(root, expandedState);

        // Ensure the path to the active file is expanded by default if state is empty
        if (expandedState.isEmpty()) {
            Path target = (state.getActiveFile() != null)
                    ? state.getActiveFile().getPath()
                    : config.mainSourceFile().getParent(); // Default to package dir

            if (target != null) {
                expandPathTo(root, target);
            }
        }
        fileTree.setRoot(root);
    }

    // Helper to auto-expand to a specific file
    private boolean expandPathTo(TreeItem<Path> currentItem, Path target) {
        if (currentItem.getValue().equals(target)) return true;
        if (currentItem.getValue().toString().equals(target.toString())) return true; // Path equality check

        boolean found = false;
        // If directory, check if target starts with this directory
        if (target.startsWith(currentItem.getValue())) {
            currentItem.setExpanded(true);
            for(TreeItem<Path> child : currentItem.getChildren()) {
                if (expandPathTo(child, target)) {
                    found = true;
                }
            }
        }
        return found;
    }

    private void buildFileTree(TreeItem<Path> parentItem, Path parentPath) {
        Path activitiesFile = config.activitiesSourceFile().toAbsolutePath();
        try (Stream<Path> files = Files.list(parentPath)) {
            files.filter(p -> !p.toAbsolutePath().equals(activitiesFile)) // generated; managed via Manage Activities
                    .sorted((p1, p2) -> {
                boolean d1 = Files.isDirectory(p1);
                boolean d2 = Files.isDirectory(p2);
                if (d1 && !d2) return -1;
                if (!d1 && d2) return 1;
                return p1.getFileName().toString().compareTo(p2.getFileName().toString());
            }).forEach(path -> {
                TreeItem<Path> item = new TreeItem<>(path);
                parentItem.getChildren().add(item);
                if (Files.isDirectory(path)) {
                    buildFileTree(item, path);
                }
            });
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void showCreateFileDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Function Library");
        dialog.setHeaderText("Create a new library of functions");
        dialog.setContentText("Name (e.g. Movement):");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            String className = name.trim().replaceAll("[^a-zA-Z0-9]", "");
            if (!className.isEmpty()) {
                codeEditorService.createFile(className);
                refreshTree();
            }
        });
    }
}