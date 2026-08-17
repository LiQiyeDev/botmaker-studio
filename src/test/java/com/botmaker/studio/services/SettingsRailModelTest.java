package com.botmaker.studio.services;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.settings.Setting;
import com.botmaker.studio.project.settings.SettingType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings dialog's rail, which is a decision rather than a widget: which buckets exist, what each holds,
 * and — the one that matters — that no setting can end up in none of them.
 */
class SettingsRailModelTest {

    private static TagCatalog catalog() {
        ActivitiesConfig activities = new ActivitiesConfig(
                List.of(new ActivityDefinition("Mining", true, "", List.of()),
                        new ActivityDefinition("Fishing", true, "", List.of())),
                List.of());
        return TagCatalog.of(activities, List.of("Timing"));
    }

    private static List<Setting> settings() {
        return List.of(
                Setting.create("RETRIES", SettingType.INT, "Mining"),
                Setting.create("ORE", SettingType.CHOICE, "Mining"),
                Setting.create("BAIT", SettingType.TEXT, "Fishing"),
                Setting.create("DEBUG", SettingType.BOOL, ""),
                Setting.create("GAP", SettingType.DURATION, "Timing"));
    }

    @Test
    void theRailIsAllThenGeneralThenEachDeclaredGroup() {
        List<SettingsRailModel.Row> rows = SettingsRailModel.rows(settings(), catalog());

        assertEquals(List.of("All settings (5)", "General (1)", "#Activities", "Mining (2)", "Fishing (1)",
                        "#Custom", "Timing (1)"),
                rows.stream().map(SettingsRailModelTest::render).toList());
    }

    /** Both computed rows exist even with nothing in them: a bucket you cannot select is one you cannot fill. */
    @Test
    void allAndGeneralAreOfferedByAnEmptyProject() {
        List<SettingsRailModel.Row> rows = SettingsRailModel.rows(List.of(), TagCatalog.empty());

        assertEquals(List.of("All settings (0)", "General (0)"),
                rows.stream().map(SettingsRailModelTest::render).toList());
    }

    /**
     * The forward-and-backward compatibility case: an activity was deleted, so a setting carries a tag nothing
     * declares any more. It must still have a home, or a value would be invisible in the one dialog that edits
     * it while still being generated into the bot.
     */
    @Test
    void aSettingFiledUnderAVanishedTagIsListedUnderGeneral() {
        List<Setting> settings = List.of(Setting.create("ORE", SettingType.TEXT, "Smelting"));

        List<Setting> general = SettingsRailModel.in(settings, Setting.GENERAL, catalog());

        assertEquals(List.of("ORE"), general.stream().map(Setting::name).toList());
        assertEquals(1, SettingsRailModel.in(settings, SettingsRailModel.ALL, catalog()).size());
    }

    @Test
    void everySettingIsReachableFromExactlyOneTagRow() {
        List<Setting> settings = settings();
        TagCatalog catalog = catalog();

        for (Setting s : settings) {
            long homes = SettingsRailModel.rows(settings, catalog).stream()
                    .filter(r -> r instanceof SettingsRailModel.TagRow t && !t.tag().equals(SettingsRailModel.ALL))
                    .map(r -> ((SettingsRailModel.TagRow) r).tag())
                    .filter(tag -> SettingsRailModel.in(settings, tag, catalog).contains(s))
                    .count();
            assertEquals(1, homes, s.name() + " should be listed under exactly one tag");
        }
    }

    @Test
    void aTagIsMatchedHoweverItIsSpelled() {
        List<Setting> settings = List.of(Setting.create("RETRIES", SettingType.INT, "mining"));

        assertTrue(SettingsRailModel.in(settings, "Mining", catalog()).contains(settings.getFirst()),
                "the catalog is case-insensitive, so the rail must be too");
    }

    private static String render(SettingsRailModel.Row row) {
        return switch (row) {
            case SettingsRailModel.Heading heading -> "#" + heading.text();
            case SettingsRailModel.TagRow tag -> tag.tag() + " (" + tag.count() + ")";
        };
    }
}
