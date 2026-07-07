package com.botmaker.studio.ui.fx;

import org.testfx.framework.junit5.ApplicationTest;

/**
 * Base class for headless JavaFX UI tests.
 *
 * <p>Extends TestFX's {@link ApplicationTest}, which starts the real JavaFX runtime, drives the real
 * scene graph with a robot (real mouse/keyboard events on real controls) and lets tests assert on
 * rendered nodes. The Monocle <em>Headless</em> platform makes this run with <b>no X server / display</b>,
 * so the same tests pass on a developer laptop and in CI — this is the automated counterpart to the
 * manual {@code testing/linux} x11docker harness (which instead exercises the native window layer in
 * {@code botmaker-shared} that genuinely needs a real display server).
 *
 * <p>The Monocle system properties are normally set by Surefire (see {@code pom.xml}); the static block
 * below re-applies them as a fallback so the tests are also headless when run straight from an IDE.
 * They must be in place before the FX toolkit initialises, hence a static initialiser.
 *
 * <p>Subclasses override {@link ApplicationTest#start(javafx.stage.Stage)} to install the scene under
 * test, then use {@code lookup(...)}, {@code clickOn(...)}, {@code write(...)}, {@code verifyThat(...)}
 * to interact and assert.
 */
public abstract class FxHeadlessTest extends ApplicationTest {

    static {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
    }
}
