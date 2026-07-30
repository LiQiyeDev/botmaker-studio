package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.SessionKeyboard;
import com.botmaker.session.SessionPointer;

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

        @Override public GenericWindow getForegroundWindow() { return null; }
        @Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
        @Override public List<GenericWindow> getAllWindows() { return List.of(); }
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

        FakeSession(NativeController controller, GenericWindow attached, BufferedImage frame, Set<Capability> caps) {
            this.controller = controller;
            this.attached = attached;
            this.frame = frame;
            this.caps = caps;
        }

        @Override public Set<Capability> capabilities() { return caps; }
        @Override public Rectangle screen() { return attached == null ? new Rectangle() : attached.getRect(); }
        @Override public SessionPointer pointer() { return null; }
        @Override public SessionKeyboard keyboard() { return null; }
        @Override public void attach(GenericWindow window) { }
        @Override public GenericWindow attached() { return attached; }
        @Override public void launch(LaunchSpec spec) { }
        @Override public BufferedImage capture() { return frame; }
        @Override public NativeController controller() { return controller; }
        @Override public void close() { }
    }
}
