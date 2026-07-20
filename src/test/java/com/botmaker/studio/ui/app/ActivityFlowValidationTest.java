package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the flow dialog refuses to save. Every rule here exists because the generator would otherwise emit Java
 * that doesn't compile — the point is to say so in the dialog rather than in a build log.
 */
class ActivityFlowValidationTest {

    private static ActivitiesConfig of(ActivityDefinition... activities) {
        return new ActivitiesConfig(List.of(activities), List.of());
    }

    private static ActivityDefinition activity(String name, String... outcomes) {
        return ActivityDefinition.create(name, "").withOutcomes(List.of(outcomes));
    }

    @Test
    void anOrdinaryActivityWithOutcomesIsFine() {
        assertNull(ActivityFlowDialog.validate(of(activity("Mining", "BAG_FULL", "NO_ORE"))));
    }

    @Test
    void anOutcomeMustBeAValidJavaIdentifier() {
        // It becomes a constant of the generated Outcome enum.
        String problem = ActivityFlowDialog.validate(of(activity("Mining", "bag full")));
        assertNotNull(problem);
        assertTrue(problem.contains("bag full"), problem);
    }

    @Test
    void anOutcomeCannotBeDeclaredTwice() {
        assertNotNull(ActivityFlowDialog.validate(of(activity("Mining", "BAG_FULL", "BAG_FULL"))));
    }

    @Test
    void anOutcomeCannotRedeclareTheImplicitNext() {
        // allOutcomes() de-duplicates it, so this must be caught as a clash rather than silently swallowed —
        // otherwise the user adds a NEXT outcome, gets no port for it, and has nothing to explain why.
        assertNotNull(ActivityFlowDialog.validate(of(activity("Mining", "NEXT"))));
    }

    @Test
    void anOutcomeTypedWithSpacesIsNormalisedRatherThanRejected() {
        // "bag full" is a perfectly clear thing to type; turning it into the enum constant is our job.
        assertEquals("BAG_FULL", ActivityFlowDialog.normalizeOutcome("bag full"));
        assertEquals("BAG_FULL", ActivityFlowDialog.normalizeOutcome("  bag-full "));
        assertEquals("NO_ORE", ActivityFlowDialog.normalizeOutcome("no.ore"));
        assertNull(ActivityFlowDialog.validate(of(activity("Mining",
                ActivityFlowDialog.normalizeOutcome("bag full")))));
    }

    @Test
    void twoActivitiesDifferingOnlyInCaseAreRejected() {
        // The registry names its singleton by upper-casing, so MINING would be declared twice. Their stub
        // files would also collide on a case-insensitive filesystem.
        String problem = ActivityFlowDialog.validate(of(activity("Mining"), activity("MINING")));
        assertNotNull(problem);
        assertTrue(problem.contains("case"), problem);
    }
}
