package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled plugin set actually loads on Studio's own classpath.
 *
 * <p><b>This exists because it did not, and the whole palette was gone.</b> Phase 4 of the plugin platform
 * moved {@code AbstractStudioPlugin} into {@code botmaker-plugin-toolkit} and made {@code SdkPlugin} extend
 * it; the SDK declares the toolkit {@code optional}, so it is not transitive, and Studio deliberately did not
 * list it. Everything still compiled. At runtime {@code ServiceLoader} threw
 * {@code NoClassDefFoundError: com/botmaker/plugin/toolkit/AbstractStudioPlugin} while constructing the one
 * plugin Studio ships, {@link PluginHost#discover} caught it — correctly, since a classpath with no plugin on
 * it is an ordinary state — and Studio started with an empty palette, no name recognition and none of the
 * SDK's slot editors, having printed one line to stderr.
 *
 * <p>So the rule this test states is: <b>a compile-time check cannot see a missing runtime dependency, and
 * the failure it produces here is silent by design.</b> Every assertion below is about the bundled set being
 * non-empty, because empty is exactly what a break looks like — never an exception, never a red build until
 * something asks.
 *
 * <p>It reads the static, unbound state on purpose: no project is open, so {@code PluginHost} answers from
 * {@code BUNDLED}, which is the set built from Studio's own classloader at class-init.
 */
class PluginHostLoadTest {

    @Test
    void studios_own_classpath_yields_the_bundled_plugin() {
        List<StudioPlugin> plugins = PluginHost.plugins();

        assertFalse(plugins.isEmpty(),
                "no StudioPlugin loaded from Studio's own classpath — the bundled set is empty, which is how"
                        + " a missing runtime dependency of a bundled plugin presents. Check that every"
                        + " dependency SdkPlugin needs is on Studio's runtime classpath.");
        assertTrue(plugins.stream().anyMatch(plugin -> "com.botmaker.sdk".equals(plugin.id())),
                "the SDK is Studio's plugin #1 and did not load; found: "
                        + plugins.stream().map(StudioPlugin::id).toList());
    }

    /**
     * The companion set loads too, and it is the half that fails <em>most</em> silently.
     *
     * <p>A {@link StudioPlugin} that does not load costs the palette, which is visible the moment somebody
     * opens an insert menu. A {@link com.botmaker.plugin.api.CompanionPlugin} that does not load costs a
     * toolbar button — and a button that was never drawn looks exactly like a feature that was never
     * written. The Remote Pilot is declared in its own {@code META-INF/services} file, so a build that
     * packages one services file and not the other presents here and nowhere else.
     */
    @Test
    void studios_own_classpath_yields_the_bundled_companion() {
        List<String> ids = PluginHost.companions().stream()
                .map(com.botmaker.plugin.api.CompanionPlugin::id).toList();

        assertTrue(ids.contains("botmaker-pilot"),
                "the Remote Pilot did not load as a CompanionPlugin, so its toolbar button is silently"
                        + " absent; found: " + ids);
    }

    /** The palette is the SDK plugin's largest contribution, and an empty one is a usable-looking Studio. */
    @Test
    void the_bundled_palette_has_facades() {
        assertFalse(PluginHost.bundled().facades().isEmpty(),
                "the bundled palette catalog is empty, so the insert menus offer nothing");
        assertTrue(PluginHost.bundled().problems().isEmpty(),
                "the bundled palette reports problems: " + PluginHost.bundled().problems());
    }

    /**
     * The two surfaces that are built by <em>touching</em> the toolkit rather than merely extending it: the
     * value vocabulary every project file is written in, and the editors drawn for it.
     */
    @Test
    void the_bundled_value_types_and_editors_are_present() {
        assertFalse(PluginHost.valueTypes().types().isEmpty(),
                "no value types registered — a project's variables have no vocabulary to be stored in");
        assertFalse(PluginHost.slotEditors().isEmpty(),
                "no slot editors registered — every slot falls through to Studio's own pickers");
    }
}
