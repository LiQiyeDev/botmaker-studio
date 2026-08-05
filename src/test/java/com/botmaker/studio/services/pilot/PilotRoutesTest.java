package com.botmaker.studio.services.pilot;

import com.botmaker.session.Capability;
import com.botmaker.studio.emulator.EmulatorSurface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

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

    @Test
    void withNothingConfiguredTheRouteIsTheRealDesktop() {
        assertInstanceOf(PilotRoute.Desktop.class,
                routes(new PilotSession(), new String[]{null}, new Opener()).current());
    }

    @Test
    void aConfiguredEmulatorNameBecomesAnEmulatorRoute() {
        Opener opener = new Opener();

        PilotRoute route = routes(new PilotSession(), new String[]{"Waydroid"}, opener).current();

        assertInstanceOf(PilotRoute.Emulator.class, route);
        assertEquals(List.of("Waydroid"), opener.opened);
    }

    /**
     * A nested session outranks an emulator: the user explicitly asked for background mode and it launched the
     * game itself. The emulator connection is dropped rather than left held behind a route nobody is using.
     */
    @Test
    void aLiveSessionOutranksAConfiguredEmulatorAndReleasesIt() {
        Opener opener = new Opener();
        PilotSession session = new PilotSession();
        PilotRoutes routes = routes(session, new String[]{"Waydroid"}, opener);

        PilotRoute first = routes.current();
        PilotFakes.RecordingSurface surface = (PilotFakes.RecordingSurface) ((PilotRoute.Emulator) first).surface();

        session.set(new PilotFakes.FakeSession(new PilotFakes.RecordingController(), null, null,
                EnumSet.of(Capability.BACKGROUND_CLICK)));

        assertInstanceOf(PilotRoute.Session.class, routes.current());
        assertTrue(surface.closed, "the emulator connection must not stay open behind a session route");
    }

    /** An instance no product reports degrades to the desktop — a stopped emulator must not blank the pilot. */
    @Test
    void anUnresolvableInstanceFallsBackToTheDesktop() {
        Opener opener = new Opener();
        opener.unknown = "GhostDroid";

        assertInstanceOf(PilotRoute.Desktop.class,
                routes(new PilotSession(), new String[]{"GhostDroid"}, opener).current());
    }

    /**
     * Resolution runs a full product scan, and the caller is a frame loop — so the surface is opened once and
     * reused until the configured name actually changes.
     */
    @Test
    void theSurfaceIsOpenedOncePerNameAndReopenedWhenItChanges() {
        Opener opener = new Opener();
        String[] name = {"Waydroid"};
        PilotRoutes routes = routes(new PilotSession(), name, opener);

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
        PilotRoutes routes = new PilotRoutes(new PilotSession(), () -> {
            throw new IllegalStateException("settings not loaded yet");
        }, name -> null);

        assertInstanceOf(PilotRoute.Desktop.class, routes.current());
    }

    @Test
    void closingReleasesTheHeldConnection() {
        Opener opener = new Opener();
        PilotRoutes routes = routes(new PilotSession(), new String[]{"Waydroid"}, opener);
        PilotFakes.RecordingSurface surface =
                (PilotFakes.RecordingSurface) ((PilotRoute.Emulator) routes.current()).surface();

        routes.close();

        assertTrue(surface.closed);
    }
}
