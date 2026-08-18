package com.botmaker.studio.project.activity;

import com.botmaker.studio.palette.BotType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a project variable is, as a model: who it is <em>for</em>, where it is filed, and — for the choice
 * types — what it may be set to. All model rules, so all tested without a scene.
 */
class ParameterModelTest {

    private static ActivityVariable variable(String name, BotType type) {
        return ActivityVariable.create(name, BotType.Choice.of(type));
    }

    /**
     * A colour is a storable type in its own right — {@code java.awt.Color}, the JDK one the block editor's
     * colour picker already writes — and its wire form is {@code #RRGGBB}, upper case, alpha dropped.
     */
    @Test
    void aColourIsStoredAsUpperCaseHexAndFallsBackToWhite() {
        assertTrue(BotType.COLOR.storable());
        assertEquals(List.of("#FFFFFF"), variable("accent", BotType.COLOR).value());
        assertEquals(List.of("#1A2B3C"),
                variable("accent", BotType.COLOR).withValue("#1a2b3c").value());
        assertEquals(List.of("#1A2B3C"),
                variable("accent", BotType.COLOR).withValue("1a2b3c").value(), "the hash is optional");
        assertEquals(List.of("#FFFFFF"),
                variable("accent", BotType.COLOR).withValue("mauve").value(), "unreadable reads as white");
    }

    /** A fresh image variable names the template every project ships, not an empty chip nothing can run on. */
    @Test
    void aFreshImageVariablePointsAtTheDefaultTemplate() {
        assertEquals(List.of(com.botmaker.studio.services.ImageTemplateLibrary.DEFAULT_TEMPLATE_NAME),
                variable("target", BotType.IMAGE_TEMPLATE).value());
    }

    /**
     * Retyping resets the value to the new type's default rather than reinterpreting the old one — the rule
     * the Parameters dialog leans on when it throws the value widget away and builds the new type's.
     */
    @Test
    void retypingResetsTheValueEvenWhenItHadBeenEdited() {
        ActivityVariable edited = variable("gap", BotType.TEXT).withValue("hello");

        ActivityVariable retyped = edited.withType(BotType.Choice.of(BotType.COLOR));

        assertEquals(BotType.COLOR, retyped.type().type());
        assertEquals(List.of("#FFFFFF"), retyped.value());
    }

    @Test
    void aVariableNobodyHasThoughtAboutIsStillOfferedToTheUser() {
        // The default flipped with the tagged-variable model: a variable exists because someone wanted to
        // configure something, so the useful default is the one the person running the bot can see. Hiding
        // one is now the decision that has to be taken.
        assertEquals(ParamVisibility.PUBLIC, variable("retryDelay", BotType.WHOLE_NUMBER).visibility());
        assertTrue(variable("retryDelay", BotType.WHOLE_NUMBER).isPublic());
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
    void aVariableIsFiledUnderGeneralUntilItIsTagged() {
        // The tag is a display grouping and never a scope, so "no tag" has to be a real bucket rather than
        // an absence: a variable with no tag is still readable from every activity.
        assertEquals(ActivityVariable.GENERAL, variable("speed", BotType.WHOLE_NUMBER).tagOrGeneral());
        assertEquals("", variable("speed", BotType.WHOLE_NUMBER).tag());
        assertEquals("Mining", variable("speed", BotType.WHOLE_NUMBER).withTag("Mining").tagOrGeneral());
    }

    @Test
    void everythingASavedVariableCarriesSurvivesTheRoundTrip(@TempDir Path dir) throws Exception {
        ActivityVariable mode = ActivityVariable.create("mode", BotType.Choice.of(BotType.CHOICE))
                .withDescription("how careful to be")
                .withOptions(List.of("fast", "safe"))
                .withValue("safe")
                .withTag("Mining");
        ActivitiesConfig.of(List.of(ActivityDefinition.create("Mining", "")), List.of(mode)).write(dir);

        ActivityVariable read = ActivitiesConfig.read(dir).variables().getFirst();

        assertEquals(ParamVisibility.PUBLIC, read.visibility());
        assertEquals(List.of("fast", "safe"), read.options());
        assertEquals("safe", read.singleValue());
        assertEquals("Mining", read.tag());
        assertEquals("how careful to be", read.description());
        assertEquals(BotType.Choice.of(BotType.CHOICE), read.type());
    }

    /** A value is a list of strings on the wire, whatever the type — one entry, or one per item. */
    @Test
    void aValueIsStoredAsStringsAndReadBackThatWay(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "variables": [
                    { "name": "count", "type": { "type": "WHOLE_NUMBER", "list": false }, "value": ["7"] },
                    { "name": "skills", "type": { "type": "TEXT", "list": true }, "value": ["mine", "cook"] }
                ] }
                """);

        List<ActivityVariable> read = ActivitiesConfig.read(dir).variables();

        assertEquals("7", read.getFirst().singleValue());
        assertEquals(List.of(), read.getFirst().options());
        assertEquals(List.of("mine", "cook"), read.get(1).value());
    }

    /** A file that says nothing about a variable's shape still loads: every field has a total default. */
    @Test
    void aFileMissingEveryOptionalFieldStillLoads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "variables": [ { "name": "count" } ] }
                """);

