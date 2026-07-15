import com.botmaker.studio.blocks.ClassBlock;
import com.botmaker.studio.blocks.misc.InitializerBlock;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@code static { … }} branch of {@code BlockConverter.parseRoot}.
 *
 * <p>Before it existed, JDT's {@code Initializer} matched none of the handled body-declaration types and was
 * dropped on the floor: the generated {@code Activities.java} rendered without the static block that loads every
 * activity's value from {@code activities.json}. That is a data-loss hazard rather than a cosmetic one, because
 * {@code ClassBlock} rewrites the class from the body declarations it holds — an initializer the block tree
 * never saw is an initializer a later edit can silently delete.
 */
public class InitializerBlockTest {

    /** Shaped like the generated Activities.java: fields, a static loader, and a helper method. */
    private static final String WITH_STATIC_BLOCK = """
            package test;

            public class Subject {
                public static final int SPEED;

                static {
                    int loaded = 5;
                    SPEED = loaded;
                }

                private static int helper() {
                    return 1;
                }
            }
            """;

    private static AbstractCodeBlock parse(String source) {
        ProjectState state = new ProjectState();
        Path path = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, source));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        BlockConverter converter = new BlockConverter(null, state);
        BlockConverter.ConvertResult result = converter.convert(
                source, state.getMutableNodeToBlockMap(),
                new BlockDragAndDropManager(new EventBus(false)), false, false);
        state.setCompilationUnit(result.cu());
        return result.root();
    }

    private static InitializerBlock findInitializer(ClassBlock classBlock) {
        for (CodeBlock member : classBlock.getChildren()) {
            if (member instanceof InitializerBlock init) return init;
        }
        return null;
    }

    @Test
    void aStaticInitializerBecomesABlock() {
        ClassBlock root = (ClassBlock) parse(WITH_STATIC_BLOCK);
        InitializerBlock init = findInitializer(root);

        assertNotNull(init, "the static block must appear in the tree, not vanish from it");
        assertTrue(init.isStatic());
    }

    @Test
    void theStatementsInsideAreRealBlocks() {
        ClassBlock root = (ClassBlock) parse(WITH_STATIC_BLOCK);
        InitializerBlock init = findInitializer(root);

        assertEquals(1, init.getChildren().size(), "the initializer owns its body");
        var body = (com.botmaker.studio.core.BodyBlock) init.getChildren().getFirst();
        assertEquals(2, body.getStatements().size(), "both statements of the static block are parsed");
    }

    @Test
    void theOtherMembersStillParseAlongsideIt() {
        // The initializer must not swallow or displace its siblings.
        ClassBlock root = (ClassBlock) parse(WITH_STATIC_BLOCK);
        assertEquals(3, root.getChildren().size(),
                "field + static initializer + method, in source order");
    }

    @Test
    void aClassWithoutAnInitializerIsUnaffected() {
        ClassBlock root = (ClassBlock) parse("""
                package test;
                public class Subject {
                    private static int helper() { return 1; }
                }
                """);
        assertNull(findInitializer(root));
        assertEquals(1, root.getChildren().size());
    }
}
