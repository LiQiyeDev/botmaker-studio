package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.ActivitiesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.ActivityPreset;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.ValueWire;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the project's <em>activities</em> — the game tasks a bot performs — and the project-wide
 * {@link ActivityVariable variables} they read. It owns exactly one file:
 * {@code src/main/resources/activities.json}, the whole model, values included, read at runtime by the bot.
 *
 * <p><b>It does not generate Java, and it no longer knows anybody who does.</b> Five generators lived here —
 * {@code Activities.java}, {@code Parameters.java}, {@code ActivityRegistry.java}, {@code FlowDriver.java}
 * and the per-activity stub — each of them a scaffold template from the pinned SDK jar with Studio's fills
 * spliced into its fences. Four of the five are gone entirely: a file whose contents follow from the model is
 * read from the model at run time now rather than compiled from it. <b>So is the fifth</b>: an activity's
 * behaviour is an {@code Activities.define("Mining", ctx -> …)} call in a file the user wrote and owns, and
 * this class's job ends where the JSON does.
 *
 * <p>The stub went through one more shape on the way out. It was briefly a <em>seed</em> — a real class the
 * SDK shipped, written into the project once and then maintained at marked regions — with a key ledger, a
 * reconciler and a rename engine here to keep owning it. All of that is deleted: a project's structure
 * belongs to the user, so BotMaker writes no Java into one at all.
 *
 * <p>One model, one store. Studio briefly generated a {@code Settings.java} holding every value as a compiled
 * Java literal instead; it is gone, along with the discriminator that chose between the two. What that
 * experiment bought — project-wide variables organised by tag, and a type list as wide as the one methods
 * use — was kept, and lives in {@link ActivitiesConfig#variables()} and {@link ValueWire}.
 *
 * All I/O lives here at the service edge. {@link #update} runs off the calling thread and publishes
 * {@link ActivitiesChangedEvent} once state is refreshed.
 */
public final class ActivityService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;

    public ActivityService(ProjectConfig config, ProjectState state, EventBus eventBus) {
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
    }

    /** The project these activities belong to — so a caller holding this service needn't also be handed it. */
    public ProjectConfig projectConfig() {
        return config;
    }

    /** The current activities (from project state, loaded at open and refreshed on change). */
    public ActivitiesConfig current() {
        // Null-tolerant on state as well as on its contents: this is reachable with no project state at all,
        // and a null check is cheaper than a second constructor.
        ActivitiesConfig c = state == null ? null : state.getActivities();
        return c != null ? c : ActivitiesConfig.empty();
    }

    /** Loads activities from disk into project state (called once at project open). */
    public ActivitiesConfig load() {
        ActivitiesConfig loaded = ActivitiesConfig.read(config.resourcesRoot());
        state.setActivities(loaded);
        return loaded;
    }

    /**
     * Persists {@code newConfig} — writes {@code activities.json}, then asks every loaded plugin to bring the
     * project's files in line with it — and refreshes project state and publishes
     * {@link ActivitiesChangedEvent}. Runs asynchronously; the returned future completes exceptionally if
     * writing the JSON fails.
     *
     * <p><b>The JSON is written first and read back, and that ordering is the design.</b> {@link SeedSync}
     * asks each plugin what files it wants for this project, and the SDK answers by reading the very
     * {@code activities.json} this method has just written. The two therefore agree by construction: what
     * lands on disk describes what is stored, never what a caller believed it was about to store.
     *
     * <p><b>Saving an activity writes no Java at all.</b> An activity's behaviour is an
     * {@code Activities.define("Mining", ctx -> …)} call the user wrote somewhere in their own source, so
     * adding one on the canvas creates a file for nobody to own, renaming one moves nothing, and deleting
     * one leaves whatever the user wrote exactly where it is. What used to be here —
     * {@code Regeneration.ensureStubs}, {@code ActivityStubSync}, {@code SeedSync} and this class's own
     * {@code deleteRemovedStubs} — is gone, not generalised: the model is the file, and the file is all of
     * it.
     */
    public CompletableFuture<Void> update(ActivitiesConfig newConfig) {
        return CompletableFuture.runAsync(() -> {
            try {
                newConfig.write(config.resourcesRoot());
            } catch (IOException e) {
                throw new RuntimeException("Failed to save activities: " + e.getMessage(), e);
            }
            state.setActivities(newConfig);
            eventBus.publish(new ActivitiesChangedEvent(newConfig));
        });
    }

    /**
     * Applies {@code preset} to the current config — each activity is enabled iff the preset names it — and
     * persists through the normal {@link #update} path. Wiring and order are untouched: a preset only says
     * <em>which</em> activities run, never in what order.
     */
    public CompletableFuture<Void> applyPreset(ActivityPreset preset) {
        return update(current().applyPreset(preset));
    }

    /**
     * Persists a new canvas {@link ActivityFlow} (node placements + wires) — the drawn order, saved to the
     * model and read back at run time by the bot's own flow walker.
     */
    public CompletableFuture<Void> updateFlow(ActivityFlow flow) {
        return update(current().withFlow(flow));
    }
}
