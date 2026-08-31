package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.render.components.pickers.ImageTemplateGroupPicker;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code ImageTemplate...} varargs slot is edited as a whole list — the same multi-image chip row an
 * {@code ImageTemplateGroup} slot gets — because rendering one single-image picker per argument that already
 * existed left {@code found.hasAny(coin)} with no affordance that could ever produce
 * {@code found.hasAny(coin, gem)}.
 *
 * <p>{@link CodeEditor#setTrailingArguments} is the writer behind that row, and
 * {@link ImageTemplateGroupPicker#templatePath} is the guard deciding whether the row may appear at all.
 *
 * <p>The writer takes <b>expressions</b> rather than template paths since 2026-08-31 — the row is the SDK
 * plugin's now, and it hands over the Java it wants written. These tests still speak in paths and spell the
 * constructor themselves, which is what the plugin does: the assertions are unchanged, because the source
 * that comes out is the same source.
 */
public class ImageTemplateVarargsTest {

    /** The expression the plugin's row writes for one picture. */
    private static String template(String path) {
        return "new ImageTemplate(\"" + path + "\")";
    }

    /** Runs {@code setTrailingArguments} on the named call and returns the rewritten source. */
    private String setArgs(String source, String call, int fromIndex, List<String> paths) {
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

        new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state))
                .setTrailingArguments(target, fromIndex, paths.stream().map(ImageTemplateVarargsTest::template).toList(),
                        "com.botmaker.sdk.api.vision.ImageTemplate");
        assertNotNull(lastCode[0], "edit should have produced a code update");
        return lastCode[0];
    }

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

    /** The point of the whole change: a one-template varargs call can grow a second template. */
    @Test
    void aVarargsSlotGrowsASecondTemplate() {
        String result = setArgs(subject("found.hasAny(new ImageTemplate(\"a.png\"))"),
                "hasAny", 0, List.of("a.png", "b.png")).replace(" ", "");
        assertTrue(result.contains("hasAny(newImageTemplate(\"a.png\"),newImageTemplate(\"b.png\"))"),
                () -> "expected both templates in the call: " + result);
    }

    /** Removing the last chip empties the varargs rather than leaving a stale or malformed argument. */
    @Test
    void removingTheLastTemplateLeavesAnEmptyCall() {
        String result = setArgs(subject("found.hasAll(new ImageTemplate(\"a.png\"))"),
                "hasAll", 0, List.of()).replace(" ", "");
        assertTrue(result.contains("found.hasAll()"), () -> "expected an emptied call: " + result);
    }

    /**
     * The tail is rewritten, the fixed parameters before it are not — an image varargs method that also takes
     * a leading argument would otherwise lose it on the first chip edit.
     */
    @Test
    void fixedArgumentsBeforeTheVarargsSurvive() {
        String result = setArgs(subject("ImageFinder.findAny(source, new ImageTemplate(\"a.png\"))"),
                "findAny", 1, List.of("b.png", "c.png")).replace(" ", "");
        assertTrue(result.contains("findAny(source,newImageTemplate(\"b.png\"),newImageTemplate(\"c.png\"))"),
                () -> "the leading argument must be untouched: " + result);
    }

    /**
     * The row may only claim a tail it can faithfully represent. A varargs argument holding a variable has no
     * path to show, and would be silently replaced by the next chip edit — so those calls keep the ordinary
     * per-argument pickers, and this reader is what tells them apart.
     */
    @Test
    void onlyTemplateLiteralsAreReadableAsPaths() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(subject("found.hasAny(coin, new ImageTemplate(\"b.png\"))").toCharArray());
        MethodInvocation call = findCall((CompilationUnit) parser.createAST(null), "hasAny");

        assertTrue(ImageTemplateGroupPicker.templatePath(call.arguments().get(0)).isEmpty(),
                "a bare variable is not a template path");
        assertEquals("b.png", ImageTemplateGroupPicker.templatePath(call.arguments().get(1)).orElse(null));
    }
}
