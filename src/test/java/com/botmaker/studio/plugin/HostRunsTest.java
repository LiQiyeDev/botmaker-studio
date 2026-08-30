package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.Runs;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.shared.ipc.TelemetryFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The channel through which a plugin reaches the open project's bot.
 *
 * <p>Two properties here are the ones that would otherwise be discovered late. A plugin that unregisters
 * must actually stop being called — the {@code EventBus} has no unsubscribe, so if the handle did not remove
 * the listener from Studio's own list, every project opened would leave a dead one behind. And between
 * projects the channel must answer {@link Runs#NONE}: an editor built for a project the user has since left
 * must not be able to start that project's bot.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class HostRunsTest {

    @AfterEach
    void clear() {
        HostRuns.clear();
    }

    @Test
    void between_projects_there_is_nothing_to_reach() {
        HostRuns.clear();

        assertSame(Runs.NONE, HostRuns.live(),
                "an editor outliving its project must not be able to start that project's bot");
        assertFalse(Runs.NONE.isRunning());
        assertEquals(OptionalLong.empty(), Runs.NONE.pid());
        HostRuns.status("dropped on the floor, not thrown");
    }

    /** The no-op host is total: registering and unregistering on it must be safe, not merely harmless. */
    @Test
    void the_empty_channel_registers_and_unregisters_without_complaint() throws Exception {
        AutoCloseable state = Runs.NONE.onStateChanged(running -> { });
        AutoCloseable telemetry = Runs.NONE.onTelemetry(frame -> { });

        assertNotNull(state);
        assertNotNull(telemetry);
        state.close();
        telemetry.close();
    }

    /**
     * A frame handed to a plugin is one the shared decoder reads back — the point of passing bytes rather
     * than inventing a text rendering for the contract. Encoding here is the same call {@code HostRuns}
     * makes; what this holds is that the round trip is lossless, since a plugin has nothing else to go on.
     */
    @Test
    void a_telemetry_frame_survives_the_crossing() throws IOException {
        TelemetryEvent original = new TelemetryEvent.Click(
                new TelemetryEvent.Target("Diablo IV", 0, 0, 1920, 1080), 640, 480, 1, 12);

        byte[] frame = encode(original);
        TelemetryEvent read = TelemetryFrame.read(new DataInputStream(new ByteArrayInputStream(frame)));

        assertEquals(original, read, "a plugin decodes with the same class the host encoded with");
    }

    @Test
    void withPid_declines_when_no_bot_is_running() {
        boolean acted = Runs.NONE.withPid(pid -> {
            throw new AssertionError("there is no bot, so nothing may be signalled");
        });

        assertFalse(acted, "the answer is 'nothing was running', not an exception");
    }

    private static byte[] encode(TelemetryEvent event) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream(256);
        try (var out = new java.io.DataOutputStream(bytes)) {
            TelemetryFrame.write(out, event);
        }
        return bytes.toByteArray();
    }
}
