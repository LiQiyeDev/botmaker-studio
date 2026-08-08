package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.PreviewFrame;
import com.botmaker.session.SessionKeyboard;
import com.botmaker.session.SessionPointer;
import com.botmaker.studio.emulator.EmulatorSurface;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared test doubles for the pilot session-routing tests: a {@link NativeController} that records the input
 * calls made to it, and a {@link DesktopSession} that hands back a fixed controller / attached window / frame.
 * Everything the pilot doesn't exercise is a harmless stub.
 */
final class PilotFakes {

    private PilotFakes() {}

    /** Records mouse gestures and capture calls; the rest of {@link NativeController} is stubbed. */
    static final class RecordingController implements NativeController {
        final List<String> calls = new ArrayList<>();
        BufferedImage windowFrame;
        boolean background;
        boolean reliableCalled;
        /** Non-null makes the cursor-restoring paths actually restore — the difference a session tap must not make. */
        java.awt.Point cursor;
        /** What a {@code :0} window search sees, for the title-matched desktop target path. */
        final List<GenericWindow> windows = new ArrayList<>();

        @Override public GenericWindow getForegroundWindow() { return null; }
        @Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
        @Override public List<GenericWindow> getAllWindows() { return windows; }
        @Override public BufferedImage captureWindow(GenericWindow window) { calls.add("capture"); return windowFrame; }
        @Override public void postLeftClick(GenericWindow window, int x, int y) { }
        @Override public void focusWindow(GenericWindow window) { }
        @Override public void moveWindow(GenericWindow window, int x, int y) { }
        @Override public void resizeWindow(GenericWindow window, int width, int height) { }
        @Override public void keyDown(int nativeKeyCode) { }
        @Override public void keyUp(int nativeKeyCode) { }
        @Override public void typeText(String text) { }
        @Override public void mouseMove(int xAbs, int yAbs) { calls.add("move " + xAbs + "," + yAbs); }
        @Override public void mouseButton(int button, boolean press) { calls.add("button " + button + " " + press); }
        @Override public void scroll(int amount) { calls.add("scroll " + amount); }
        @Override public java.awt.Point cursorPosition() { return cursor; }
        @Override public boolean supportsBackgroundInput() { return background; }
        @Override public boolean useReliableInput() { reliableCalled = true; return true; }
    }

    /** A session that just wraps a controller, an attached window and a frame — no real display behind it. */
    static final class FakeSession implements DesktopSession {
        private final NativeController controller;
        private final GenericWindow attached;
        private final BufferedImage frame;
        private final Set<Capability> caps;

        /** The {@code :N} root frame, when this session has one distinct from its window frame. */
        BufferedImage screenFrame;

        /** The session screen, when it is not simply the attached window's rect. */
        Rectangle screenRect;

        /** Whether this session's pixels are on X11 — false stands in for gamescope hosting a Wayland-only client. */
        boolean x11Capturable = true;

        /**
         * A pre-encoded preview, standing in for a display agent that encoded a frame itself — carrying the
         * rect it chose, which is deliberately settable apart from {@link #screenRect}: a compositing backend
         * answers with a <em>window's</em> rect, and the whole point of the record is that the caller must not
         * assume otherwise. {@code null} — the default, and what the real {@code DesktopSession} answers unless
         * it is nested — means the caller must fall back to grabbing and encoding pixels here.
         */
        PreviewFrame previewFrame;

        FakeSession(NativeController controller, GenericWindow attached, BufferedImage frame, Set<Capability> caps) {
            this.controller = controller;
            this.attached = attached;
            this.frame = frame;
            this.caps = caps;
        }

        @Override public Set<Capability> capabilities() { return caps; }

        @Override
        public Rectangle screen() {
            if (screenRect != null) return screenRect;
            return attached == null ? new Rectangle() : attached.getRect();
        }

        @Override
        public BufferedImage captureScreen() {
            return screenFrame != null ? screenFrame : frame;
        }

        @Override public boolean x11Capturable() { return x11Capturable; }
        @Override public PreviewFrame previewFrame(int maxEdge, float quality) { return previewFrame; }
        @Override public SessionPointer pointer() { return null; }
        @Override public SessionKeyboard keyboard() { return null; }
        @Override public void attach(GenericWindow window) { }
        @Override public GenericWindow attached() { return attached; }
        @Override public void launch(LaunchSpec spec) { }
        @Override public BufferedImage capture() { return frame; }
        @Override public NativeController controller() { return controller; }
        @Override public void close() { }
    }

    /**
     * An {@link EmulatorSurface} that records the ADB verbs asked of it and hands back a fixed frame — the
     * emulator counterpart of {@link RecordingController}, so the emulator route can be tested with no ADB,
     * no emulator and no network.
     */
    static final class RecordingSurface implements EmulatorSurface {
        final List<String> calls = new ArrayList<>();
        BufferedImage frame;
        boolean closed;

        RecordingSurface(BufferedImage frame) {
            this.frame = frame;
        }

        @Override public String instanceName() { return "FakeDroid"; }
        @Override public BufferedImage grab() { calls.add("grab"); return frame; }
        @Override public void tap(int x, int y) { calls.add("tap " + x + "," + y); }

        @Override
        public void drag(int x1, int y1, int x2, int y2, long durationMs) {
            // The duration is wall-clock, so it is deliberately not recorded — asserting on it would make the
            // test time-dependent for no gain.
            calls.add("drag " + x1 + "," + y1 + "->" + x2 + "," + y2);
        }

        @Override public void scroll(int x, int y, int amount) { calls.add("scroll " + x + "," + y + " " + amount); }
        @Override public void close() { closed = true; }
    }
}
