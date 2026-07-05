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
 */
public class ProjectState {

    // --- File State ---
    private final Map<Path, ProjectFile> openFiles = new LinkedHashMap<>();
    private ProjectFile activeFile;

    // --- Build State ---
    private List<String> resolvedClasspath = new ArrayList<>();

    // --- Activities (global config variables) ---
    private ActivitiesConfig activities = ActivitiesConfig.empty();

    // --- AST Block Mapping (for active file) ---
    private Map<ASTNode, CodeBlock> nodeToBlockMap = new HashMap<>();

    // --- UI State ---
    private CodeBlock highlightedBlock;
    private boolean isDebugging;
    private final Set<String> breakpointIds = new HashSet<>();
    private final Set<String> collapsedMethods = new HashSet<>();
    private long docVersion = 1;

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

    public ActivitiesConfig getActivities() {
        return activities;
    }

    public void setActivities(ActivitiesConfig activities) {
        this.activities = activities != null ? activities : ActivitiesConfig.empty();
    }

    // =========================================================================
    // AST BLOCK MAPPING
    // =========================================================================

    public Map<ASTNode, CodeBlock> getNodeToBlockMap() {
        return Collections.unmodifiableMap(nodeToBlockMap);
    }

    public Map<ASTNode, CodeBlock> getMutableNodeToBlockMap() {
        return nodeToBlockMap;
    }

    public void setNodeToBlockMap(Map<ASTNode, CodeBlock> map) {
        this.nodeToBlockMap = map != null ? new HashMap<>(map) : new HashMap<>();
    }

    public void clearNodeToBlockMap() {
        this.nodeToBlockMap.clear();
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
}