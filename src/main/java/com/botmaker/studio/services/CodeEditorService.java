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
import com.botmaker.studio.parser.SlotVacancy;
import com.botmaker.studio.parser.StatementPlacement;
import com.botmaker.studio.parser.helpers.BlockNodes;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.state.HistoryManager;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.types.SlotFit;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SdkDocs;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.dnd.DropInfo;
import com.botmaker.studio.ui.dnd.ExpressionDropInfo;
import com.botmaker.studio.ui.dnd.MoveBlockInfo;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final SdkSurfaceService sdkSurfaceService;

    /** Cache of the last rendered block-tree root, exposed via {@link #getRootBlock()} for the overlay editor. */
    private AbstractCodeBlock lastRootBlock;

    public CodeEditorService(
            ProjectConfig config,
            ProjectState state,
            EventBus eventBus,
            BlockConverter blockConverter,
            BlockDragAndDropManager dragAndDropManager,
            DiagnosticsManager diagnosticsManager, ProjectAnalyzer projectAnalyzer,
            SdkDocsService sdkDocsService,
            SdkSurfaceService sdkSurfaceService) {
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
        this.blockConverter = blockConverter;
        this.dragAndDropManager = dragAndDropManager;
        this.diagnosticsManager = diagnosticsManager;
        this.projectAnalyzer = projectAnalyzer;
        this.sdkDocsService = sdkDocsService;
        this.sdkSurfaceService = sdkSurfaceService;
        this.historyManager = new HistoryManager(this::restoreFiles);
        this.codeEditor = new CodeEditor(config, state, eventBus, projectAnalyzer, sdkSurfaceService);
        setupEventHandlers();
    }


    public ProjectConfig getConfig() { return config; }
    public ProjectAnalyzer getProjectAnalyzer() { return projectAnalyzer; }

    /** Current compile diagnostics — the overlay marks broken rows from these, as the main editor does. */
    public DiagnosticsManager getDiagnosticsManager() { return diagnosticsManager; }

    /** SDK method documentation (summaries + param docs), parsed from the resolved SDK sources jar.
     *  {@link SdkDocs#EMPTY} while loading or when no docs service is wired (headless tests). */
    public SdkDocs getSdkDocs() { return sdkDocsService == null ? SdkDocs.EMPTY : sdkDocsService.current(); }

    // -----------------------------------------------------------------------------------------------------
    // This project's SDK surface. Nullable (headless tests wire no such service), so the three questions the
    // blocks actually ask are answered here rather than at ~10 call sites that would each have to remember
    // which way "unknown" falls. Presence answers optimistically, deprecation answers negatively — a probe
    // that isn't there must never hide a block, and must never strike one through either.
    // -----------------------------------------------------------------------------------------------------

    /** This project's SDK surface, or {@code null} when none is wired. Prefer the three helpers below. */
    public SdkSurfaceService getSdkSurface() { return sdkSurfaceService; }

    /** The menu facades this project's SDK actually has — {@link SdkType#MENU_FACADES} when unknown. */
    public List<SdkType> sdkMenuFacades() {
        return sdkSurfaceService == null ? SdkType.MENU_FACADES : sdkSurfaceService.menuFacades();
    }

    /** The facade class names this project's SDK actually has — {@link SdkType#FACADE_NAMES} when unknown. */
    public List<String> sdkFacadeNames() {
        return sdkSurfaceService == null ? SdkType.FACADE_NAMES : sdkSurfaceService.facadeNames();
    }

    /** True when {@code className.member} is {@code @Deprecated} in this project's SDK. False when unknown. */
    public boolean isSdkMemberDeprecated(String className, String member) {
        return sdkSurfaceService != null && sdkSurfaceService.isMemberDeprecated(className, member);
    }

    private void setupEventHandlers() {
        // Both of the code-refresh subscriptions below adopt the new text into ProjectState *now* and defer only
        // the block rebuild — see adopt(). The deferral is what keeps a burst of writes from re-rendering the
        // canvas on the publishing call; it was never meant to hold back what the file says.
        eventBus.subscribe(CoreApplicationEvents.UIRefreshRequestedEvent.class, event -> {
            CompilationUnit parsed = adopt(event.code());
            Platform.runLater(() -> render(parsed, event.code(), false));
        }, false);

        eventBus.subscribe(CoreApplicationEvents.BreakpointToggledEvent.class,
                this::handleBreakpointToggle, false);

        // ActivityService rewrites Activities.java / ActivityRegistry.java on disk behind our back; forget the
        // cached copies so the next open reads the regenerated source instead of a stale snapshot.
        eventBus.subscribe(CoreApplicationEvents.ActivitiesChangedEvent.class, event -> {
            evictGeneratedActivityFiles();
            // update() publishes from its background thread; re-rendering the open file is FX-thread work.
            Platform.runLater(this::reloadActivityStubs);
        }, false);

        eventBus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, event -> {
            handleCodeUpdateForHistory(event);
            CompilationUnit parsed = adopt(event.newCode());
            Platform.runLater(() -> render(parsed, event.newCode(), event.markNewIdentifiersAsUnedited()));
        }, false);

        eventBus.subscribe(CoreApplicationEvents.UndoRequestedEvent.class,
                e -> undo(), false);

        eventBus.subscribe(CoreApplicationEvents.RedoRequestedEvent.class,
                e -> redo(), false);
        eventBus.subscribe(CoreApplicationEvents.CopyRequestedEvent.class, e -> copySelectedBlock(), true);
        eventBus.subscribe(CoreApplicationEvents.PasteRequestedEvent.class, e -> pasteFromClipboard(), true);

        eventBus.subscribe(CoreApplicationEvents.BlockDropRequestedEvent.class, e -> handleBlockDrop(e.info()), false);
        eventBus.subscribe(CoreApplicationEvents.BlockMoveRequestedEvent.class, e -> handleBlockMove(e.info()), false);
        eventBus.subscribe(CoreApplicationEvents.ExpressionDropRequestedEvent.class, e -> handleExpressionDrop(e.info()), false);

        // Debug/trace Follow: DebuggingService publishes BlockHighlightEvent as the program advances (and to
        // clear on stop). Apply it to ProjectState so the executing/paused block is highlighted live.
        eventBus.subscribe(CoreApplicationEvents.BlockHighlightEvent.class, event -> {
            if (event.block() != null) state.setHighlightedBlock(event.block());
            else state.clearHighlight();
        }, true);
    }

    /** Resolves a palette drop into the matching CodeEditor "add" call. */
    private void handleBlockDrop(DropInfo info) {
        BlockType type = info.type();
        if (info.targetBody() != null && type.isStatement()) {
            StatementPlacement.Jump jump = StatementPlacement.jumpOf(type);
            if (!StatementPlacement.allows(jump, info.targetBody().getAstNode())) {
                rejectJumpPlacement(jump);
                return;
            }
            codeEditor.addStatement(info.targetBody(), type, info.insertionIndex());
        } else if (info.targetClass() != null && type.isClassMember()) {
            TypeDeclaration typeDecl = (TypeDeclaration) info.targetClass().getAstNode();
            switch (type) {
                // No dialog on this path — a drop has nowhere to ask — so the name is made free rather than
                // refused. See FunctionDraft.freeName; the ClassBlock button does open the dialog.
                case BlockType.MethodMember ignored ->
                        codeEditor.addMethodToClass(typeDecl,
                                FunctionDraft.freeName("newMethod", declaredMethodNames(typeDecl)),
                                "void", info.insertionIndex());
                case BlockType.EnumDecl ignored ->
                        codeEditor.addEnumToClass(typeDecl, "NewEnum", info.insertionIndex());
                default -> { /* no other block type reports isClassMember() */ }
            }
        }
    }

    /** Every method name a class declares — including the ones the editor doesn't draw. */
    private static Set<String> declaredMethodNames(TypeDeclaration typeDecl) {
        Set<String> names = new HashSet<>();
        for (MethodDeclaration method : typeDecl.getMethods()) names.add(method.getName().getIdentifier());
        return names;
    }

    private void rejectJumpPlacement(StatementPlacement.Jump jump) {
        if (jump == null) return;
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(jump.rejectionMessage()));
    }

    /** Resolves an existing-block drop (by id) into the matching CodeEditor "move" call. */
    private void handleBlockMove(MoveBlockInfo info) {
        CodeBlock block = findBlockById(info.blockId());
        if (block == null) return;

        if (info.targetBody() != null) {
            if (info.expressionPayload()) {
                liftExpressionIntoBody(block, info);
            } else if (block instanceof StatementBlock stmt) {
                StatementPlacement.Jump jump = StatementPlacement.jumpOf(stmt.getAstNode());
                if (!StatementPlacement.allows(jump, info.targetBody().getAstNode())) {
                    rejectJumpPlacement(jump);
                    return;
                }
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

    /**
     * A value dragged out of its slot and dropped where a line goes: it becomes {@code value;} and its old slot
     * is vacated. The drag layer already refused the forms that cannot be a line — this is the second door, and
     * the one that speaks, since only here is the AST in hand to be sure.
     */
    private void liftExpressionIntoBody(CodeBlock block, MoveBlockInfo info) {
        if (!(block.getAstNode() instanceof Expression source)) {
            refuseDrop("That value isn't there any more — try the drag again.");
            return;
        }
        if (!SlotVacancy.canStandAlone(source)) {
            refuseDrop(SlotFit.NOT_A_STATEMENT);
            return;
        }
        codeEditor.moveExpressionToStatement(source, info.targetBody(), info.insertionIndex());
    }

    /**
     * Resolves a drop onto an expression slot into the matching {@code CodeEditor} call. The drag layer knows
     * only ids; the AST nodes they name live here, which is the same split {@link #handleBlockMove} makes.
     */
    private void handleExpressionDrop(ExpressionDropInfo info) {
        CodeBlock target = findBlockById(info.targetBlockId());
        if (target == null) {
            refuseDrop("That slot isn't there any more — try the drag again.");
            return;
        }
        if (info.emptySlot()) {
            handleEmptySlotDrop(target, info);
            return;
        }
        if (!(target.getAstNode() instanceof Expression slot)) {
            refuseDrop("That is a whole line, not a slot a value can go in.");
            return;
        }

        if (info.paletteType() != null) {
            codeEditor.fillSlotFromPalette(slot, info.paletteType());
            return;
        }
        if (info.sourceIsExpression()) {
            Expression source = droppedExpression(info.sourceBlockId(), slot);
            if (source != null) codeEditor.moveExpressionBetweenSlots(slot, source);
            return;
        }
        ExpressionStatement stmt = droppedStatement(info.sourceBlockId());
        if (stmt == null) {
            refuseDrop("Only a line that is a single value can go in a slot.");
            return;
        }
        // Dropping a statement into a slot inside itself would delete the statement and leave the slot
        // referring to a node that no longer exists. Nothing upstream can see this — drag-over has ids, not
        // ancestry — so the refusal has to be here.
        if (encloses(stmt, slot)) {
            refuseDrop("That line is where the slot lives — moving it in would delete both.");
            return;
        }
        String refusal = SlotFit.refusal(
                ProjectAnalyzer.inferExpectedType(slot), projectAnalyzer.valueTypeOf(stmt.getExpression()));
        if (refusal != null) {
            refuseDrop(refusal);
            return;
        }
        codeEditor.moveExpressionIntoSlot(slot, stmt);
    }

    /**
     * The statement a dragged block id names, or null when the id names something that is not a line with one
     * value on it. {@link BlockNodes#expressionStatementOf} does the normalising: a call line is drawn by two
     * different block classes holding two different nodes, and asking {@code instanceof ExpressionStatement}
     * here meant half of them silently fell out of the drop path.
     */
    /**
     * The value a slot-to-slot drag names, checked against the slot it is landing in, or null with the reason
     * already said. Everything here is a refusal the drag layer could not make: it holds an id, and ancestry
     * and the authoritative type both need the AST.
     *
     * @param target the slot being filled — an {@link Expression} when it holds a value, otherwise the
     *               statement around a hole, which has no declared type of its own to check against
     */
    private Expression droppedExpression(String sourceBlockId, ASTNode target) {
        CodeBlock source = findBlockById(sourceBlockId);
        if (source == null || !(source.getAstNode() instanceof Expression value)) {
            refuseDrop("That value isn't there any more — try the drag again.");
            return null;
        }
        // Either direction is fatal: dropping a value inside itself deletes it, and dropping it onto a slot it
        // contains leaves the surviving node pointing into a tree that no longer exists.
        if (encloses(value, target) || encloses(target, value)) {
            refuseDrop("That value is where the slot lives — moving it in would delete both.");
            return null;
        }
        ResolvedType expected = target instanceof Expression slot
                ? ProjectAnalyzer.inferExpectedType(slot) : ResolvedType.UNKNOWN;
        String refusal = SlotFit.refusal(expected, projectAnalyzer.valueTypeOf(value));
        if (refusal != null) {
            refuseDrop(refusal);
            return null;
        }
        return value;
    }

    private ExpressionStatement droppedStatement(String sourceBlockId) {
        CodeBlock source = findBlockById(sourceBlockId);
        return source == null ? null : BlockNodes.expressionStatementOf(source.getAstNode());
    }

    /**
     * Says why a drop did not happen. Every early return on this path used to be a bare {@code return}: the
     * block sprang back, nothing changed, and nothing was said — the single most reported "the editor is
     * broken" symptom, because a refusal and a bug look identical when neither speaks.
     */
    private void refuseDrop(String reason) {
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(reason));
    }

    /**
     * A drop onto a slot that holds nothing. {@code target} is the statement around the hole, not an
     * expression, so the placement is {@code CodeEditor}'s to work out from its shape — an initialiser, an
     * argument — and this only has to refuse the one case ids cannot see: dropping a statement into its own
     * slot, which would consume the statement the slot lives in.
     */
    private void handleEmptySlotDrop(CodeBlock target, ExpressionDropInfo info) {
        ASTNode node = target.getAstNode();
        if (node == null) {
            refuseDrop("That slot isn't there any more — try the drag again.");
            return;
        }
        // The placement rules are written against the statement around the hole, and a call line's block may
        // hold the invocation instead — the same normalising the filled-slot path needs.
        ExpressionStatement asStatement = BlockNodes.expressionStatementOf(node);
        ASTNode owner = asStatement != null ? asStatement : node;
        if (info.paletteType() != null) {
            codeEditor.fillEmptySlotFromPalette(owner, info.paletteType());
            return;
        }
        if (info.sourceIsExpression()) {
            Expression source = droppedExpression(info.sourceBlockId(), owner);
            if (source != null) codeEditor.fillEmptySlotFromExpression(owner, source);
            return;
        }
        ExpressionStatement stmt = droppedStatement(info.sourceBlockId());
        if (stmt == null) {
            refuseDrop("Only a line that is a single value can go in a slot.");
            return;
        }
        if (stmt == owner || encloses(stmt, owner)) {
            refuseDrop("That line is where the slot lives — moving it in would delete both.");
            return;
        }
        // An empty slot has no declared type of its own to check against — the statement around it does, and
        // that is CodeEditor's placement rule to apply. The one answer that holds regardless is that a line
        // producing nothing is not a value, which UNKNOWN still refuses.
        String refusal = SlotFit.refusal(
                ResolvedType.UNKNOWN, projectAnalyzer.valueTypeOf(stmt.getExpression()));
        if (refusal != null) {
            refuseDrop(refusal);
            return;
        }
        codeEditor.fillEmptySlot(owner, stmt);
    }

    /** Whether {@code ancestor} contains {@code node} — including being it. */
    private static boolean encloses(ASTNode ancestor, ASTNode node) {
        for (ASTNode cur = node; cur != null; cur = cur.getParent()) {
            if (cur == ancestor) return true;
        }
        return false;
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

    /**
     * Turns one write into one history step: the active file, plus every other file the same edit rewrote.
     *
     * <p>The second half is why this is a map rather than a string. A signature migration writes the
     * declaration here and the calls in the files that use it, and it is one change to the user — so it has to
     * be one ↶.
     */
    private void handleCodeUpdateForHistory(CoreApplicationEvents.CodeUpdatedEvent event) {
        Map<Path, String> before = new LinkedHashMap<>();
        Map<Path, String> after = new LinkedHashMap<>();

        Path active = activePath();
        String previousCode = event.previousCode();
        if (active != null && previousCode != null && !previousCode.isEmpty()) {
            before.put(active, previousCode);
            after.put(active, event.newCode());
        }
        for (CoreApplicationEvents.FileEdit edit : event.alsoChanged()) {
            before.put(edit.path(), edit.previousContent());
            after.put(edit.path(), edit.newContent());
        }

        historyManager.record(event.label(), before, after);
        broadcastHistoryState();
    }

    private void undo() {
        if (!historyManager.canUndo()) return;
        historyManager.undo();
        broadcastHistoryState();
    }

    private void redo() {
        if (!historyManager.canRedo()) return;
        historyManager.redo();
        broadcastHistoryState();
    }

    /**
     * Puts one step's files back: the ones nobody is looking at straight to disk, the open one through the
     * refresh.
     *
     * <p>Restoring without recording is the point of {@code UIRefreshRequestedEvent} — it updates
     * {@link ProjectState} and the canvas, and publishes no {@code CodeUpdatedEvent}, so an undo does not
     * become a step of its own.
     *
     * <p>The other files are written to disk here because that is where the change put them:
     * {@code CallMigrator.commit} wrote them immediately rather than leaving them for a save. Their cached AST
     * goes with the text, or every suggestion in Studio keeps reading the version that was just taken back.
     */
    private void restoreFiles(Map<Path, String> files) {
        Path active = activePath();
        for (Map.Entry<Path, String> each : files.entrySet()) {
            if (each.getKey().equals(active)) continue;
            writeBack(each.getKey(), each.getValue());
        }
        String activeText = active == null ? null : files.get(active);
        if (activeText != null) {
            eventBus.publish(new CoreApplicationEvents.UIRefreshRequestedEvent(activeText));
        }
    }

    private void writeBack(Path path, String text) {
        state.getFile(path).ifPresent(file -> {
            file.setContent(text);
            file.setAst(null);
        });
        try {
            Files.writeString(path, text);
        } catch (IOException e) {
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                    "Couldn't put " + path.getFileName() + " back: " + e.getMessage()));
        }
    }

    private Path activePath() {
        ProjectFile file = state.getActiveFile();
        return file == null ? null : file.getPath();
    }

    private void broadcastHistoryState() {
        eventBus.publish(new CoreApplicationEvents.HistoryStateChangedEvent(
                historyManager.canUndo(), historyManager.canRedo(),
                historyManager.undoLabel(), historyManager.redoLabel()));
    }

    private void handleBreakpointToggle(CoreApplicationEvents.BreakpointToggledEvent event) {
        if (event.enabled()) {
            state.addBreakpoint(event.block().getId());
        } else {
            state.removeBreakpoint(event.block().getId());
        }
    }

    /**
     * Project open, in one call: read every source file, then render the entry point. Split in two because the
     * halves belong on different threads — see {@link #readProjectSources()} and
     * {@link #openInitialFile(List)}. Kept for any caller that has no window to keep responsive.
     */
    public void loadInitialCode() {
        openInitialFile(readProjectSources());
    }

    /**
     * Reads every {@code .java} under the source root, <b>off the FX thread</b>: disk walk, {@code readString},
     * and nothing else — no {@link ProjectState}, no parse, no event.
     *
     * <p>It has to happen at open and cannot be made lazy: {@link ProjectAnalyzer} answers "what other classes
     * does this project have, and what can I call on them" by scanning {@code state.getAllFiles()}, so a file
     * nobody has opened yet still has to be in that collection or it silently vanishes from every suggestion
     * menu. What *is* lazy is the expensive half — a file is parsed and turned into blocks only when it becomes
     * the active file ({@link #switchToFile}), so this is a few hundred KB of text, not N compilations.
     *
     * @return the files read, for {@link #openInitialFile} to hand to the FX thread
     */
    public List<ProjectFile> readProjectSources() {
        Path mainFile = config.mainSourceFile();

        // The source root (src/main/java), walked from the main file upwards.
        Path sourceRoot = mainFile.getParent();
        while (sourceRoot != null && !sourceRoot.getFileName().toString().equals("java")) {
            sourceRoot = sourceRoot.getParent();
        }
        if (sourceRoot == null) sourceRoot = mainFile.getParent();

        return readSourcesUnder(sourceRoot);
    }

    /** The disk half of {@link #readProjectSources()}, with no project attached so it can be tested headlessly. */
    static List<ProjectFile> readSourcesUnder(Path sourceRoot) {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) return List.of();

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return new ProjectFile(path, Files.readString(path));
                        } catch (Exception e) {
                            System.err.println("Error loading file: " + path + " (" + e.getMessage() + ")");
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Adopts the files {@link #readProjectSources()} read and renders the entry point. <b>FX thread only</b> —
     * it writes {@link ProjectState} and publishes, and the parse of the main file it ends with is the one
     * genuinely slow step of project open.
     */
    public void openInitialFile(List<ProjectFile> sources) {
        try {
            for (ProjectFile file : sources) {
                boolean alreadyLoaded = state.getAllFiles().stream()
                        .anyMatch(f -> f.getPath().equals(file.getPath()));
                if (!alreadyLoaded) state.addFile(file);
            }
            switchToFile(config.mainSourceFile());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Makes {@code path} the active file and renders it.
     *
     * <p>{@code openFiles} is populated by {@link #openInitialFile(List)} at project open, but files appear on
     * disk afterwards too — {@code ActivityService} writes activity stubs, {@code ProjectRepair} restores deleted
     * scaffolding — and neither goes through this service. Such a file showed in the explorer (which reads the
     * real filesystem) but silently refused to open until the next restart re-walked the tree. So a miss here
     * means "not loaded yet", not "doesn't exist": load it from disk and carry on. Only a path that isn't a
     * readable file is an actual error.
     */
    public void switchToFile(Path path) {
        ProjectFile file = state.getAllFiles().stream()
                .filter(f -> f.getPath().equals(path))
                .findFirst().orElseGet(() -> loadFromDisk(path));

        if (file == null) return;

        state.setActiveFile(path);
        state.setDocUri(file.getUri());

        refreshUI(file.getContent(), false);

        // Only after the file is parsed and active: a case with no trailing break gets one, so the break the
        // switch block draws as fixed chrome is always really there, and a bare arrow-rule body gets braces so
        // the branch is somewhere a block can actually be dropped. Skipped for files the user can't edit
        // anyway (generated scaffolding), and a no-op when both are already true.
        if (!LockResolver.forActiveFile(config, state).suppressesInteraction()) {
            codeEditor.normalizeSwitches();
        }
    }

    /**
     * Reads {@code path} into {@code openFiles} and returns it, or {@code null} when it isn't a readable file.
     * The lazy counterpart to {@link #readProjectSources}, for files created after project open.
     */
    private ProjectFile loadFromDisk(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            System.err.println("File not found: " + path);
            return null;
        }
        try {
            ProjectFile loaded = new ProjectFile(path, Files.readString(path));
            state.addFile(loaded);
            return loaded;
        } catch (Exception e) {
            System.err.println("Error loading file: " + path + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * Drops the cached copies of the files {@code ActivityService} regenerates, so the next open re-reads them.
     * Without this the {@code ProjectFile} kept the content captured at project open while the real file was
     * rewritten underneath it, and the editor showed a stale {@code Activities}/{@code ActivityRegistry} —
     * missing exactly the activity that was just added — until Studio restarted.
     */
    private void evictGeneratedActivityFiles() {
        state.removeFile(config.activitiesSourceFile());
        state.removeFile(config.activityRegistrySourceFile());
    }

    /**
     * Re-reads the activity stubs {@code ActivityStubSync} may have just rewritten, and re-renders the open
     * one if it was among them.
     *
     * <p>Adding an outcome in the flow dialog puts a new constant in that activity's {@code Outcome} enum, on
     * disk. The editor's copy is a snapshot taken when the file was opened, and every block reads the AST of
     * that snapshot — so the {@code return} block's outcome picker kept offering yesterday's constants until
     * Studio was restarted, which read as "adding an outcome doesn't work".
     *
     * <p>Re-reading rather than {@link #evictGeneratedActivityFiles evicting}: a stub is ordinary user code,
     * and dropping it from {@code openFiles} would take it out of {@link ProjectAnalyzer}'s view of the
     * project until someone happened to open it again.
     */
    private void reloadActivityStubs() {
        Path dir = config.activitiesPackageDir();
        if (dir == null) return;
        Path active = state.getActiveFile() == null ? null : state.getActiveFile().getPath();
        boolean activeChanged = false;
        for (ProjectFile file : List.copyOf(state.getAllFiles())) {
            if (!file.getPath().startsWith(dir) || !Files.isRegularFile(file.getPath())) continue;
            try {
                String onDisk = Files.readString(file.getPath());
                if (onDisk.equals(file.getContent())) continue;
                file.setContent(onDisk);
                activeChanged |= file.getPath().equals(active);
            } catch (Exception e) {
                System.err.println("Error re-reading activity stub: " + file.getPath() + " (" + e.getMessage() + ")");
            }
        }
        if (activeChanged) switchToFile(active);
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

    // There is no deleteFile here any more, and that is deliberate rather than an omission. Its only caller was
    // the file explorer's context menu, and a file in a bot project is never just a file: an activity's stub is
    // half of a pair with generated code that names it, and everything else is scaffolding the build needs.
    // Removing an activity is ActivityService's job (it deletes the stub and stops generating the rest, both
    // in one save), and nothing else here should go.

    private void refreshUI(String javaCode, boolean markNewIdentifiersAsUnedited) {
        render(adopt(javaCode), javaCode, markNewIdentifiersAsUnedited);
    }

    /**
     * The half of a refresh that every other screen depends on: the new text and its parse, in
     * {@link ProjectState}, before anybody is told the write happened.
     *
     * <p>It used to be the first line of {@link #render}, which ran inside a {@code Platform.runLater} while
     * {@code CodeUpdatedEvent} was published synchronously. So a listener that reacted to a write by asking the
     * state what the file now said was answered with the file as it was <em>before</em> that write — the Edit
     * Variable screen showed the type picked one click ago, and any block resolving its node against the state
     * resolved it against a tree that no longer existed. Splitting the refresh puts the model in front of the
     * pixels: this runs on the publishing thread, and only the block rebuild is deferred.
     *
     * <p>Returns the parse so {@link #render} does not repeat it. Null when the source could not be parsed at
     * all, which {@code BlockConverter} then handles exactly as it did before.
     */
    private CompilationUnit adopt(String javaCode) {
        state.setCurrentCode(javaCode);
        try {
            CompilationUnit parsed = blockConverter.parse(javaCode);
            state.setCompilationUnit(parsed);
            return parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void render(CompilationUnit parsed, String javaCode, boolean markNewIdentifiersAsUnedited) {
        // The registry is rebuilt into a fresh map and published once, at the end. Clearing the live one and
        // refilling it in place is what let a background reader walk a half-built registry (bugs.md B10).
        Map<ASTNode, CodeBlock> rebuilt = new HashMap<>();
        state.clearNodeToBlockMap();

        if (diagnosticsManager != null) {
            diagnosticsManager.updateSource(rebuilt, javaCode);
        }

        // The file's default verdict — see project/LockResolver (the single source of these rules). It is only
        // a default: a per-method lock can override it either way while parsing — locking an activity's
        // isEnabled() inside the user's own file, or unlocking a SIGNATURE body inside a locked one.
        LockResolver resolver = LockResolver.forActiveFile(config, state);

        BlockConverter.ConvertResult result = blockConverter.convert(
                parsed,
                javaCode,
                rebuilt,
                dragAndDropManager,
                resolver.suppressesInteraction(),
                markNewIdentifiersAsUnedited
        );
        state.setNodeToBlockMap(rebuilt);
        AbstractCodeBlock rootBlock = result.root();
        this.lastRootBlock = rootBlock;

        for (CodeBlock block : state.getNodeToBlockMap().values()) {
            if (state.hasBreakpoint(block.getId())) {
                block.setBreakpoint(true);
            }
        }

        state.setCompilationUnit(result.cu());
        eventBus.publish(new CoreApplicationEvents.UIBlocksUpdatedEvent(rootBlock));

        if (state.getActiveFile() != null) {
            String fileName = state.getActiveFile().getPath().getFileName().toString();
            String badge = resolver.role().badge();
            if (badge != null) fileName += " [" + badge + "]";
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
    /** The most recently rendered block tree root (published via {@code UIBlocksUpdatedEvent}), or empty. */
    public Optional<CodeBlock> getRootBlock() { return Optional.ofNullable(lastRootBlock); }

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
