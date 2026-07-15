import com.botmaker.studio.blocks.ClassBlock;
import com.botmaker.studio.blocks.expr.MethodReferenceBlock;
import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.events.EventBus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression cover for the "{@code supervise()} renders with empty parentheses" bug.
 *
 * <p>The generated game-bot entry point is {@code Bot.supervise(GameLoop::run, GoHome::run, Startup::run)}.
 * {@code Bot} is an SDK facade, so the call becomes a {@code LibraryCallBlock} whose arguments are populated
 * via {@code parseExpression(...).ifPresent(block::addArgument)}. Method references matched no branch of
 * {@code dispatchExpression}, so all three arguments resolved to {@code Optional.empty()} and were silently
 * dropped — the block rendered as {@code supervise()} while the source kept the real arguments.
 */
public class MethodReferenceBlockTest {

    private static final String GAME_BOT_MAIN = """
            package test;

            public class Subject {
                public static void main(String[] args) {
                    Bot.supervise(GameLoop::run, GoHome::run, Startup::run);
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

    /** Depth-first collection of every block of a given type. */
    private static <T> List<T> collect(CodeBlock block, Class<T> type) {
        List<T> found = new ArrayList<>();
        walk(block, type, found);
        return found;
    }

    private static <T> void walk(CodeBlock block, Class<T> type, List<T> out) {
        if (block == null) return;
        if (type.isInstance(block)) out.add(type.cast(block));
        if (block instanceof MethodInvocationBlock mi) {
            for (ExpressionBlock arg : mi.getArgumentBlocks()) walk(arg, type, out);
        }
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) walk(child, type, out);
        }
    }

    @Test
    void superviseKeepsItsThreeMethodReferenceArguments() {
        AbstractCodeBlock root = parse(GAME_BOT_MAIN);
        assertNotNull(root);

        List<MethodInvocationBlock> calls = collect(root, MethodInvocationBlock.class);
        MethodInvocationBlock supervise = calls.stream()
                .filter(c -> !c.getArgumentBlocks().isEmpty() || c.getDetails().contains("supervise"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no supervise call block was produced"));

        assertEquals(3, supervise.getArgumentBlocks().size(),
                "supervise(a, b, c) must expose three argument blocks, not render as supervise()");
    }

    @Test
    void methodReferencesBecomeMethodReferenceBlocks() {
        AbstractCodeBlock root = parse(GAME_BOT_MAIN);

        List<MethodReferenceBlock> refs = collect(root, MethodReferenceBlock.class);
        assertEquals(3, refs.size(), "expected a block for each of GameLoop::run, GoHome::run, Startup::run");

        List<String> rendered = refs.stream()
                .map(r -> r.getTargetName() + "::" + r.getMethodName())
                .toList();
        assertTrue(rendered.contains("GameLoop::run"), "got " + rendered);
        assertTrue(rendered.contains("GoHome::run"), "got " + rendered);
        assertTrue(rendered.contains("Startup::run"), "got " + rendered);
    }

    @Test
    void classBlockExposesItsSuperclass() {
        // Exactly the shape ActivityService.generateStubSource emits.
        AbstractCodeBlock root = parse("""
                package test;

                public class Mining extends Activity {
                    public Mining() { super("Mining"); }
                }
                """);

        List<ClassBlock> classes = collect(root, ClassBlock.class);
        assertFalse(classes.isEmpty(), "expected a ClassBlock");
        ClassBlock cb = classes.get(0);
        assertEquals("Activity", cb.getSuperClassName(),
                "the activity stub's superclass must be visible, not hidden");
        assertTrue(cb.headerText().contains("extends Activity"), "got: " + cb.headerText());
    }

    @Test
    void classWithoutSuperclassHasNoInheritanceClause() {
        AbstractCodeBlock root = parse("""
                package test;

                public class Plain {
                    public void go() {}
                }
                """);

        ClassBlock cb = collect(root, ClassBlock.class).get(0);
        assertNull(cb.getSuperClassName());
        assertEquals("Plain", cb.headerText());
    }
}