        ActivityVariable read = ActivitiesConfig.read(dir).variables().getFirst();

        assertEquals(BotType.Choice.of(BotType.TEXT), read.type(), "text holds anything, so it is the default");
        assertEquals(List.of(""), read.value());
        assertEquals("", read.tag());
        assertEquals(List.of(), read.options());
    }

    @Test
    void deletingAChoiceUnsetsItWhereverItWasChosen() {
        // Otherwise the bot goes on running with a value that no longer appears anywhere in the UI that set
        // it — invisible, and therefore undebuggable.
        ActivityVariable single = ActivityVariable.create("mode", BotType.Choice.of(BotType.CHOICE))
                .withOptions(List.of("fast", "safe", "reckless")).withValue("reckless");

        assertEquals("fast", single.withOptions(List.of("fast", "safe")).singleValue(),
                "the deleted choice falls back to the first one still offered");

        ActivityVariable many = ActivityVariable.create("skills", new BotType.Choice(BotType.CHOICE, true))
                .withOptions(List.of("mine", "fish", "cook")).withValue(List.of("mine", "fish"));

        assertEquals(List.of("mine"), many.withOptions(List.of("mine", "cook")).value());
    }

    @Test
    void aChosenListIsWrittenInTheDeclarationOrderAndNotThePickingOrder() {
        // Two people who ticked the same boxes must produce the same file, or a diff shows a change nobody
        // made.
        ActivityVariable skills = ActivityVariable.create("skills", new BotType.Choice(BotType.CHOICE, true))
                .withOptions(List.of("mine", "fish", "cook"));

        assertEquals(List.of("mine", "cook"), skills.withValue(List.of("cook", "mine")).value());
    }

    @Test
    void retypingResetsTheValueAndDropsOptionsThatNoLongerApply() {
        ActivityVariable mode = ActivityVariable.create("mode", BotType.Choice.of(BotType.CHOICE))
                .withDescription("how careful").withOptions(List.of("fast", "safe")).withValue("safe");

        ActivityVariable asNumber = mode.withType(BotType.Choice.of(BotType.WHOLE_NUMBER));
        assertEquals("0", asNumber.singleValue(), "a choice is not a number; don't pretend it carries over");
        assertEquals(List.of(), asNumber.options());
        assertEquals(ParamVisibility.PUBLIC, asNumber.visibility(), "who it is for doesn't change with the type");
        assertEquals("how careful", asNumber.description());

        assertEquals(List.of("fast", "safe"),
                mode.withType(new BotType.Choice(BotType.CHOICE, true)).options(),
                "the choices survive a move onto the list axis");
    }

    /** The types whose choices are their own: the editor never writes an SDK enum's constants down. */
    @Test
    void anEnumTypeBringsItsOwnChoicesAndSnapsToOne() {
        ActivityVariable key = variable("hotkey", BotType.KEY);

        assertFalse(VariableWire.hasOptions(BotType.KEY), "there is nothing for the editor to edit");
        assertFalse(VariableWire.fixedOptions(BotType.KEY).isEmpty());
        assertTrue(VariableWire.fixedOptions(BotType.KEY).contains(key.singleValue()));
        assertTrue(VariableWire.fixedOptions(BotType.KEY).contains(key.withValue("NOT_A_KEY").singleValue()),
                "a value the enum does not have falls back to one it does");
    }

    @Test
    void onlyTheChoiceTypeCarriesEditableOptions() {
        for (BotType type : BotType.storableTypes()) {
            assertEquals(type == BotType.CHOICE, VariableWire.hasOptions(type),
                    type + " should " + (type == BotType.CHOICE ? "" : "not ") + "have editable options");
        }
    }

    /** Normalising is a fixed point for every storable type, or the editor and the file disagree forever. */
    @Test
    void normalisingTwiceChangesNothing() {
        for (BotType type : BotType.storableTypes()) {
            for (boolean list : new boolean[]{false, true}) {
                BotType.Choice choice = new BotType.Choice(type, list);
                List<String> options = type == BotType.CHOICE ? List.of("fast", "safe") : List.of();
                List<String> once = VariableWire.normalize(VariableWire.defaultWire(choice), choice, options,
                        Bounds.NONE);
                assertEquals(once, VariableWire.normalize(once, choice, options, Bounds.NONE), choice.toString());
            }
        }
    }

    @Test
    void aVariableAndAnActivityCannotShareAName() {
        // Both become a field on the same generated class, so the clash is a project that saves and then
        // will not compile.
        ActivitiesConfig config = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", "")),
                List.of(variable("speed", BotType.WHOLE_NUMBER)));

        assertTrue(config.nameClash("Mining", null));
        assertTrue(config.nameClash("mining", null), "the stub files are named after activities");
        assertTrue(config.nameClash("speed", null));
        assertFalse(config.nameClash("speed", "speed"), "renaming a variable to itself is not a clash");
        assertFalse(config.nameClash("depth", null));
    }

    @Test
    void theRunnerIsOfferedThePublicVariablesGroupedByTag() {
        ActivityVariable speed = variable("speed", BotType.WHOLE_NUMBER);
        ActivityVariable retryDelay = variable("retryDelay", BotType.WHOLE_NUMBER)
                .withVisibility(ParamVisibility.EDITOR_ONLY);
        ActivityVariable ore = variable("ore", BotType.TEXT).withTag("Mining");

        ActivitiesConfig config = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", "")), List.of(speed, retryDelay, ore));

        var shared = config.sharedVariables();

        assertEquals(List.of("General", "Mining"), List.copyOf(shared.keySet()));
        assertEquals(List.of("speed"), shared.get("General").stream().map(ActivityVariable::name).toList(),
                "the editor-only one stays with the editor");
        assertEquals(List.of("ore"), shared.get("Mining").stream().map(ActivityVariable::name).toList());
    }

    /**
     * A declared range is a clamp, never a refusal: a value outside it is pulled to the nearest bound rather
     * than making the project unsaveable because somebody tightened a limit after the fact.
     */
    @Test
    void aDeclaredRangePullsAStoredValueBackIntoIt() {
        ActivityVariable retries = variable("retries", BotType.WHOLE_NUMBER)
                .withBounds(new Bounds("1", "5", "1"))
                .withValue("42");

        assertEquals(List.of("5"), retries.value());
        assertEquals(List.of("1"), retries.withValue("-3").value());
        assertEquals(List.of("3"), retries.withValue("3").value(), "inside the range, nothing moves");
    }

    /**
     * Only the two number types have a range, and the predicate that says so lives with the clamp — the
     * dialog offering a range and the code enforcing one must not come to disagree about which types have one.
     */
    @Test
    void onlyTheNumbersAreBounded() {
        for (BotType type : BotType.storableTypes()) {
            assertEquals(type == BotType.WHOLE_NUMBER || type == BotType.DECIMAL_NUMBER,
                    VariableWire.isBounded(type), type.toString());
        }
    }

    /** Retyping drops the range with the value: a range for a number means nothing to the date replacing it. */
    @Test
    void retypingForgetsTheRange() {
        ActivityVariable retries = variable("retries", BotType.WHOLE_NUMBER).withBounds(new Bounds("1", "5", null));

        assertEquals(Bounds.NONE, retries.withType(BotType.Choice.of(BotType.DATE)).bounds());
    }

    /** An archived activity contributes no enable flag: a switch for something that cannot run. */
    @Test
    void everyActivityContributesAnEnableFlagAheadOfTheVariables() {
        ActivitiesConfig config = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", ""),
                        ActivityDefinition.create("Smelting", "")),
                List.of(variable("speed", BotType.WHOLE_NUMBER)));

        assertEquals(List.of("Mining", "Smelting", "speed"),
                config.allVariables().stream().map(ActivityVariable::name).toList());
    }
}
