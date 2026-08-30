package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.CompanionPlugin;
import com.botmaker.plugin.api.StudioPlugin;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling the outgoing plugins that their project is over.
 *
 * <p>The failure this guards against is invisible in exactly the way a leak is: a plugin that opened a port
 * or a nested display for a project keeps holding it, the next project opens fine, and the symptom arrives
 * much later as an address already in use or a display nobody reaped. Nothing about it is a red build, so it
 * is a test.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ProjectClosingTest {

    /** A plugin that writes down that it was told. */
    private static final class Fake implements StudioPlugin {
        private final String id;
        private final List<String> log;
        private int told;

        Fake(String id, List<String> log) {
            this.id = id;
            this.log = log;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void projectClosing() {
            told++;
            log.add(id);
        }
    }

    /** A bug in somebody else's jar, met on the way out of a project. */
    private record Broken(String id) implements StudioPlugin {
        @Override
        public void projectClosing() {
            throw new IllegalStateException("no");
        }
    }

    /** A companion plugin that writes down that it was told, the same way {@link Fake} does. */
    private static final class FakeCompanion implements CompanionPlugin {
        private final String id;
        private final List<String> log;

        FakeCompanion(String id, List<String> log) {
            this.id = id;
            this.log = log;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void projectClosing() {
            log.add(id);
        }
    }

    /**
     * A plugin that is both, counting how many times it was told.
     *
     * <p>Javac forces every one of these overrides: two unrelated interfaces declaring the same default
     * method cannot both be inherited. That is the compiler stating the thing this test is about — such a
     * plugin has <em>one</em> {@code projectClosing()}, so the host must call it once.
     */
    private static final class Both implements StudioPlugin, CompanionPlugin {
        private int told;

        @Override public String id() { return "both"; }

        @Override public String displayName() { return "Both"; }

        @Override public List<com.botmaker.plugin.api.ToolbarItem> toolbarItems() { return List.of(); }

        @Override public void projectClosing() { told++; }
    }

    /** The plugins with no companion set — the shape most of the rules below are about. */
    private static void closeOutgoing(List<? extends StudioPlugin> outgoing) {
        PluginHost.closeOutgoing(outgoing, List.of());
    }

    @Test
    void every_plugin_serving_the_project_is_told_once() {
        List<String> log = new ArrayList<>();
        Fake first = new Fake("first", log);
        Fake second = new Fake("second", log);

        closeOutgoing(List.of(first, second));

        assertEquals(List.of("first", "second"), log);
        assertEquals(1, first.told, "a plugin told twice would release something it no longer holds");
        assertEquals(1, second.told);
    }

    /**
     * One plugin failing on the way out must not cost the next one its own release — and must not stop the
     * project that is opening. The host cannot finish releasing on the plugin's behalf, so what the thrower
     * held is leaked; what it must not take with it is everybody else's.
     */
    @Test
    void a_plugin_that_throws_does_not_stop_the_others() {
        List<String> log = new ArrayList<>();
        Fake after = new Fake("after", log);

        closeOutgoing(List.of(new Broken("broken"), after));

        assertEquals(List.of("after"), log);
    }

    /**
     * A plugin that does not implement it at all is the ordinary case — every contribution surface returns
     * data and needs no lifecycle — so the default must be a no-op rather than an {@code AbstractMethodError}
     * met while a project is closing.
     */
    @Test
    void a_plugin_that_never_heard_of_it_is_unaffected() {
        StudioPlugin older = () -> "older";

        closeOutgoing(List.of(older));
    }

    /** A companion plugin is told exactly like any other — it is the kind most likely to hold a port. */
    @Test
    void companion_plugins_are_told_too() {
        List<String> log = new ArrayList<>();

        PluginHost.closeOutgoing(
                List.of(new Fake("plugin", log)),
                List.of(new FakeCompanion("companion", log)));

        assertEquals(List.of("plugin", "companion"), log);
    }

    /**
     * One object implementing both interfaces appears in both lists and is told once.
     *
     * <p>Told twice, a plugin releases something it no longer holds — closing a port it has already closed,
     * or reaping a display that now belongs to the project that is opening. The SDK implements both, so this
     * is not hypothetical.
     */
    @Test
    void a_plugin_that_is_both_is_told_once() {
        Both both = new Both();

        PluginHost.closeOutgoing(List.of(both), List.of(both));

        assertEquals(1, both.told);
    }

    /**
     * Binding and unbinding with nothing resolvable leaves the bundled set live and tells it its project is
     * over — the fail-open path, where the set holding a project's resources has no loader of its own and
     * would otherwise never be told.
     */
    @Test
    void the_bundled_set_survives_a_bind_and_unbind() {
        List<StudioPlugin> before = PluginHost.plugins();

        PluginHost.bind(List.of());
        PluginHost.unbind();

        assertSame(before, PluginHost.plugins(), "the bundled set is never unloaded");
        assertTrue(PluginHost.plugins().stream().anyMatch(p -> "com.botmaker.sdk".equals(p.id())),
                "and still holds the SDK, so the palette survives a project being closed");
    }
}
