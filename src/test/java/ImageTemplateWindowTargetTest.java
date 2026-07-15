import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.parser.CodeEditor;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies Phase 5a window-targeted codegen: {@link CodeEditor#setImageTemplate(Expression, String, String)}
 * injects {@code Window.find("<title>").orElseThrow()} as the capture source only when the template is the
 * sole argument of an {@code ImageFinder.find(...)} call and a window title is supplied — so a project with a
 * default window target finds inside that window automatically, without the author picking a window per call.
 */
public class ImageTemplateWindowTargetTest {

    private String applyTemplate(String source, String enclosingMethod, String path, String windowTitle) {
        ProjectState state = new ProjectState();
        Path p = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(p, source));
        state.setActiveFile(p);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        EventBus bus = new EventBus(false);
        String[] lastCode = new String[1];
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode[0] = e.newCode());

        BlockConverter converter = new BlockConverter(null, state);
        BlockConverter.ConvertResult result = converter.convert(
                source, state.getMutableNodeToBlockMap(), new BlockDragAndDropManager(bus), false, false);
        state.setCompilationUnit(result.cu());

        CodeEditor editor = new CodeEditor(state, bus, new ProjectAnalyzer(null, state));
        Expression templateSlot = findTemplateArg(result.cu(), enclosingMethod);
        assertNotNull(templateSlot, "test setup: could not find the ImageTemplate argument of " + enclosingMethod);

        editor.setImageTemplate(templateSlot, path, windowTitle);
        assertNotNull(lastCode[0], "edit should have produced a code update");
        return lastCode[0];
    }

    /** The {@code new ImageTemplate(...)} argument node of a call whose method name is {@code methodName}. */
    private static Expression findTemplateArg(CompilationUnit cu, String methodName) {
        Expression[] found = new Expression[1];
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(ClassInstanceCreation node) {
                if (node.getType().toString().equals("ImageTemplate")
                        && node.getParent() instanceof MethodInvocation mi
                        && mi.getName().getIdentifier().equals(methodName)) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    @Test
    void findWithWindowDefault_injectsWindowSource() {
        String src = """
                package test;
                public class Subject {
                    public void run() {
                        ImageFinder.find(new ImageTemplate(""));
                    }
                }
                """;
        String out = applyTemplate(src, "find", "btn.png", "Chrome");
        assertTrue(out.contains("new ImageTemplate(\"btn.png\")"), "template path should be set:\n" + out);
        assertTrue(out.contains("Window.find(\"Chrome\").orElseThrow()"),
                "window source should be injected:\n" + out);
    }

    @Test
    void findWithScreenDefault_staysAbsolute() {
        String src = """
                package test;
                public class Subject {
                    public void run() {
                        ImageFinder.find(new ImageTemplate(""));
                    }
                }
                """;
        String out = applyTemplate(src, "find", "btn.png", null);
        assertTrue(out.contains("new ImageTemplate(\"btn.png\")"), "template path should be set:\n" + out);
        assertFalse(out.contains("Window.find"), "no window source when default isn't a window:\n" + out);
    }

    @Test
    void nonFindCall_isNotWindowTargeted() {
        // ImageClicker has no CaptureSource overload — a window title must not inject a source here.
        String src = """
                package test;
                public class Subject {
                    public void run() {
                        ImageClicker.click(new ImageTemplate(""));
                    }
                }
                """;
        String out = applyTemplate(src, "click", "btn.png", "Chrome");
        assertTrue(out.contains("new ImageTemplate(\"btn.png\")"), "template path should be set:\n" + out);
        assertFalse(out.contains("Window.find"), "window source must not be injected into a non-find call:\n" + out);
    }
}
