package com.botmaker.studio.services.pilot;

import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.shared.emulator.EmulatorSurface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order in which the pilot picks a surface, and the caching that keeps a per-frame question off a
 * registry scan. Both effects are injected, so none of this needs a project on disk or an emulator on the
 * machine.
 */
class PilotRoutesTest {

    /** Records every name it was asked to open, so the caching can be asserted rather than assumed. */
    private static final class Opener implements java.util.function.Function<String, EmulatorSurface> {
        final List<String> opened = new ArrayList<>();
        /** Names that resolve to nothing — a configured instance no product reports. */
        String unknown;

        @Override
        public EmulatorSurface apply(String name) {
            opened.add(name);
            return name.equals(unknown) ? null : new PilotFakes.RecordingSurface(null);
        }
    }

    private static PilotRoutes routes(PilotSession session, String[] name, Opener opener) {
        return new PilotRoutes(session, () -> name[0], opener);
    }

    /** A holder standing in for the project's {@code BackgroundLauncher}: the pilot asks it on every read. */
    private static final class SessionHolder extends AtomicReference<DesktopSession> {
        PilotSession asked() {
            return new PilotSession(this::get);
        }
    }

    /** No session live — the common case for the emulator/desktop rungs below. */
    private static PilotSession noSession() {
        return new SessionHolder().asked();
    }

    @Test
    void withNothingConfiguredTheRouteIsTheRealDesktop() {
        assertInstanceOf(PilotRoute.Desktop.class,
                routes(noSession(), new String[]{null}, new Opener()).current());
    }

    @Test
    void aConfiguredEmulatorNameBecomesAnEmulatorRoute() {
        Opener opener = new Opener();

        PilotRoute route = routes(noSession(), new String[]{"Waydroid"}, opener).current();

        assertInstanceOf(PilotRoute.Emulator.class, route);
        assertEquals(List.of("Waydroid"), opener.opened);
    }

    /**
     * A nested session outranks an emulator: the user explicitly asked for background mode and it launched the
     * game itself. The emulator connection is dropped rather than left held behind a route nobody is using.
     *
     * <p>Note what does <em>not</em> happen here: nobody tells the routes about the session. It appears in the
     * holder — as it does when Studio's ▶ Launch toolbar starts one — and the next frame's question finds it.
     * The pilot used to be *pushed* the session by an object the user had to open a dialog to create, so this
     * exact sequence left it streaming and clicking the real {@code :0} desktop.
     */
    @Test
    void aLiveSessionOutranksAConfiguredEmulatorAndReleasesIt() {
        Opener opener = new Opener();
        SessionHolder holder = new SessionHolder();
        PilotRoutes routes = routes(holder.asked(), new String[]{"Waydroid"}, opener);

        PilotRoute first = routes.current();
        PilotFakes.RecordingSurface surface = (PilotFakes.RecordingSurface) ((PilotRoute.Emulator) first).surface();

        holder.set(new PilotFakes.FakeSession(new PilotFakes.RecordingController(), null, null,
                EnumSet.of(Capability.BACKGROUND_CLICK)));

        assertInstanceOf(PilotRoute.Session.class, routes.current());
        assertTrue(surface.closed, "the emulator connection must not stay open behind a session route");
    }

    /** A session that goes away (stopped, or its display died) returns the pilot to {@code :0} on the next frame. */
    @Test
    void aSessionThatLeavesTheHolderFallsBackToTheDesktop() {
        SessionHolder holder = new SessionHolder();
        holder.set(new PilotFakes.FakeSession(new PilotFakes.RecordingController(), null, null,
                EnumSet.of(Capability.BACKGROUND_CLICK)));
        PilotRoutes routes = routes(holder.asked(), new String[]{null}, new Opener());
        assertInstanceOf(PilotRoute.Session.class, routes.current());

        holder.set(null);

        assertInstanceOf(PilotRoute.Desktop.class, routes.current());
    }

