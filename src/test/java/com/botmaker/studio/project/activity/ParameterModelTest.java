package com.botmaker.studio.project.activity;

import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueShape;
import com.botmaker.studio.palette.BotType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a project variable is, as a model: who it is <em>for</em>, where it is filed, and — for the choice
 * types — what it may be set to. All model rules, so all tested without a scene.
 */
class ParameterModelTest {

    private static ActivityVariable variable(String name, BotType type) {
        return ActivityVariable.create(name, oneOf(type));
    }

    /** One free value, in the contract's vocabulary — the enum still names the type the test is about. */
    private static ValueChoice oneOf(BotType type) {
        return BotType.Choice.of(type).toValue();
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

        ActivityVariable retyped = edited.withType(oneOf(BotType.COLOR));

        assertEquals(BotType.COLOR.name(), retyped.type().type().id());
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
        ActivityVariable mode = ActivityVariable.create("mode", chosenFrom(BotType.TEXT))
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
        assertEquals(chosenFrom(BotType.TEXT), read.type());
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

        assertEquals(oneOf(BotType.TEXT), read.type(), "text holds anything, so it is the default");
        assertEquals(List.of(""), read.value());
        assertEquals("", read.tag());
        assertEquals(List.of(), read.options());
    }

    @Test
    void deletingAChoiceUnsetsItWhereverItWasChosen() {
        // Otherwise the bot goes on running with a value that no longer appears anywhere in the UI that set
        // it — invisible, and therefore undebuggable.
        ActivityVariable single = ActivityVariable.create("mode", chosenFrom(BotType.TEXT))
                .withOptions(List.of("fast", "safe", "reckless")).withValue("reckless");

        assertEquals("fast", single.withOptions(List.of("fast", "safe")).singleValue(),
                "the deleted choice falls back to the first one still offered");

        ActivityVariable many = ActivityVariable.create("skills", manyOf(BotType.TEXT))
                .withOptions(List.of("mine", "fish", "cook")).withValue(List.of("mine", "fish"));

        assertEquals(List.of("mine"), many.withOptions(List.of("mine", "cook")).value());
    }

    @Test
    void aChosenListIsWrittenInTheDeclarationOrderAndNotThePickingOrder() {
        // Two people who ticked the same boxes must produce the same file, or a diff shows a change nobody
        // made.
        ActivityVariable skills = ActivityVariable.create("skills", manyOf(BotType.TEXT))
                .withOptions(List.of("mine", "fish", "cook"));

        assertEquals(List.of("mine", "cook"), skills.withValue(List.of("cook", "mine")).value());
    }

    /**
     * "Many of…" — several out of a set the author wrote down. {@link BotType.Choice#listOf} is the
     * <em>other</em> list shape, the open one a signature carries, and it declares no set at all.
     */
    private static ValueChoice manyOf(BotType type) {
        return new BotType.Choice(type, BotType.Shape.ANY_OF).toValue();
    }

    /** One value out of a written-down set. */
    private static ValueChoice chosenFrom(BotType type) {
        return new BotType.Choice(type, BotType.Shape.ONE_OF).toValue();
    }

    @Test
    void retypingResetsTheValueAndDropsOptionsThatNoLongerApply() {
        ActivityVariable mode = ActivityVariable.create("mode", chosenFrom(BotType.TEXT))
                .withDescription("how careful").withOptions(List.of("fast", "safe")).withValue("safe");

        ActivityVariable asNumber = mode.withType(oneOf(BotType.WHOLE_NUMBER));
        assertEquals("0", asNumber.singleValue(), "a choice is not a number; don't pretend it carries over");
        assertEquals(List.of(), asNumber.options());
        assertEquals(ParamVisibility.PUBLIC, asNumber.visibility(), "who it is for doesn't change with the type");
        assertEquals("how careful", asNumber.description());

        assertEquals(List.of("fast", "safe"),
                mode.withType(manyOf(BotType.TEXT)).options(),
                "the choices survive a move from one of them to several of them");
        assertEquals(List.of(), mode.withType(BotType.Choice.listOf(BotType.TEXT).toValue()).options(),
                "an open list declares no set, so there is nothing for the choices to survive into");
    }

