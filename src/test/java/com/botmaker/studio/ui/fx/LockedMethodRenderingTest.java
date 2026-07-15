package com.botmaker.studio.ui.fx;

import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders a method header for real and asserts a locked one offers no way to change its signature.
 *
 * <p>This is the regression the user actually hit: {@code ReadOnlyDecorator} only toggles a pseudo-class and
 * {@code InteractionDecorator} only suppresses the right-click menu, so a "read-only" method still rendered a
 * live name {@link TextField}, a clickable return type and a delete button — the signature that
 * {@code Bot.supervise} depends on could be renamed or deleted straight from the header. Styling is not
 * enforcement; the controls themselves have to go.
 */
class LockedMethodRenderingTest extends FxHeadlessTest {

    private static final String SOURCE = """
            package test;
            public class GoHome {
                public static void run(int times) {}
            }
            """;

    private VBox root;
    private CodeEditorService context;

    @Override
    public void start(Stage stage) {
        // The header only reaches getState() (for collapse state) while rendering; the rest of the service is
        // needed at click time, which is exactly what a locked header must never get to.
        com.botmaker.studio.events.EventBus eventBus = new com.botmaker.studio.events.EventBus(false);
        context = new CodeEditorService(
                null, new com.botmaker.studio.project.ProjectState(), eventBus, null,
                new com.botmaker.studio.ui.dnd.BlockDragAndDropManager(eventBus),
                new com.botmaker.studio.validation.DiagnosticsManager(), null, null);

        root = new VBox();
        stage.setScene(new Scene(root, 600, 300));
        stage.show();
    }

    private static MethodDeclaration parseRun() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(SOURCE.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        return ((TypeDeclaration) cu.types().getFirst()).getMethods()[0];
    }

    /** Renders {@code block}'s header into the live scene and returns the rendered node. */
    private Node render(MethodDeclarationBlock block) {
        // createUINode is the raw content, without the decorator pipeline — exactly what we want to inspect:
        // it is the header's own controls, not the decorators, that were leaking write access.
        Node[] holder = new Node[1];
        interact(() -> {
            holder[0] = block.getUINode(context);
            root.getChildren().setAll(holder[0]);
        });
        return holder[0];
    }

    private static <T> List<T> descendants(Node node, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(node, type, found);
        return found;
    }

    private static <T> void collect(Node node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) out.add(type.cast(node));
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collect(child, type, out);
        }
    }

    @Test
    void anEditableMethodOffersItsNameForEditing() {
        MethodDeclarationBlock block = new MethodDeclarationBlock("id", parseRun(), null);
        Node ui = render(block);

        assertFalse(descendants(ui, TextField.class).isEmpty(),
                "an ordinary method's name and parameters stay editable");
    }

    @Test
    void aLockedMethodHasNoEditableControls() {
        MethodDeclarationBlock block = new MethodDeclarationBlock("id", parseRun(), null);
        block.setReadOnly(true);
        Node ui = render(block);

        assertTrue(descendants(ui, TextField.class).isEmpty(),
                "a locked method must not render a live name or parameter field");
        assertTrue(descendants(ui, javafx.scene.control.Button.class).stream()
                        .noneMatch(b -> "×".equals(b.getText())),
                "a locked method must not offer a delete button");
    }

    @Test
    void aLockedMethodStillShowsItsSignature() {
        // Locking must not hide the code: the user should still be able to read what the method is.
        MethodDeclarationBlock block = new MethodDeclarationBlock("id", parseRun(), null);
        block.setReadOnly(true);
        Node ui = render(block);

        List<String> labels = descendants(ui, Label.class).stream().map(Label::getText).toList();
        assertTrue(labels.contains("run"), "the method name is still displayed: " + labels);
        assertTrue(labels.contains("times"), "the parameter name is still displayed: " + labels);
    }

    @Test
    void theBadgeTellsTheUserWhyItIsLocked() {
        MethodDeclarationBlock block = new MethodDeclarationBlock("id", parseRun(), null);
        block.setReadOnly(true);
        block.setLockBadge("Name and parameters required by BotMaker");
        Node ui = render(block);

        List<Label> badges = descendants(ui, Label.class).stream()
                .filter(l -> l.getStyleClass().contains("method-lock-badge"))
                .toList();
        assertEquals(1, badges.size());
        assertEquals("Name and parameters required by BotMaker", badges.getFirst().getText());
    }
}
