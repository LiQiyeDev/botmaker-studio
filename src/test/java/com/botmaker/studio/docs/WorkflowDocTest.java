package com.botmaker.studio.docs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that makes "one source" true rather than aspirational: {@code WORKFLOW.md} is generated from
 * {@link Workflow}, so editing the class without regenerating — or editing the file by hand — fails here.
 */
class WorkflowDocTest {

    @Test
    void theCommittedWorkflowDocMatchesWhatTheModelRenders() throws Exception {
        String expected = WorkflowMarkdown.render();
        Path committed = WorkflowMarkdown.FILE;
        assertTrue(Files.exists(committed), "WORKFLOW.md is missing — run WorkflowMarkdown.main to generate it");

        String actual = Files.readString(committed, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            // Write the expected text somewhere the maintainer can just copy, rather than making them
            // reconstruct it from a diff of two 300-line strings in the surefire report.
            Path regenerated = Path.of("target", "WORKFLOW.md");
            Files.createDirectories(regenerated.getParent());
            Files.writeString(regenerated, expected, StandardCharsets.UTF_8);
            assertEquals(expected, actual,
                    "WORKFLOW.md is out of date with com.botmaker.studio.docs.Workflow — "
                    + "copy " + regenerated.toAbsolutePath() + " over it (or run WorkflowMarkdown.main)");
        }
    }

    @Test
    void everyStepIsRenderableInBothPlaces() {
        for (WorkflowStep step : Workflow.steps()) {
            assertFalse(step.title().isBlank(), "a step with no title renders as an empty heading");
            assertFalse(step.summary().isBlank(), step.title() + " has no summary");
            if (step.action() != null) {
                // Both renderings must exist: the dialog needs a button label, the doc a menu path.
                assertFalse(step.action().buttonLabel().isBlank());
                assertFalse(step.action().menuPath().isBlank());
            }
        }
    }

    /**
     * A destination nothing points at is a button label nobody ever reads — and, more usefully, a sign that a
     * step was removed without its destination going with it.
     */
    @Test
    void everyDestinationIsUsedByAStep() {
        Set<StudioAction> used = EnumSet.noneOf(StudioAction.class);
        Workflow.steps().forEach(step -> {
            if (step.action() != null) used.add(step.action());
        });
        assertEquals(EnumSet.allOf(StudioAction.class), used,
                "StudioAction exists to be pointed at by the workflow; drop the unused constants");
    }

    /**
     * The launch target has to be step 2. You cannot choose the window the bot watches before the game is on
     * screen, and the guide walked people into exactly that dead end for as long as capture came first.
     */
    @Test
    void theLaunchTargetIsAskedForBeforeTheCaptureTarget() {
        List<WorkflowStep> steps = Workflow.steps();
        int launch = indexOfAction(steps, StudioAction.LAUNCH_TARGET);
        int capture = indexOfAction(steps, StudioAction.CAPTURE_TARGETS);

        assertTrue(launch >= 0 && capture >= 0, "both target steps must exist");
        assertTrue(launch < capture,
                "you can't pick a capture target before the game is running — launch must come first");
    }

    private static int indexOfAction(List<WorkflowStep> steps, StudioAction action) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).action() == action) return i;
        }
        return -1;
    }

    /**
     * The Mermaid graph is generated from {@link RuntimeDiagram}'s model, so the failure this guards against
     * is a node added to the model that never reaches the drawing — which would look fine in the dialog (it
     * walks the same list) and be silently missing from {@code WORKFLOW.md}.
     */
    @Test
    void everyRuntimeNodeAndEdgeReachesTheDrawnGraph() {
        String mermaid = RuntimeDiagram.mermaid();
        List<RuntimeDiagram.Node> chain = RuntimeDiagram.chain();

        assertTrue(mermaid.startsWith("flowchart TD"), mermaid);
        for (RuntimeDiagram.Node node : chain) {
            assertTrue(mermaid.contains(node.title()), "node missing from the graph: " + node.title());
            assertFalse(node.detail().isBlank(), node.title() + " has no explanation for the legend");
        }
        for (int i = 0; i < chain.size() - 1; i++) {
            assertTrue(mermaid.contains(chain.get(i).id() + " --> " + chain.get(i + 1).id()),
                    "the chain is broken after " + chain.get(i).id());
        }
        // The two edges that make it a runtime rather than a list: the loop back, and the guard's side channel.
        assertTrue(mermaid.contains("--> " + RuntimeDiagram.loopTarget() + "\n"), "no loop back to the driver");
        assertTrue(mermaid.contains(".-> " + RuntimeDiagram.guardAttachesTo()), "the popup guard is detached");
    }

    /** A generated diagram is only single-sourced if the doc actually carries it. */
    @Test
    void theRenderedDocCarriesTheDiagramAndItsLegend() {
        String md = WorkflowMarkdown.render();

        assertTrue(md.contains("## " + RuntimeDiagram.TITLE), "the runtime section is missing");
        assertTrue(md.contains("```mermaid"), "the diagram is not rendered as a Mermaid block");
        assertTrue(md.contains(RuntimeDiagram.guard().detail()), "the popup guard's legend entry is missing");
        assertTrue(md.contains(RuntimeDiagram.LOOP_NOTE), "nothing says how a run ends");
    }
}
