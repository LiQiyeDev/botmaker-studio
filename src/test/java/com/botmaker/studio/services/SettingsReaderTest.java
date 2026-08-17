package com.botmaker.studio.services;

import com.botmaker.studio.project.activity.ParamVisibility;
import com.botmaker.studio.project.settings.RawSetting;
import com.botmaker.studio.project.settings.Setting;
import com.botmaker.studio.project.settings.SettingType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other half of {@link SettingsClassWriterTest}: for a java-model project the generated file <em>is</em>
 * the store, so what matters is not that the reader parses but that <b>write → read → write is a fixed
 * point</b>. Anything the pair does not agree on is a value that changes every time the project is saved.
 */
class SettingsReaderTest {

    private static final String PKG = "minerbot";

    /** One of every type, so a round-trip covers the whole table rather than the easy half of it. */
    private static List<Setting> everyType() {
        return List.of(
                Setting.create("RETRIES", SettingType.INT, "Mining")
                        .withBounds(new Setting.Bounds("1", "10", "1")).withValue("3"),
                Setting.create("RATIO", SettingType.DOUBLE, "Mining").withValue("0.75"),
                Setting.create("PAUSED", SettingType.BOOL, "Mining").withValue("true"),
                Setting.create("GIVE_UP_AFTER", SettingType.DURATION, "Mining")
                        .withLabel("Give up after").withValue("1m30s")
                        .withVisibility(ParamVisibility.PUBLIC),
                Setting.create("GREETING", SettingType.TEXT, "").withValue("say \"hi\"\n\tplease"),
                Setting.create("START_AT", SettingType.TIME, "").withValue("07:30"),
                Setting.create("EXPIRES", SettingType.DATE, "").withValue("2026-08-17"),
                Setting.create("ORE", SettingType.CHOICE, "Mining")
                        .withOptions(List.of("Iron", "Gold")).withValue("Gold"),
                Setting.create("TOOLS", SettingType.MULTI_CHOICE, "Mining")
                        .withOptions(List.of("Pick", "Axe", "Net")).withValues(List.of("Net", "Pick")),
                Setting.create("ORE_ICON", SettingType.TEMPLATE, "Mining").withValue("ore-vein"),
                Setting.create("JUMP", SettingType.KEY, ""),
                Setting.create("CLICK", SettingType.MOUSE_BUTTON, ""));
    }

    @Test
    void writeReadWriteIsAFixedPointForEveryType() {
        String first = SettingsClassWriter.settingsSource(PKG, everyType());
        SettingsReader.Result read = SettingsReader.parse(first);

        assertEquals(List.of(), read.warnings(), "a file this build wrote is a file it fully understands");
        assertEquals(everyType().size(), read.settings().size());
        assertEquals(first, SettingsClassWriter.settingsSource(PKG, read.settings()),
                "the second write must be byte-identical, or every save churns the file");
    }

    @Test
    void everyPieceOfMetadataSurvivesTheRoundTrip() {
        SettingsReader.Result read = SettingsReader.parse(SettingsClassWriter.settingsSource(PKG, everyType()));

        Setting duration = byName(read, "GIVE_UP_AFTER");
        assertEquals(SettingType.DURATION, duration.type());
        assertEquals("Mining", duration.tag());
        assertEquals("Give up after", duration.label());
        assertEquals("1m30s", duration.singleValue());
        assertTrue(duration.isShared(), "the Runner would stop offering it otherwise");

        Setting retries = byName(read, "RETRIES");
        assertEquals(new Setting.Bounds("1", "10", "1"), retries.bounds());

        Setting ore = byName(read, "ORE");
        assertEquals(List.of("Iron", "Gold"), ore.options());
        assertEquals("Gold", ore.singleValue());

        assertEquals(List.of("Pick", "Net"), byName(read, "TOOLS").value(),
                "a multi-valued setting keeps every choice, in declaration order");
        assertEquals("say \"hi\"\n\tplease", byName(read, "GREETING").singleValue(),
                "the escaping is undone exactly");
    }

    /**
     * The enable flags are canvas state; {@code ActivitiesConfig.allSettings()} regenerates them on every
     * save. Reading them back here would give the flag a second store — and the two would disagree the first
     * time a preset was applied.
     */
    @Test
    void anEnableFlagIsNotReadBack() {
        String source = SettingsClassWriter.settingsSource(PKG,
                List.of(Setting.enableFlag("Mining", true),
                        Setting.create("RETRIES", SettingType.INT, "Mining").withValue("3")));

        SettingsReader.Result read = SettingsReader.parse(source);

        assertTrue(source.contains("public static final boolean Mining;"), "it is still generated");
        assertEquals(List.of("RETRIES"), read.settings().stream().map(Setting::name).toList());
    }

