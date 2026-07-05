package com.botmaker.studio.sharing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny semantic-version helper for proposing the next publish tag. Pure and side-effect free.
 *
 * <p>Only the {@code MAJOR.MINOR.PATCH} core is understood (an optional leading {@code v} is preserved);
 * pre-release / build metadata is not. Anything it can't parse falls back to the first version, so the
 * publish dialog always has a sensible default.
 */
public final class SemVer {

    private SemVer() {}

    /** First version proposed when there is no prior tag (or it can't be parsed). */
    public static final String FIRST = "1.0.0";

    private static final Pattern CORE = Pattern.compile("^(v)?(\\d+)\\.(\\d+)\\.(\\d+)$");

    /** True if {@code tag} is a plain {@code [v]MAJOR.MINOR.PATCH} version. */
    public static boolean isValid(String tag) {
        return tag != null && CORE.matcher(tag.trim()).matches();
    }

    /**
     * The next version after {@code currentTag}: bumps the patch component and preserves an optional
     * {@code v} prefix. Returns {@link #FIRST} for null/blank/unparseable input.
     */
    public static String next(String currentTag) {
        return bump(currentTag, 2);
    }

    /** Next minor version (minor+1, patch reset to 0); {@link #FIRST} for unparseable input. */
    public static String nextMinor(String currentTag) {
        return bump(currentTag, 1);
    }

    /** Next major version (major+1, minor+patch reset to 0); {@link #FIRST} for unparseable input. */
    public static String nextMajor(String currentTag) {
        return bump(currentTag, 0);
    }

    /** Bumps component {@code idx} (0=major, 1=minor, 2=patch) and zeroes everything after it. */
    private static String bump(String currentTag, int idx) {
        if (currentTag == null) return FIRST;
        Matcher m = CORE.matcher(currentTag.trim());
        if (!m.matches()) return FIRST;
        String prefix = m.group(1) == null ? "" : m.group(1);
        int[] parts = {Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
        parts[idx]++;
        for (int i = idx + 1; i < parts.length; i++) parts[i] = 0;
        return prefix + parts[0] + "." + parts[1] + "." + parts[2];
    }

    /**
     * Compares two versions by their numeric {@code MAJOR.MINOR.PATCH} core (an optional {@code v}
     * prefix is ignored). An unparseable version sorts below any valid one (and two unparseable
     * versions compare equal).
     */
    public static int compare(String a, String b) {
        int[] pa = parse(a);
        int[] pb = parse(b);
        if (pa == null && pb == null) return 0;
        if (pa == null) return -1;
        if (pb == null) return 1;
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(pa[i], pb[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    /**
     * True if {@code candidate} is a valid version strictly greater than {@code baseline}. A blank or
     * invalid {@code baseline} imposes no lower bound (any valid {@code candidate} passes).
     */
    public static boolean isGreater(String candidate, String baseline) {
        if (!isValid(candidate)) return false;
        if (baseline == null || baseline.isBlank() || !isValid(baseline)) return true;
        return compare(candidate, baseline) > 0;
    }

    private static int[] parse(String tag) {
        if (tag == null) return null;
        Matcher m = CORE.matcher(tag.trim());
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
    }
}
