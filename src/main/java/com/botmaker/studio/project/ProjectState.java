package com.botmaker.studio.project;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.nio.file.Path;
import java.util.*;

/**
 * Mutable state for the currently open project.
 * Tracks open files, active file, AST mappings, and UI state.
 *
 * <p><b>This object is confined to the FX thread.</b> Every mutator here is called from an editor action, and
 * nothing synchronises them. A background reader must not call these getters: it takes a {@link #snapshot()}
 * on the FX thread <em>before</em> it starts and reads that instead. See {@code docs/refactor/bugs.md} B10 for
 * the two failures the alternative produced — a {@code ConcurrentModificationException} on the debug thread,
 * and the quiet one, a reader compiling the code from one revision against the classpath from the next.
 */
public class ProjectState {

    // --- File State ---
    private final Map<Path, ProjectFile> openFiles = new LinkedHashMap<>();
    private ProjectFile activeFile;

    // --- Build State ---
    private List<String> resolvedClasspath = new ArrayList<>();

    // --- Activities (global config variables) ---
    private ActivitiesConfig activities = ActivitiesConfig.empty();

    // --- Editor settings (capture targets, etc.) ---
    private StudioProjectSettings settings = StudioProjectSettings.empty();

    // --- The template this project was created from, resolved once at open (see BotProject.open). Drives
    //     FileRole/MethodLock, so it is read on every tree cell and block — resolved eagerly, never re-derived.
    private ProjectTemplate template;

    // --- AST Block Mapping (for active file). Replaced wholesale, never mutated in place: a rebuild fills a
    //     fresh map and publishes it here in one assignment, so a reader iterating the previous one is never
    //     structurally modified underneath. volatile because that assignment is what publishes it. ---
    private volatile Map<ASTNode, CodeBlock> nodeToBlockMap = Map.of();

    // --- UI State ---
    private CodeBlock highlightedBlock;
    private InsertionCursor insertionCursor;
    private boolean isDebugging;
    private final Set<String> breakpointIds = new HashSet<>();
    private final Set<String> collapsedMethods = new HashSet<>();
    private long docVersion = 1;

    // =========================================================================
    // SNAPSHOT — the only thing a background thread may read
    // =========================================================================

    /**
     * One file as it stood when the snapshot was taken. {@link ProjectFile} is mutable and the editor keeps
     * writing to it, so a background writer of the source tree needs the content copied out, not the object.
     */
    public record SourceFile(Path path, String content) {}

    /**
     * Everything a background reader of {@link ProjectState} needs, read as a single value.
     *
     * <p>The point is not that each field is immutable — several were already handed out as copies. It is that
     * they are read <em>together</em>, on the thread that also writes them, so they cannot describe two
     * different revisions of the project. A compile that reads the code before an edit and the classpath after
     * it is a bot built against a dependency set that never existed; breakpoints mapped against a
     * {@link CompilationUnit} from a different revision attach to lines the user did not choose. Neither
     * announces itself.
     */
    public record Snapshot(String code,
                           CompilationUnit compilationUnit,
                           Map<ASTNode, CodeBlock> nodeToBlockMap,
                           List<String> resolvedClasspath,
                           List<SourceFile> files,
                           ProjectTemplate template) {}

    /**
     * Takes a {@link Snapshot}. <b>Call this on the FX thread</b>, before handing work to a background thread —
     * that is what makes the result one revision rather than several, since the FX thread is also the only
     * writer.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                getCurrentCode(),
                getCompilationUnit().orElse(null),
                nodeToBlockMap,                       // already immutable and never mutated in place
                List.copyOf(resolvedClasspath),
                openFiles.values().stream()
                        .map(f -> new SourceFile(f.getPath(), f.getContent()))
                        .toList(),
                template);
    }

    // =========================================================================
    // FILE MANAGEMENT
    // =========================================================================

    public void addFile(ProjectFile file) {
        openFiles.put(file.getPath(), file);
    }

    public void removeFile(Path path) {
        openFiles.remove(path);
    }

    public void setActiveFile(Path path) {
        this.activeFile = openFiles.get(path);
    }

    public ProjectFile getActiveFile() {
        return activeFile;
    }

    public Collection<ProjectFile> getAllFiles() {
        return Collections.unmodifiableCollection(openFiles.values());
    }

    public Optional<ProjectFile> getFile(Path path) {
        return Optional.ofNullable(openFiles.get(path));
    }

    // =========================================================================
    // CODE ACCESSORS (delegates to active file)
    // =========================================================================

    public String getCurrentCode() {
        return activeFile != null ? activeFile.getContent() : "";
    }

    public void setCurrentCode(String code) {
        if (activeFile != null) activeFile.setContent(code);
    }

    public String getDocUri() {
        return activeFile != null ? activeFile.getUri() : "";
    }

    public void setDocUri(String docUri) {
        // No-op: URI is derived from active file path
    }

    public Optional<CompilationUnit> getCompilationUnit() {
        return activeFile != null ? Optional.ofNullable(activeFile.getAst()) : Optional.empty();
    }

    public void setCompilationUnit(CompilationUnit cu) {
        if (activeFile != null) activeFile.setAst(cu);
    }

    // =========================================================================
    // BUILD STATE
    // =========================================================================

    public List<String> getResolvedClasspath() {
        return Collections.unmodifiableList(resolvedClasspath);
    }

    public void setResolvedClasspath(List<String> classpath) {
        this.resolvedClasspath = classpath != null ? new ArrayList<>(classpath) : new ArrayList<>();
    }

    // =========================================================================
    // ACTIVITIES
    // =========================================================================

    public StudioProjectSettings getSettings() {
        return settings;
    }

    /**
     * The template this project was created from, or {@code null} for a legacy project whose template couldn't
     * be determined. Callers ({@link FileRole}, {@link MethodLock}) treat null as "not a game bot".
     */
    public ProjectTemplate getTemplate() {
        return template;
    }

