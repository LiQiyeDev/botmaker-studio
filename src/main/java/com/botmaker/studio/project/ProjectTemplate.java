package com.botmaker.studio.project;

/**
 * The starting content of a new project.
 *
 * <ul>
 *   <li>{@link #EMPTY} — a bare {@code main} that prints a greeting (the historical default).</li>
 *   <li>{@link #GAME_BOT} — a full game-bot scaffold: a supervised entry point ({@code Bot.supervise}),
 *       a {@code MacroLoop} that dispatches over the activity registry, and editable {@code GoHome} /
 *       {@code Startup} recovery hooks, plus an initial (empty) {@code ActivityRegistry}.</li>
 * </ul>
 */
public enum ProjectTemplate {
    EMPTY("Empty", "A bare main() — start from scratch."),
    GAME_BOT("Game bot", "Supervised loop, activity dispatch, go-home + startup recovery hooks.");

    private final String displayName;
    private final String description;

    ProjectTemplate(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
