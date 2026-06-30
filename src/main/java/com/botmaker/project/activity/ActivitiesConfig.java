package com.botmaker.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The set of activities for a project, persisted as {@code activities.json} under the project's
 * {@code src/main/resources} (so it is on the runtime classpath and read by the generated
 * {@code Activities} class). The schema (names + types) is owned by the editor; the values are owned
 * by the user. Read/write modeled on {@link com.botmaker.sharing.BotSource}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivitiesConfig(List<ActivityVariable> activities) {

    public static final String FILE_NAME = "activities.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ActivitiesConfig {
        activities = activities == null ? List.of() : List.copyOf(activities);
    }

    public static ActivitiesConfig empty() {
        return new ActivitiesConfig(List.of());
    }

    /** Reads {@code activities.json} from {@code resourcesDir}; returns {@link #empty()} if absent/invalid. */
    public static ActivitiesConfig read(Path resourcesDir) {
        Path file = resourcesDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return empty();
        try {
            return MAPPER.readValue(file.toFile(), ActivitiesConfig.class);
        } catch (Exception e) {
            System.err.println("Failed to read " + FILE_NAME + " in " + resourcesDir + ": " + e.getMessage());
            return empty();
        }
    }

    /** Writes (overwrites) {@code activities.json} into {@code resourcesDir}, creating it if needed. */
    public void write(Path resourcesDir) throws IOException {
        Files.createDirectories(resourcesDir);
        MAPPER.writeValue(resourcesDir.resolve(FILE_NAME).toFile(), this);
    }
}
