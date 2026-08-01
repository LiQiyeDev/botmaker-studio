package com.botmaker.studio.project;

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
                          LinuxInput linuxInput,
                          boolean isolatedSession,
                          SessionBackend sessionBackend) {

    /**
     * Which Linux backend delivers real input, mirroring {@code LinuxController.selectBackend}'s
     * {@code botmaker.linux.input} property. {@link #AUTO} is the SDK's own choice and writes no key at
     * all — the others exist because "it works on my machine" here means a udev rule, an ACL or an installed
     * {@code xdotool}, and pinning the one that works on <em>this</em> machine is a per-project answer.
     */
    public enum LinuxInput {
        AUTO("auto", "Automatic (uinput, then xdotool, then XTest)"),
        UINPUT("uinput", "uinput — a virtual device the kernel reports as real"),
        XDOTOOL("xdotool", "xdotool — the XTEST extension via the xdotool command"),
        XTEST("xtest", "XTest — X11's own synthetic input");

        private final String id;
        private final String label;

        LinuxInput(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** The value written as {@code input.linuxBackend}; stable, it is persisted. */
        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** Total parse: an unrecognised id (a newer Studio's, a typo in a hand-edit) reads back as {@link #AUTO}. */
        public static LinuxInput fromId(String id) {
            if (id == null) return AUTO;
            for (LinuxInput v : values()) {
                if (v.id.equalsIgnoreCase(id.trim())) return v;
            }
            return AUTO;
        }
    }

    /**
     * Which nested display hosts an isolated bot, mirroring the SDK's {@code Session.useBackend} and the
     * project's {@code session.backend} key. {@link #AUTO} is the SDK's own kind-driven choice — a game gets
     * gamescope (a real GPU in the private display), a plain command the lighter Xephyr — and, like
     * {@link LinuxInput#AUTO}, it writes no key at all.
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
            new BotSettings(false, 500, 200, 0.8, true, 0.05, 20, LinuxInput.AUTO, true, SessionBackend.AUTO);

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
                LinuxInput.fromId(props.getProperty(ProjectProperties.KEY_INPUT_LINUX_BACKEND)),
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
                settings.linuxInput() == LinuxInput.AUTO ? null : settings.linuxInput().id());
        values.put(ProjectProperties.KEY_SESSION_ISOLATED, Boolean.toString(settings.isolatedSession()));
        values.put(ProjectProperties.KEY_SESSION_BACKEND,
                settings.sessionBackend() == SessionBackend.AUTO ? null : settings.sessionBackend().id());
        ProjectCreator.writeProjectKeys(resourcesDir, values);
    }

    // --- migration from the generated BotSettings.java ---

    /** The generated file's name, as projects created before this change still carry it on disk. */
    private static final String LEGACY_FILE_NAME = "BotSettings.java";

    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";

    private static Pattern call(String method, String valueGroup) {
        return Pattern.compile("ClickConfig\\s*\\.\\s*" + method + "\\s*\\(\\s*(" + valueGroup + ")\\s*\\)");
    }

    private static final Pattern LEGACY_REAL_INPUT = call("useRealInput", "true|false");
    private static final Pattern LEGACY_FOUND_DELAY = call("setFoundDelay", NUMBER);
    private static final Pattern LEGACY_NOT_FOUND_DELAY = call("setNotFoundDelay", NUMBER);
    private static final Pattern LEGACY_CONFIDENCE = call("setDefaultConfidence", NUMBER);
    private static final Pattern LEGACY_RANDOM_CLICKS = call("enableRandomClicks", "true|false");
    private static final Pattern LEGACY_MAX_RETRIES = call("setMaxRetryAttempts", NUMBER);
    private static final Pattern LEGACY_COMPARE_MARGIN = Pattern.compile(
            "ClickConfig\\s*\\.\\s*DEFAULT_COMPARE_MARGIN\\s*=\\s*(" + NUMBER + ")");
    private static final Pattern LEGACY_LINUX_INPUT = Pattern.compile(
            "setProperty\\s*\\(\\s*\"botmaker\\.linux\\.input\"\\s*,\\s*\"([^\"]*)\"\\s*\\)");
    /**
     * The isolation statement in any of the three spellings the facade offered — {@code Session.disable()},
     * {@code Session.enable()} and {@code Session.set(<bool>)} — because a user hand-editing the generated file
     * reached for whichever read best, and all three were real API.
     */
    private static final Pattern LEGACY_SESSION_ISOLATED = Pattern.compile(
            "Session\\s*\\.\\s*(?:(enable|disable)\\s*\\(\\s*\\)|set\\s*\\(\\s*(true|false)\\s*\\))");
    private static final Pattern LEGACY_SESSION_BACKEND = Pattern.compile(
            "Session\\s*\\.\\s*useBackend\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");

    /** The {@code BotSettings.apply();} call the generated entry point made, with its indentation and newline. */
    private static final Pattern APPLY_CALL = Pattern.compile("[ \\t]*BotSettings\\s*\\.\\s*apply\\s*\\(\\s*\\)\\s*;"
            + "[ \\t]*\\r?\\n?");

    /**
     * The even older form: an inline {@code ClickConfig.useRealInput(...)} in {@code main}, from before the
     * generated file existed at all. A project can be at either stage, so both are handled.
     */
    private static final Pattern LEGACY_INLINE_REAL_INPUT = Pattern.compile(
            "[ \\t]*(?:com\\.botmaker\\.sdk\\.api\\.vision\\.)?ClickConfig\\s*\\.\\s*useRealInput\\s*\\(\\s*"
                    + "(?:true|false)\\s*\\)\\s*;[ \\t]*\\r?\\n?");

    /**
     * Moves a project's tuning out of the generated {@code BotSettings.java} and into
     * {@code botmaker-project.properties}, then deletes that file and the {@code BotSettings.apply();} call the
     * entry point made.
     *
     * <p>Both halves matter and both fail quietly if skipped: leaving the values behind silently reverts the
     * project to SDK defaults (a game bot stops driving real input, and nothing says so), and leaving the file
     * behind leaves generated source calling a facade that no longer exists — which fails at compile time, but
     * in a file the user never wrote.
     *
     * <p>Runs on project open and is a no-op once there is no legacy file, so it costs one {@code exists} check
     * per open. It also handles the older shape where the settings were an inline call in {@code main}.
     *
     * @return the entry point's new contents when it was rewritten, or {@code null} when nothing changed — so
     *         the caller can refresh the copy the editor holds in memory
     */
    public static String migrate(ProjectConfig config) throws IOException {
        Path main = config.mainSourceFile();
        Path legacy = legacyFile(main);
        boolean hasLegacyFile = legacy != null && Files.exists(legacy);
        String source = main != null && Files.exists(main) ? Files.readString(main) : null;
        boolean hasInlineCall = source != null && LEGACY_INLINE_REAL_INPUT.matcher(source).find();
        if (!hasLegacyFile && !hasInlineCall) {
            return null;
        }

        // Values first: if the rewrite below fails halfway, the settings are already safe in the properties file
        // rather than in a source file that has just been deleted.
        String legacySource = hasLegacyFile ? readOrEmpty(legacy) : source;
        write(config.resourcesRoot(), parseLegacy(legacySource, config.resourcesRoot()));
        if (hasLegacyFile) {
            Files.deleteIfExists(legacy);
        }
        if (source == null) {
            return null;
        }

        String updated = removeFirst(source, APPLY_CALL);
        updated = removeFirst(updated, LEGACY_INLINE_REAL_INPUT);
        // The import goes with the call it served: a leftover import of a type the entry point no longer mentions
        // is a warning the user didn't write. "No longer mentions" is judged with the import line itself removed —
        // that is the one occurrence which must not count.
        String importLine = "import com.botmaker.sdk.api.vision.ClickConfig;\n";
        String withoutImport = updated.replace(importLine, "");
        if (!withoutImport.contains("ClickConfig")) {
            updated = withoutImport;
        }
        if (updated.equals(source)) {
            return null;
        }
        Files.writeString(main, updated);
        return updated;
    }

    /** {@code BotSettings.java} beside the entry point — where the generated file used to sit. */
    private static Path legacyFile(Path mainSourceFile) {
        Path dir = mainSourceFile == null ? null : mainSourceFile.getParent();
        return dir == null ? null : dir.resolve(LEGACY_FILE_NAME);
    }

    /**
     * The settings the legacy source spells out, falling back per setting to what the project already has —
     * <em>not</em> to {@link #DEFAULTS}. The session keys in particular were always written to the properties
     * file as well, and that copy is the one Studio itself read, so a generated file that never mentioned
     * {@code Session} must not now overwrite them.
     */
    private static BotSettings parseLegacy(String source, Path resourcesDir) {
        BotSettings existing = read(resourcesDir);
        String isolated = null;
        Matcher m = LEGACY_SESSION_ISOLATED.matcher(source);
        if (m.find()) {
            isolated = m.group(1) != null ? Boolean.toString("enable".equals(m.group(1))) : m.group(2);
        }
        return new BotSettings(
                legacyBool(LEGACY_REAL_INPUT, source, existing.realInput()),
                legacyInt(LEGACY_FOUND_DELAY, source, existing.foundDelay()),
                legacyInt(LEGACY_NOT_FOUND_DELAY, source, existing.notFoundDelay()),
                legacyReal(LEGACY_CONFIDENCE, source, existing.confidence()),
                legacyBool(LEGACY_RANDOM_CLICKS, source, existing.randomizeClicks()),
                legacyReal(LEGACY_COMPARE_MARGIN, source, existing.compareMargin()),
                legacyInt(LEGACY_MAX_RETRIES, source, existing.maxRetryAttempts()),
                legacyText(LEGACY_LINUX_INPUT, source) == null ? existing.linuxInput()
                        : LinuxInput.fromId(legacyText(LEGACY_LINUX_INPUT, source)),
                isolated == null ? existing.isolatedSession() : Boolean.parseBoolean(isolated),
                legacyText(LEGACY_SESSION_BACKEND, source) == null ? existing.sessionBackend()
                        : SessionBackend.fromId(legacyText(LEGACY_SESSION_BACKEND, source)));
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    /** Removes {@code pattern}'s first match from {@code source}, or returns it unchanged when there is none. */
    private static String removeFirst(String source, Pattern pattern) {
        Matcher m = pattern.matcher(source);
        return m.find() ? new StringBuilder(source).delete(m.start(), m.end()).toString() : source;
    }

    private static String legacyText(Pattern p, String source) {
        Matcher m = p.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private static boolean legacyBool(Pattern p, String source, boolean fallback) {
        String v = legacyText(p, source);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    private static int legacyInt(Pattern p, String source, int fallback) {
        String v = legacyText(p, source);
        try {
            return v == null ? fallback : (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double legacyReal(Pattern p, String source, double fallback) {
        String v = legacyText(p, source);
        try {
            return v == null ? fallback : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
