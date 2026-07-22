package com.botmaker.studio.project.launch;

/**
 * The single place that turns a {@code launch.target} spec ({@code "<kind>:<rest>"}, as written into
 * {@code botmaker-project.properties}) into text a human reads, plus the two accessors every consumer would
 * otherwise re-split by hand. Shared by the Launch Target dialog, the toolbar's Launch Target button and the
 * Project Setup checklist so they can't drift on how a target is named — the same rule
 * {@code project/capture/CaptureTargetNames} follows for capture targets.
 *
 * <p>Parsing is total: an unknown kind, a missing colon or a {@code null} spec all yield something printable
 * rather than throwing, because the spec is user-editable text in a properties file.
 */
public final class LaunchTargetNames {

    private LaunchTargetNames() {}

    /** The {@code <kind>} of a spec ({@code "steam"}, {@code "heroic"}, …), or {@code null} if it has none. */
    public static String kindOf(String spec) {
        int colon = spec == null ? -1 : spec.indexOf(':');
        return colon <= 0 ? null : spec.substring(0, colon);
    }

    /** Everything after the first colon — the launch token (game id, path, command), or {@code null}. */
    public static String tokenOf(String spec) {
        int colon = spec == null ? -1 : spec.indexOf(':');
        return colon <= 0 || colon == spec.length() - 1 ? null : spec.substring(colon + 1);
    }

    /** A friendly one-line description, e.g. {@code "Steam game 570"}. {@code null} → {@code "(none)"}. */
    public static String describe(String spec) {
        if (spec == null || spec.isBlank()) return "(none)";
        String kind = kindOf(spec);
        String rest = tokenOf(spec);
        if (kind == null || rest == null) return spec;
        return switch (kind) {
            case "steam" -> "Steam game " + rest;
            case "epic" -> "Epic game " + rest;
            case "heroic" -> "Heroic game " + rest;
            case "cli" -> "Command: " + rest;
            case "exe" -> "Executable " + fileName(rest);
            case "emu-app" -> "Emulator app " + rest;
            default -> spec;
        };
    }

    /**
     * The short label for a button: {@code displayName} when the spec resolved to an installed game's title,
     * else the bare token (a file name for {@code exe:}), else {@code "Launch Target"} for no spec at all.
     */
    public static String shortLabel(String spec, String displayName) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (spec == null || spec.isBlank()) return "Launch Target";
        String kind = kindOf(spec);
        String rest = tokenOf(spec);
        if (kind == null || rest == null) return spec;
        return "exe".equals(kind) ? fileName(rest) : rest;
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }
}
