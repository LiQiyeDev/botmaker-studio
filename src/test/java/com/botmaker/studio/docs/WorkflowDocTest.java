package com.botmaker.studio.docs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
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
}
