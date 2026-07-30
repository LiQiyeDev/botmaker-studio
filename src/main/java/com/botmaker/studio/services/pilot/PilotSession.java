package com.botmaker.studio.services.pilot;

import com.botmaker.session.DesktopSession;

/**
 * The single switch that decides <em>which display</em> the pilot's capture and Interact input act on: the
 * user's real {@code :0} desktop (the default — {@code null} session) or a bot-owned nested {@code :N}
 * {@link DesktopSession}.
 *
 * <p>This is the whole point of Phase 5. When a nested session is active, {@link TargetCapture} previews the
 * window that session launched into {@code :N} and {@link PilotInputService} drives that session's
 * {@code :N}-bound controller — so capture and input share <em>one</em> coordinate space (the nested screen)
 * and every gesture is a flawless background click by construction, with no {@code :0} cursor to hijack and no
 * {@code useReliableInput()} escalation to pay for. Clear it and the pilot falls straight back to today's
 * {@code :0} behaviour, unchanged.
 *
 * <p>Held here rather than passed through each call so a session can be swapped (launched / closed) while the
 * pilot server runs; the field is {@code volatile} because the frame loop, the WS command thread and the UI
 * thread all read it. One holder instance is shared by the server, its capture and its input service.
 */
public final class PilotSession {

    private volatile DesktopSession active;

    /** Route the pilot through {@code session}'s nested {@code :N} display; {@code null} restores the {@code :0} path. */
    public void set(DesktopSession session) {
        this.active = session;
    }

    /** Restore the default {@code :0} path (equivalent to {@code set(null)}). */
    public void clear() {
        this.active = null;
    }

    /** The active nested session, or {@code null} when the pilot should use the real {@code :0} desktop. */
    public DesktopSession get() {
        return active;
    }
}
