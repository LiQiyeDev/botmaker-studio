package com.botmaker.studio.emulator;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.studio.config.BotMakerDirs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What we last learned about an emulator's installed apps, kept on disk so it outlives the process.
 *
 * <p>The picker already remembered this — in a {@code static} map, which is exactly as long-lived as the JVM.
 * Its javadoc promised a stopped instance would still show "its last-known apps", and that held until Studio
 * was restarted, at which point a stopped Waydroid went back to "start it to list apps". Everything here is a
 * cache in the strict sense: losing it costs one ADB query per instance (and, for icons, several round-trips
 * per app — see {@code ApkIcon}), never correctness.
 *
 * <p><b>Files, not a serialized map.</b> One text file per instance, one package per line, plus PNGs for the
 * icons. It is inspectable with {@code cat}, a corrupt file costs one instance's list rather than all of them,
 * and it needs no schema or version field. Every operation is best-effort and total: an unreadable cache is an
 * empty one, and a failed write is dropped silently rather than taking a picker down.
 */
public final class EmulatorAppCache {

    /** Where the cache lives, overridable for tests. */
    private final Path root;

    public EmulatorAppCache(Path root) {
        this.root = root;
    }

    /** The shared instance over {@code <cache>/emulators}, which is what the pickers use. */
    public static EmulatorAppCache shared() {
        return new EmulatorAppCache(BotMakerDirs.getCacheDir().resolve("emulators"));
    }

    /** The packages last seen on {@code instance}, or an empty list when nothing was ever cached. */
    public List<String> packages(EmulatorInstance instance) {
        Path file = packageFile(instance);
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Records the packages currently installed on {@code instance}. A null/empty list clears nothing. */
    public void putPackages(EmulatorInstance instance, List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return;
        }
        try {
            Path file = packageFile(instance);
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", packages) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A cache that can't be written is a cache that isn't there — never a reason to fail a picker.
        }
    }

    /** The cached launcher icon for one app, or {@code null} when we haven't stored one. */
    public BufferedImage icon(EmulatorInstance instance, String packageName) {
        Path file = iconFile(instance, packageName);
        try {
            return Files.isRegularFile(file) ? ImageIO.read(file.toFile()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Stores one app's launcher icon. A null image is a no-op — an absent icon is re-derived, not remembered. */
    public void putIcon(EmulatorInstance instance, String packageName, BufferedImage icon) {
        if (icon == null) {
            return;
        }
        try {
            Path file = iconFile(instance, packageName);
            Files.createDirectories(file.getParent());
            ImageIO.write(icon, "png", file.toFile());
        } catch (Exception e) {
            // As above: best-effort.
        }
    }

    private Path packageFile(EmulatorInstance instance) {
        return root.resolve(safe(instance.identity()) + ".txt");
    }

    private Path iconFile(EmulatorInstance instance, String packageName) {
        return root.resolve("icons").resolve(safe(instance.identity())).resolve(safe(packageName) + ".png");
    }

    /**
     * A file name from a key that contains {@code @}, {@code :} and dots ({@code waydroid@192.168.240.112:5555}).
     * Only characters that are safe everywhere survive; everything else becomes {@code _}. Collisions are
     * harmless here — two keys mapping to one file means one stale app list, which the next live query fixes.
     */
    private static String safe(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
