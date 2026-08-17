package com.botmaker.studio.project.settings;

/**
 * The text form of a {@link SettingType#DURATION} — {@code 90s}, {@code 5m}, {@code 1h30m}, {@code 250ms}.
 *
 * <p>Durations are stored as text rather than as a number of milliseconds because the unit is the part a
 * reader needs: {@code 90000} in a settings file says nothing, and the editor that wrote it had "a minute and
 * a half" in mind. ISO-8601 ({@code PT1M30S}) would round-trip just as well and reads like a protocol.
 *
 * <p>{@link #parse} is deliberately generous — it accepts any ordering, whitespace, a bare number (read as
 * milliseconds) and any subset of units — while {@link #format} emits exactly one canonical spelling.
 * That asymmetry is the point: a person can type {@code 90 s} and the file that comes back says {@code 1m30s},
 * so the stored form is stable no matter how it was entered, and a diff never churns on spacing.
 */
public final class DurationWire {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;

    private DurationWire() {}

    /**
     * The milliseconds {@code text} names, or {@code fallback} when it names nothing usable — an empty string,
     * a negative total, or a unit this doesn't know. Never throws: a wire value arrives from a file that a
     * person may have edited.
     */
    public static long parse(String text, long fallback) {
        if (text == null || text.isBlank()) return fallback;
        String s = text.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");

        long total = 0;
        long digits = 0;
        boolean sawDigit = false;
        boolean sawAny = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                digits = digits * 10 + (c - '0');
                if (digits > Integer.MAX_VALUE) return fallback;   // a bot delay measured in weeks is a typo
                sawDigit = true;
                continue;
            }
            if (!sawDigit) return fallback;                        // a unit with no number in front of it

            // "ms" is the only two-letter unit, and it must be checked before the bare "m" it starts with.
            if (c == 'm' && i + 1 < s.length() && s.charAt(i + 1) == 's') {
                total += digits;
                i++;
            } else if (c == 'h') {
                total += digits * HOUR;
            } else if (c == 'm') {
                total += digits * MINUTE;
            } else if (c == 's') {
                total += digits * SECOND;
            } else {
                return fallback;
            }
            digits = 0;
            sawDigit = false;
            sawAny = true;
        }

        // A trailing number with no unit is milliseconds, so a bare "500" still means something.
        if (sawDigit) {
            total += digits;
            sawAny = true;
        }
        return sawAny ? total : fallback;
    }

    /**
     * {@code millis} in the canonical spelling: the non-zero components in descending order, {@code 0s} for
     * nothing and for anything negative.
     */
    public static String format(long millis) {
        if (millis <= 0) return "0s";
        StringBuilder out = new StringBuilder();
        long left = millis;
        left = append(out, left, HOUR, "h");
        left = append(out, left, MINUTE, "m");
        left = append(out, left, SECOND, "s");
        if (left > 0) out.append(left).append("ms");
        return out.toString();
    }

    private static long append(StringBuilder out, long left, long unit, String suffix) {
        long count = left / unit;
        if (count > 0) out.append(count).append(suffix);
        return left % unit;
    }
}
