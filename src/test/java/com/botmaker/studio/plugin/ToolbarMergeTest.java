package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.ToolbarGroup;
import com.botmaker.plugin.api.ToolbarItem;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How two plugins' toolbar items compose into one bar.
 *
 * <p>Every rule here is one <b>with no visible symptom when it is wrong</b>, which is why it is tested rather
 * than left to the eye: a bar in a slightly different order reads as somebody's preference, and a button
 * silently absent reads as a feature that was never written. The three are the group refusal, the tie-break
 * that makes the order independent of discovery order, and a plugin failing alone.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ToolbarMergeTest {

    /** A plugin that offers exactly what it is handed. */
    private record Fake(String id, List<ToolbarItem> items) implements StudioPlugin {
        @Override
        public List<ToolbarItem> toolbarItems() {
            return items;
        }
    }

    /** A plugin whose item list throws — a bug in somebody else's jar, met while a project is opening. */
    private record Broken(String id) implements StudioPlugin {
        @Override
        public List<ToolbarItem> toolbarItems() {
            throw new IllegalStateException("no");
        }
    }

    private static ToolbarItem item(String id, ToolbarGroup group, int order) {
        return ToolbarItem.of(id, id, null, group, order, c -> { });
    }

    private static List<String> ids(List<ToolbarItem> items) {
        List<String> out = new ArrayList<>();
        for (ToolbarItem item : items) out.add(item.id());
        return out;
    }

    @Test
    void items_sort_by_group_then_by_their_own_order() {
        List<ToolbarItem> merged = PluginHost.mergeToolbarItems(List.of(
                new Fake("a", List.of(
                        item("tools-late", ToolbarGroup.TOOLS, 90),
                        item("project-early", ToolbarGroup.PROJECT, 10),
                        item("project-late", ToolbarGroup.PROJECT, 20),
                        item("run", ToolbarGroup.RUN, 5)))));

        assertEquals(List.of("project-early", "project-late", "run", "tools-late"), ids(merged));
    }

    /**
     * Two plugins claiming the same slot are ordered by their own ids, not by which one
     * {@code ServiceLoader} happened to find first — the same reasoning that makes the value catalog refuse
     * a clash rather than let load order decide it.
     */
    @Test
    void a_tie_breaks_on_the_plugin_id_rather_than_on_discovery_order() {
        List<ToolbarItem> zebraFirst = PluginHost.mergeToolbarItems(List.of(
                new Fake("zebra", List.of(item("z", ToolbarGroup.RUN, 10))),
                new Fake("alpha", List.of(item("a", ToolbarGroup.RUN, 10)))));
        List<ToolbarItem> alphaFirst = PluginHost.mergeToolbarItems(List.of(
                new Fake("alpha", List.of(item("a", ToolbarGroup.RUN, 10))),
                new Fake("zebra", List.of(item("z", ToolbarGroup.RUN, 10)))));

        assertEquals(List.of("a", "z"), ids(zebraFirst));
        assertEquals(ids(alphaFirst), ids(zebraFirst), "the bar must not depend on which plugin loaded first");
    }

    /**
     * {@link ToolbarGroup#STUDIO} is the host's own section and a plugin item asking for it is dropped, not
     * re-homed. Quietly moving it would put a plugin's button where a user reads the application rather than
     * their project.
     */
    @Test
    void the_studio_group_is_refused_and_the_plugins_other_items_survive() {
        List<ToolbarItem> merged = PluginHost.mergeToolbarItems(List.of(
                new Fake("a", List.of(
                        item("sneaky", ToolbarGroup.STUDIO, 1),
                        item("honest", ToolbarGroup.TOOLS, 1)))));

        assertEquals(List.of("honest"), ids(merged));
    }

    /** A plugin that cannot list its buttons costs its own and nothing else — never the project. */
    @Test
    void a_plugin_that_throws_does_not_cost_the_others_their_items() {
        List<ToolbarItem> merged = PluginHost.mergeToolbarItems(List.of(
                new Broken("broken"),
                new Fake("good", List.of(item("kept", ToolbarGroup.RUN, 1)))));

        assertEquals(List.of("kept"), ids(merged));
    }

    /** A malformed item is dropped rather than crashing the merge or drawing a button that does nothing. */
    @Test
    void an_item_with_no_label_or_no_action_is_dropped() {
        List<ToolbarItem> offered = new ArrayList<>();
        offered.add(null);
        offered.add(new ToolbarItem("no-label", null, null, null, ToolbarGroup.RUN, 1,
                com.botmaker.plugin.api.EnabledWhen.ALWAYS, c -> { }));
        offered.add(new ToolbarItem("no-action", () -> "x", null, null, ToolbarGroup.RUN, 2,
                com.botmaker.plugin.api.EnabledWhen.ALWAYS, null));
        offered.add(item("fine", ToolbarGroup.RUN, 3));

        assertEquals(List.of("fine"), ids(PluginHost.mergeToolbarItems(List.of(new Fake("a", offered)))));
    }

    /** A plugin contributing nothing is the ordinary case, and the default the contract ships. */
    @Test
    void a_plugin_that_contributes_nothing_is_not_an_error() {
        assertTrue(PluginHost.mergeToolbarItems(List.of(new Fake("a", List.of()))).isEmpty());
        assertTrue(PluginHost.mergeToolbarItems(List.of(new Fake("a", null))).isEmpty());
        assertTrue(PluginHost.mergeToolbarItems(List.of()).isEmpty());
    }

    /** The bundled SDK plugin offers none yet — its buttons are still Studio's, and phase 13 is where they move. */
    @Test
    void the_bundled_set_offers_no_toolbar_items_yet() {
        assertFalse(PluginHost.plugins().isEmpty(), "the bundled set is empty; see PluginHostLoadTest");
        assertTrue(PluginHost.toolbarItems().isEmpty(),
                "the SDK plugin has started contributing toolbar items — update this test and the bar's"
                        + " own Studio-side list, which would otherwise offer the same buttons twice");
    }
}
