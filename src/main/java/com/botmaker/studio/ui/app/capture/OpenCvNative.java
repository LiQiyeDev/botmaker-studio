package com.botmaker.studio.ui.app.capture;

import nu.pattern.OpenCV;

/**
 * Idempotent OpenCV native loader for the Studio.
 *
 * <p>Mirrors {@code com.botmaker.sdk.internal.opencv.OpenCvNative} in the SDK — the two modules pin the same
 * {@code org.openpnp:opencv} artifact, so they must extract and load the native library the same way. Call
 * {@link #ensureLoaded()} from a {@code static {}} block on any class that links an {@code org.opencv} type
 * before it is first touched; today that is {@link MagicWand} only.
 */
public final class OpenCvNative {

    private OpenCvNative() {}

    private static volatile boolean loaded = false;

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        // OpenPnP handles extracting and loading the correct OS native library automatically.
        OpenCV.loadLocally();
        loaded = true;
    }
}
