package com.botmaker.studio.project;

import com.botmaker.sdk.authoring.Authoring;
import com.botmaker.sdk.authoring.CaptureModel;
import com.botmaker.sdk.authoring.CaptureModel.Resolution;
import com.botmaker.sdk.authoring.CaptureTargetModel;
import com.botmaker.plugin.api.authoring.SdkVersion;
import com.botmaker.studio.project.migration.SchemaFile;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-project editor settings, persisted as {@code settings.json} under the project's
 * {@code src/main/resources}. Currently holds the saved {@link CaptureTargetModel}s and which one is the
 * default used by all on-screen pickers. Modeled on
 * {@link com.botmaker.studio.project.activity.ActivitiesConfig}.
 *
 * <p><b>The capture targets are not in this file, and since 2026-08-31 neither is the resolution they are
 * captured at.</b> All three live in the SDK's own {@code capture.json}, read and written here through
 * {@link Authoring} — see {@link #read} and {@link #write}. The targets were stored twice (this file's list,
 * and the one {@code capture.source} spec a running bot reads out of {@code botmaker-project.properties})
 * with nothing keeping the two in step, so the editor and the bot could silently disagree about which window
 * to look at. The resolution followed them for the reason that decides every file in this project: it
 * describes the pictures, and the plugin that captures and matches those pictures has to be able to read it.
 * All three stay record components because every picker in the editor asks for them here; only the file
 * underneath them changed.
 *
 * @param captureTargets      the saved screen/window targets (order is the display order); stored in
 *                            {@code capture.json}, not in this file
 * @param defaultTargetIndex  index into {@code captureTargets} of the default, or {@code null} for none;
 *                            stored in {@code capture.json}, not in this file
 * @param knownWindowTitles   window titles seen/used before, remembered so a window can be picked as a
 *                            target without the app being currently open (backward-compatible; absent → empty)
 * @param favoriteOverloads   per-method chosen overload: {@code methodKey → signatureKey} (see
 *                            {@code ExpressionMenu}); the favorite is created by default when clicking
 *                            the method (backward-compatible; absent → empty)
 * @param referenceResolution the canonical target-window size (logical px) image templates are captured at.
 *                            The overlay snaps the window to this before capturing so templates share one
 *                            resolution (avoids lossy match-time up/downscaling). {@code null} until the first
 *                            capture seeds it from the window's current size. <b>Stored in
 *                            {@code capture.json}, not in this file</b> — it describes the pictures, so it
 *                            belongs to the SDK version that captures and matches them
 * @param favoriteMethods     per-class preferred methods: {@code className → [methodName, …]}, surfaced first in
 *                            the overlay palette and other pickers (backward-compatible; absent → empty)
 * @param template            the {@link ProjectTemplate} the project was created from, recorded at creation so
 *                            {@code FileRole}/{@code ProjectRepair} know which files are scaffolding instead of
 *                            guessing from the sources. {@code null} for projects created before this was
 *                            persisted — callers fall back to {@code ProjectRepair.looksLikeGameBot}
 *                            (backward-compatible; absent → null)
 * @param lastRecordedActivity the activity the overlay editor last authored into, preselected the next time it
 *                            opens so a recording session resumes where the previous one left off. {@code null}
 *                            until the overlay has been used, and ignored once the activity is gone
 *                            (backward-compatible; absent → null)
 * @param overlayState        where the overlay HUD sat and how tall its tree was, restored the next time it
 *                            opens (backward-compatible; absent → null)
 * @param workspaceLayout     where the main window's two dividers sat and which bottom tab was open, restored
 *                            the next time the project opens (backward-compatible; absent → null)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudioProjectSettings(@JsonIgnore List<CaptureTargetModel> captureTargets,
                                    @JsonIgnore Integer defaultTargetIndex,
                                    List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                    @JsonIgnore Resolution referenceResolution,
                                    Map<String, List<String>> favoriteMethods,
                                    ProjectTemplate template, String lastRecordedActivity,
                                    OverlayState overlayState, WorkspaceLayout workspaceLayout) {

    /**
     * The overlay editor HUD's remembered layout: its top-left corner on screen and how many tree rows it
     * shows before scrolling.
     *
     * <p>Persisted because the HUD is dragged out of the way of whatever the user is doing in the game, and
     * that placement is a property of the <em>project</em> — the same game, the same UI, the same corner that
     * is safe to cover. Re-dragging it on every open (and re-growing a tree that reopened at 8 rows) is the
     * kind of friction that is invisible in a single session and constant across many. A position off every
     * attached screen is discarded on restore rather than trusted; monitors come and go.
     */
    public record OverlayState(int x, int y, int visibleLines) {}

    /**
     * The main window's remembered layout: the explorer/canvas divider, the canvas/bottom divider, and the
     * name of the bottom tab that was open. Window geometry is not here — that is the user's, not the
     * project's, and already persists through {@code ProjectPreferences.WindowState}.
     *
     * <p>Per project, because how much room the file tree or the console deserves is a property of the bot
     * being worked on: a bot being read wants a wide tree, a bot being debugged wants a tall terminal.
     *
     * <p>Each divider is {@code null} when it was never saved <em>or</em> when the saved value is unusable —
     * a divider at 0.0 or 1.0 hides a whole pane, and a settings file that has been hand-edited (or written
     * before the window was ever laid out) must not be able to open a window with no canvas in it. Callers
     * ask with {@link #explorerDividerOr}/{@link #bottomDividerOr} and get their own default back instead.
     *
     * @param explorerDivider the horizontal split's position, {@code 0..1}, or {@code null} if unusable
     * @param bottomDivider   the vertical split's position, {@code 0..1}, or {@code null} if unusable
     * @param bottomTab       the open bottom tab's key, or {@code null}; an unknown key is ignored on restore
     */
    public record WorkspaceLayout(Double explorerDivider, Double bottomDivider, String bottomTab) {

        /** Dividers this close to an edge have hidden a pane, so they are treated as never saved. */
        private static final double EDGE = 0.02;

        public WorkspaceLayout {
            explorerDivider = usable(explorerDivider);
            bottomDivider = usable(bottomDivider);
        }

        private static Double usable(Double position) {
            if (position == null || !Double.isFinite(position)) return null;
            return position < EDGE || position > 1 - EDGE ? null : position;
        }

        public double explorerDividerOr(double fallback) {
            return explorerDivider == null ? fallback : explorerDivider;
        }

        public double bottomDividerOr(double fallback) {
            return bottomDivider == null ? fallback : bottomDivider;
        }
    }

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

    /** Convenience constructor for callers that manage the overlay's layout but not the window's. */
    public StudioProjectSettings(List<CaptureTargetModel> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                 Resolution referenceResolution, Map<String, List<String>> favoriteMethods,
                                 ProjectTemplate template, String lastRecordedActivity,
                                 OverlayState overlayState) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads, referenceResolution,
                favoriteMethods, template, lastRecordedActivity, overlayState, null);
    }

    /** Convenience constructor for callers that manage the last activity but not the overlay's layout. */
    public StudioProjectSettings(List<CaptureTargetModel> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                 Resolution referenceResolution, Map<String, List<String>> favoriteMethods,
                                 ProjectTemplate template, String lastRecordedActivity) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads, referenceResolution,
                favoriteMethods, template, lastRecordedActivity, null);
    }

    /** Convenience constructor for callers that manage the template but not the overlay's last activity. */
    public StudioProjectSettings(List<CaptureTargetModel> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                 Resolution referenceResolution, Map<String, List<String>> favoriteMethods,
                                 ProjectTemplate template) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads, referenceResolution,
                favoriteMethods, template, null, null);
    }

    /** Convenience constructor for callers that manage favorite methods but not the template. */
    public StudioProjectSettings(List<CaptureTargetModel> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                 Resolution referenceResolution, Map<String, List<String>> favoriteMethods) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads, referenceResolution,
                favoriteMethods, null, null);
    }

    /** Convenience constructor for callers that manage favorite overloads + resolution but not favorite methods. */
    public StudioProjectSettings(List<CaptureTargetModel> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                 Resolution referenceResolution) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads, referenceResolution, Map.of(), null);
    }

    /** Convenience constructor for callers that manage favorite overloads but not the reference resolution. */
    public StudioProjectSettings(List<CaptureTargetModel> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles, Map<String, String> favoriteOverloads) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads, null, Map.of(), null);
    }

    /** Convenience constructor for callers that don't manage favorite overloads. */
    public StudioProjectSettings(List<CaptureTargetModel> captureTargets, Integer defaultTargetIndex,
                                 List<String> knownWindowTitles) {
        this(captureTargets, defaultTargetIndex, knownWindowTitles, Map.of(), null, Map.of(), null);
    }

    /** Convenience constructor for callers that don't manage the remembered window titles. */
    public StudioProjectSettings(List<CaptureTargetModel> captureTargets, Integer defaultTargetIndex) {
        this(captureTargets, defaultTargetIndex, List.of(), Map.of(), null, Map.of(), null);
    }

    /**
     * A fresh project's settings: the whole desktop is seeded as the sole capture target and the default,
     * so every picker/toolbar already shows "Whole desktop" instead of an empty "no default set" state.
     */
    public static StudioProjectSettings empty() {
        return new StudioProjectSettings(List.of(CaptureTargetModel.desktop()), 0, List.of(), Map.of(), null,
                Map.of(), null);
    }

    /** The default target, or {@code null} if none is set (pickers then show the chooser). */
    @JsonIgnore
    public CaptureTargetModel defaultTarget() {
        return defaultTargetIndex == null ? null : captureTargets.get(defaultTargetIndex);
    }

    // withTargets, withDefaultIndex and withKnownWindowTitles are deleted (2026-08-31). Their one caller was
    // the targets dialog, which is the SDK plugin's now — so the editor has no way left to change a target
    // list it does not own, which is the point rather than a side effect.

    /** This settings with the capture reference resolution replaced ({@code null} clears it). */
    public StudioProjectSettings withReferenceResolution(Resolution resolution) {
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads,
                resolution, favoriteMethods, template, lastRecordedActivity, overlayState, workspaceLayout);
    }

    /** This settings with the originating template recorded ({@code null} clears it). */
    public StudioProjectSettings withTemplate(ProjectTemplate template) {
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads,
                referenceResolution, favoriteMethods, template, lastRecordedActivity, overlayState, workspaceLayout);
    }

    /** This settings with the overlay editor's last authored activity recorded ({@code null} clears it). */
    public StudioProjectSettings withLastRecordedActivity(String activityName) {
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads,
                referenceResolution, favoriteMethods, template, activityName, overlayState, workspaceLayout);
    }

    /** This settings with the overlay HUD's remembered layout replaced ({@code null} clears it). */
    public StudioProjectSettings withOverlayState(OverlayState state) {
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads,
                referenceResolution, favoriteMethods, template, lastRecordedActivity, state, workspaceLayout);
    }

    /** This settings with the main window's remembered layout replaced ({@code null} clears it). */
    public StudioProjectSettings withWorkspaceLayout(WorkspaceLayout layout) {
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, favoriteOverloads,
                referenceResolution, favoriteMethods, template, lastRecordedActivity, overlayState, layout);
    }

    /**
     * This settings with {@code methodKey}'s favorite overload set to {@code signatureKey} (or removed when
     * {@code signatureKey} is {@code null}). Keys are opaque strings minted by {@code ExpressionMenu}.
     */
    public StudioProjectSettings withFavoriteOverload(String methodKey, String signatureKey) {
        Map<String, String> next = new LinkedHashMap<>(favoriteOverloads);
        if (signatureKey == null) next.remove(methodKey);
        else next.put(methodKey, signatureKey);
        return new StudioProjectSettings(captureTargets, defaultTargetIndex, knownWindowTitles, next,
                referenceResolution, favoriteMethods, template, lastRecordedActivity, overlayState, workspaceLayout);
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
                referenceResolution, next, template, lastRecordedActivity, overlayState, workspaceLayout);
    }

    /** The favorite method names for {@code className} (preference order), or an empty list if none. */
    @JsonIgnore
    public List<String> favoriteMethodsFor(String className) {
        return favoriteMethods.getOrDefault(className, List.of());
    }

    /**
     * Reads {@code settings.json} from {@code resourcesDir}; returns {@link #empty()} if absent/invalid.
     *
     * <p>The capture targets come from {@code capture.json} beside it. When that file does not exist the
     * targets this settings file itself used to hold are read instead and carried in memory — so a project
     * written by an older Studio opens with its pickers intact, and the next write moves them across. Once
     * {@code capture.json} exists it is the answer, empty or not: the migration must not resurrect a list the
     * user has since emptied.
     *
     * <p><b>Neither migration is here any more (2026-08-31).</b> Both were reads of <em>this</em> file on
     * behalf of the SDK's, and they had a hole with no symptom: the editor has stopped writing the targets,
     * so a migration that only ran here would never move them across and the first reader that asked the
     * plugin would be told a configured project had none. The reader of a file owns its migration, so both
     * live in {@code Authoring.readCapture} and this simply asks it.
     */
    public static StudioProjectSettings read(Path resourcesDir) {
        Path file = resourcesDir.resolve(FILE_NAME);
        StudioProjectSettings settings = empty();
        if (Files.exists(file)) {
            try {
                settings = MAPPER.readValue(file.toFile(), StudioProjectSettings.class);
            } catch (Exception e) {
                System.err.println("Failed to read " + FILE_NAME + " in " + resourcesDir + ": " + e.getMessage());
                return empty();
            }
        }
        return settings.withCapture(readCapture(resourcesDir));
    }

    /**
     * Writes (overwrites) {@code settings.json} into {@code resourcesDir}, creating it if needed, stamped with
     * the file's {@code schemaVersion} — see {@link SchemaFile#stamped} and the same note on
     * {@link com.botmaker.studio.project.activity.ActivitiesConfig#write}.
     *
     * <p><b>Only the reference resolution reaches {@code capture.json}, and it is merged rather than
     * written (2026-08-31).</b> The target list in that file belongs to the SDK plugin's own manager, which
     * writes it while this editor is not looking; writing {@link #captureModel()} whole would put whatever
     * this settings was read with back over the top of it. So the file is re-read and only the component the
     * editor still owns is replaced.
     *
     * <p>The {@code capture.source} projection went with the list. Its writer is the plugin that owns the
     * default target, because two authors of one projection is exactly the disagreement moving the targets
     * out was meant to end.
     */
    public void write(Path resourcesDir) throws IOException {
        Files.createDirectories(resourcesDir);
        ObjectNode body = MAPPER.valueToTree(this);
        MAPPER.writeValue(resourcesDir.resolve(FILE_NAME).toFile(), SchemaFile.SETTINGS.stamped(body));
        Authoring.writeCapture(SdkVersion.latest(), resourcesDir,
                readCapture(resourcesDir).withReference(referenceResolution));
    }

    /**
     * Projects the default target onto {@code botmaker-project.properties}' {@code capture.source} — the one
     * spec a <em>running</em> bot resolves.
     *
     * <p>It is a projection with one writer and one direction, written in the same pass as the list it comes
     * from, which is what makes it a cache rather than a second answer. A bot cannot read {@code capture.json}
     * itself: {@link Authoring} reaches the value vocabulary in {@code botmaker-studio-api}, which is
     * deliberately off a bot's classpath, so the properties file stays the bot's side of this.
     *
     * <p>A project with no default target is left alone rather than cleared. The property is also written
     * directly by the launch-target dialog and the emulator picker for a project that has no target list at
     * all, and clearing it here would silently un-configure those.
     */
    /** This settings with the three {@code capture.json} components taken from {@code capture}. */
    private StudioProjectSettings withCapture(CaptureModel capture) {
        return new StudioProjectSettings(capture.targets(), capture.defaultIndex(), knownWindowTitles,
                favoriteOverloads, capture.reference(), favoriteMethods, template, lastRecordedActivity,
                overlayState, workspaceLayout);
    }

    /**
     * The project's capture model, asked of the SDK rather than parsed here.
     *
     * <p>Including the migration off this very file, which used to be read here: whoever reads a file owns
     * what to do with its older shapes, and the editor is no longer that reader.
     */
    private static CaptureModel readCapture(Path resourcesDir) {
        try {
            return Authoring.readCapture(SdkVersion.latest(), resourcesDir);
        } catch (Exception e) {
            System.err.println("Failed to read " + CaptureModel.FILE_NAME + " in " + resourcesDir + ": "
                    + e.getMessage());
            return CaptureModel.empty();
        }
    }
}
