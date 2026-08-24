package com.botmaker.studio.project.activity;

/**
 * The text form of a {@link com.botmaker.studio.palette.BotType#DURATION} — {@code 90s}, {@code 5m}, {@code 1h30m}, {@code 250ms}.
 *
 * <p>Durations are stored as text rather than as a number of milliseconds because the unit is the part a
 * reader needs: {@code 90000} in a settings file says nothing, and the editor that wrote it had "a minute and
 * a half" in mind. ISO-8601 ({@code PT1M30S}) would round-trip just as well and reads like a protocol.
 *
 * <p><b>Only the spelling lives here.</b> Reading {@code 1h30m} back is
 * {@link com.botmaker.sdk.api.config.Wire#duration}'s job, in the SDK, because the running bot has to do it
 * too and cannot call into Studio. This class used to carry a {@code parse} of its own, and the generated
 * {@code Activities} carried a third copy as a text block, with a comment asking the next reader to diff the
 * two by eye. One grammar, one implementation.
 *
 * <p>What is left is the asymmetry that made the pair worth having: reading is deliberately generous — any
 * ordering, whitespace, a bare number read as milliseconds, any subset of units — while {@link #format} emits
 * exactly one canonical spelling. A person can type {@code 90 s} and the file that comes back says
 * {@code 1m30s}, so the stored form is stable however it was entered and a diff never churns on spacing.
 */
public final class DurationWire {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;

    private DurationWire() {}

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
