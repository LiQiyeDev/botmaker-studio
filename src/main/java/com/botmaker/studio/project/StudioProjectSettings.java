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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @param favoriteOverloads   per-method chosen overload: {@code methodKey → signatureKey} (see
 *                            {@code ExpressionMenuFactory}); the favorite is created by default when clicking
 *                            the method (backward-compatible; absent → empty)
 * @param referenceResolution the canonical target-window size (logical px) image templates are captured at.
 *                            The overlay snaps the window to this before capturing so templates share one
 *                            resolution (avoids lossy match-time up/downscaling). {@code null} until the first
 *                            capture seeds it from the window's current size (backward-compatible; absent → null)
 * @param favoriteMethods     per-class preferred methods: {@code className → [methodName, …]}, surfaced first in
 *                            the overlay palette and other pickers (backward-compatible; absent → empty)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudioProjectSettings(List<CaptureTarget> captureTargets, Integer defaultTargetIndex,
                                    List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                    Resolution referenceResolution, Map<String, List<String>> favoriteMethods) {

    /** A target-window size in logical screen pixels. */
    public record Resolution(int width, int height) {}

    public static final String FILE_NAME = "settings.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public StudioProjectSettings {
        captureTargets = captureTargets == null ? List.of() : List.copyOf(captureTargets);
        knownWindowTitles = knownWindowTitles == null ? List.of() : List.copyOf(knownWindowTitles);
        favoriteOverloads = favoriteOverloads == null ? Map.of() : Map.copyOf(favoriteOverloads);
        favoriteMethods = favoriteMethods == null ? Map.of() : deepCopy(favoriteMethods);
        if (defaultTargetIndex != null
                && (defaultTargetIndex < 0 || defaultTargetIndex >= captureTargets.size())) {
            defaultTargetIndex = null;
        }
    }

    private static Map<String, List<String>> deepCopy(Map<String, List<String>> src) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, v == null ? List.of() : List.copyOf(v)));
        return Map.copyOf(out);
    }

    /** Convenience constructor for callers that manage favorite overloads + resolution but not favorite methods. */
    public StudioProjectSettings(List<CaptureTarget> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                 Resolution referenceResolution) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads, referenceResolution, Map.of());
    }

    /** Convenience constructor for callers that manage favorite overloads but not the reference resolution. */
    public StudioProjectSettings(List<CaptureTarget> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles, Map<String, String> favoriteOverloads) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads, null, Map.of());
    }

    /** Convenience constructor for callers that don't manage favorite overloads. */
    public StudioProjectSettings(List<CaptureTarget> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, Map.of(), null, Map.of());
    }

    /** Convenience constructor for callers that don't manage the remembered window titles. */
    public StudioProjectSettings(List<CaptureTarget> captureTargets, Integer defaultTargetIndex) {
        this(captureTargets, defaultTargetIndex, List.of(), Map.of(), null, Map.of());
    }

    /**
     * A fresh project's settings: the whole desktop is seeded as the sole capture target and the default,
     * so every picker/toolbar already shows "Whole desktop" instead of an empty "no default set" state.
     */
    public static StudioProjectSettings empty() {
        return new StudioProjectSettings(List.of(new CaptureTarget.DesktopTarget()), 0, List.of(), Map.of(), null, Map.of());
    }

    /** The default target, or {@code null} if none is set (pickers then show the chooser). */
    @JsonIgnore
    public CaptureTarget defaultTarget() {
        return defaultTargetIndex == null ? null : captureTargets.get(defaultTargetIndex);
    }

    /** This settings with the target list replaced (keeps the default if still in range). */
    public StudioProjectSettings withTargets(List<CaptureTarget> targets) {
        return new StudioProjectSettings(targets, defaultTargetIndex, knownWindowTitles, favoriteOverloads,
                referenceResolution, favoriteMethods);
    }

    /** This settings with the default index replaced. */
    public StudioProjectSettings withDefaultIndex(Integer index) {
        return new StudioProjectSettings(captureTargets, index, knownWindowTitles, favoriteOverloads,
                referenceResolution, favoriteMethods);
    }

    /** This settings with the remembered window titles replaced. */
    public StudioProjectSettings withKnownWindowTitles(List<String> titles) {
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, titles, favoriteOverloads,
                referenceResolution, favoriteMethods);
    }

    /** This settings with the capture reference resolution replaced ({@code null} clears it). */
    public StudioProjectSettings withReferenceResolution(Resolution resolution) {
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads,
                resolution, favoriteMethods);
    }

    /**
     * This settings with {@code methodKey}'s favorite overload set to {@code signatureKey} (or removed when
     * {@code signatureKey} is {@code null}). Keys are opaque strings minted by {@code ExpressionMenuFactory}.
     */
    public StudioProjectSettings withFavoriteOverload(String methodKey, String signatureKey) {
        Map<String, String> next = new LinkedHashMap<>(favoriteOverloads);
        if (signatureKey == null) next.remove(methodKey);
        else next.put(methodKey, signatureKey);
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, next,
                referenceResolution, favoriteMethods);
    }

    /** The chosen overload signature key for {@code methodKey}, or {@code null} if no favorite is set. */
    @JsonIgnore
    public String favoriteSignature(String methodKey) {
        return favoriteOverloads.get(methodKey);
    }

    /**
     * This settings with {@code className}'s favorite method list replaced (an empty/null list removes the
     * entry). Order in {@code methods} is the preference order.
     */
    public StudioProjectSettings withFavoriteMethods(String className, List<String> methods) {
        Map<String, List<String>> next = new LinkedHashMap<>(favoriteMethods);
        if (methods == null || methods.isEmpty()) next.remove(className);
        else next.put(className, List.copyOf(methods));
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads,
                referenceResolution, next);
    }

    /** The favorite method names for {@code className} (preference order), or an empty list if none. */
    @JsonIgnore
    public List<String> favoriteMethodsFor(String className) {
        return favoriteMethods.getOrDefault(className, List.of());
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
