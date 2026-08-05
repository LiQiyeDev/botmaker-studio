package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.CursorNavigator;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Block trees for the overlay's headless tests, built through the <em>real</em> converter so the shapes under
 * test are the ones Studio actually produces — an {@code else if} really is a chained {@code IfBlock} here,
 * which is the whole point of the branch tests. No JavaFX toolkit is required.
 */
final class OverlayTestTrees {

    private OverlayTestTrees() {}

    /** The block tree for {@code source}. */
    static AbstractCodeBlock treeOf(String source) {
        ProjectState state = new ProjectState();
        Path path = Paths.get("Mining.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, source));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                new BlockConverter(null, state), state, source,
                new BlockDragAndDropManager(new EventBus(false)), false, false);
        assertNotNull(result.root(), "converter should produce a root block");
        return result.root();
    }

    /** An activity class whose {@code run()} contains {@code runBody}. */
    static String activity(String runBody) {
        return """
                package com.test.activities;

                public class Mining {
                    public boolean isEnabled() {
                        return true;
                    }

                    public String run() {
                %s    }
                }
                """.formatted(runBody);
    }

    /** Shorthand for {@code treeOf(activity(runBody))}. */
    static AbstractCodeBlock activityTree(String runBody) {
        return treeOf(activity(runBody));
    }

    /** The body of the method named {@code name}. */
    static BodyBlock bodyOf(CodeBlock root, String name) {
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b instanceof com.botmaker.studio.blocks.func.MethodDeclarationBlock m
                    && name.equals(m.getMethodName())) {
                for (CodeBlock child : m.getChildren()) {
                    if (child instanceof BodyBlock body) return body;
                }
            }
        }
        return null;
    }
}
