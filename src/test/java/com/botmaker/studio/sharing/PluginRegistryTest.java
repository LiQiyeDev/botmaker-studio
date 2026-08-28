package com.botmaker.studio.sharing;

import com.botmaker.studio.project.UserLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of Manage Plugins that has no JavaFX in it: what an entry means, and whether a plugin is already
 * on this project.
 *
 * <p>The dialog is a browser and an Install button; everything it decides is here, so the decisions are
 * testable headlessly and the same rule as {@code BlockTree} applies — the model is pure, the view is not.
 */
class PluginRegistryTest {

    private static final String INDEX = """
            [ {
              "id" : "com.example.discord",
              "name" : "Discord Notifier",
              "coordinate" : "com.github.someone:botmaker-discord-plugin",
              "repo" : "someone/botmaker-discord-plugin",
              "description" : "Sends a message when a bot finishes",
              "tags" : [ "notifications" ],
              "minContractVersion" : "1.0.0",
              "valueTypeIds" : [ "discord.channel" ],
              "verifiedVersion" : "v0.1.0",
              "verifiedAt" : "2026-08-28"
            } ]
            """;

    @Test
    void an_entry_reads_back_whole() {
        PluginRegistry.Plugin plugin = PluginRegistry.parse(INDEX).getFirst();

        assertEquals("com.example.discord", plugin.id());
        assertEquals("com.github.someone", plugin.groupId());
        assertEquals("botmaker-discord-plugin", plugin.artifactId());
        assertEquals("v0.1.0", plugin.verifiedVersion());
        assertEquals(List.of("discord.channel"), plugin.valueTypeIds());
        assertEquals("https://github.com/someone/botmaker-discord-plugin", plugin.htmlUrl());
    }

    /** This Studio is the reader that lags, so a field a newer `botmaker publish` writes must not break it. */
    @Test
    void an_unknown_field_does_not_lose_the_catalog() {
        List<PluginRegistry.Plugin> plugins =
                PluginRegistry.parse("[{\"id\":\"a.b\",\"coordinate\":\"g:a\",\"whatIsThis\":42}]");

        assertEquals(1, plugins.size());
        assertEquals("a.b", plugins.getFirst().id());
    }

    /** Every failure resolves to an empty catalog: the dialog degrades to a message, never an exception. */
    @Test
    void a_missing_or_broken_index_is_an_empty_catalog() {
        assertTrue(PluginRegistry.parse(null).isEmpty());
        assertTrue(PluginRegistry.parse("").isEmpty());
        assertTrue(PluginRegistry.parse("not json at all").isEmpty());
    }

    /**
     * Installed is decided by coordinate and never by version — a plugin pinned to an older version is
     * still installed, and offering to install it again would be a lie.
     */
    @Test
    void installed_is_by_coordinate_not_by_version() {
        PluginRegistry.Plugin plugin = PluginRegistry.parse(INDEX).getFirst();
        List<UserLibrary> older = List.of(
                new UserLibrary("com.github.someone", "botmaker-discord-plugin", "v0.0.9"));

        assertTrue(plugin.isInstalledIn(older));
        assertFalse(plugin.isInstalledIn(List.of(new UserLibrary("com.github.someone", "something-else", "1"))));
        assertFalse(plugin.isInstalledIn(List.of()));
    }

    @Test
    void an_entry_with_no_coordinate_is_not_installable() {
        PluginRegistry.Plugin plugin = PluginRegistry.parse("[{\"id\":\"a.b\",\"name\":\"A\"}]").getFirst();

        assertFalse(plugin.isInstallable());
        assertEquals("", plugin.htmlUrl());
    }

    @Test
    void search_matches_name_id_description_and_tags() {
        PluginRegistry.Plugin plugin = PluginRegistry.parse(INDEX).getFirst();

        assertTrue(plugin.matches(""));
        assertTrue(plugin.matches(null));
        assertTrue(plugin.matches("discord"));
        assertTrue(plugin.matches("NOTIFICATIONS"));
        assertTrue(plugin.matches("finishes"));
        assertFalse(plugin.matches("telegram"));
    }
}
