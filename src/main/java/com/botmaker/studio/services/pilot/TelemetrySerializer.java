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
 * {@code pilot-wire/wire-golden.json} corpus, and both assert its digest, so neither copy can move alone.
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
        return stateJson(runState, backgroundInput, null);
    }

    /**
     * The {@code state} message carrying an optional {@code reason} — a short human sentence for why the client
     * is not receiving frames, so a blank canvas says something instead of nothing.
     *
     * <p>The key is <b>omitted</b> when there is no reason, rather than sent as {@code null}. That is what keeps
     * the three no-reason cases in {@code wire-golden.json} byte-identical: the corpus is digest-locked against
     * a copy in the pilot repo that this session cannot edit, so an unconditional field would fail both halves
     * of the contract test at once. A corpus entry for the reason-bearing shape belongs with the client change
     * that renders it.
     */
    public static String stateJson(String runState, boolean backgroundInput, String reason) {
        return "{\"type\":\"state\",\"run\":\"" + runState + "\",\"backgroundInput\":" + backgroundInput
                + (reason == null ? "" : ",\"reason\":" + jsonStr(reason)) + "}";
    }

    /**
     * The {@code video} message: "binary frames from here on are H.264 access units for this codec, covering
     * this surface rect". Sent to a client that declared H.264 support, immediately before the first packet.
     *
     * <p>The rect is here rather than on each frame, which is the point of the message existing at all. A JPEG
     * frame carries a 16-byte header because its surface can change between any two frames; a video stream is
     * one encoder on one display, so the rect changes only when the stream does — sending it 24 times a second
     * would be 384 bytes/s of a constant, and parsing it would mean the client stripping a header off a buffer
     * it wants to hand to the decoder untouched.
     */
    public static String videoJson(String codec, int sx, int sy, int sw, int sh) {
        return "{\"type\":\"video\",\"codec\":" + jsonStr(codec)
                + ",\"sx\":" + sx + ",\"sy\":" + sy + ",\"sw\":" + sw + ",\"sh\":" + sh + "}";
    }

    /**
     * The {@code video} message that ends a stream — a null codec. The client tears its decoder down and goes
     * back to drawing the JPEG frames that resume in its place, so this is sent on <em>every</em> way a stream
     * can end (route change, encoder death, last H.264 client leaving) and not only on a tidy shutdown.
     */
    public static String videoStoppedJson() {
        return "{\"type\":\"video\",\"codec\":null}";
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
            case TelemetryEvent.Swipe s -> sb.append(",\"kind\":\"Swipe\"")
                    .append(",\"x1\":").append(s.x1()).append(",\"y1\":").append(s.y1())
                    .append(",\"x2\":").append(s.x2()).append(",\"y2\":").append(s.y2())
                    .append(",\"duration\":").append(s.durationMs());
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
