package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic grow/shrink affordance on a varargs slot: {@code MethodSignature} has always modelled the
 * trailing parameter correctly, but nothing could produce or drop an argument, so a call was frozen at the
 * arguments it was created with. These cover the two rewrites the {@code ＋} / {@code ✕} buttons drive.
 */
public class VarargsArgumentEditTest {

    /** Runs {@code edit} against the named call in {@code source} and returns the rewritten source. */
    private String rewrite(String source, String call, Consumer<Edit> edit) {
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
        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                converter, state, source, new BlockDragAndDropManager(bus), false, false);
        state.setCompilationUnit(result.cu());

        MethodInvocation target = findCall(result.cu(), call);
        assertNotNull(target, "test setup: no call named " + call);

        edit.accept(new Edit(new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state)), target));
        assertNotNull(lastCode[0], "edit should have produced a code update");
        return lastCode[0].replace(" ", "");
    }

    /** The pair every test needs: the editor and the call to run it against. */
    private record Edit(CodeEditor editor, MethodInvocation call) {}

    private static MethodInvocation findCall(CompilationUnit cu, String name) {
        MethodInvocation[] found = new MethodInvocation[1];
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation mi) {
                if (mi.getName().getIdentifier().equals(name)) found[0] = mi;
                return true;
            }
        });
        return found[0];
    }

    private static String subject(String call) {
        return """
                package test;
                public class Subject {
                    void run() {
                        %s;
                    }
                }
                """.formatted(call);
    }

    /** The ＋ appends a default-valued slot of the varargs element type, ready to be edited. */
    @Test
    void addingAVarargsArgumentAppendsADefault() {
        String result = rewrite(subject("Text.join(\"a\")"), "join",
                e -> e.editor().addVarargsArgument(e.call(), ResolvedType.named("java.lang.String")));
        assertTrue(result.contains("Text.join(\"a\",\"\")"), () -> "expected a second String slot: " + result);
    }

    /** The ✕ drops exactly the argument it sits on, leaving the ones around it alone. */
    @Test
    void removingAnArgumentKeepsItsNeighbours() {
        String result = rewrite(subject("Text.join(\"a\", \"b\", \"c\")"), "join",
                e -> e.editor().deleteArgumentFromMethodInvocation(e.call(), 1));
        assertTrue(result.contains("Text.join(\"a\",\"c\")"), () -> "expected only \"b\" to go: " + result);
    }

    /** An out-of-range index is a no-op rather than an exception — the block and AST can be a frame apart. */
    @Test
    void removingAnAbsentArgumentChangesNothing() {
        String result = rewrite(subject("Text.join(\"a\")"), "join",
                e -> e.editor().deleteArgumentFromMethodInvocation(e.call(), 5));
        assertTrue(result.contains("Text.join(\"a\")"), () -> "the call must survive untouched: " + result);
    }
}
