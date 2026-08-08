package com.botmaker.studio.services.pilot;

import java.util.Optional;

/**
 * The control commands the phone can send over the pilot socket — the {@code cmd} field of an inbound JSON
 * frame, as a closed set.
 *
 * <p>The tokens are a wire format shared with the BotPilot app, which lives outside this repo, so they are
 * <b>unchanged</b>: this adds a name and a total parse, not a new protocol. {@link #from(String)} returning
 * empty is the same "ignore silently" the old {@code default} arm did — an older phone build, or a newer one
 * sending something this Studio predates, must not drop the connection.
 */
public enum PilotCommand {

    /** Run the bot. */
    START("start"),
    /** Stop the running bot. */
    STOP("stop"),
    /** Suspend execution, leaving the session up. */
    PAUSE("pause"),
    /** Resume after {@link #PAUSE}. */
    RESUME("resume"),
    /** Arm or disarm manual pointer control for this connection. */
    INTERACT("interact"),
    /** One manual gesture; only honoured while {@link #INTERACT} is armed. */
    INPUT("input"),
    /**
     * The client naming what it can decode: {@code {"cmd":"hello","accept":["h264"]}}. Optional, and its
     * absence is the answer for every build that predates it — a client that never says hello is served the
     * JPEG frames it has always been served, which is why the H.264 stream needed no change to the JPEG
     * framing and no version number anywhere.
     */
    HELLO("hello");

    private final String token;

    PilotCommand(String token) {
        this.token = token;
    }

    /** The value of the frame's {@code cmd} field. Part of the wire format; do not change. */
    public String token() {
        return token;
    }

    /** The command {@code token} names, or empty for one this Studio does not implement. */
    public static Optional<PilotCommand> from(String token) {
        if (token == null) return Optional.empty();
        for (PilotCommand command : values()) {
            if (command.token.equals(token)) return Optional.of(command);
        }
        return Optional.empty();
    }
}
