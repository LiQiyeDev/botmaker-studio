package com.botmaker.studio.project;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.DebuggingService;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.validation.DiagnosticsManager;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Represents a single open project with all its services.
 * Replaces DependencyContainer with explicit, typed ownership.
 *
 * Lifecycle:
 * 1. Create via {@link #open(String, Path, boolean)}
 * 2. Use services via getters
 * 3. Call {@link #close()} when switching projects or exiting
 */
public class BotProject {

    // --- Configuration ---
    private final ProjectConfig config;

    // --- State ---
    private final ProjectState state;
    private final EventBus eventBus;

    // --- Services ---
    private final DiagnosticsManager diagnosticsManager;
    private final BlockDragAndDropManager dragAndDropManager;
    private final ProjectAnalyzer projectAnalyzer;
    private final LibraryService libraryService;
    private final ActivityService activityService;

    // --- Lazy-initialized services ---
    private CodeEditorService codeEditorService;
    private CodeExecutionService codeExecutionService;
    private DebuggingService debuggingService;
    private BlockConverter blockConverter;

    private BotProject(ProjectConfig config,
                       ProjectState state,
                       EventBus eventBus,
                       DiagnosticsManager diagnosticsManager,
                       BlockDragAndDropManager dragAndDropManager,
                       ProjectAnalyzer projectAnalyzer,
                       LibraryService libraryService,
                       ActivityService activityService) {
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
        this.diagnosticsManager = diagnosticsManager;
        this.dragAndDropManager = dragAndDropManager;
        this.projectAnalyzer = projectAnalyzer;
        this.libraryService = libraryService;
        this.activityService = activityService;
    }

    // =========================================================================
    // FACTORY
    // =========================================================================

    /**
     * Opens a project: resolves dependencies, builds the type index, creates all services.
     *
     * @param projectName Name of the project
     * @param projectsRoot Root directory containing all projects
     * @param enableEventLogging Whether to log events for debugging
     * @return Fully initialized BotProject
     */
    public static BotProject open(String projectName,
                                  Path projectsRoot,
                                  boolean enableEventLogging) {
        return open(projectName, projectsRoot, enableEventLogging, msg -> {});
    }

    /**
     * As {@link #open(String, Path, boolean)}, but reports coarse-grained progress via {@code progress}
     * (e.g. {@code "Resolving dependencies…"}, per-jar download messages, {@code "Loading project…"}).
     * The consumer may be invoked from background/worker threads.
     */
    public static BotProject open(String projectName,
                                  Path projectsRoot,
                                  boolean enableEventLogging,
                                  Consumer<String> progress) {
        // 1. Create config
        ProjectConfig config = ProjectConfig.forProject(projectName, projectsRoot);

        // 2. Create state
        ProjectState state = new ProjectState();
        state.setSourcePath(config.sourceRoot());

        // 3. Create event bus
        EventBus eventBus = new EventBus(enableEventLogging);

        // 4. Create infrastructure services
        DiagnosticsManager diagnosticsManager = new DiagnosticsManager();
        BlockDragAndDropManager dragAndDropManager = new BlockDragAndDropManager(eventBus);

        // 5. Resolve dependencies (Maven Resolver, reads pom.xml)
        progress.accept("Resolving dependencies…");
        List<String> classpath;
        try {
            classpath = MavenService.resolveClasspath(config.projectPath(), progress);
            state.setResolvedClasspath(classpath);
        } catch (Exception e) {
            System.err.println("Warning: Could not resolve classpath: " + e.getMessage());
            classpath = List.of();
        }

        // 6. Build or load the type index for external libraries
        progress.accept("Indexing libraries…");
        TypeSummaryManager typeSummaryManager = TypeSummaryManager.buildOrLoad(classpath);

        // 7. Create the unified ProjectAnalyzer
        ProjectAnalyzer projectAnalyzer = new ProjectAnalyzer(typeSummaryManager, state);

        // 7a. Warm the heavy derived caches off the UI thread so the first type/method menu opens without
        // lag (the static-utility scan + per-class ResolvedType build are otherwise paid lazily on the FX
        // thread at first menu open).
        Thread warmUp = new Thread(() -> {
            typeSummaryManager.warmCaches();
            projectAnalyzer.warmLibraryTypes();
        }, "type-index-warmup");
        warmUp.setDaemon(true);
        warmUp.start();

        // 7b. Library management (pom.xml is the source of truth)
        LibraryService libraryService = new LibraryService(config, state, typeSummaryManager, eventBus);

        // 7c. Activities (global config variables); load existing schema/values into state
        ActivityService activityService = new ActivityService(config, state, eventBus);
        activityService.load();

        // 8. Create code editing pipeline
        progress.accept("Loading project…");
        BlockConverter blockConverter = new BlockConverter(state);

        // 9. Assemble the project
        BotProject project = new BotProject(
                config, state, eventBus,
                diagnosticsManager, dragAndDropManager,
                projectAnalyzer, libraryService, activityService
        );
        project.blockConverter = blockConverter;

        // 10. Create services
        project.initializeServices(config, state, eventBus, diagnosticsManager,
                dragAndDropManager, blockConverter);

        return project;
    }

    private void initializeServices(ProjectConfig config,
                                    ProjectState state,
                                    EventBus eventBus,
                                    DiagnosticsManager diagnosticsManager,
                                    BlockDragAndDropManager dragAndDropManager,
                                    BlockConverter blockConverter) {

        // Code Editor
        this.codeEditorService = new CodeEditorService(
                config, state, eventBus, blockConverter,
                dragAndDropManager, diagnosticsManager, projectAnalyzer
        );

        // Execution (subscribes to compile/run/stop events in its constructor)
        CodeExecutionService codeExec = new CodeExecutionService(
                diagnosticsManager, config, state, eventBus
        );
        this.codeExecutionService = codeExec;

        this.debuggingService = new DebuggingService(
                state, eventBus, codeExec, config
        );
    }

    // =========================================================================
    // ACCESSORS
    // =========================================================================

    public ProjectConfig getConfig() { return config; }
    public ProjectState getState() { return state; }
    public EventBus getEventBus() { return eventBus; }
    public DiagnosticsManager getDiagnosticsManager() { return diagnosticsManager; }
    public BlockDragAndDropManager getDragAndDropManager() { return dragAndDropManager; }
    public ProjectAnalyzer getProjectAnalyzer() { return projectAnalyzer; }
    public LibraryService getLibraryService() { return libraryService; }
    public ActivityService getActivityService() { return activityService; }
    public CodeEditorService getCodeEditorService() { return codeEditorService; }
    public CodeExecutionService getCodeExecutionService() { return codeExecutionService; }
    public DebuggingService getDebuggingService() { return debuggingService; }
    public BlockConverter getBlockConverter() { return blockConverter; }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    public void close() {
        // Clean shutdown of any running processes, etc.
    }
}