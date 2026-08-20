package com.botmaker.studio.ui.fx;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.services.CodeEditorService;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A screen that reacts to a write is answered with the file that write produced — not the one before it.
 *
 * <p>{@code CodeUpdatedEvent} is published synchronously by {@code CodeEditor.triggerUpdate}, while
 * {@code CodeEditorService} used to do <em>all</em> of its refresh inside a {@code Platform.runLater} — the
 * re-parse into {@code ProjectState} included. So every listener that answered the event by asking the state
 * what the file said got the file as it was before the write. The Edit Variable screen showed the type picked
 * one click ago, and a block resolving its node through the state resolved it against a tree that no longer
 * existed. Two writes in a row and the screen was two steps behind by the same mechanism.
 *
 * <p>This runs headless with the FX toolkit up because the deferred half is still a {@code runLater} — the
 * point of the fix is that the <em>state</em> half no longer is.
 */
class StateIsCurrentWhenPublishedTest extends FxHeadlessTest {

    private static final String SOURCE = """
            package com.mybot;

            public class Subject {
                public void run() {
                    int attempts = 1;
                    BotMaker.print(attempts);
                }
            }
            """;

    @Override
    public void start(Stage stage) {
        // Nothing to show: the toolkit only has to be running so the deferred render can be queued.
    }

    @Test
    void aListenerSeesTheFileTheWriteJustProduced() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        CodeEditorService context = fixture.context();

        // Subscribed *after* the service, so this stands where a dialog stands: behind the subscription that
        // adopts the new text into ProjectState, and in front of the render that used to carry it.
        List<String> asSeenByAListener = new ArrayList<>();
        context.getEventBus().subscribe(CoreApplicationEvents.CodeUpdatedEvent.class,
                e -> asSeenByAListener.add(fixture.state.getCompilationUnit()
                        .map(CompilationUnit::toString).orElse("")), false);

        fixture.editor.renameLocalVariable(declarationName(fixture), "tries");

        assertEquals(1, asSeenByAListener.size(), "one write, one event");
        assertTrue(asSeenByAListener.getFirst().contains("tries"),
                "the state a listener reads must already be the renamed file:\n" + asSeenByAListener.getFirst());
    }

    /** The {@code attempts} declaration's name node, which is what the Variables screen renames. */
    private static org.eclipse.jdt.core.dom.SimpleName declarationName(EditorFixture fixture) {
        org.eclipse.jdt.core.dom.SimpleName[] found = new org.eclipse.jdt.core.dom.SimpleName[1];
        fixture.state.getCompilationUnit().orElseThrow().accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.VariableDeclarationFragment fragment) {
                if (found[0] == null && "attempts".equals(fragment.getName().getIdentifier())) {
                    found[0] = fragment.getName();
                }
                return true;
            }
        });
        return found[0];
    }
}
