package com.botmaker.studio.project;

/**
 * How a project was started, recorded once at creation and persisted in {@code settings.json}
 * ({@link StudioProjectSettings#template()}).
 *
 * <p>It used to be a <em>choice of scaffold</em> and the New Project dropdown's item list, which is why the
 * two values below read like starting points. Since 2026-08-30 there is exactly one starting point Studio
 * composes — {@link #EMPTY} — and every richer one is a published bot downloaded from the gallery
 * ({@link #FROM_TEMPLATE}, see {@code TemplateProject}). So this is now a record of provenance rather than a
 * menu, and it survives because two things still read it: {@code ProjectSpecs} (whether the SDK writes an
 * {@code activities.json}) and the runtime defaults a new project is seeded with.
 *
 * <ul>
 *   <li>{@link #GAME_BOT} — <b>legacy only.</b> No project is created this way any more; the value is kept
 *       because projects made before 2026-08-30 have it written in their {@code settings.json} and must keep
 *       opening.</li>
 *   <li>{@link #EMPTY} — a pom, an empty {@code src} tree and a {@code main} that prints a line.</li>
 *   <li>{@link #FROM_TEMPLATE} — unpacked from somebody's published bot and renamed. Every file in it,
 *       including the pom and its versions, is the template author's work and then the user's.</li>
 * </ul>
 */
public enum ProjectTemplate {
    GAME_BOT("Game bot", "Supervised loop, activity flow, go-home recovery hook."),
    EMPTY("Blank", "A pom, an empty source tree and a main() — start from scratch."),
    FROM_TEMPLATE("From a template", "Unpacked from a published template and renamed.");

    private final String displayName;
    private final String description;

    ProjectTemplate(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
