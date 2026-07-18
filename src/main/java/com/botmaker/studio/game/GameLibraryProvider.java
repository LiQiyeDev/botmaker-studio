package com.botmaker.studio.game;

import java.util.List;
import java.util.Optional;

/**
 * Discovers games installed by one launcher (Steam and Epic today; GOG later) so the shared game picker can
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

    /**
     * The installed game whose launch {@link InstalledGame#id() id} equals {@code id} (so the picker can show
     * a saved id as its game name + art), or empty if it isn't installed. Never throws. The default scans
     * {@link #installedGames()}; a provider with a cheaper lookup may override.
     */
    default Optional<InstalledGame> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String needle = id.trim();
        return installedGames().stream().filter(g -> needle.equals(g.id())).findFirst();
    }
}
