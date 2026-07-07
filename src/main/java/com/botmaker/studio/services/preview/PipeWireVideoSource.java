package com.botmaker.studio.services.preview;

import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.elements.AppSink;

import java.nio.ByteBuffer;

/**
 * Plays a live PipeWire screen stream (from {@link PortalScreenCast}) through a GStreamer pipeline
 * {@code pipewiresrc fd=… path=… ! videoconvert ! video/x-raw,format=BGRx ! appsink} and pushes each decoded
 * frame to a {@link FrameListener} as a packed ARGB {@code int[]}. This is the "live video" feed for the
 * preview panel on Wayland — no per-frame portal prompt, and independent of any other screen-cast consumer.
 *
 * <p>Requires native GStreamer with the PipeWire plugin (Fedora: {@code gstreamer1-plugins-good}). All entry
 * points are guarded; if GStreamer can't initialize or the pipeline errors, {@link #start()} throws and the
 * caller falls back to AWT {@code Robot}.
 */
public final class PipeWireVideoSource implements AutoCloseable {

    /** Receives one decoded frame as a packed ARGB {@code int[]} of length {@code width*height}. */
    public interface FrameListener { void onFrame(int[] argb, int width, int height); }

    private static volatile boolean gstInitAttempted;
    private static volatile boolean gstInitOk;

    private final int fd;
    private final int nodeId;
    private final FrameListener onFrame;

    private Pipeline pipeline;
    private volatile boolean running;

    public PipeWireVideoSource(int fd, int nodeId, FrameListener onFrame) {
        this.fd = fd;
        this.nodeId = nodeId;
        this.onFrame = onFrame;
    }

    /** One-time, process-wide GStreamer init; returns false (does not throw) if the native stack is missing. */
    private static synchronized boolean ensureGstInit() {
        if (gstInitAttempted) return gstInitOk;
        gstInitAttempted = true;
        try {
            Gst.init("BotMakerPreview", new String[0]);
            gstInitOk = true;
        } catch (Throwable t) {
            System.err.println("[preview] GStreamer unavailable (" + t.getMessage() + "); using Robot fallback.");
            gstInitOk = false;
        }
        return gstInitOk;
    }

    /** Builds and starts the pipeline. Throws if GStreamer/PipeWire is unavailable so the caller can fall back. */
    public void start() throws Exception {
        if (!ensureGstInit()) throw new IllegalStateException("GStreamer not available");

        // fd is duplicated by pipewiresrc; the source keeps ownership so the portal session stays open.
        String desc = "pipewiresrc fd=" + fd + " path=" + nodeId + " keepalive-time=1000 "
                + "! videoconvert ! video/x-raw,format=BGRx ! appsink name=sink emit-signals=true max-buffers=2 drop=true";
        pipeline = (Pipeline) Gst.parseLaunch(desc);
        AppSink sink = (AppSink) pipeline.getElementByName("sink");
        sink.set("emit-signals", true);
        sink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = elem.pullSample();
            if (sample == null) return FlowReturn.OK;
            try {
                if (running) emit(sample);
            } finally {
                sample.dispose();
            }
            return FlowReturn.OK;
        });
        running = true;
        pipeline.play();
    }

    /** Decodes a BGRx GStreamer sample to a packed ARGB {@code int[]} (assumes tightly-packed rows). */
    private void emit(Sample sample) {
        Caps caps = sample.getCaps();
        if (caps == null || caps.size() == 0) return;
        Structure s = caps.getStructure(0);
        int w = s.getInteger("width");
        int h = s.getInteger("height");
        if (w <= 0 || h <= 0) return;

        Buffer buffer = sample.getBuffer();
        ByteBuffer bb = buffer.map(false);
        if (bb == null) return;
        try {
            int pixels = w * h;
            if (bb.remaining() < pixels * 4) return;
            byte[] row = new byte[pixels * 4];
            bb.get(row, 0, pixels * 4);
            int[] argb = new int[pixels];
            for (int i = 0, p = 0; i < pixels; i++, p += 4) {
                int b = row[p] & 0xFF, g = row[p + 1] & 0xFF, r = row[p + 2] & 0xFF; // BGRx
                argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            onFrame.onFrame(argb, w, h);
        } finally {
            buffer.unmap();
        }
    }

    @Override
    public void close() {
        running = false;
        if (pipeline != null) {
            try {
                pipeline.stop();
                pipeline.dispose();
            } catch (Throwable ignored) {}
            pipeline = null;
        }
    }
}
