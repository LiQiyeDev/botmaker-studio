package com.botmaker.studio.project;

import com.botmaker.shared.capture.linux.input.LinuxInputBackendId;
import com.botmaker.shared.config.ProjectProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bot's runtime tuning — everything the SDK's {@code api.BotSettings} exposes, plus the Linux input backend
 * and the private-display choice — as a value, stored in the project's {@code botmaker-project.properties}.
 *
 * <p><b>It used to be a generated {@code BotSettings.java}</b>, and the reasoning for that was sound as far as it
 * went: these knobs change what the bot <em>does</em>, so they have to apply when it runs outside the Studio, and
 * source travels with the code. The mistake was the storage format. Studio wrote Java and read its own values
 * back with a per-statement regex, which made the format "whatever that parser still recognises" — while the
 * project already had a file both sides speak, carrying the capture source, the launch target and the session
 * keys. These values now live there too, and the SDK reads them on first use. What was lost is the ability to
 * see the calls in the editor; what was gained is one storage format instead of two, and no generated file to
 * keep in step with a facade rename.
 *
 * <p>Reading is total: an absent or unparseable key is that setting's default, so a hand-edited file, or one
 * written by an older Studio with fewer settings, still loads.
 */
public record BotSettings(boolean realInput,
                          int foundDelay,
                          int notFoundDelay,
                          double confidence,
                          boolean randomizeClicks,
                          double compareMargin,
                          int maxRetryAttempts,
                          LinuxInputBackendId linuxInput,
                          boolean isolatedSession,
                          SessionBackend sessionBackend) {

    /**
     * Which nested display hosts an isolated bot, mirroring the SDK's {@code Session.useBackend} and the
     * project's {@code session.backend} key. {@link #AUTO} is the SDK's own kind-driven choice — a game gets
     * gamescope (a real GPU in the private display), a plain command the lighter Xephyr — and, like
     * {@link LinuxInputBackendId#AUTO}, it writes no key at all.
     *
     * <p>The ids are the session module's {@code NestedSession.Backend.id()} values; they are persisted, so they
     * must stay stable. Pinning {@link #XEPHYR} for a game is the one combination worth avoiding: its software
     * GL is what makes store launchers and Proton titles abort.
     */
    public enum SessionBackend {
        AUTO("auto", "Automatic (gamescope for games, Xephyr for commands)"),
        GAMESCOPE("gamescope", "gamescope — a real GPU in the private display (3D games)"),
        XEPHYR("xephyr", "Xephyr — software-rendered 2D (crashes 3D games)");

        private final String id;
        private final String label;

        SessionBackend(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** The value written to {@code session.backend}; stable. */
        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** Total parse: an unrecognised id (a newer Studio's, a typo in a hand-edit) reads back as {@link #AUTO}. */
        public static SessionBackend fromId(String id) {
            if (id == null) return AUTO;
            for (SessionBackend v : values()) {
                if (v.id.equalsIgnoreCase(id.trim())) return v;
            }
            return AUTO;
        }
    }

    /**
     * The SDK's own defaults (the {@code DEFAULT_*} constants on {@code api.BotSettings}), with real input off —
     * and isolation <b>on</b>, matching the SDK's default-on {@code Session}.
     */
    public static final BotSettings DEFAULTS =
            new BotSettings(false, 500, 200, 0.8, true, 0.05, 20, LinuxInputBackendId.AUTO, true, SessionBackend.AUTO);

    /**
     * What a Game-bot project starts with: {@link #DEFAULTS} but driving the real mouse and keyboard, because
     * a game ignores the quiet background events the SDK sends otherwise.
     */
    public static final BotSettings GAME_DEFAULTS = DEFAULTS.withRealInput(true);

    public BotSettings withRealInput(boolean enabled) {
        return new BotSettings(enabled, foundDelay, notFoundDelay, confidence, randomizeClicks, compareMargin,
                maxRetryAttempts, linuxInput, isolatedSession, sessionBackend);
    }

    /** This settings value with the session (private-display) part replaced — the dialog's save path. */
    public BotSettings withSession(boolean isolated, SessionBackend backend) {
        return new BotSettings(realInput, foundDelay, notFoundDelay, confidence, randomizeClicks, compareMargin,
                maxRetryAttempts, linuxInput, isolated, backend == null ? SessionBackend.AUTO : backend);
    }

    // --- reading ---

    /**
     * The project's current settings, from {@code botmaker-project.properties} in {@code resourcesDir}. Each
     * absent or unparseable key falls back to {@link #DEFAULTS}, so a project written before a setting existed
     * reads as that setting's default rather than failing.
     */
    public static BotSettings read(Path resourcesDir) {
        Properties props = ProjectCreator.readProjectProperties(resourcesDir);
        return new BotSettings(
                bool(props, ProjectProperties.KEY_INPUT_REAL, DEFAULTS.realInput()),
                integer(props, ProjectProperties.KEY_CLICKS_FOUND_DELAY, DEFAULTS.foundDelay()),
                integer(props, ProjectProperties.KEY_CLICKS_NOT_FOUND_DELAY, DEFAULTS.notFoundDelay()),
                real(props, ProjectProperties.KEY_VISION_CONFIDENCE, DEFAULTS.confidence()),
                bool(props, ProjectProperties.KEY_CLICKS_RANDOMIZE, DEFAULTS.randomizeClicks()),
                real(props, ProjectProperties.KEY_VISION_COMPARE_MARGIN, DEFAULTS.compareMargin()),
                integer(props, ProjectProperties.KEY_BOT_MAX_RETRY_ATTEMPTS, DEFAULTS.maxRetryAttempts()),
                LinuxInputBackendId.fromId(props.getProperty(ProjectProperties.KEY_INPUT_LINUX_BACKEND)),
                isolatedFrom(props),
                SessionBackend.fromId(props.getProperty(ProjectProperties.KEY_SESSION_BACKEND)));
    }

    /** {@code session.isolated}: on unless explicitly turned off, matching the SDK's default-on isolation. */
    private static boolean isolatedFrom(Properties props) {
        String spec = props.getProperty(ProjectProperties.KEY_SESSION_ISOLATED);
        if (spec == null || spec.isBlank()) {
            return DEFAULTS.isolatedSession();
        }
        return switch (spec.trim().toLowerCase()) {
            case "false", "0", "no", "off" -> false;
            default -> true;
        };
    }

    private static boolean bool(Properties props, String key, boolean fallback) {
        String v = value(props, key);
        if (v == null) return fallback;
        return switch (v.toLowerCase()) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static int integer(Properties props, String key, int fallback) {
        String v = value(props, key);
        try {
            return v == null ? fallback : (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double real(Properties props, String key, double fallback) {
        String v = value(props, key);
        try {
            return v == null ? fallback : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String value(Properties props, String key) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    // --- writing ---

    /**
     * Writes every setting into {@code botmaker-project.properties}, preserving the keys this record does not
     * own (capture source, launch target, resolution).
     *
     * <p>The two {@code AUTO} enum values <b>remove</b> their key rather than writing the string {@code "auto"}:
     * absent is what the SDK reads as "choose for me", and it is the state a project that never pinned one is
     * in — writing a value for it would make "never chose" and "chose automatic" different bytes.
     */
    public static void write(Path resourcesDir, BotSettings settings) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ProjectProperties.KEY_INPUT_REAL, Boolean.toString(settings.realInput()));
        values.put(ProjectProperties.KEY_CLICKS_FOUND_DELAY, Integer.toString(settings.foundDelay()));
        values.put(ProjectProperties.KEY_CLICKS_NOT_FOUND_DELAY, Integer.toString(settings.notFoundDelay()));
        values.put(ProjectProperties.KEY_CLICKS_RANDOMIZE, Boolean.toString(settings.randomizeClicks()));
        values.put(ProjectProperties.KEY_VISION_CONFIDENCE, Double.toString(settings.confidence()));
        values.put(ProjectProperties.KEY_VISION_COMPARE_MARGIN, Double.toString(settings.compareMargin()));
        values.put(ProjectProperties.KEY_BOT_MAX_RETRY_ATTEMPTS, Integer.toString(settings.maxRetryAttempts()));
        values.put(ProjectProperties.KEY_INPUT_LINUX_BACKEND,
                settings.linuxInput() == LinuxInputBackendId.AUTO ? null : settings.linuxInput().id());
        values.put(ProjectProperties.KEY_SESSION_ISOLATED, Boolean.toString(settings.isolatedSession()));
        values.put(ProjectProperties.KEY_SESSION_BACKEND,
                settings.sessionBackend() == SessionBackend.AUTO ? null : settings.sessionBackend().id());
        ProjectCreator.writeProjectKeys(resourcesDir, values);
    }

    // --- the migration from a generated BotSettings.java stood here until 2026-09-02 ---
    //
    // It moved a project's tuning out of a generated BotSettings.java into the properties file, deleted that
    // file and the `BotSettings.apply();` call the entry point made, and handled the older inline shape too.
    // Every line of it read one plugin's vocabulary out of the user's source with regexes: ClickConfig
    // .useRealInput, .setFoundDelay, .setDefaultConfidence, Session.enable/disable/useBackend, and an
    // `import com.botmaker.sdk.api.vision.ClickConfig;` spelled out in full. The editor does not know what a
    // ClickConfig is and has no business repairing source that names one.
    //
    // The accepted cost: a project last opened before that migration keeps its generated BotSettings.java and
    // its apply() call, and will not compile against a current SDK. Nothing repairs it automatically now.

}
