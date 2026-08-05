package com.botmaker.studio.emulator;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorReadiness;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Editor-side liveness and one-shot queries against a discovered emulator instance, for the pickers that
 * render it ({@link com.botmaker.studio.ui.render.components.EmulatorPickerDialog}, the capture-source
 * picker). Both used to carry their own byte-identical copies of these; they live here so the pickers can't
 * drift apart on timeouts or failure handling.
 *
 * <p>Every call is <b>best-effort and never throws</b> — a stopped instance, a refused connection or a
 * half-booted ADB all resolve to "not running" / empty / null rather than an error, because these drive
 * decoration (a status dot, a thumbnail, an app list) that must never take a picker down. Each opens and
 * closes its own short-lived connection, so nothing is left holding an emulator open. All of it blocks on
 * I/O: call from a background thread, never the FX thread.
 */
public final class EmulatorProbe {

    private EmulatorProbe() {}

    /**
     * Whether the instance's ADB port accepts a connection — the quick "is it up?" check behind a picker's
     * running/stopped dot. A TCP probe rather than an ADB handshake, so it stays cheap enough to run for
     * every listed instance.
     *
     * <p>Delegates to shared's {@link EmulatorReadiness#portOpen}: this was a byte-identical copy of the
     * launcher's own probe, and the pair disagreeing about what "running" means is what let an app launch
     * fire into a half-booted Android. Note the distinction that survives the merge — a port that answers is
     * <em>not</em> a device that can be driven; that question is {@link EmulatorReadiness#isReady}.
     */
    public static boolean isRunning(EmulatorInstance instance) {
        return EmulatorReadiness.portOpen(instance);
    }

    /** One ADB {@code screencap} of a running instance; {@code null} if it isn't up or the grab fails. */
    public static BufferedImage screencap(EmulatorInstance instance) {
        return withDevice(instance, AdbDevice::screencap, null);
    }

    /**
     * The instance's installed third-party apps, or {@code null} when we could not talk to it at all.
     *
     * <p>The null is load-bearing, and the reason is the ADB authorization prompt. Android's {@code adbd}
     * asks the user to trust a new host key, and a <em>refused</em> prompt looks exactly like a running
     * instance from the outside: {@link #isRunning} is a TCP probe, so the port answers and the dot goes
     * green, while every actual query fails. Collapsing that into an empty list told the user "no apps
     * installed", which is a lie about their device. Empty now means "asked, and there are none".
     */
    public static List<String> installedApps(EmulatorInstance instance) {
        return withDevice(instance, AdbDevice::installedApps, null);
    }

    /** One app's launcher icon, read out of its APK over ADB; {@code null} when it has none we can decode. */
    public static BufferedImage appIcon(EmulatorInstance instance, String packageName) {
        return withDevice(instance, device -> device.appIcon(packageName), null);
    }

    /**
     * Runs {@code query} against a short-lived ADB connection, returning {@code fallback} on any failure and
     * always closing the device. {@link Throwable} rather than {@link Exception}: dadb pulls in native and
     * Kotlin machinery whose failures can surface as {@link Error}s, and a picker thumbnail is never worth
     * propagating one.
     */
    private static <T> T withDevice(EmulatorInstance instance, DeviceQuery<T> query, T fallback) {
        try (AdbDevice device = AdbDevice.connect(instance.host(), instance.adbPort())) {
            T result = query.run(device);
            return result == null ? fallback : result;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** One question asked of a connected device; may throw, which {@link #withDevice} turns into the fallback. */
    @FunctionalInterface
    private interface DeviceQuery<T> {
        T run(AdbDevice device) throws Exception;
    }
}
