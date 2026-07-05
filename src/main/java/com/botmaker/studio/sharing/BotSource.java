package com.botmaker.studio.sharing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Provenance of an installed (or published) bot, persisted as {@code botmaker-source.json} in the project
 * directory. Records which GitHub repo + release tag the project came from, so the Studio can later detect
 * and pull updates. Absent for hand-created local projects.
 *
 * @param owner GitHub repo owner
 * @param repo  GitHub repo name
 * @param tag   the installed release tag
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotSource(String owner, String repo, String tag) {

    public static final String FILE_NAME = "botmaker-source.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String slug() {
        return owner + "/" + repo;
    }

    /** Reads the provenance file from {@code projectDir}, if present and valid. */
    public static Optional<BotSource> read(Path projectDir) {
        Path file = projectDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), BotSource.class));
        } catch (Exception e) {
            System.err.println("Failed to read " + FILE_NAME + " in " + projectDir + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Writes (overwrites) the provenance file into {@code projectDir}. */
    public void write(Path projectDir) throws java.io.IOException {
        Files.createDirectories(projectDir);
        MAPPER.writeValue(projectDir.resolve(FILE_NAME).toFile(), this);
    }
}
