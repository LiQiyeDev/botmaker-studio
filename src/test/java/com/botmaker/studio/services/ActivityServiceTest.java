package com.botmaker.studio.services;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the activity type mapping, JSON round-trip, and generated-class source. */
public class ActivityServiceTest {

    @Test
    void activityTypeMappings() {
        assertEquals("boolean", ActivityType.BOOL.javaType());
        assertEquals("int", ActivityType.INT.javaType());
        assertEquals("java.time.LocalTime", ActivityType.TIME.javaType());

        assertEquals("v.asInt(0)", ActivityType.INT.loadExpression("v"));
        assertEquals("v.asBoolean(false)", ActivityType.BOOL.loadExpression("v"));
        assertEquals("java.time.LocalTime.parse(v.asText(\"00:00\"))", ActivityType.TIME.loadExpression("v"));

        assertTrue(ActivityType.INT.resolvedType().isNumeric());
        assertTrue(ActivityType.BOOL.resolvedType().isBoolean());
        for (ActivityType t : ActivityType.values()) assertNotNull(t.defaultValue());
    }

    @Test
    void configRoundTrip(@TempDir Path dir) throws Exception {
        ActivitiesConfig cfg = new ActivitiesConfig(List.of(
                ActivityVariable.create("maxRetries", ActivityType.INT),
                ActivityVariable.create("startTime", ActivityType.TIME)));
        cfg.write(dir);

        ActivitiesConfig read = ActivitiesConfig.read(dir);
        assertEquals(2, read.activities().size());
        ActivityVariable first = read.activities().get(0);
        assertEquals("maxRetries", first.name());
        assertEquals(ActivityType.INT, first.type());
        assertEquals(0, first.value().asInt(-1));
        assertEquals("00:00", read.activities().get(1).value().asText());
    }

    @Test
    void readsEmptyWhenMissing(@TempDir Path dir) {
        assertTrue(ActivitiesConfig.read(dir).activities().isEmpty());
    }

    @Test
    void generatedSourceDeclaresAndLoadsFields(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", dir);
        ActivityService service = new ActivityService(config, new ProjectState(), new EventBus(false));

        ActivitiesConfig cfg = new ActivitiesConfig(List.of(
                ActivityVariable.create("maxRetries", ActivityType.INT),
                ActivityVariable.create("startTime", ActivityType.TIME)));
        String src = service.generateSource(cfg);

        assertTrue(src.contains("package com.mybot;"), src);
        assertTrue(src.contains("public static final int maxRetries;"), src);
        assertTrue(src.contains("public static final java.time.LocalTime startTime;"), src);
        assertTrue(src.contains("maxRetries = node(v, \"maxRetries\").asInt(0);"), src);
        assertTrue(src.contains("startTime = java.time.LocalTime.parse(node(v, \"startTime\").asText(\"00:00\"));"), src);
        assertTrue(src.contains("getResourceAsStream(\"/activities.json\")"), src);
    }
}
