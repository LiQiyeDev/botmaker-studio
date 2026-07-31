package com.botmaker.studio.services.pilot;

import com.botmaker.shared.ipc.TelemetryEvent;

import java.util.Locale;

/**
 * Serializes a decoded {@link TelemetryEvent} to the compact JSON shape the pilot client consumes
 * (kind, target, rect/region/click coords, confidence), and the {@code state} message alongside it — every
 * text message Studio sends a pilot client is built here, so the wire schema has one owner.
 *
 * <p>The pilot web app's {@code types.ts} mirrors it field for field, and what keeps that true is
 * {@code TelemetryWireContractTest} plus its counterpart in the pilot repo: both read the same
 * {@code pilot/wire-golden.json} corpus, and both assert its digest, so neither copy can move alone.
 */
public final class TelemetrySerializer {

    private TelemetrySerializer() {}

    /** The full {@code telemetry} text message, as it goes out on the socket. */
    public static String telemetryJson(TelemetryEvent te) {
        return "{\"type\":\"telemetry\",\"event\":" + eventJson(te) + "}";
    }

    /**
     * The full {@code state} text message. {@code backgroundInput} tells the client whether Interact will
     * leave the host's real cursor alone, so it can warn before the user's pointer visibly gets hijacked.
     */
    public static String stateJson(String runState, boolean backgroundInput) {
        return "{\"type\":\"state\",\"run\":\"" + runState + "\",\"backgroundInput\":" + backgroundInput + "}";
    }

    /** The event body only (no {@code type} wrapper) — callers wrap it as needed for SSE vs. WS. */
    public static String eventJson(TelemetryEvent te) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"ts\":").append(System.currentTimeMillis());
        TelemetryEvent.Target t = te.target();
        sb.append(",\"target\":").append(targetJson(t));
        switch (te) {
            case TelemetryEvent.Match m -> sb.append(",\"kind\":\"Match\"")
                    .append(",\"found\":").append(m.found())
                    .append(",\"confidence\":").append(String.format(Locale.US, "%.4f", m.confidence()))
                    .append(",\"region\":").append(rectJson(m.region()))
                    .append(",\"rect\":").append(rectJson(m.rect()));
            case TelemetryEvent.Click c -> sb.append(",\"kind\":\"Click\"")
                    .append(",\"x\":").append(c.x()).append(",\"y\":").append(c.y())
                    .append(",\"button\":").append(c.button());
            case TelemetryEvent.Region r -> sb.append(",\"kind\":\"Region\"")
                    .append(",\"rect\":").append(rectJson(r.rect()));
        }
        return sb.append("}").toString();
    }

    static String targetJson(TelemetryEvent.Target t) {
        if (t == null) return "null";
        return String.format(Locale.US,
                "{\"title\":%s,\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}",
                jsonStr(t.title()), t.x(), t.y(), t.width(), t.height());
    }

    static String rectJson(TelemetryEvent.Rect r) {
        if (r == null) return "null";
        return String.format(Locale.US,
                "{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}", r.x(), r.y(), r.width(), r.height());
    }

    static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
