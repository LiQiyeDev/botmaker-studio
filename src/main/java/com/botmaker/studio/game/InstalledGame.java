package com.botmaker.studio.game;

import java.nio.file.Path;

/**
 * A game discovered on disk by a {@link GameLibraryProvider}, ready to launch.
 *
 * @param platform the launcher it belongs to, e.g. {@code "steam"} (matches {@link GameLibraryProvider#platform()})
 * @param id       the launch token — for Steam this is the appId passed to {@code Game.launchSteam(...)}
 * @param name     the human-readable game title
 * @param artwork  a local cover-image file (portrait), or {@code null} if none was found (no network fetch)
 */
public record InstalledGame(String platform, String id, String name, Path artwork) {}
