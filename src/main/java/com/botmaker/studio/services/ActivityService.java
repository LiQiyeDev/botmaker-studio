package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.ActivitiesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.Regeneration;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.ActivityPreset;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.ValueWire;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the project's <em>activities</em> — the game tasks a bot performs — and the project-wide
 * {@link ActivityVariable variables} they read. It owns exactly one file:
 * {@code src/main/resources/activities.json}, the whole model, values included, read at runtime by the bot.
 *
 * <p><b>It does not generate Java itself, and since 2026-08-26 it does ask for it again.</b> Five generators
 * lived here — {@code Activities.java}, {@code Parameters.java}, {@code ActivityRegistry.java},
 * {@code FlowDriver.java} and the per-activity stub — each of them a scaffold template from the pinned SDK
 * jar with Studio's fills spliced into its fences. They left with the templates on 2026-08-25, and for one
 * day a save rewrote no source at all. The emitters now live in the SDK, where a generated file is compiled
 * against the API it calls in the same build, and {@link #update} reaches them through
 * {@link Regeneration#write}.
 *
 * <p><b>The JSON is written first and the generator reads it back</b>, rather than being handed the config in
 * memory. That is what makes the two agree by construction: the source describes what is stored, never what
 * a caller believed it was about to store.
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
     * Persists {@code newConfig} — writes {@code activities.json}, drops the stubs of removed activities and
     * reconciles the surviving ones' {@code Outcome} enums — then refreshes project state and publishes
     * {@link ActivitiesChangedEvent}. Runs asynchronously; the returned future completes exceptionally if
     * writing fails.
     *
     * <p><b>The generated Java is rewritten too</b>, by the project's own SDK, from the JSON this method has
     * just written. {@link Regeneration#write} renders every file before it writes any, so a save never
     * leaves a new {@code Activities} beside a {@code FlowDriver} describing the previous flow.
     *
     * <p>Ordering: JSON, then stubs ({@link Regeneration#ensureStubs}, which creates and never overwrites),
     * then the regenerated set. The stubs go in the middle because a newly added activity's file must exist
     * before {@code ActivityRegistry} names it, and a removed one's must be gone before the registry stops.
     */
    public CompletableFuture<Void> update(ActivitiesConfig newConfig) {
        // Read on the caller's thread, before the async body: deleteRemovedStubs needs to know which
        // activities this save drops, and state is not for background callers.
        ActivitiesConfig previous = current();
        return CompletableFuture.runAsync(() -> {
            try {
                newConfig.write(config.resourcesRoot());
                deleteRemovedStubs(previous, newConfig);
                Regeneration.ensureStubs(config);
                ActivityStubSync.sync(config, newConfig);
                Regeneration.write(config);
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
     * model, and the generated {@code FlowDriver} rewritten from it by {@link #update}.
     */
    public CompletableFuture<Void> updateFlow(ActivityFlow flow) {
        return update(current().withFlow(flow));
    }

    /**
     * Deletes the stub of every activity this save removed.
     *
     * <p>Not optional: a removed activity stops having an {@code Activities.<Name>} field, so its
     * {@code <Name>.java} — which reads that field in {@code isEnabled()} — no longer compiles. Leaving the
     * file behind turns "I removed an activity" into a broken build in a file the user never opened.
     *
     * <p>Keyed on the <em>difference</em> between the two configs, never on "every file in {@code activities/}
     * that isn't an activity": a helper class the user parked in that package is not this method's business,
     * and a sweep would eat it on the next unrelated save.
     */
    private void deleteRemovedStubs(ActivitiesConfig previous, ActivitiesConfig cfg) throws IOException {
        Set<String> kept = cfg.activities().stream().map(ActivityDefinition::name).collect(Collectors.toSet());
        for (ActivityDefinition gone : previous.activities()) {
            if (kept.contains(gone.name())) continue;
            Files.deleteIfExists(config.activitiesPackageDir().resolve(gone.name() + ".java"));
        }
    }
}
