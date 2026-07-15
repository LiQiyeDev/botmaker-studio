package com.botmaker.studio.project;

/**
 * The starting content of a new project. Chosen once in the New Project dialog and then <b>persisted</b> in
 * {@code settings.json} ({@link StudioProjectSettings#template()}), because which files are scaffolding depends
 * on it: {@link FileRole} locks the entry point only for {@link #GAME_BOT}, and {@code ProjectRepair} regenerates
 * a different file set per template. Declaration order is the dropdown order, so {@link #GAME_BOT} — the default
 * — comes first.
 *
 * <ul>
 *   <li>{@link #GAME_BOT} — a full game-bot scaffold: a supervised entry point ({@code Bot.supervise}),
 *       a {@code GameLoop} that dispatches over the activity registry, and editable {@code GoHome} /
 *       {@code Startup} recovery hooks, plus an initial (empty) {@code ActivityRegistry}.</li>
 *   <li>{@link #EMPTY} — a bare {@code main} that prints a greeting.</li>
 * </ul>
 */
public enum ProjectTemplate {
    GAME_BOT("Game bot", "Supervised loop, activity dispatch, go-home + startup recovery hooks."),
    EMPTY("Empty", "A bare main() — start from scratch.");

    private final String displayName;
    private final String description;

    ProjectTemplate(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
