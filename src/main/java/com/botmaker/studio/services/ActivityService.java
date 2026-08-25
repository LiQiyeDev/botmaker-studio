package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.ActivitiesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.ActivityPreset;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.VariableWire;

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
 * <p><b>It no longer generates Java (2026-08-25).</b> Five generators lived here — {@code Activities.java},
 * {@code Parameters.java}, {@code ActivityRegistry.java}, {@code FlowDriver.java} and the per-activity stub —
 * each of them a scaffold template from the pinned SDK jar with Studio's fills spliced into its fences. The
 * templates are gone from the SDK and the emitters are moving into it (inversion Phase 2), where a generated
 * file can be compiled against the API it calls in the same build. Until then <b>saving the model does not
 * rewrite any source</b>: the JSON is written, stubs are reconciled, and the generated files already in the
 * project are left exactly as they are.
 *
 * <p>That is deliberate and it is visible to the user — an Activity Flow saved now has a
 * {@code FlowDriver.java} that still describes the <em>previous</em> flow. The dialogs that save the model
 * say so; this class is only the reason they have to.
 *
 * <p>One model, one store. Studio briefly generated a {@code Settings.java} holding every value as a compiled
 * Java literal instead; it is gone, along with the discriminator that chose between the two. What that
 * experiment bought — project-wide variables organised by tag, and a type list as wide as the one methods
 * use — was kept, and lives in {@link ActivitiesConfig#variables()} and {@link VariableWire}.
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
     * <p><b>No Java is generated.</b> Until the SDK ships its own emitters, the model is saved and the
     * generated files are not rewritten. The all-or-nothing render that used to run before the first byte was
     * written went with them: there is nothing left that can be refused halfway.
     */
    public CompletableFuture<Void> update(ActivitiesConfig newConfig) {
        // Read on the caller's thread, before the async body: deleteRemovedStubs needs to know which
        // activities this save drops, and state is not for background callers.
        ActivitiesConfig previous = current();
        return CompletableFuture.runAsync(() -> {
            try {
                newConfig.write(config.resourcesRoot());
                deleteRemovedStubs(previous, newConfig);
                ActivityStubSync.sync(config, newConfig);
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
     * model. The generated {@code FlowDriver} that used to be rewritten from it is not; see the class
     * javadoc.
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
