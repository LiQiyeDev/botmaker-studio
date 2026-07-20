package com.botmaker.studio.parser;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
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
 * <p>Shared by the write-path tests so each one is just its source and its assertion.
 */
final class EditorFixture {

    private static final Path PROJECTS = Paths.get("/tmp/projects");
    private static final ProjectConfig CONFIG = ProjectConfig.forProject("MyBot", PROJECTS);
    private static final List<String> RUNTIME_CLASSPATH =
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));

    final CodeEditor editor;
    final ProjectState state;
    final AbstractCodeBlock root;
    String lastCode;

    EditorFixture(String source) {
        Path file = Paths.get("Subject.java").toAbsolutePath();
        state = new ProjectState();
        state.addFile(new ProjectFile(file, source));
        state.setActiveFile(file);
        // FileRole only compares paths, it never reads them, but the parser needs a real source root and
        // classpath to resolve against.
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(RUNTIME_CLASSPATH);
        state.setTemplate(ProjectTemplate.GAME_BOT);
        state.setCurrentCode(source);

        EventBus bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode = e.newCode());

        BlockConverter converter = new BlockConverter(CONFIG, state);
        BlockConverter.ConvertResult result = converter.convert(
                source, state.getMutableNodeToBlockMap(), new BlockDragAndDropManager(bus), false, false);
        state.setCompilationUnit(result.cu());
        root = result.root();
        assertNotNull(root, "converter should produce a root block");

        editor = new CodeEditor(CONFIG, state, bus, new ProjectAnalyzer(null, state));
    }

    /** The {@link BodyBlock} for {@code methodName}'s body, found the way CodeEditorService finds it: by AST node. */
    BodyBlock body(String methodName) {
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
