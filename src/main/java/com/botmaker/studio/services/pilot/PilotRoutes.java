package com.botmaker.studio.services.pilot;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.session.DesktopSession;
import com.botmaker.studio.emulator.AdbEmulatorSurface;
import com.botmaker.studio.emulator.EmulatorSurface;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ProjectSettingsService;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Decides the pilot's {@link PilotRoute} and owns the emulator connection that route needs.
 *
 * <p>The order is the point, and each rung is someone stating an intent:
 * <ol>
 *   <li>a live nested {@code :N} {@link DesktopSession} — the user explicitly asked for background mode, and
 *       it launched the game itself, so nothing outranks it;</li>
 *   <li>{@code capture.source = emulator:<name>} — what the Launch Target dialog writes for <em>every</em>
 *       {@code emu-app:} target, so an emulator project needs no second piece of setup, and what the running
 *       bot reads, so the pilot streams what the bot sees;</li>
 *   <li>an {@code EmulatorTarget} chosen as the project's default capture target — the editor-picker path to
 *       the same statement;</li>
 *   <li>otherwise the real {@code :0} desktop.</li>
 * </ol>
 *
 * <p>An emulator that can't be resolved or reached <b>degrades to the desktop</b> rather than to a route that
 * streams nothing: a stopped emulator should leave the pilot showing something and saying so, not freeze it.
 *
 * <p>The resolved surface is <b>cached</b> and only rebuilt when the named instance changes. Resolution runs
 * a full {@code Platforms.discoverAll()} scan (registry reads, console-tool calls) and the caller is a frame
 * loop, so doing it per frame would cost more than the frame.
 *
 * <p>Constructed with its two effects injected — where the instance name comes from, and how a surface is
 * opened — so the ordering above can be tested without a project on disk or an emulator on the machine.
 * {@link #forProject} is the production wiring.
 */
public final class PilotRoutes implements AutoCloseable {

    private final PilotSession session;
    private final Supplier<String> instanceName;
    private final Function<String, EmulatorSurface> open;

    /** The live emulator surface and the name it was opened for; both null when the route isn't an emulator. */
    private EmulatorSurface surface;
    private String openedFor;

    public PilotRoutes(PilotSession session, Supplier<String> instanceName,
                       Function<String, EmulatorSurface> open) {
        this.session = session;
        this.instanceName = instanceName;
        this.open = open;
    }

    /**
     * The production wiring: the instance name comes from the project's {@code capture.source} and then its
     * default capture target, and a surface is one ADB connection to the discovered instance of that name.
     */
    public static PilotRoutes forProject(PilotSession session, Path resourcesDir,
                                         ProjectSettingsService settings) {
        return new PilotRoutes(session,
                () -> configuredInstanceName(resourcesDir, settings),
                PilotRoutes::openAdbSurface);
    }

    /** The route to stream and drive right now. Cheap enough for a frame: a field read plus, at most, a scan. */
    public synchronized PilotRoute current() {
        DesktopSession live = session != null ? session.get() : null;
        if (live != null) {
            releaseSurface(); // a nested session outranks the emulator; don't hold ADB open behind it
            return new PilotRoute.Session(live);
        }
        String wanted = safeName();
        if (wanted == null) {
            releaseSurface();
            return PilotRoute.DESKTOP;
        }
        if (surface == null || !wanted.equals(openedFor)) {
            releaseSurface();
            surface = open.apply(wanted);
            openedFor = surface == null ? null : wanted;
        }
        return surface == null ? PilotRoute.DESKTOP : new PilotRoute.Emulator(surface);
    }

    /** Drops the held emulator connection, if any. The pilot server calls this when it shuts down. */
    @Override
    public synchronized void close() {
        releaseSurface();
    }

    private void releaseSurface() {
        if (surface != null) {
            try {
                surface.close();
            } catch (Throwable ignored) {
                // A surface whose connection already died closes noisily; that is not a shutdown failure.
            }
            surface = null;
        }
        openedFor = null;
    }

    /** The supplier is project I/O; a failing read must degrade to the desktop, not take the frame loop down. */
    private String safeName() {
        try {
            String name = instanceName.get();
            return (name == null || name.isBlank()) ? null : name.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The instance the project points at: {@code capture.source} first (what the bot itself reads), then a
     * default {@code EmulatorTarget} (what the editor's capture picker sets). {@code null} when neither names
     * an emulator.
     */
    static String configuredInstanceName(Path resourcesDir, ProjectSettingsService settings) {
        String fromSource = ProjectProperties.emulatorInstanceOf(ProjectCreator.readCaptureSource(resourcesDir));
        if (fromSource != null) return fromSource;
        try {
            if (settings != null && settings.defaultTarget() instanceof CaptureTarget.EmulatorTarget target) {
                return target.instanceName();
            }
        } catch (Exception ignored) {
            // Settings not loaded yet (the pilot can be opened mid-startup) — the desktop is the right answer.
        }
        return null;
    }

    /** One ADB-backed surface for the discovered instance of this name, or {@code null} when there is none. */
    private static EmulatorSurface openAdbSurface(String name) {
        EmulatorInstance instance = EmulatorInstances.byName(name).orElse(null);
        return instance == null ? null : new AdbEmulatorSurface(instance);
    }
}
