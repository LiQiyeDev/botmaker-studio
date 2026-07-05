package com.botmaker.studio.ui.render.layout;

/**
 * Main entry point for building block UIs.
 * Provides fluent API for common block layouts.
 */
public class BlockLayout {

    // Factory methods for the layouts blocks actually use. A header() can continue into a body via
    // HeaderLayoutBuilder.andBody().
    public static HeaderLayoutBuilder header() {
        return new HeaderLayoutBuilder();
    }

    public static SentenceLayoutBuilder sentence() {
        return new SentenceLayoutBuilder();
    }
}