package com.botmaker.studio;

/**
 * Plain entry point for packaged / fat-jar launches.
 *
 * <p>A class that extends {@link javafx.application.Application} cannot be the launch main class when
 * the JavaFX modules are on the classpath rather than the module path (the JVM aborts with
 * "JavaFX runtime components are missing"). The shaded jar and the jpackage app-image therefore use
 * this wrapper, which does not extend {@code Application}, and delegates to the real entry point.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        BotMakerStudio.main(args);
    }
}
