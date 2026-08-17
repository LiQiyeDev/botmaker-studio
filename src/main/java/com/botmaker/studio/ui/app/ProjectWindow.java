package com.botmaker.studio.ui.app;

import javafx.scene.Scene;

/**
 * The window an open project is shown in — one of exactly two, chosen by <em>who is looking</em>.
 *
 * <p>{@link UIManager} builds the editor: explorer, toolbar, block canvas, the whole IDE. The Runner
 * ({@code ui.app.runner.RunnerWindow}) builds what someone who just wants to <em>use</em> the bot needs:
 * the two targets, which activities to run, the settings the editor exposed, and Run/Stop.
 *
 * <p>They are alternatives rather than one window with things hidden, and that is the point: a control that
 * is never constructed cannot be reached by a keyboard shortcut, an accelerator, or the next feature someone
 * adds without thinking about audiences. This interface exists only so {@code BotMakerStudio} can hold
 * whichever one it built and release it the same way.
 */
public interface ProjectWindow {

    /** Builds the scene for this window. Called once, on the FX thread. */
    Scene createScene();

    /** Releases what the window acquired — OS resources, static listeners, event subscriptions. Idempotent. */
    void dispose();
}
