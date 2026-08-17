package com.botmaker.studio.project.settings;

import com.botmaker.studio.project.activity.ParamVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingTest {

    @Test
    void aFreshSettingHoldsItsTypesDefaultAndIsHiddenFromTheUser() {
        Setting delay = Setting.create("GIVE_UP_AFTER", SettingType.DURATION, "Mining");

        assertEquals(List.of("0s"), delay.value());
        assertEquals(ParamVisibility.EDITOR_ONLY, delay.visibility());
        assertFalse(delay.isShared(), "a setting nobody has thought about must not be offered to a user");
        assertEquals("Mining", delay.tagOrGeneral());
    }

    @Test
    void anUntaggedSettingIsListedUnderGeneral() {
        assertEquals(Setting.GENERAL, Setting.create("RETRIES", SettingType.INT, "").tagOrGeneral());
        assertEquals(Setting.GENERAL, Setting.create("RETRIES", SettingType.INT, null).tagOrGeneral());
    }

    @Test
    void anEnableFlagIsNamedAfterItsActivityAndFiledUnderIt() {
        Setting flag = Setting.enableFlag("Mining", false);

        assertEquals("Mining", flag.name());
        assertEquals("Mining", flag.tagOrGeneral());
        assertTrue(flag.isEnableFlag());
        assertEquals("false", flag.literal());
    }

    @Test
    void settingAValueNormalisesIt() {
        Setting retries = Setting.create("RETRIES", SettingType.INT, "")
                .withBounds(new Setting.Bounds("1", "10", null));

        assertEquals("10", retries.withValue("99").singleValue());
        assertEquals("1", retries.withValue("nonsense").singleValue(), "garbage lands on the low bound, not on 0");
    }

    /** Tightening a range must move the value, not refuse the edit. */
    @Test
    void narrowingTheRangePullsAnOutOfRangeValueIn() {
        Setting retries = Setting.create("RETRIES", SettingType.INT, "").withValue("99");
        assertEquals("99", retries.singleValue());

        assertEquals("10", retries.withBounds(new Setting.Bounds("1", "10", null)).singleValue());
    }

    @Test
    void deletingAChoiceUnsetsItWhereverItWasChosen() {
        Setting ore = Setting.create("ORE", SettingType.CHOICE, "Mining")
                .withOptions(List.of("Iron", "Gold", "Mithril"))
                .withValue("Mithril");
        assertEquals("Mithril", ore.singleValue());

        assertEquals("Iron", ore.withOptions(List.of("Iron", "Gold")).singleValue());
    }

    @Test
    void retypingResetsTheValueRatherThanPretendingItSurvived() {
        Setting when = Setting.create("WHEN", SettingType.DATE, "").withValue("2026-08-17");

        Setting renumbered = when.withType(SettingType.INT);
        assertEquals(SettingType.INT, renumbered.type());
        assertEquals("0", renumbered.singleValue());
    }

    @Test
    void retypingAwayFromAnOptionTypeDropsTheOptions() {
        Setting ore = Setting.create("ORE", SettingType.CHOICE, "").withOptions(List.of("Iron", "Gold"));

        assertTrue(ore.withType(SettingType.MULTI_CHOICE).options().contains("Iron"));
        assertTrue(ore.withType(SettingType.TEXT).options().isEmpty());
    }

    @Test
    void normalisingIsIdempotent() {
        Setting ore = Setting.create("ORE", SettingType.MULTI_CHOICE, "")
                .withOptions(List.of("Iron", "Gold"))
                .withValues(List.of("Gold", "Mithril", "Iron"));

        assertEquals(List.of("Iron", "Gold"), ore.value());
        assertEquals(ore, ore.normalized());
    }

    @Test
    void theLabelStandsInForTheFieldNameWhenThereIsOne() {
        Setting retries = Setting.create("GIVE_UP_AFTER", SettingType.INT, "");

        assertEquals("GIVE_UP_AFTER", retries.displayLabel());
        assertEquals("Give up after", retries.withLabel("Give up after").displayLabel());
    }

    // ---- field names ----------------------------------------------------------------------------------

    @Test
    void aTypedNameBecomesAConstantName() {
        assertEquals("GIVE_UP_AFTER", Setting.toFieldName("give up after"));
        assertEquals("GIVE_UP_AFTER", Setting.toFieldName("Give-Up-After"));
        assertEquals("RETRIES", Setting.toFieldName("  retries  "));
    }

    /** It refuses rather than mangles: a name the editor did not ask for is worse than a message. */
    @Test
    void aNameThatCannotBeAnIdentifierIsRefused() {
        assertNull(Setting.toFieldName("2fast"));
        assertNull(Setting.toFieldName("ore!"));
        assertNull(Setting.toFieldName("café"));
        assertNull(Setting.toFieldName(""));
        assertNull(Setting.toFieldName(null));
    }
}
