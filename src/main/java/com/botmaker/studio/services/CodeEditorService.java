package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.parser.CodeEditor;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.state.HistoryManager;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.SdkDocs;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.dnd.DropInfo;
import com.botmaker.studio.ui.dnd.MoveBlockInfo;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

public class CodeEditorService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;
    private final BlockConverter blockConverter;
    private final CodeEditor codeEditor;
    private final BlockDragAndDropManager dragAndDropManager;
    private final DiagnosticsManager diagnosticsManager;
    private final HistoryManager historyManager;
    private final ProjectAnalyzer projectAnalyzer;
    private final SdkDocsService sdkDocsService;

    public CodeEditorService(
            ProjectConfig config,
            ProjectState state,
            EventBus eventBus,
            BlockConverter blockConverter,
            BlockDragAndDropManager dragAndDropManager,
            DiagnosticsManager diagnosticsManager, ProjectAnalyzer projectAnalyzer,
            SdkDocsService sdkDocsService) {
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
        this.blockConverter = blockConverter;
        this.dragAndDropManager = dragAndDropManager;
        this.diagnosticsManager = diagnosticsManager;
        this.projectAnalyzer = projectAnalyzer;
        this.sdkDocsService = sdkDocsService;
        this.historyManager = new HistoryManager();
        this.codeEditor = new CodeEditor(state, eventBus, projectAnalyzer);
        setupEventHandlers();
    }


    public ProjectConfig getConfig() { return config; }
    public ProjectAnalyzer getProjectAnalyzer() { return projectAnalyzer; }

    /** SDK method documentation (summaries + param docs), parsed from the resolved SDK sources jar. */
    public SdkDocs getSdkDocs() { return sdkDocsService.current(); }

    private void setupEventHandlers() {
        eventBus.subscribe(CoreApplicationEvents.UIRefreshRequestedEvent.class, event ->
                Platform.runLater(() -> refreshUI(event.code(), false)), false);

        eventBus.subscribe(CoreApplicationEvents.BreakpointToggledEvent.class,
                this::handleBreakpointToggle, false);

        eventBus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, event -> {
            handleCodeUpdateForHistory(event);
            Platform.runLater(() -> refreshUI(event.newCode(), event.markNewIdentifiersAsUnedited()));
        }, false);

        eventBus.subscribe(CoreApplicationEvents.UndoRequestedEvent.class,
                e -> undo(), false);

        eventBus.subscribe(CoreApplicationEvents.RedoRequestedEvent.class,
                e -> redo(), false);
        eventBus.subscribe(CoreApplicationEvents.CopyRequestedEvent.class, e -> copySelectedBlock(), true);
        eventBus.subscribe(CoreApplicationEvents.PasteRequestedEvent.class, e -> pasteFromClipboard(), true);

        eventBus.subscribe(CoreApplicationEvents.BlockDropRequestedEvent.class, e -> handleBlockDrop(e.info()), false);
        eventBus.subscribe(CoreApplicationEvents.BlockMoveRequestedEvent.class, e -> handleBlockMove(e.info()), false);
    }

    /** Resolves a palette drop into the matching CodeEditor "add" call. */
    private void handleBlockDrop(DropInfo info) {
        BlockType type = info.type();
        if (info.targetBody() != null && type.isStatement()) {
            codeEditor.addStatement(info.targetBody(), type, info.insertionIndex());
        } else if (info.targetClass() != null && type.isClassMember()) {
            TypeDeclaration typeDecl = (TypeDeclaration) info.targetClass().getAstNode();
            switch (type) {
                case BlockType.MethodMember ignored ->
                        codeEditor.addMethodToClass(typeDecl, "newMethod", "void", info.insertionIndex());
                case BlockType.EnumDecl ignored ->
                        codeEditor.addEnumToClass(typeDecl, "NewEnum", info.insertionIndex());
                default -> { /* no other block type reports isClassMember() */ }
            }
        }
    }

    /** Resolves an existing-block drop (by id) into the matching CodeEditor "move" call. */
    private void handleBlockMove(MoveBlockInfo info) {
        CodeBlock block = findBlockById(info.blockId());
        if (block == null) return;

        if (info.targetBody() != null) {
            if (block instanceof StatementBlock stmt) {
                BodyBlock sourceBody = findParentBody(stmt, state.getNodeToBlockMap());
                if (sourceBody != null) {
                    codeEditor.moveStatement(stmt, sourceBody, info.targetBody(), info.insertionIndex());
                }
            }
        } else if (info.targetClass() != null) {
            if (block.getAstNode() instanceof BodyDeclaration decl) {
                codeEditor.moveBodyDeclaration(decl, (TypeDeclaration) info.targetClass().getAstNode(), info.insertionIndex());
            }
        }
    }

    private CodeBlock findBlockById(String id) {
        if (id == null) return null;
        for (CodeBlock block : state.getNodeToBlockMap().values()) {
            CodeBlock found = findBlockByIdIn(block, id);
            if (found != null) return found;
        }
        return null;
    }

    private static CodeBlock findBlockByIdIn(CodeBlock block, String id) {
        if (id.equals(block.getId())) return block;
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) {
                CodeBlock found = findBlockByIdIn(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void handleCodeUpdateForHistory(CoreApplicationEvents.CodeUpdatedEvent event) {
        String previousCode = event.previousCode();
        if (previousCode != null && !previousCode.isEmpty()) {
            historyManager.pushState(previousCode);
            broadcastHistoryState();
        }
    }

    private void undo() {
        if (!historyManager.canUndo()) return;
        applyHistoryState(historyManager.undo(state.getCurrentCode()));
    }

    private void redo() {
        if (!historyManager.canRedo()) return;
        applyHistoryState(historyManager.redo(state.getCurrentCode()));
    }

    private void applyHistoryState(String code) {
        // Restore without recording: UIRefreshRequestedEvent refreshes the UI (and state.currentCode)
        // but does NOT push onto the history stacks, unlike CodeUpdatedEvent.
        eventBus.publish(new CoreApplicationEvents.UIRefreshRequestedEvent(code));
        broadcastHistoryState();
    }

    private void broadcastHistoryState() {
        eventBus.publish(new CoreApplicationEvents.HistoryStateChangedEvent(historyManager.canUndo(), historyManager.canRedo()));
    }

    private void handleBreakpointToggle(CoreApplicationEvents.BreakpointToggledEvent event) {
        if (event.enabled()) {
            state.addBreakpoint(event.block().getId());
        } else {
            state.removeBreakpoint(event.block().getId());
        }
    }

    // --- FIX: LOAD ALL FILES INCLUDING LIBRARY FILES ---
    public void loadInitialCode() {
        try {
            Path mainFile = config.mainSourceFile();

            // Get the source root (src/main/java)
            Path sourceRoot = mainFile.getParent();
            while (sourceRoot != null && !sourceRoot.getFileName().toString().equals("java")) {
                sourceRoot = sourceRoot.getParent();
            }

            if (sourceRoot == null) {
                sourceRoot = mainFile.getParent();
            }

            // Load ALL java files recursively, including library files
            loadFilesRecursively(sourceRoot);

            // Set Active File to Main and refresh UI
            switchToFile(mainFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively loads all .java files in the directory tree
     */
    private void loadFilesRecursively(Path directory) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            // Check if already loaded
                            boolean alreadyLoaded = state.getAllFiles().stream()
                                    .anyMatch(f -> f.getPath().equals(path));

                            if (!alreadyLoaded) {
                                String content = Files.readString(path);
                                ProjectFile pf = new ProjectFile(path, content);
                                state.addFile(pf);
                            }
                        } catch (Exception e) {
                            System.err.println("Error loading file: " + path);
                            e.printStackTrace();
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void switchToFile(Path path) {
        ProjectFile file = state.getAllFiles().stream()
                .filter(f -> f.getPath().equals(path))
                .findFirst().orElse(null);

        if (file == null) {
            System.err.println("File not found in state: " + path);
            return;
        }

        state.setActiveFile(path);
        state.setDocUri(file.getUri());

        refreshUI(file.getContent(), false);
    }

    public void createFile(String className) {
        try {
            String packageName = config.mainClassName().substring(0, config.mainClassName().lastIndexOf('.'));
            Path dir = config.mainSourceFile().getParent();
            Path newPath = dir.resolve(className + ".java");

            String template = "package " + packageName + ";\n\n" +
                    "public class " + className + " {\n" +
                    "    // Add functions here\n" +
                    "    public static void action() {\n" +
                    "        System.out.println(\"Action from " + className + "\");\n" +
                    "    }\n" +
                    "}";

            Files.writeString(newPath, template);
            ProjectFile pf = new ProjectFile(newPath, template);
            state.addFile(pf);
            switchToFile(newPath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void deleteFile(Path path) {
        try {
            // 1. Delete from disk
            Files.deleteIfExists(path);

            // 2. Remove from state
            state.removeFile(path);

            // 3. Update UI if active file was deleted
            if (state.getActiveFile() != null && state.getActiveFile().getPath().equals(path)) {
                // Reload main file
                switchToFile(config.mainSourceFile());
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Deleted active file. Switched to Main."));
            } else {
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("File deleted."));
            }

        } catch (Exception e) {
            e.printStackTrace();
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Error deleting file: " + e.getMessage()));
        }
    }

    private void refreshUI(String javaCode, boolean markNewIdentifiersAsUnedited) {
        state.setCurrentCode(javaCode);
        state.clearNodeToBlockMap();

        if (diagnosticsManager != null) {
            diagnosticsManager.updateSource(state.getMutableNodeToBlockMap(), javaCode);
        }

        // Determine if file is Read-Only (a library, or the generated Activities sidecar).
        boolean isReadOnly = false;
        boolean isGenerated = false;
        if (state.getActiveFile() != null) {
            Path activeFile = state.getActiveFile().getPath();
            String path = activeFile.toString().replace("\\", "/");
            if (path.contains("com/botmaker/library")) {
                isReadOnly = true;
            } else if (activeFile.toAbsolutePath().equals(config.activitiesSourceFile().toAbsolutePath())) {
                isReadOnly = true;
                isGenerated = true;
            }
        }

        BlockConverter.ConvertResult result = blockConverter.convert(
                javaCode,
                state.getMutableNodeToBlockMap(),
                dragAndDropManager,
                isReadOnly,
                markNewIdentifiersAsUnedited
        );
        AbstractCodeBlock rootBlock = result.root();

        for (CodeBlock block : state.getNodeToBlockMap().values()) {
            if (state.hasBreakpoint(block.getId())) {
                block.setBreakpoint(true);
            }
        }

        state.setCompilationUnit(result.cu());
        eventBus.publish(new CoreApplicationEvents.UIBlocksUpdatedEvent(rootBlock));

        if (state.getActiveFile() != null) {
            String fileName = state.getActiveFile().getPath().getFileName().toString();
            // Add a read-only indicator for library / generated files
            if (isGenerated) {
                fileName += " [Generated - Read Only]";
            } else if (isReadOnly) {
                fileName += " [Library - Read Only]";
            }
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Loaded: " + fileName));
        }
    }
    private void copySelectedBlock() {
        state.getHighlightedBlock().ifPresent(block -> {
            ASTNode node = block.getAstNode();
            if (node != null) {
                String source = node.toString();
                ClipboardContent content = new ClipboardContent();
                content.putString(source);
                Clipboard.getSystemClipboard().setContent(content);
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Copied block to clipboard."));
            }
        });
    }

    private void pasteFromClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) return;

        String codeToPaste = clipboard.getString();

        // Determine insertion point based on highlighted block
        state.getHighlightedBlock().ifPresentOrElse(selectedBlock -> {
            if (selectedBlock instanceof StatementBlock) {
                StatementBlock stmtBlock = (StatementBlock) selectedBlock;
                BodyBlock parentBody = findParentBody(stmtBlock, state.getNodeToBlockMap());

                if (parentBody != null) {
                    int index = parentBody.getStatements().indexOf(stmtBlock);
                    // Paste AFTER the selected block
                    codeEditor.pasteCode(parentBody, index + 1, codeToPaste);
                }
            }
        }, () -> {
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Select a block to paste after."));
        });
    }
    public CodeEditor getCodeEditor() { return codeEditor; }
    public ProjectState getState() { return state; }
    public EventBus getEventBus() { return eventBus; }
    public BlockDragAndDropManager getDragAndDropManager() { return dragAndDropManager; }

    private static BodyBlock findParentBody(StatementBlock target, Map<?, CodeBlock> nodeToBlockMap) {
        if (target == null || nodeToBlockMap == null) return null;
        for (CodeBlock block : nodeToBlockMap.values()) {
            if (block instanceof BodyBlock bb && bb.getStatements().contains(target)) return bb;
            if (block instanceof BlockWithChildren bwc) {
                BodyBlock found = findParentBodyInChildren(target, bwc);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static BodyBlock findParentBodyInChildren(StatementBlock target, BlockWithChildren parent) {
        for (CodeBlock child : parent.getChildren()) {
            if (child instanceof BodyBlock bb && bb.getStatements().contains(target)) return bb;
            if (child instanceof BlockWithChildren bwc) {
                BodyBlock found = findParentBodyInChildren(target, bwc);
                if (found != null) return found;
            }
        }
        return null;
    }
}