    /** The types whose choices are their own: the editor never writes an SDK enum's constants down. */
    @Test
    void anEnumTypeBringsItsOwnChoicesAndSnapsToOne() {
        ActivityVariable key = variable("hotkey", BotType.KEY);

        assertFalse(ValueWire.hasOptions(oneOf(BotType.KEY)),
                "one value of an enum type: there is nothing for the editor to write down");
        assertFalse(VariableWire.fixedOptions(BotType.KEY).isEmpty());
        assertTrue(VariableWire.fixedOptions(BotType.KEY).contains(key.singleValue()));
        assertTrue(VariableWire.fixedOptions(BotType.KEY).contains(key.withValue("NOT_A_KEY").singleValue()),
                "a value the enum does not have falls back to one it does");
    }

    /**
     * Having a set of choices is a property of the <em>shape</em>, not of the type. That is the whole point of
     * the axis: it used to be true of one pseudo-type and false of the other twenty, which is exactly why
     * "one of these three whole numbers" could not be said.
     *
     * <p>The exception is a type that already <em>is</em> a set ({@link BotType#isClosedSet()}): its editor
     * shows every value it has, so it takes {@code ONE} and {@code ANY_OF} and no {@code ONE_OF}.
     *
     * <p>The two shapes that carry a set are the two closed ones. An open list is many values and no set —
     * which is the whole reason it is a shape of its own rather than an emptiness inside {@code ANY_OF}.
     */
    @Test
    void everyShapeableTypeCarriesOptionsInEveryShapeButOne() {
        for (BotType type : BotType.storableTypes()) {
            assertEquals(!type.isClosedSet(), type.shapeable(),
                    type + " offers One of… iff it is not already a set of its own");
            assertFalse(ValueWire.hasOptions(oneOf(type)), type + " as one free value");
            assertFalse(ValueWire.hasOptions(BotType.Choice.listOf(type).toValue()), type + " as an open list");
            assertTrue(ValueWire.hasOptions(manyOf(type)), type + " many of");
            if (!type.shapeable()) continue;
            assertTrue(ValueWire.hasOptions(chosenFrom(type)), type + " one of");
        }
    }

    /** Normalising is a fixed point for every storable type, or the editor and the file disagree forever. */
    @Test
    void normalisingTwiceChangesNothing() {
        for (BotType type : BotType.storableTypes()) {
            for (BotType.Shape shape : BotType.Shape.values()) {
                if (shape == BotType.Shape.ONE_OF && !type.shapeable()) continue;
                BotType.Choice choice = new BotType.Choice(type, shape);
                // A declared set has to be values of the type, or normalising them is what changes on the
                // second pass. Two of the type's own defaults is the one set every type can supply.
                List<String> options = shape.hasOptions()
                        ? VariableWire.normalizeOptions(VariableWire.defaultWire(choice), choice, Bounds.NONE)
                        : List.of();
                List<String> once = VariableWire.normalize(VariableWire.defaultWire(choice), choice, options,
                        Bounds.NONE);
                assertEquals(once, VariableWire.normalize(once, choice, options, Bounds.NONE), choice.toString());
            }
        }
    }

