package com.botmaker.studio.project;

import com.botmaker.studio.project.capture.CaptureTarget;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Per-project editor settings, persisted as {@code settings.json} under the project's
 * {@code src/main/resources}. Currently holds the saved {@link CaptureTarget}s and which one is the
 * default used by all on-screen pickers. Modeled on
 * {@link com.botmaker.studio.project.activity.ActivitiesConfig}.
 *
 * @param captureTargets      the saved screen/window targets (order is the display order)
 * @param defaultTargetIndex  index into {@code captureTargets} of the default, or {@code null} for none
 * @param knownWindowTitles   window titles seen/used before, remembered so a window can be picked as a
 *                            target without the app being currently open (backward-compatible; absent → empty)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudioProjectSettings(List<CaptureTarget> captureTargets, Integer defaultTargetIndex,
                                    List<String> knownWindowTitles) {

    public static final String FILE_NAME = "settings.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public StudioProjectSettings {
        captureTargets = captureTargets == null ? List.of() : List.copyOf(captureTargets);
        knownWindowTitles = knownWindowTitles == null ? List.of() : List.copyOf(knownWindowTitles);
        if (defaultTargetIndex != null
                && (defaultTargetIndex < 0 || defaultTargetIndex >= captureTargets.size())) {
            defaultTargetIndex = null;
        }
    }

    /** Convenience constructor for callers that don't manage the remembered window titles. */
    public StudioProjectSettings(List<CaptureTarget> captureTargets, Integer defaultTargetIndex) {
        this(captureTargets, defaultTargetIndex, List.of());
    }

    public static StudioProjectSettings empty() {
        return new StudioProjectSettings(List.of(), null, List.of());
    }

    /** The default target, or {@code null} if none is set (pickers then show the chooser). */
    @JsonIgnore
    public CaptureTarget defaultTarget() {
        return defaultTargetIndex == null ? null : captureTargets.get(defaultTargetIndex);
    }

    /** This settings with the target list replaced (keeps the default if still in range). */
    public StudioProjectSettings withTargets(List<CaptureTarget> targets) {
        return new StudioProjectSettings(targets, defaultTargetIndex, knownWindowTitles);
    }

    /** This settings with the default index replaced. */
    public StudioProjectSettings withDefaultIndex(Integer index) {
        return new StudioProjectSettings(captureTargets, index, knownWindowTitles);
    }

    /** This settings with the remembered window titles replaced. */
    public StudioProjectSettings withKnownWindowTitles(List<String> titles) {
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, titles);
    }

    /** Reads {@code settings.json} from {@code resourcesDir}; returns {@link #empty()} if absent/invalid. */
    public static StudioProjectSettings read(Path resourcesDir) {
        Path file = resourcesDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return empty();
        try {
            return MAPPER.readValue(file.toFile(), StudioProjectSettings.class);
        } catch (Exception e) {
            System.err.println("Failed to read " + FILE_NAME + " in " + resourcesDir + ": " + e.getMessage());
            return empty();
        }
    }

    /** Writes (overwrites) {@code settings.json} into {@code resourcesDir}, creating it if needed. */
    public void write(Path resourcesDir) throws IOException {
        Files.createDirectories(resourcesDir);
        MAPPER.writeValue(resourcesDir.resolve(FILE_NAME).toFile(), this);
    }
}