    @Test
    void aFieldWithoutTheAnnotationIsInvisible() {
        String source = SettingsClassWriter.settingsSource(PKG, everyType())
                .replace("    private Settings() {}",
                        "    public static final int HAND_WRITTEN = 7;\n\n    private Settings() {}");

        assertFalse(SettingsReader.parse(source).settings().stream()
                .anyMatch(s -> s.name().equals("HAND_WRITTEN")), "somebody's own constant is not a setting");
    }

    /**
     * The forward-compatibility rule: a type from a newer Studio is carried, not dropped. Losing it would mean
     * opening a project in an older Studio and saving silently deletes settings it never learned about.
     */
    @Test
    void aTypeThisBuildDoesNotKnowIsCarriedThroughUntouched() {
        String source = SettingsClassWriter.settingsSource(PKG, everyType())
                .replace("    private Settings() {}", """
                            @Setting(type = "COLOUR", tag = "Mining", value = "#ff8800")
                            public static final java.awt.Color HIGHLIGHT;

                            private Settings() {}""");

        SettingsReader.Result read = SettingsReader.parse(source);

        assertEquals(1, read.unknown().size());
        RawSetting raw = read.unknown().getFirst();
        assertEquals("HIGHLIGHT", raw.name());
        assertEquals("java.awt.Color", raw.javaType());
        assertTrue(raw.annotation().contains("\"COLOUR\""), "the annotation goes back verbatim");
        assertTrue(read.hasWarnings(), "and the editor is told, rather than it happening silently");

        String rewritten = SettingsClassWriter.settingsSource(PKG, read.settings(), read.unknown());
        assertTrue(rewritten.contains("@Setting(type = \"COLOUR\", tag = \"Mining\", value = \"#ff8800\")"));
        assertTrue(rewritten.contains("public static final java.awt.Color HIGHLIGHT;"));
        assertEquals(read.unknown(), SettingsReader.parse(rewritten).unknown(),
                "and it is still there after a second save");
    }

    /**
     * An unknown field's value lives in the static block, which is the one case the reader looks at it — and
     * only as text, never parsed.
     */
    @Test
    void anUnknownSettingKeepsItsInitializer() {
        String source = SettingsClassWriter.settingsSource(PKG, List.of())
                .replace("    private Settings() {}", """
                            @Setting(type = "COLOUR", value = "#ff8800")
                            public static final java.awt.Color HIGHLIGHT;

                            private Settings() {}""")
                .replace("    static {\n", "    static {\n        HIGHLIGHT = new java.awt.Color(255, 136, 0);\n");

        RawSetting raw = SettingsReader.parse(source).unknown().getFirst();

        assertEquals("new java.awt.Color(255, 136, 0)", raw.initializer());
        assertTrue(SettingsClassWriter.settingsSource(PKG, List.of(), List.of(raw))
                .contains("HIGHLIGHT = new java.awt.Color(255, 136, 0);"));
    }

    /** A half-written file must not read as "no settings" without saying so — that is a silent data loss. */
    @Test
    void aTruncatedFileReadsAsEmptyWithAWarningRatherThanThrowing() {
        String truncated = SettingsClassWriter.settingsSource(PKG, everyType());
        truncated = truncated.substring(0, truncated.length() / 2);

        SettingsReader.Result read = SettingsReader.parse(truncated);

        assertTrue(read.isEmpty());
        assertTrue(read.hasWarnings(), "loud, because for this model the file is the only copy");
    }

    @Test
    void anEmptyOrAbsentFileIsNotAnError() {
        assertFalse(SettingsReader.parse(SettingsClassWriter.settingsSource(PKG, List.of())).hasWarnings());
        assertTrue(SettingsReader.read(null).isEmpty());
        assertFalse(SettingsReader.read(null).hasWarnings(), "having no settings is a legitimate state");
    }

    /** A name collision cannot be written out: the file would not compile, and the raw one is the loser. */
    @Test
    void aLiveSettingWinsANameClashWithAnUnknownOne() {
        String source = SettingsClassWriter.settingsSource(PKG,
                List.of(Setting.create("RETRIES", SettingType.INT, "Mining").withValue("3")),
                List.of(new RawSetting("RETRIES", "@Setting(type = \"COLOUR\", value = \"x\")",
                        "java.awt.Color", "null")));

        assertEquals(1, source.split("RETRIES;", -1).length - 1, "declared exactly once");
        assertTrue(source.contains("public static final int RETRIES;"));
    }

    private static Setting byName(SettingsReader.Result read, String name) {
        return read.settings().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}
