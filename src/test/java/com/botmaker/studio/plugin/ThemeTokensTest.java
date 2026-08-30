package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.ThemeTokens;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's look as data, for a plugin that draws where JavaFX cannot reach.
 *
 * <p>Nothing here needs a JavaFX toolkit: that is the point of the record and is what makes it the one part
 * of the theme a plugin in another process could ever be told about.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ThemeTokensTest {

    /**
     * Built with no project and no capture service, which is legal and is the point: the tokens are a
     * property of the application, so nothing about them needs one.
     */
    private static ThemeTokens tokens() {
        return new HostServices(null, null, null).themeTokens();
    }

    @AfterEach
    void restore() {
        BlockTheme.setTheme(BlockTheme.ThemeType.DEFAULT);
    }

    /**
     * {@code dark} is measured from the background rather than read off the theme's name, so a theme added
     * later reports itself correctly instead of defaulting to light and making a client draw white on white.
     */
    @Test
    void dark_follows_the_background_the_host_is_actually_painting() {
        BlockTheme.setTheme(BlockTheme.ThemeType.DEFAULT);
        assertFalse(tokens().dark(), "the default theme paints a white ground");

        BlockTheme.setTheme(BlockTheme.ThemeType.DARK);
        assertTrue(tokens().dark());

        BlockTheme.setTheme(BlockTheme.ThemeType.BLACK);
        assertTrue(tokens().dark());
    }

    /** Switching the theme changes what a plugin is told — the tokens are read live, never cached. */
    @Test
    void the_colours_follow_the_active_theme() {
        BlockTheme.setTheme(BlockTheme.ThemeType.DEFAULT);
        String light = tokens().background();

        BlockTheme.setTheme(BlockTheme.ThemeType.DARK);

        assertNotEquals(light, tokens().background());
    }

    /**
     * Every token is a usable CSS value. A blank or null one is the failure worth guarding: a stylesheet
     * built from it produces an unstyled page rather than an error anybody would see.
     */
    @Test
    void no_token_is_blank() {
        for (BlockTheme.ThemeType type : BlockTheme.ThemeType.values()) {
            BlockTheme.setTheme(type);
            ThemeTokens t = tokens();
            for (String value : new String[]{t.background(), t.text(), t.accent(), t.hover(),
                    t.error(), t.warning(), t.success(), t.fontFamily(), t.monoFamily()}) {
                assertTrue(value != null && !value.isBlank(), type + " reported a blank token");
            }
            assertTrue(t.fontSize() > 0, type + " reported a font size of " + t.fontSize());
        }
    }

    /**
     * The contract's fallback is a legible palette rather than a set of blanks, because a host with no theme
     * — the CLI's validator, a test harness — must still let a plugin render something readable.
     */
    @Test
    void the_contracts_default_is_legible() {
        assertFalse(ThemeTokens.DEFAULT.dark());
        assertEquals("#FFFFFF", ThemeTokens.DEFAULT.background());
        assertNotEquals(ThemeTokens.DEFAULT.background(), ThemeTokens.DEFAULT.text());
    }
}
