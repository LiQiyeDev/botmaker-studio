package com.botmaker.studio.project.activity;

import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueShape;
import com.botmaker.plugin.api.value.ValueType;
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
 *
 * <p>The types are looked up by {@linkplain ValueType#id() id} rather than named as enum constants: since
 * phase 10b the vocabulary is the contract's open registry, so a type is a thing a plugin registered and the
 * id is what {@code activities.json} holds either way.
 */
class ParameterModelTest {

    private static final ValueType TEXT = ValueWire.type("TEXT");
    private static final ValueType WHOLE_NUMBER = ValueWire.type("WHOLE_NUMBER");
    private static final ValueType DECIMAL_NUMBER = ValueWire.type("DECIMAL_NUMBER");
    private static final ValueType YES_NO = ValueWire.type("YES_NO");
    private static final ValueType COLOR = ValueWire.type("COLOR");
    private static final ValueType DATE = ValueWire.type("DATE");
    private static final ValueType DURATION = ValueWire.type("DURATION");
    private static final ValueType POINT = ValueWire.type("POINT");
    private static final ValueType RECT = ValueWire.type("RECT");
    private static final ValueType KEY = ValueWire.type("KEY");
    private static final ValueType MOUSE_BUTTON = ValueWire.type("MOUSE_BUTTON");
    private static final ValueType DIRECTION = ValueWire.type("DIRECTION");
    private static final ValueType PRECISION = ValueWire.type("PRECISION");
    private static final ValueType IMAGE_TEMPLATE = ValueWire.type("IMAGE_TEMPLATE");

    private static ActivityVariable variable(String name, ValueType type) {
        return ActivityVariable.create(name, oneOf(type));
    }

    /** One free value. */
    private static ValueChoice oneOf(ValueType type) {
        return ValueChoice.of(type);
    }

    /**
     * A colour is a storable type in its own right — {@code java.awt.Color}, the JDK one the block editor's
     * colour picker already writes — and its wire form is {@code #RRGGBB}, upper case, alpha dropped.
     */
    @Test
    void aColourIsStoredAsUpperCaseHexAndFallsBackToWhite() {
        assertTrue(COLOR.known(), "some plugin has to have registered it");
        assertEquals(List.of("#FFFFFF"), variable("accent", COLOR).value());
        assertEquals(List.of("#1A2B3C"),
                variable("accent", COLOR).withValue("#1a2b3c").value());
        assertEquals(List.of("#1A2B3C"),
                variable("accent", COLOR).withValue("1a2b3c").value(), "the hash is optional");
        assertEquals(List.of("#FFFFFF"),
                variable("accent", COLOR).withValue("mauve").value(), "unreadable reads as white");
    }

    /**
     * A type no loaded plugin registers keeps its stored text rather than being coerced or dropped.
     *
     * <p>This asserted the SDK's own default template name until 2026-09-02. It cannot any more, and the
     * reason is the assertion: Studio bundles no plugin, so in a headless test nothing registers
     * {@code IMAGE_TEMPLATE} and it resolves to {@link ValueType#unknown}. That is the ordinary state of a
     * project opened without one of its plugins, and what matters about it is that the value survives —
     * an unknown type renders read-only and declines to emit, it never loses what the file said.
     */
    @Test
    void aTypeNoPluginRegistersKeepsItsStoredValue() {
        assertEquals(List.of("ore.png"), variable("target", IMAGE_TEMPLATE).withValue("ore.png").value());
    }

    /**
     * Retyping resets the value to the new type's default rather than reinterpreting the old one — the rule
     * the Parameters dialog leans on when it throws the value widget away and builds the new type's.
     */
    @Test
    void retypingResetsTheValueEvenWhenItHadBeenEdited() {
        ActivityVariable edited = variable("gap", TEXT).withValue("hello");

        ActivityVariable retyped = edited.withType(oneOf(COLOR));

        assertEquals(COLOR.id(), retyped.type().type().id());
        assertEquals(List.of("#FFFFFF"), retyped.value());
    }

    @Test
    void aVariableNobodyHasThoughtAboutIsStillOfferedToTheUser() {
        // The default flipped with the tagged-variable model: a variable exists because someone wanted to
        // configure something, so the useful default is the one the person running the bot can see. Hiding
        // one is now the decision that has to be taken.
        assertEquals(ParamVisibility.PUBLIC, variable("retryDelay", WHOLE_NUMBER).visibility());
        assertTrue(variable("retryDelay", WHOLE_NUMBER).isPublic());
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
        assertEquals(ActivityVariable.GENERAL, variable("speed", WHOLE_NUMBER).tagOrGeneral());
        assertEquals("", variable("speed", WHOLE_NUMBER).tag());
        assertEquals("Mining", variable("speed", WHOLE_NUMBER).withTag("Mining").tagOrGeneral());
    }

    @Test
    void everythingASavedVariableCarriesSurvivesTheRoundTrip(@TempDir Path dir) throws Exception {
        ActivityVariable mode = ActivityVariable.create("mode", chosenFrom(TEXT))
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
        assertEquals(chosenFrom(TEXT), read.type());
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

        assertEquals(oneOf(TEXT), read.type(), "text holds anything, so it is the default");
        assertEquals(List.of(""), read.value());
        assertEquals("", read.tag());
        assertEquals(List.of(), read.options());
    }

    @Test
    void deletingAChoiceUnsetsItWhereverItWasChosen() {
        // Otherwise the bot goes on running with a value that no longer appears anywhere in the UI that set
        // it — invisible, and therefore undebuggable.
        ActivityVariable single = ActivityVariable.create("mode", chosenFrom(TEXT))
                .withOptions(List.of("fast", "safe", "reckless")).withValue("reckless");

        assertEquals("fast", single.withOptions(List.of("fast", "safe")).singleValue(),
                "the deleted choice falls back to the first one still offered");

        ActivityVariable many = ActivityVariable.create("skills", manyOf(TEXT))
                .withOptions(List.of("mine", "fish", "cook")).withValue(List.of("mine", "fish"));

        assertEquals(List.of("mine"), many.withOptions(List.of("mine", "cook")).value());
    }

    @Test
    void aChosenListIsWrittenInTheDeclarationOrderAndNotThePickingOrder() {
        // Two people who ticked the same boxes must produce the same file, or a diff shows a change nobody
        // made.
        ActivityVariable skills = ActivityVariable.create("skills", manyOf(TEXT))
                .withOptions(List.of("mine", "fish", "cook"));

        assertEquals(List.of("mine", "cook"), skills.withValue(List.of("cook", "mine")).value());
    }

    /**
     * "Many of…" — several out of a set the author wrote down. {@link ValueChoice#listOf} is the
     * <em>other</em> list shape, the open one a signature carries, and it declares no set at all.
     */
    private static ValueChoice manyOf(ValueType type) {
        return new ValueChoice(type, ValueShape.ANY_OF);
    }

    /** One value out of a written-down set. */
    private static ValueChoice chosenFrom(ValueType type) {
        return new ValueChoice(type, ValueShape.ONE_OF);
    }

    @Test
    void retypingResetsTheValueAndDropsOptionsThatNoLongerApply() {
        ActivityVariable mode = ActivityVariable.create("mode", chosenFrom(TEXT))
                .withDescription("how careful").withOptions(List.of("fast", "safe")).withValue("safe");

        ActivityVariable asNumber = mode.withType(oneOf(WHOLE_NUMBER));
        assertEquals("0", asNumber.singleValue(), "a choice is not a number; don't pretend it carries over");
        assertEquals(List.of(), asNumber.options());
        assertEquals(ParamVisibility.PUBLIC, asNumber.visibility(), "who it is for doesn't change with the type");
        assertEquals("how careful", asNumber.description());

        assertEquals(List.of("fast", "safe"),
                mode.withType(manyOf(TEXT)).options(),
                "the choices survive a move from one of them to several of them");
        assertEquals(List.of(), mode.withType(ValueChoice.listOf(TEXT)).options(),
                "an open list declares no set, so there is nothing for the choices to survive into");
    }

    /** The types whose choices are their own: the editor never writes an SDK enum's constants down. */
    @Test
    void anEnumTypeBringsItsOwnChoicesAndSnapsToOne() {
        ActivityVariable key = variable("hotkey", KEY);

        assertFalse(ValueWire.hasOptions(oneOf(KEY)),
                "one value of an enum type: there is nothing for the editor to write down");
        assertFalse(ValueWire.fixedOptions(KEY).isEmpty());
        assertTrue(ValueWire.fixedOptions(KEY).contains(key.singleValue()));
        assertTrue(ValueWire.fixedOptions(KEY).contains(key.withValue("NOT_A_KEY").singleValue()),
                "a value the enum does not have falls back to one it does");
    }

    /**
     * Having a set of choices is a property of the <em>shape</em>, not of the type. That is the whole point of
     * the axis: it used to be true of one pseudo-type and false of the other twenty, which is exactly why
     * "one of these three whole numbers" could not be said.
     *
     * <p>The exception is a type that already <em>is</em> a set ({@link ValueType#isClosedSet()}): its editor
     * shows every value it has, so it takes {@code ONE} and {@code ANY_OF} and no {@code ONE_OF}.
     *
     * <p>The two shapes that carry a set are the two closed ones. An open list is many values and no set —
     * which is the whole reason it is a shape of its own rather than an emptiness inside {@code ANY_OF}.
     */
    @Test
    void everyShapeableTypeCarriesOptionsInEveryShapeButOne() {
        for (ValueType type : ValueWire.registered()) {
            assertEquals(!type.isClosedSet(), type.shapeable(),
                    type + " offers One of… iff it is not already a set of its own");
            assertFalse(ValueWire.hasOptions(oneOf(type)), type + " as one free value");
            assertFalse(ValueWire.hasOptions(ValueChoice.listOf(type)), type + " as an open list");
            assertTrue(ValueWire.hasOptions(manyOf(type)), type + " many of");
            if (!type.shapeable()) continue;
            assertTrue(ValueWire.hasOptions(chosenFrom(type)), type + " one of");
        }
    }

    /** Normalising is a fixed point for every storable type, or the editor and the file disagree forever. */
    @Test
    void normalisingTwiceChangesNothing() {
        for (ValueType type : ValueWire.registered()) {
            for (ValueShape shape : ValueShape.values()) {
                if (shape == ValueShape.ONE_OF && !type.shapeable()) continue;
                ValueChoice choice = new ValueChoice(type, shape);
                // A declared set has to be values of the type, or normalising them is what changes on the
                // second pass. Two of the type's own defaults is the one set every type can supply.
                List<String> options = shape.hasOptions()
                        ? ValueWire.normalizeOptions(ValueWire.defaultWire(choice), choice, Bounds.NONE)
                        : List.of();
                List<String> once = ValueWire.normalize(ValueWire.defaultWire(choice), choice, options,
                        Bounds.NONE);
                assertEquals(once, ValueWire.normalize(once, choice, options, Bounds.NONE), choice.toString());
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

        assertEquals(chosenFrom(TEXT), read.get(0).type());
        assertEquals(List.of("fast", "safe"), read.get(0).options());
        assertEquals("safe", read.get(0).singleValue());

        // The two list shapes, told apart by the one thing the old file said about them: a set of choices was
        // written down for "skills" and none for "spots", so one is tick boxes and the other is rows the user
        // fills in — which is exactly how each of them was drawn before they were two shapes.
        assertEquals(manyOf(TEXT), read.get(1).type());
        assertEquals(List.of("mine"), read.get(1).value());

        assertEquals(oneOf(WHOLE_NUMBER), read.get(2).type());
        assertEquals(ValueChoice.listOf(POINT), read.get(3).type());
        assertEquals(ValueShape.OPEN_LIST, read.get(3).type().shape());
    }

    /**
     * A project written by the Studio that had one list shape, reopened by the one that has two. The stored
     * word is the same {@code ANY_OF} in both variables and only the choices tell them apart — so the rule
     * has to be read off the variable, not off its type, which is why it does not live in
     * {@code ValueChoice}'s own reader.
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
        for (ValueType type : ValueWire.registered()) {
            assertEquals(ValueChoice.listOf(type).sourceName(), manyOf(type).sourceName(), type.toString());
        }
    }

    @Test
    void aVariableAndAnActivityCannotShareAName() {
        // Both become a field on the same generated class, so the clash is a project that saves and then
        // will not compile.
        ActivitiesConfig config = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", "")),
                List.of(variable("speed", WHOLE_NUMBER)));

        assertTrue(config.nameClash("Mining", null));
        assertTrue(config.nameClash("mining", null), "the stub files are named after activities");
        assertTrue(config.nameClash("speed", null));
        assertFalse(config.nameClash("speed", "speed"), "renaming a variable to itself is not a clash");
        assertFalse(config.nameClash("depth", null));
    }

    @Test
    void theNamespaceIsThePluginSectionAndNotTheProject() {
        // Phase 11: a name has to be unique inside its own plugin's section, because each section becomes
        // its own generated class. Two plugins may both call something "timeout" and neither shadows the
        // other — which the flat namespace made impossible.
        ActivitiesConfig config = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", "")),
                List.of(variable("speed", WHOLE_NUMBER),
                        variable("speed", WHOLE_NUMBER).withGroup("discord")));

        assertTrue(config.nameClash("speed", null), "the default section's own name is taken");
        assertTrue(config.nameClash("speed", null, "discord"), "and so is the other section's");
        assertFalse(config.nameClash("speed", null, "steam"), "a third plugin's namespace is empty");
        assertTrue(config.nameClash("Mining", null, "discord"),
                "the activity stubs are the host's — one set, in every section");
        assertEquals(List.of("speed"),
                config.variablesIn("discord").stream().map(ActivityVariable::name).toList());
        assertEquals(List.of("", "discord"), config.variableGroups());
    }

    @Test
    void aVariableWithNoSectionBelongsToTheDefaultPlugin() {
        // Every variable in every project written before sections existed. The absent field reads as the
        // SDK's group, which is what makes the partition need no migration.
        ActivityVariable v = variable("speed", WHOLE_NUMBER);

        assertEquals("", v.group());
        assertTrue(v.isIn(null));
        assertTrue(v.isIn(""));
        assertFalse(v.isIn("discord"));
    }

    @Test
    void theRunnerIsOfferedThePublicVariablesGroupedByTag() {
        ActivityVariable speed = variable("speed", WHOLE_NUMBER);
        ActivityVariable retryDelay = variable("retryDelay", WHOLE_NUMBER)
                .withVisibility(ParamVisibility.EDITOR_ONLY);
        ActivityVariable ore = variable("ore", TEXT).withTag("Mining");

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
        ActivityVariable retries = variable("retries", WHOLE_NUMBER)
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
        ActivityVariable atMost = variable("count", WHOLE_NUMBER).withBounds(new Bounds(null, "10"));
        assertEquals(List.of("10"), atMost.withValue("99").value());
        assertEquals(List.of("-500"), atMost.withValue("-500").value(), "no minimum means no floor");

        ActivityVariable atLeast = variable("count", WHOLE_NUMBER).withBounds(new Bounds("1", null));
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
        for (ValueType type : ValueWire.registered()) {
            assertEquals(type.id().equals(WHOLE_NUMBER.id()) || type.id().equals(DECIMAL_NUMBER.id()),
                    ValueWire.isBounded(type), type.toString());
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
        List<String> buttons = ValueWire.effectiveOptions(MOUSE_BUTTON, List.of());

        assertTrue(buttons.containsAll(List.of("LEFT", "MIDDLE", "RIGHT", "BACK", "FORWARD")), buttons.toString());
        assertEquals(List.of("BACK"), variable("button", MOUSE_BUTTON).withValue("BACK").value());
    }

    /**
     * A precision arrives as its three numbers, not as an enum constant. This is the shape the editor renders
     * fields for, and the reason the old dropdown was empty: {@code Precision} is a record, so asking it for
     * enum constants got an empty list and the slot rendered as a combo box with nothing in it.
     */
    @Test
    void aPrecisionIsThreeNumbersAndNotAConstant() {
        assertEquals(List.of(), ValueWire.effectiveOptions(PRECISION, List.of()));

        ActivityVariable precision = variable("precision", PRECISION);
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
        ActivityVariable retries = variable("retries", WHOLE_NUMBER).withBounds(new Bounds("1", "5"));

        assertEquals(Bounds.NONE, retries.withType(oneOf(DATE)).bounds());
    }

    /** An archived activity contributes no enable flag: a switch for something that cannot run. */
    @Test
    void everyActivityContributesAnEnableFlagAheadOfTheVariables() {
        ActivitiesConfig config = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", ""),
                        ActivityDefinition.create("Smelting", "")),
                List.of(variable("speed", WHOLE_NUMBER)));

        assertEquals(List.of("Mining", "Smelting", "speed"),
                config.allVariables().stream().map(ActivityVariable::name).toList());
    }

    /**
     * The Variables screen writes a wire value into the user's own source, so {@link ValueWire#literalSource}
     * has to produce the literal the generated helper would have parsed. The pairs below are the ones that can
     * silently disagree: a duration whose wire grammar is Studio's alone, a colour, geometry whose helper is
     * a comma split.
     *
     * <p>Since the vocabulary moved to the contract these are the <em>codec's</em> literals, and a codec writes
     * the parsed value rather than the text — {@code new java.awt.Color(255, 0, 0)} and never
     * {@code Color.decode("#FF0000")}, which parses at class initialisation and can throw. A type whose codec
     * spells its literal in full needs no import, which is what the sibling test below now reads.
     */
    @Test
    void aWireValueIsWrittenOutAsTheLiteralTheHelperWouldHaveParsed() {
        assertAll(
                () -> assertEquals("\"hello\"", ValueWire.literalSource(TEXT, "hello").source()),
                () -> assertEquals("true", ValueWire.literalSource(YES_NO, "true").source()),
                () -> assertEquals("7", ValueWire.literalSource(WHOLE_NUMBER, "7").source()),
                () -> assertEquals("java.time.Duration.ofMillis(5400000L)",
                        ValueWire.literalSource(DURATION, "1h30m").source()),
                () -> assertEquals("new java.awt.Color(255, 0, 0)",
                        ValueWire.literalSource(COLOR, "#FF0000").source()),
                () -> assertEquals("new Point(3, 4)",
                        ValueWire.literalSource(POINT, "3,4").source()),
                () -> assertEquals("new Rect(1, 2, 3, 4)",
                        ValueWire.literalSource(RECT, "1,2,3,4").source()),
                () -> assertEquals("MouseButton.BACK",
                        ValueWire.literalSource(MOUSE_BUTTON, "BACK").source()));
    }

    /**
     * The same clamps the generated {@code precision} helper applies, applied here — otherwise a value the bot
     * would have survived becomes source that throws in the user's own file, since {@code Precision}'s
     * constructor rejects a ΔE below zero and an area below one.
     */
    @Test
    void anImpossiblePrecisionIsClampedOnTheWayIntoSourceToo() {
        assertEquals("new Precision(0.0, 1, 0)",
                ValueWire.literalSource(PRECISION, "-3,0,-9").source());
    }

    /**
     * Each literal carries the one import that makes it resolve, and only where it needs one: a plain literal
     * carries none, and neither does one a codec already spelled in full. What must never happen is a literal
     * naming a simple name with nothing to resolve it — which is the SDK's own types, whose codecs write
     * {@code new Point(3, 4)} because a generated file imports them.
     */
    @Test
    void eachLiteralNamesTheImportItNeeds() {
        assertAll(
                () -> assertNull(ValueWire.literalSource(TEXT, "x").importFqn()),
                () -> assertNull(ValueWire.literalSource(COLOR, "#FFF").importFqn()),
                () -> assertNull(ValueWire.literalSource(DURATION, "2s").importFqn()),
                () -> assertTrue(ValueWire.literalSource(POINT, "0,0").importFqn().endsWith(".Point")));
    }

    /**
     * A type nobody registered has no literal either, rather than a wrong one — which is the ordinary state of
     * a project opened without one of its plugins, not an error.
     */
    @Test
    void aTypeNoPluginRegisteredHasNoLiteral() {
        ValueType nobodys = ValueWire.type("CAPTURE_SOURCE");
        assertFalse(nobodys.known());
        assertNull(ValueWire.literalSource(nobodys, "anything"));
    }

    /** DIRECTION is a closed set: it brings its own choices and declines a written-down subset. */
    @Test
    void aClosedSetTypeIsNotOfferedASubset() {
        assertTrue(DIRECTION.isClosedSet());
        assertFalse(DIRECTION.shapeable());
        assertEquals(ValueShape.ONE, new ValueChoice(DIRECTION, ValueShape.ONE_OF).shape(),
                "the record corrects an impossible pairing rather than refusing the file that holds it");
    }
}
