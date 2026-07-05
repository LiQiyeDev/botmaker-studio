package com.botmaker.studio.game;

import java.util.List;

/**
 * Discovers games installed by one launcher (Steam today; Epic/GOG later) so the shared game picker can
 * present them uniformly. Implementations read only local, on-disk metadata — no login, no API key, no
 * network — and are best-effort: {@link #installedGames()} returns an empty list rather than throwing when
 * the launcher isn't installed or its files can't be read.
 */
public interface GameLibraryProvider {

    /** Stable platform key stamped onto every {@link InstalledGame}, e.g. {@code "steam"}. */
    String platform();

    /** Human-readable launcher name for the picker UI, e.g. {@code "Steam"}. */
    String displayName();

    /** All locally-installed (hence launchable) games from this launcher. Never throws. */
    List<InstalledGame> installedGames();
}
