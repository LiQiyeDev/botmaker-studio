package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.ActionContext;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.studio.project.ProjectConfig;

/**
 * Studio's side of {@link ActionContext} — the facts a toolbar item's click is handed.
 *
 * <p><b>Everything is read at call time, nothing is captured.</b> The same rule {@link HostSlotContext}
 * keeps, for the same reason and with a longer fuse here: a toolbar button is built once when a project's
 * plugins are bound and outlives every project opened after it, so a context holding the project it was
 * created with would answer for a project the user closed an hour ago.
 */
public final class HostActionContext implements ActionContext {

    /** Where the currently open project comes from; may answer null, which is an ordinary state. */
    private final java.util.function.Supplier<ProjectConfig> project;

    /** How the pin is read for whichever plugin's item is being pressed. */
    private final java.util.function.Supplier<String> pin;

    public HostActionContext(java.util.function.Supplier<ProjectConfig> project,
                             java.util.function.Supplier<String> pin) {
        this.project = project == null ? () -> null : project;
        this.pin = pin == null ? () -> "" : pin;
    }

    @Override
    public String projectName() {
        ProjectConfig config = project.get();
        return config == null ? null : config.projectName();
    }

    @Override
    public String pinnedVersion() {
        String version = pin.get();
        return version == null ? "" : version;
    }

    @Override
    public StudioServices services() {
        return HostServices.forProject(project.get());
    }
}
