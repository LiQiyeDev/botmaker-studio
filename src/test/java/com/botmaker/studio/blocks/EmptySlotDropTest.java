package com.botmaker.studio.blocks;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.parser.CodeEditor;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dropping a value into a slot that holds <em>nothing</em> — {@code int x;}'s missing initialiser, an empty
 * {@code Print:}'s missing argument.
 *
 * <p>Those two were drawn as dashed rectangles saying "drop here" and refused every drop, because every fill
 * path in the editor names the expression it replaces and an empty slot has none. The fix is a placement rule
 * per statement shape rather than a substitution, which is what this pins: where the hole is, that the
 * statement the value came from is consumed, and that a shape with no hole is left alone rather than mangled.
 */
class EmptySlotDropTest {

    private String code;
    private CodeEditor editor;
    private CompilationUnit unit;

    private void open(String source) {
        ProjectState state = new ProjectState();
        Path path = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, source));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        EventBus bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> code = e.newCode());

        BlockConverter converter = new BlockConverter(null, state);
        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                converter, state, source, new BlockDragAndDropManager(bus), false, false);
        state.setCompilationUnit(result.cu());
        unit = result.cu();
        editor = new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state));
    }

    /** The statements of the first method, in source order. */
    private List<Statement> statements() {
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
        Block body = type.getMethods()[0].getBody();
        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();
        return statements;
    }

    @Test
    void aDeclarationWithNoInitialiserTakesTheDroppedValue() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        int x;
                        "abc".length();
                    }
                }
                """);
        editor.fillEmptySlot(statements().getFirst(), (ExpressionStatement) statements().get(1));

        assertNotNull(code, "the drop should have produced a code update");
        assertTrue(code.contains("int x = \"abc\".length();"), code);
        assertFalse(code.contains("\n        \"abc\".length();"),
                "the statement the value came from is consumed, not duplicated:\n" + code);
    }

    @Test
    void aCallWithNoArgumentTakesTheDroppedValueAsOne() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        System.out.println();
                        "abc".trim();
                    }
                }
                """);
        editor.fillEmptySlot(statements().getFirst(), (ExpressionStatement) statements().get(1));

        assertTrue(code.contains("System.out.println(\"abc\".trim());"), code);
    }

    @Test
    void aSlotThatIsNotEmptyIsLeftAlone() {
        // The drop is a placement, never a substitution: a filled slot is the other path's job
        // (moveExpressionIntoSlot), and doing nothing is the only safe answer to a shape with no hole.
        open("""
                package test;

                public class Subject {
                    public void run() {
                        int x = 1;
                        "abc".length();
                    }
                }
                """);
        editor.fillEmptySlot(statements().getFirst(), (ExpressionStatement) statements().get(1));

        assertEquals(2, statements().size(), "nothing was consumed");
        assertTrue(code == null || code.contains("int x = 1;"), String.valueOf(code));
    }
}
