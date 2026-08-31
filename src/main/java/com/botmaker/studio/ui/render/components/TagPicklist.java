package com.botmaker.studio.ui.render.components;

import com.botmaker.sdk.internal.plugin.capture.TagPicker;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.project.ProjectConfig;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Studio's name for the SDK plugin's {@link TagPicker}, bound to the open project.
 *
 * <p>The control itself moved on 2026-08-31, with the naming step it belongs to. A tag is a tag <em>of a
 * picture</em>, and a picture is the SDK's concept: the catalog this offers is read out of that plugin's own
 * manifest under the project's resources directory, and nothing about it needs the host beyond knowing which
 * project is open. What stayed behind is this façade, for the same reason {@code ImageTemplateLibrary}'s did
 * — three of its callers are host work that is not moving (the tag manager, the parameters screen and the
 * resource manager), and they should not each have to build a {@code StudioServices} to open a menu.
 */
public final class TagPicklist extends TagPicker {

    public TagPicklist(ProjectConfig config) {
        super(HostServices.forProject(config));
    }

    /** See {@link TagPicker#promptNewTag} — the rules and the wording are that class's. */
    public static Optional<String> promptNewTag(Window owner, ProjectConfig config) {
        return TagPicker.promptNewTag(HostServices.forProject(config), owner);
    }
}