    /**
     * The Waydroid case: gamescope hosts a Wayland-only client, so the session's {@code :N} root has nothing on
     * it and an X11 grab of it returns black forever. The configured emulator is the route that can actually
     * see those pixels, and it must win.
     */
    @Test
    void aSessionThatCannotBeCapturedOverX11LosesToAConfiguredEmulator() {
        Opener opener = new Opener();
        SessionHolder holder = new SessionHolder();
        PilotFakes.FakeSession wayland = new PilotFakes.FakeSession(new PilotFakes.RecordingController(), null, null,
                EnumSet.of(Capability.BACKGROUND_CLICK));
        wayland.x11Capturable = false;
        holder.set(wayland);

        PilotRoute route = routes(holder.asked(), new String[]{"Waydroid"}, opener).current();

        assertInstanceOf(PilotRoute.Emulator.class, route);
        assertEquals(List.of("Waydroid"), opener.opened);
    }

    /**
     * Losing rung 1 is a demotion, not a skip. With no emulator to fall to, an uncapturable session still beats
     * the real desktop — streaming the user's screen to a possibly-public URL and replaying taps on it is a
     * worse answer than a black frame, and the server says why rather than showing it.
     */
    @Test
    void anUncapturableSessionStillOutranksTheUsersRealDesktop() {
        SessionHolder holder = new SessionHolder();
        PilotFakes.FakeSession wayland = new PilotFakes.FakeSession(new PilotFakes.RecordingController(), null, null,
                EnumSet.of(Capability.BACKGROUND_CLICK));
        wayland.x11Capturable = false;
        holder.set(wayland);

        assertInstanceOf(PilotRoute.Session.class,
                routes(holder.asked(), new String[]{null}, new Opener()).current());
    }

    /** The ordinary gamescope case — an X11 game in the session — is untouched: rung 1 still wins outright. */
    @Test
    void anX11CapturableSessionStillWinsOverAConfiguredEmulator() {
        Opener opener = new Opener();
        SessionHolder holder = new SessionHolder();
        holder.set(new PilotFakes.FakeSession(new PilotFakes.RecordingController(), null, null,
                EnumSet.of(Capability.BACKGROUND_CLICK)));

        assertInstanceOf(PilotRoute.Session.class,
                routes(holder.asked(), new String[]{"Waydroid"}, opener).current());
        assertEquals(List.of(), opener.opened, "the emulator must not even be opened behind a winning session");
    }

    /** An instance no product reports degrades to the desktop — a stopped emulator must not blank the pilot. */
    @Test
    void anUnresolvableInstanceFallsBackToTheDesktop() {
        Opener opener = new Opener();
        opener.unknown = "GhostDroid";

        assertInstanceOf(PilotRoute.Desktop.class,
                routes(noSession(), new String[]{"GhostDroid"}, opener).current());
    }

    /**
     * Resolution runs a full product scan, and the caller is a frame loop — so the surface is opened once and
     * reused until the configured name actually changes.
     */
    @Test
    void theSurfaceIsOpenedOncePerNameAndReopenedWhenItChanges() {
        Opener opener = new Opener();
        String[] name = {"Waydroid"};
        PilotRoutes routes = routes(noSession(), name, opener);

        PilotRoute first = routes.current();
        PilotRoute again = routes.current();
        assertSame(((PilotRoute.Emulator) first).surface(), ((PilotRoute.Emulator) again).surface());
        assertEquals(List.of("Waydroid"), opener.opened, "a second frame must not re-scan");

        name[0] = "MuMu";
        PilotRoute switched = routes.current();
        assertEquals(List.of("Waydroid", "MuMu"), opener.opened);
        assertTrue(((PilotFakes.RecordingSurface) ((PilotRoute.Emulator) first).surface()).closed,
                "the old instance's connection is closed when the project points somewhere else");
        assertInstanceOf(PilotRoute.Emulator.class, switched);
    }

    /** The name comes from project I/O; a failing read is the desktop, not an exception into the frame loop. */
    @Test
    void aFailingLookupIsTheDesktopRatherThanAThrow() {
        PilotRoutes routes = new PilotRoutes(noSession(), () -> {
            throw new IllegalStateException("settings not loaded yet");
        }, name -> null);

        assertInstanceOf(PilotRoute.Desktop.class, routes.current());
    }

    @Test
    void closingReleasesTheHeldConnection() {
        Opener opener = new Opener();
        PilotRoutes routes = routes(noSession(), new String[]{"Waydroid"}, opener);
        PilotFakes.RecordingSurface surface =
                (PilotFakes.RecordingSurface) ((PilotRoute.Emulator) routes.current()).surface();

        routes.close();

        assertTrue(surface.closed);
    }
}