    /**
     * A project written before the shape axis existed opens without a migration step anyone can forget: the
     * type reader takes both spellings, and {@code CHOICE} — a constant this editor no longer has — is what
     * "text, one of a declared set" always meant.
     */
    @Test
    void aProjectFromBeforeTheShapeAxisStillOpens(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "variables": [
                    { "name": "mode", "type": { "type": "CHOICE", "list": false },
                      "options": ["fast", "safe"], "value": ["safe"] },
                    { "name": "skills", "type": { "type": "CHOICE", "list": true },
                      "options": ["mine", "cook"], "value": ["mine"] },
                    { "name": "count", "type": { "type": "WHOLE_NUMBER", "list": false }, "value": ["7"] },
                    { "name": "spots", "type": { "type": "POINT", "list": true }, "value": ["1,2"] }
                ] }
                """);

        List<ActivityVariable> read = ActivitiesConfig.read(dir).variables();

        assertEquals(chosenFrom(BotType.TEXT), read.get(0).type());
        assertEquals(List.of("fast", "safe"), read.get(0).options());
        assertEquals("safe", read.get(0).singleValue());

        // The two list shapes, told apart by the one thing the old file said about them: a set of choices was
        // written down for "skills" and none for "spots", so one is tick boxes and the other is rows the user
        // fills in — which is exactly how each of them was drawn before they were two shapes.
        assertEquals(manyOf(BotType.TEXT), read.get(1).type());
        assertEquals(List.of("mine"), read.get(1).value());

        assertEquals(oneOf(BotType.WHOLE_NUMBER), read.get(2).type());
        assertEquals(BotType.Choice.listOf(BotType.POINT).toValue(), read.get(3).type());
        assertEquals(ValueShape.OPEN_LIST, read.get(3).type().shape());
    }

    /**
     * A project written by the Studio that had one list shape, reopened by the one that has two. The stored
     * word is the same {@code ANY_OF} in both variables and only the choices tell them apart — so the rule
     * has to be read off the variable, not off its type, which is why it does not live in
     * {@code BotType.Choice}'s own reader.
     */
    @Test
    void aStoredListKeepsTheWidgetItUsedToBeDrawnWith(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ActivitiesConfig.FILE_NAME), """
                { "variables": [
                    { "name": "skills", "type": { "type": "TEXT", "shape": "ANY_OF" },
                      "options": ["mine", "cook"], "value": ["mine"] },
                    { "name": "notes", "type": { "type": "TEXT", "shape": "ANY_OF" },
                      "value": ["first", "second"] },
                    { "name": "ways", "type": { "type": "DIRECTION", "shape": "ANY_OF" }, "value": [] }
                ] }
                """);

        List<ActivityVariable> read = ActivitiesConfig.read(dir).variables();

        assertEquals(ValueShape.ANY_OF, read.get(0).type().shape(), "a set was written down: tick boxes");
        assertEquals(ValueShape.OPEN_LIST, read.get(1).type().shape(),
                "none was, so it was a free list and stays one");
        assertEquals(ValueShape.ANY_OF, read.get(2).type().shape(),
                "a closed set brings its own choices; nobody was ever going to write them down");
        assertEquals(List.of("first", "second"), read.get(1).value(),
                "and the values it already held are not pruned against a set it does not have");
    }

    /** The split is a label, not a type: both list shapes are the same {@code List<T>} a bot compiles. */
    @Test
    void bothListShapesSpellTheSameSource() {
        for (BotType type : BotType.storableTypes()) {
            assertEquals(BotType.Choice.listOf(type).toValue().sourceName(), manyOf(type).sourceName(),
                    type.toString());
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
                .withBounds(new Bounds("1", "5"))
                .withValue("42");

        assertEquals(List.of("5"), retries.value());
        assertEquals(List.of("1"), retries.withValue("-3").value());
        assertEquals(List.of("3"), retries.withValue("3").value(), "inside the range, nothing moves");
    }

    /**
     * Each end of a range stands on its own: "at most 10" and "at least 1" are sentences a person says, and
     * both used to be unsayable — the value editor only became a spinner once <em>both</em> ends were filled
     * in, so a one-sided range silently got the unguided text field.
     */
    @Test
    void eitherEndOfARangeCanBeDeclaredWithoutTheOther() {
        ActivityVariable atMost = variable("count", BotType.WHOLE_NUMBER).withBounds(new Bounds(null, "10"));
        assertEquals(List.of("10"), atMost.withValue("99").value());
        assertEquals(List.of("-500"), atMost.withValue("-500").value(), "no minimum means no floor");

        ActivityVariable atLeast = variable("count", BotType.WHOLE_NUMBER).withBounds(new Bounds("1", null));
        assertEquals(List.of("1"), atLeast.withValue("0").value());
        assertEquals(List.of("999999"), atLeast.withValue("999999").value(), "no maximum means no ceiling");

        assertFalse(new Bounds(null, "10").isEmpty(), "one end declared is a declared range");
        assertTrue(Bounds.NONE.isEmpty());
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

    /**
     * The side buttons are real values a bot can ask for, and the editor offers them because the SDK enum has
     * them — there is no second list of button names in Studio to fall out of step with it.
     *
     * <p>They are named by what they do rather than where they sit, which is what makes a bot that says
     * {@code BACK} keep working on a mouse whose back button is under a different thumb.
     */
    @Test
    void theSideButtonsAreOfferedBecauseTheSdkHasThem() {
        List<String> buttons = VariableWire.effectiveOptions(BotType.MOUSE_BUTTON, List.of());

        assertTrue(buttons.containsAll(List.of("LEFT", "MIDDLE", "RIGHT", "BACK", "FORWARD")), buttons.toString());
        assertEquals(List.of("BACK"), variable("button", BotType.MOUSE_BUTTON).withValue("BACK").value());
    }

    /**
     * A precision arrives as its three numbers, not as an enum constant. This is the shape the editor renders
     * fields for, and the reason the old dropdown was empty: {@code Precision} is a record, so asking it for
     * enum constants got an empty list and the slot rendered as a combo box with nothing in it.
     */
    @Test
    void aPrecisionIsThreeNumbersAndNotAConstant() {
        assertEquals(List.of(), ValueWire.effectiveOptions(oneOf(BotType.PRECISION).type(), List.of()));

        ActivityVariable precision = variable("precision", BotType.PRECISION);
        // The floor moved with the codec: the SDK's own WireText.precision clamps minArea to 1, where
        // Studio's copy clamped it to 4. The plugin that owns the type owns the number, which is the whole
        // point of the vocabulary moving — but it is a visible default change, not a rounding difference.
        assertEquals(List.of("12.0,1,0"), precision.value(), "the default is a usable tolerance, not blank");
        assertEquals(List.of("5.0,16,2"), precision.withValue("5,16,2").value());
        assertEquals(List.of("0.0,1,0"), precision.withValue("-3,0,-9").value(), "each number has its own floor");
    }

    /** Retyping drops the range with the value: a range for a number means nothing to the date replacing it. */
    @Test
    void retypingForgetsTheRange() {
        ActivityVariable retries = variable("retries", BotType.WHOLE_NUMBER).withBounds(new Bounds("1", "5"));

        assertEquals(Bounds.NONE, retries.withType(oneOf(BotType.DATE)).bounds());
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

    /**
     * The Variables screen writes a wire value into the user's own source, so {@link VariableWire#literalSource}
     * has to produce the literal the generated helper would have parsed. The pairs below are the ones that can
     * silently disagree: a duration whose wire grammar is Studio's alone, a colour whose helper is
     * {@code Color.decode}, geometry whose helper is a comma split.
     */
    @Test
    void aWireValueIsWrittenOutAsTheLiteralTheHelperWouldHaveParsed() {
        assertAll(
                () -> assertEquals("\"hello\"", VariableWire.literalSource(BotType.TEXT, "hello").source()),
                () -> assertEquals("true", VariableWire.literalSource(BotType.YES_NO, "true").source()),
                () -> assertEquals("7", VariableWire.literalSource(BotType.WHOLE_NUMBER, "7").source()),
                () -> assertEquals("Duration.ofMillis(5400000L)",
                        VariableWire.literalSource(BotType.DURATION, "1h30m").source()),
                () -> assertEquals("Color.decode(\"#FF0000\")",
                        VariableWire.literalSource(BotType.COLOR, "#FF0000").source()),
                () -> assertEquals("new Point(3, 4)",
                        VariableWire.literalSource(BotType.POINT, "3,4").source()),
                () -> assertEquals("new Rect(1, 2, 3, 4)",
                        VariableWire.literalSource(BotType.RECT, "1,2,3,4").source()),
                () -> assertEquals("MouseButton.BACK",
                        VariableWire.literalSource(BotType.MOUSE_BUTTON, "BACK").source()));
    }

    /**
     * The same clamps the generated {@code precision} helper applies, applied here — otherwise a value the bot
     * would have survived becomes source that throws in the user's own file, since {@code Precision}'s
     * constructor rejects a ΔE below zero and an area below one.
     */
    @Test
    void anImpossiblePrecisionIsClampedOnTheWayIntoSourceToo() {
        assertEquals("new Precision(0.0, 1, 0)",
                VariableWire.literalSource(BotType.PRECISION, "-3,0,-9").source());
    }

    /** Each literal carries the one import that makes it resolve; a plain literal carries none. */
    @Test
    void eachLiteralNamesTheImportItNeeds() {
        assertAll(
                () -> assertNull(VariableWire.literalSource(BotType.TEXT, "x").importFqn()),
                () -> assertEquals("java.awt.Color", VariableWire.literalSource(BotType.COLOR, "#FFF").importFqn()),
                () -> assertEquals("java.time.Duration", VariableWire.literalSource(BotType.DURATION, "2s").importFqn()),
                () -> assertTrue(VariableWire.literalSource(BotType.POINT, "0,0").importFqn().endsWith(".Point")));
    }

    /** A type with no written-down form has no literal either, rather than a wrong one. */
    @Test
    void aTypeThatIsNotStorableHasNoLiteral() {
        assertNull(VariableWire.literalSource(BotType.CAPTURE_SOURCE, "anything"));
    }
}
