package com.botmaker.studio.project.settings;

import com.botmaker.studio.palette.SdkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings model is the store now: a value that does not survive being written and read back is a value
 * the editor loses. These are the guards for that — round-trip stability, the exact literal each type emits,
 * and the promise that no wire value can produce source that does not compile.
 */
class SettingTypeTest {

    private static final Setting.Bounds NO_BOUNDS = Setting.Bounds.NONE;

    // ---- round-trip -----------------------------------------------------------------------------------

    /**
     * Normalising is idempotent for every type. This is what lets the writer normalise on the way out and
     * the reader normalise on the way in without the file flipping between two spellings of one value.
     */
    @ParameterizedTest
    @EnumSource(SettingType.class)
    void normalizingACanonicalValueChangesNothing(SettingType type) {
        List<String> options = type.hasOptions() ? List.of("Iron", "Gold") : List.of();
        List<String> once = type.normalize(type.defaultWire(), options, NO_BOUNDS);
        List<String> twice = type.normalize(once, options, NO_BOUNDS);

        assertEquals(once, twice, type + " does not settle after one normalisation");
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void everyTypeRendersALiteralForItsDefault(SettingType type) {
        String literal = type.literal(type.normalize(type.defaultWire(), List.of("Iron"), NO_BOUNDS));

        assertNotNull(literal, type + " renders no literal");
        assertFalse(literal.isBlank(), type + " renders a blank literal");
    }

    /** Garbage in a stored value must never reach the generator — it degrades to the type's own default. */
    @ParameterizedTest
    @EnumSource(SettingType.class)
    void garbageDegradesToSomethingRenderable(SettingType type) {
        List<String> options = type.hasOptions() ? List.of("Iron", "Gold") : List.of();
        List<String> normalized = type.normalize(List.of("¯\\_(ツ)_/¯"), options, NO_BOUNDS);

        assertEquals(normalized, type.normalize(normalized, options, NO_BOUNDS));
        assertFalse(type.literal(normalized).isBlank(), type + " cannot render its fallback");
    }

    // ---- the literal each type emits ------------------------------------------------------------------

    @Test
    void theSimpleTypesEmitTheLiteralsTheTableSays() {
        assertEquals("true", SettingType.BOOL.literal(List.of("true")));
        assertEquals("true", SettingType.ENABLE.literal(List.of("true")));
        assertEquals("3", SettingType.INT.literal(List.of("3")));
        assertEquals("0.75d", SettingType.DOUBLE.literal(List.of("0.75")));
        assertEquals("\"Iron\"", SettingType.TEXT.literal(List.of("Iron")));
        assertEquals("\"Gold\"", SettingType.CHOICE.literal(List.of("Gold")));
    }

    @Test
    void theTimeTypesEmitFactoryCallsRatherThanParsedText() {
        assertEquals("java.time.LocalTime.of(7, 30)", SettingType.TIME.literal(List.of("07:30")));
        assertEquals("java.time.LocalTime.of(7, 30, 15)", SettingType.TIME.literal(List.of("07:30:15")));
        assertEquals("java.time.LocalDate.of(2026, 8, 17)", SettingType.DATE.literal(List.of("2026-08-17")));
    }

    @Test
    void aDurationBecomesMillisecondsAtGenerationTime() {
        assertEquals("java.time.Duration.ofMillis(90000L)", SettingType.DURATION.literal(List.of("90s")));
        assertEquals("java.time.Duration.ofMillis(90000L)", SettingType.DURATION.literal(List.of("1m30s")));
        assertEquals("java.time.Duration.ofMillis(0L)", SettingType.DURATION.literal(List.of("nonsense")));
    }

    @Test
    void multiChoiceEmitsAnImmutableList() {
        assertEquals("java.util.List.of()", SettingType.MULTI_CHOICE.literal(List.of()));
        assertEquals("java.util.List.of(\"a\", \"b\")", SettingType.MULTI_CHOICE.literal(List.of("a", "b")));
    }

    /** The choices are the declaration's, in the declaration's order — not the file's. */
    @Test
    void multiChoiceKeepsTheDeclaredOrderAndDropsWhatIsNoLongerOffered() {
        List<String> chosen = SettingType.MULTI_CHOICE.normalize(
                List.of("Coal", "Iron", "Mithril"), List.of("Iron", "Gold", "Coal"), NO_BOUNDS);

        assertEquals(List.of("Iron", "Coal"), chosen);
    }

    @Test
    void aChoiceThatIsNoLongerOfferedFallsBackToTheFirstOne() {
        assertEquals(List.of("Iron"), SettingType.CHOICE.normalize(List.of("Mithril"), List.of("Iron", "Gold"), NO_BOUNDS));
        assertEquals(List.of("Gold"), SettingType.CHOICE.normalize(List.of("Gold"), List.of("Iron", "Gold"), NO_BOUNDS));
    }

    // ---- the SDK-enum types ---------------------------------------------------------------------------

    /**
     * The option list for these two is the SDK's own enum, read off the class literal in {@code SdkType} —
     * so a constant added to the SDK appears in the picker with no Studio edit.
     */
    @Test
    void theEnumTypesTakeTheirOptionsFromTheSdk() {
        assertEquals(SdkType.KEY.enumConstantNames(), SettingType.KEY.fixedOptions());
        assertEquals(SdkType.MOUSE_BUTTON.enumConstantNames(), SettingType.MOUSE_BUTTON.fixedOptions());
        assertFalse(SettingType.KEY.fixedOptions().isEmpty(), "the SDK's Key enum has no constants");
    }

    @Test
    void anUnknownEnumConstantFallsBackToARealOne() {
        String fallback = SdkType.KEY.enumConstantNames().getFirst();
        assertEquals(List.of(fallback), SettingType.KEY.normalize(List.of("NOT_A_KEY"), List.of(), NO_BOUNDS));

        String real = SdkType.KEY.enumConstantNames().getLast();
        assertEquals(SdkType.KEY.qualifiedName() + "." + real, SettingType.KEY.literal(List.of(real)));
    }

    // ---- templates ------------------------------------------------------------------------------------

    @Test
    void aTemplateGoesThroughItsGeneratedConstant() {
        assertEquals("new com.botmaker.sdk.api.vision.ImageTemplate(Templates.ORE)",
                SettingType.TEMPLATE.literal(List.of("ore")));
    }

    /** A name too old to have a constant keeps working, as a path — the coexistence TemplateConstants allows. */
    @Test
    void aTemplateWithNoConstantFallsBackToItsPath() {
        assertEquals("new com.botmaker.sdk.api.vision.ImageTemplate(\"src/main/resources/images/My-Old.png\")",
                SettingType.TEMPLATE.literal(List.of("My-Old")));
    }

    // ---- bounds ---------------------------------------------------------------------------------------

    @Test
    void aNumberOutsideItsRangeIsPulledToTheNearestBound() {
        Setting.Bounds oneToTen = new Setting.Bounds("1", "10", null);

        assertEquals(List.of("10"), SettingType.INT.normalize(List.of("99"), List.of(), oneToTen));
        assertEquals(List.of("1"), SettingType.INT.normalize(List.of("-5"), List.of(), oneToTen));
        assertEquals(List.of("7"), SettingType.INT.normalize(List.of("7"), List.of(), oneToTen));
    }

    @Test
    void aDurationBoundIsWrittenTheWayADurationIs() {
        Setting.Bounds atLeastThirtySeconds = new Setting.Bounds("30s", null, null);

        assertEquals(List.of("30s"), SettingType.DURATION.normalize(List.of("5s"), List.of(), atLeastThirtySeconds));
        assertEquals(List.of("1m"), SettingType.DURATION.normalize(List.of("60s"), List.of(), atLeastThirtySeconds));
    }

    @Test
    void aHalfOpenRangeClampsOnlyTheSideItDeclares() {
        assertEquals(List.of("1000"), SettingType.INT.normalize(
                List.of("99999"), List.of(), new Setting.Bounds(null, "1000", null)));
        assertEquals(List.of("99999"), SettingType.INT.normalize(
                List.of("99999"), List.of(), new Setting.Bounds("1", null, null)));
    }

    // ---- parsing an id --------------------------------------------------------------------------------

    /**
     * Unlike every other parse in this codebase, this one is not total — and deliberately so: the reader has
     * to be able to tell "a type I have never heard of" from "text", or a newer Studio's setting is silently
     * retyped the next time the file is saved.
     */
    @Test
    void anUnknownTypeIdIsNullRatherThanADefault() {
        assertEquals(SettingType.DURATION, SettingType.fromId("DURATION"));
        assertEquals(SettingType.DURATION, SettingType.fromId(" duration "));
        assertNull(SettingType.fromId("COLOUR_PICKER"));
        assertNull(SettingType.fromId(null));
    }

    @Test
    void theEnableFlagIsNotATypeAnEditorCanChoose() {
        assertFalse(SettingType.selectable().contains(SettingType.ENABLE));
        assertTrue(SettingType.selectable().contains(SettingType.BOOL));
    }
}
