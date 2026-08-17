package com.botmaker.studio.project.activity;

import com.botmaker.studio.project.settings.Setting;
import com.botmaker.studio.project.settings.SettingType;
import com.botmaker.studio.project.settings.SettingsModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discriminator that separates the two generation paths, and the one thing about it that would be a data
 * loss if it were wrong: an old project must load as {@link SettingsModel#JSON}, and a new one's settings must
 * <em>not</em> end up in {@code activities.json} — their store is the generated {@code Settings.java}, and two
 * stores for one value is two answers to what the value is.
 */
class SettingsModelTest {

    private static ActivitiesConfig javaProject() {
        return new ActivitiesConfig(
                List.of(new ActivityDefinition("Mining", true, "", List.of()),
                        new ActivityDefinition("Idle", false, "", List.of(), true, List.of())),
                List.of(), ActivityFlow.empty(), List.of(), Boolean.TRUE, SettingsModel.JAVA,
                List.of(Setting.create("RETRIES", SettingType.INT, "Mining").withValue("3")));
    }

    @Test
    void anIdIsParsedTotallyAndAbsentMeansTheLegacyModel() {
        assertEquals(SettingsModel.JAVA, SettingsModel.fromId("java"));
        assertEquals(SettingsModel.JAVA, SettingsModel.fromId(" JAVA "));
        assertEquals(SettingsModel.JSON, SettingsModel.fromId("json"));
        assertEquals(SettingsModel.JSON, SettingsModel.fromId(null), "absent is a project written before this");
        assertEquals(SettingsModel.JSON, SettingsModel.fromId("something-newer"));
    }

    @Test
    void aConfigWithNoModelIsALegacyOne() {
        assertEquals(SettingsModel.JSON, ActivitiesConfig.empty().settingsModel());
        assertFalse(ActivitiesConfig.empty().settingsModel().isJava());
    }

    /** The whole point of {@code @JsonIgnore} on that component: the java file is the only copy. */
    @Test
    void theSettingsThemselvesAreNotWrittenIntoActivitiesJson(@TempDir Path dir) throws Exception {
        javaProject().write(dir);
        String json = Files.readString(dir.resolve(ActivitiesConfig.FILE_NAME));

        assertTrue(json.contains("\"settingsModel\" : \"java\""), "the model is persisted");
        assertFalse(json.contains("RETRIES"), "the values are not — Settings.java is their store");
    }

    @Test
    void theModelSurvivesARoundTripAndTheSettingsComeBackEmpty(@TempDir Path dir) throws Exception {
        javaProject().write(dir);
        ActivitiesConfig read = ActivitiesConfig.read(dir);

        assertEquals(SettingsModel.JAVA, read.settingsModel());
        assertEquals(List.of(), read.settings(), "read back from Settings.java, not from here");
        assertEquals(2, read.activities().size(), "the canvas model still round-trips in full");
    }

    /**
     * The enable flags are derived, never stored alongside: whether an activity runs is canvas state, so
     * {@code activities.json} stays its one home and the generated ENABLE field is output regenerated from it.
     */
    @Test
    void allSettingsAddsAnEnableFlagPerLiveActivity() {
        List<Setting> all = javaProject().allSettings();

        assertEquals(List.of("Mining", "RETRIES"), all.stream().map(Setting::name).toList());
        assertTrue(all.getFirst().isEnableFlag());
        assertEquals("true", all.getFirst().singleValue());
        assertFalse(all.stream().anyMatch(s -> s.name().equals("Idle")), "an archived activity generates nothing");
    }

    @Test
    void onlyTheSharedSettingsReachTheRunnerAndTheyArriveGroupedByTag() {
        ActivitiesConfig cfg = javaProject().withSettings(List.of(
                Setting.create("RETRIES", SettingType.INT, "Mining")
                        .withVisibility(ParamVisibility.PUBLIC),
                Setting.create("SECRET", SettingType.INT, "Mining"),
                Setting.create("NAME", SettingType.TEXT, "").withVisibility(ParamVisibility.PUBLIC)));

        assertEquals(List.of("Mining", "General"), List.copyOf(cfg.sharedSettings().keySet()));
        assertEquals(List.of("RETRIES"),
                cfg.sharedSettings().get("Mining").stream().map(Setting::name).toList(),
                "an enable flag is not a knob the bot's user is handed, and nor is a private setting");
    }
}
