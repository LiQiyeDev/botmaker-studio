package com.botmaker.studio.services.capture;

import com.botmaker.shared.config.CaptureSourceKind;

/**
 * Something on this machine to point a capture at — a window, a monitor, the whole desktop, an emulator.
 *
 * <h2>Why Studio has one of these at all</h2>
 *
 * <p>This is not the project's capture target. That is {@code capture.json}, it describes what the <em>bot</em>
 * looks at, and it belongs to the plugin that owns {@code CaptureSource} — which is why
 * {@code com.botmaker.sdk.authoring.CaptureTargetModel} is not named here and Studio no longer reads that file.
 *
 * <p>What Studio still needs, and what this is, is a way to say <b>which window the editor is drawing over</b>.
 * The overlay editor is Studio's (see {@code docs/refactor/24-plugin-platform.md} §2), and a window to draw on
 * is a fact about this desktop rather than about anybody's API: {@code botmaker-shared} enumerates windows,
 * grabs their pixels and owns the {@link CaptureSourceKind} vocabulary this is written in. So the type is
 * Studio's, the spec grammar is shared's, and no plugin is involved either way.
 *
 * <p>It deliberately parses nothing itself — {@link CaptureSourceKind} already answers every question about a
 * spec string, and a second parser is a second answer that drifts.
 *
 * @param spec  the {@code kind:argument} string, in shared's grammar
 * @param label how to name it to a person; blank falls back to the spec
 */
public record CaptureTarget(String spec, String label) {

    public CaptureTarget {
        spec = spec == null ? "" : spec.trim();
        label = label == null ? "" : label.trim();
    }

    /** A target from its spec, labelled by the spec itself. */
    public static CaptureTarget of(String spec) {
        return new CaptureTarget(spec, "");
    }

    /** The window whose title contains {@code titleSubstring}. */
    public static CaptureTarget window(String titleSubstring) {
        return new CaptureTarget(CaptureSourceKind.WINDOW.spec(titleSubstring), "");
    }

    /** What kind of thing this names, read by shared's own parser. */
    public CaptureSourceKind kind() {
        return CaptureSourceKind.of(spec);
    }

    public boolean is(CaptureSourceKind wanted) {
        return wanted != null && wanted == kind();
    }

    /**
     * Whether this means the whole virtual desktop.
     *
     * <p>True for a spec nothing recognises as well, which is the fallback every reader of a target takes: an
     * unreadable target is better read as "everything" than as a dialog the user did not ask for.
     */
    public boolean isDesktop() {
        CaptureSourceKind kind = kind();
        return kind == null || kind == CaptureSourceKind.DESKTOP;
    }

    /** The monitor this names, or {@code -1} when it names something else or the index will not parse. */
    public int monitorIndex() {
        if (!is(CaptureSourceKind.MONITOR)) return -1;
        try {
            return Integer.parseInt(CaptureSourceKind.MONITOR.argumentOf(spec).trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The title substring this names, or {@code null} when it does not name a window. */
    public String windowTitle() {
        return is(CaptureSourceKind.WINDOW) ? CaptureSourceKind.WINDOW.argumentOf(spec) : null;
    }

    /** The emulator instance this names, or {@code null} when it does not name one. */
    public String emulatorName() {
        return is(CaptureSourceKind.EMULATOR) ? CaptureSourceKind.EMULATOR.argumentOf(spec) : null;
    }

    /** A short name for a chip or a status line — the label when there is one, else the spec. */
    public String shortLabel() {
        if (!label.isEmpty()) return label;
        String title = windowTitle();
        if (title != null && !title.isBlank()) return title;
        return spec.isEmpty() ? "Screen" : spec;
    }
}