    public void setTemplate(ProjectTemplate template) {
        this.template = template;
    }

    public void setSettings(StudioProjectSettings settings) {
        this.settings = settings != null ? settings : StudioProjectSettings.empty();
    }

    public ActivitiesConfig getActivities() {
        return activities;
    }

    public void setActivities(ActivitiesConfig activities) {
        this.activities = activities != null ? activities : ActivitiesConfig.empty();
    }

    // =========================================================================
    // AST BLOCK MAPPING
    // =========================================================================

    /**
     * The block registry, as an unmodifiable snapshot in its own right — the map returned here is never
     * mutated again, so it stays safe to hold and to iterate across an edit.
     */
    public Map<ASTNode, CodeBlock> getNodeToBlockMap() {
        return nodeToBlockMap;
    }

    /**
     * Publishes a freshly built registry. The caller fills its own map and hands it over complete; there is
     * deliberately no accessor that exposes the live one, because a half-filled registry is what a background
     * reader used to walk.
     */
    public void setNodeToBlockMap(Map<ASTNode, CodeBlock> map) {
        // unmodifiableMap over a copy rather than Map.copyOf: the values are nullable (an unmapped node) and
        // Map.copyOf rejects nulls.
        this.nodeToBlockMap = map != null ? Collections.unmodifiableMap(new HashMap<>(map)) : Map.of();
    }

    public void clearNodeToBlockMap() {
        this.nodeToBlockMap = Map.of();
    }

    public Optional<CodeBlock> getBlockForNode(ASTNode node) {
        return Optional.ofNullable(nodeToBlockMap.get(node));
    }

    // =========================================================================
    // UI STATE
    // =========================================================================

    public Optional<CodeBlock> getHighlightedBlock() {
        return Optional.ofNullable(highlightedBlock);
    }

    public void setHighlightedBlock(CodeBlock block) {
        if (this.highlightedBlock != null) this.highlightedBlock.unhighlight();
        this.highlightedBlock = block;
        if (this.highlightedBlock != null) this.highlightedBlock.highlight();
    }

    public void clearHighlight() {
        setHighlightedBlock(null);
    }

    /**
     * The overlay authoring caret (where the next block is inserted), or empty when no overlay session is
     * active. Distinct from {@link #getHighlightedBlock()} (a selected block) — see {@link InsertionCursor}.
     */
    public Optional<InsertionCursor> getInsertionCursor() {
        return Optional.ofNullable(insertionCursor);
    }

    public void setInsertionCursor(InsertionCursor cursor) {
        this.insertionCursor = cursor;
    }

    // =========================================================================
    // METHOD COLLAPSE STATE
    // =========================================================================

    public boolean isMethodCollapsed(String methodKey) {
        return collapsedMethods.contains(methodKey);
    }

    public void setMethodCollapsed(String methodKey, boolean collapsed) {
        if (collapsed) collapsedMethods.add(methodKey);
        else collapsedMethods.remove(methodKey);
    }

    // =========================================================================
    // DEBUGGING STATE
    // =========================================================================

    public boolean isDebugging() { return isDebugging; }
    public void setDebugging(boolean debugging) { this.isDebugging = debugging; }

    public Set<String> getBreakpointIds() { return Collections.unmodifiableSet(breakpointIds); }
    public void addBreakpoint(String blockId) { breakpointIds.add(blockId); }
    public void removeBreakpoint(String blockId) { breakpointIds.remove(blockId); }
    public boolean hasBreakpoint(String blockId) { return breakpointIds.contains(blockId); }

    // =========================================================================
    // VERSIONING
    // =========================================================================

    public long getDocVersion() { return docVersion; }
    public void setDocVersion(long version) { this.docVersion = version; }
    public void incrementDocVersion() { this.docVersion++; }

    // =========================================================================
    // SOURCE PATH (for AST parser environment)
    // =========================================================================

    private Path sourcePath;

    public Path getSourcePath() { return sourcePath; }
    public void setSourcePath(Path sourcePath) { this.sourcePath = sourcePath; }

    // =========================================================================
    // READER / EDITOR MODE
    // =========================================================================

    /** True when this project is open for reading only — every edit is refused (see {@link com.botmaker.studio.project.LockResolver}). */
    private boolean readerMode;

    public boolean isReaderMode() { return readerMode; }
    public void setReaderMode(boolean readerMode) { this.readerMode = readerMode; }
}
