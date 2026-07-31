package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.SdkDocsService;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.validation.DiagnosticsManager;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A real {@link CodeEditor} over a source string, wired the way {@code BotProject.open} wires one: parsed to
 * blocks by {@link BlockConverter}, with an {@link EventBus} that captures the {@code CodeUpdatedEvent} so a
 * test can assert on the rewritten source.
 *
 * <p>Shared by the write-path tests so each one is just its source and its assertion. Public because the
 * block-construction tests in {@code blocks/} drive the same converter — the harness is the module's one
 * source-to-block-tree entry point for tests, and a second copy of it would drift.
 */
public final class EditorFixture {

    private static final Path PROJECTS = Paths.get("/tmp/projects");
    private static final ProjectConfig CONFIG = ProjectConfig.forProject("MyBot", PROJECTS);
    private static final List<String> RUNTIME_CLASSPATH =
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));

    public final CodeEditor editor;
    public final ProjectState state;
    public final AbstractCodeBlock root;
    public String lastCode;

    private final EventBus bus;
    private final BlockConverter converter;
    private final BlockDragAndDropManager dragAndDrop;
    private final ProjectAnalyzer analyzer;
    private CodeEditorService context;

    public EditorFixture(String source) {
        this(source, Paths.get("Subject.java").toAbsolutePath());
    }

    /** As {@link #EditorFixture(String)} but with an explicit file path — e.g. one under the activities dir. */
    public EditorFixture(String source, Path file) {
        state = new ProjectState();
        state.addFile(new ProjectFile(file, source));
        state.setActiveFile(file);
        // FileRole only compares paths, it never reads them, but the parser needs a real source root and
        // classpath to resolve against.
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(RUNTIME_CLASSPATH);
        state.setTemplate(ProjectTemplate.GAME_BOT);
        state.setCurrentCode(source);

        bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode = e.newCode());

        converter = new BlockConverter(CONFIG, state);
        dragAndDrop = new BlockDragAndDropManager(bus);
        analyzer = new ProjectAnalyzer(null, state);
        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                converter, state, source, dragAndDrop, false, false);
        state.setCompilationUnit(result.cu());
        root = result.root();
        assertNotNull(root, "converter should produce a root block");

        editor = new CodeEditor(CONFIG, state, bus, analyzer);
    }

    /**
     * The {@link CodeEditorService} the UI layer is handed — every block's {@code getUINode} and every
     * argument editor takes one. Built lazily because it opens an {@code SdkDocsService} loader thread that
     * the write-path tests have no use for.
     */
    public CodeEditorService context() {
        if (context == null) {
            context = new CodeEditorService(CONFIG, state, bus, converter, dragAndDrop,
                    new DiagnosticsManager(), analyzer, new SdkDocsService(CONFIG, bus));
        }
        return context;
    }

    /** A path under this project's activities package — a file there is treated as an activity stub. */
    public static Path activitiesFile(String fileName) {
        return CONFIG.activitiesPackageDir().resolve(fileName).toAbsolutePath();
    }

    /** The {@link BodyBlock} for {@code methodName}'s body, found the way CodeEditorService finds it: by AST node. */
    public BodyBlock body(String methodName) {
        TypeDeclaration type = (TypeDeclaration) root.getAstNode();
        MethodDeclaration found = null;
        for (MethodDeclaration m : type.getMethods()) {
            if (m.getName().getIdentifier().equals(methodName)) found = m;
        }
        assertNotNull(found, "fixture should have " + methodName + "()");
        var target = found.getBody();
        for (CodeBlock b : all(root)) {
            if (b instanceof BodyBlock bb && bb.getAstNode() == target) return bb;
        }
        throw new AssertionError("no body block for " + methodName);
    }

    private static List<CodeBlock> all(CodeBlock from) {
        List<CodeBlock> out = new ArrayList<>();
        out.add(from);
        if (from instanceof BlockWithChildren parent) {
            for (CodeBlock child : parent.getChildren()) out.addAll(all(child));
        }
        return out;
    }
}
