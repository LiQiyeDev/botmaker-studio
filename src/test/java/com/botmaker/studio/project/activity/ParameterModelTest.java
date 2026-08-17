package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things a parameter gained in this phase: who it is <em>for</em>, and — for the choice types — what
 * it may be set to. Both are model rules, so they are tested without a scene.
 */
class ParameterModelTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void aParameterNobodyHasThoughtAboutIsNotOfferedToTheUser() {
        // The default has to be the private one: exposing a setting is a decision, and a project written
        // before this field existed must not start publishing its internals on the next load.
        assertEquals(ParamVisibility.EDITOR_ONLY,
                ActivityVariable.create("retryDelay", ActivityType.INT).visibility());
        assertFalse(ActivityVariable.create("retryDelay", ActivityType.INT).isPublic());
    }

    @Test
    void anUnknownVisibilityLoadsAsEditorOnlyRatherThanFailing() {
        // A value from a newer Studio must not take the whole activities.json down with it, and the safe
        // reading of "I don't recognise this" is "don't show it to the user".
        assertEquals(ParamVisibility.EDITOR_ONLY, ParamVisibility.fromId("everyone-plus-cats"));
        assertEquals(ParamVisibility.EDITOR_ONLY, ParamVisibility.fromId(null));
        assertEquals(ParamVisibility.PUBLIC, ParamVisibility.fromId("public"));
        assertEquals(ParamVisibility.PUBLIC, ParamVisibility.fromId("PUBLIC"), "the enum name works too");
    }

    @Test
    void visibilityAndOptionsSurviveTheRoundTripThroughActivitiesJson(@TempDir Path dir) throws Exception {
        ActivityVariable mode = new ActivityVariable("mode", ActivityType.CHOICE, JSON.textNode("safe"),
                "how careful to be", ParamVisibility.PUBLIC, List.of("fast", "safe"));
        new ActivitiesConfig(List.of(ActivityDefinition.create("Mining", "").withParams(List.of(mode))),
                List.of()).write(dir);

        ActivityVariable read = ActivitiesConfig.read(dir).activities().getFirst().params().getFirst();

        assertEquals(ParamVisibility.PUBLIC, read.visibility());
        assertEquals(List.of("fast", "safe"), read.options());
        assertEquals("safe", read.value().asText(""));
    }

    @Test
    void anOlderFileWithNeitherFieldStillLoads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "globals": [ { "name": "count", "type": "INT", "value": 7 } ] }
                """);

        ActivityVariable read = ActivitiesConfig.read(dir).globals().getFirst();

        assertEquals(ParamVisibility.EDITOR_ONLY, read.visibility());
        assertEquals(List.of(), read.options());
        assertEquals(7, read.value().asInt(0));
    }

    @Test
    void deletingAChoiceUnsetsItWhereverItWasChosen() {
        // Otherwise the bot goes on running with a setting that no longer appears anywhere in the UI that set
        // it — invisible, and therefore undebuggable.
        ActivityVariable single = new ActivityVariable("mode", ActivityType.CHOICE, JSON.textNode("reckless"),
                "", ParamVisibility.PUBLIC, List.of("fast", "safe", "reckless"));

        assertEquals("fast", single.withOptions(List.of("fast", "safe")).value().asText(""),
                "the deleted choice falls back to the first one still offered");

        ActivityVariable many = new ActivityVariable("skills", ActivityType.MULTI_CHOICE,
                JSON.arrayNode().add("mine").add("fish"), "", ParamVisibility.EDITOR_ONLY,
                List.of("mine", "fish", "cook"));

        assertEquals(1, many.withOptions(List.of("mine", "cook")).value().size());
        assertEquals("mine", many.withOptions(List.of("mine", "cook")).value().get(0).asText(""));
    }

    @Test
    void retypingResetsTheValueAndDropsOptionsThatNoLongerApply() {
        ActivityVariable mode = new ActivityVariable("mode", ActivityType.CHOICE, JSON.textNode("safe"),
                "how careful", ParamVisibility.PUBLIC, List.of("fast", "safe"));

        ActivityVariable asNumber = mode.withType(ActivityType.INT);
        assertEquals(0, asNumber.value().asInt(-1), "a choice is not a number; don't pretend it carries over");
        assertEquals(List.of(), asNumber.options());
        assertEquals(ParamVisibility.PUBLIC, asNumber.visibility(), "who it is for doesn't change with the type");
        assertEquals("how careful", asNumber.description());

        assertEquals(List.of("fast", "safe"), mode.withType(ActivityType.MULTI_CHOICE).options(),
                "the choices survive a move between the two types that have any");
    }

    @Test
    void onlyTheChoiceTypesCarryOptions() {
        for (ActivityType type : ActivityType.values()) {
            boolean expected = type == ActivityType.CHOICE || type == ActivityType.MULTI_CHOICE;
            assertEquals(expected, type.hasOptions(), type + " should " + (expected ? "" : "not ") + "have options");
        }
        // Pruning is the identity for every other type: it is called on any option edit, and must not touch
        // the value of a param that has no options to prune against.
        assertTrue(ActivityType.INT.pruneValue(JSON.numberNode(42), List.of()).asInt(0) == 42);
    }
}
