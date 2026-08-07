package com.botmaker.studio.ui.render;

import com.botmaker.studio.palette.BlockCategory;
import javafx.css.CssParser;
import javafx.css.Stylesheet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block palette is legible — measured, in every theme.
 *
 * <p>Blocks are filled with their category's colour, so every label inside one is read against that fill. The
 * fill/text pairing therefore has to hold for ten categories across four themes, and until this test there was
 * nothing checking it but the eye of whoever last opened the editor. It hadn't held: every label colour in
 * {@code blocks.css} was a hardcoded white written for coloured fills that were never applied, and once they
 * were, white failed WCAG AA on eight of the ten categories — {@code #3498db} scores 3.15 against white and
 * 5.52 against near-black. "Dark-looking fill ⇒ white text" is the intuition this palette punishes.
 *
 * <p>So the contract is checked, not trusted: for every {@code -bm-fill-*} token there is an
 * {@code -bm-on-fill-*} beside it in the same theme block, it is one of the two audited on-colours, and the
 * measured contrast ratio clears 4.5:1. A new category, a new theme, or a designer nudging one hex all fail
 * here rather than in a screenshot.
 *
 * <p>The fill is a separate token from the {@code -bm-cat-*} accent because the two diverge in a dark theme:
 * the accent is a 1px border and stays bright, while the surface a whole block is painted with drops to ~20%
 * lightness, which is the difference between a dark theme and a canvas of neon slabs. The light themes
 * declare {@code -bm-fill-X: -bm-cat-X} and are measured through that reference.
 */
class BlockPaletteContrastTest {

    /** WCAG 2.1 AA for normal-size text. */
    private static final double AA = 4.5;

    /**
     * The only two on-colours the palette is allowed to use. Two is not a stylistic limit — each one is a
     * contrast audit, so a third would have to be measured against all forty fills.
     */
    private static final String NEAR_BLACK = "#1a1a1a";
    private static final String WHITE = "#ffffff";

    /** The theme selectors in blocks.css, each of which redefines the whole token set. */
    private static final String[] THEMES = {".root", ".dark-theme", ".black-theme", ".high-contrast-theme"};

    /** A token's value: either a hex literal or a reference to another token in the same theme block. */
    private static final Pattern FILL = Pattern.compile("-bm-fill-([a-z-]+):\\s*(#[0-9a-fA-F]{6}|-bm-[a-z-]+);");
    private static final Pattern ON_FILL = Pattern.compile("-bm-on-fill-([a-z-]+):\\s*(#[0-9a-fA-F]{6});");
    private static final Pattern ANY_TOKEN = Pattern.compile("(-bm-[a-z-]+):\\s*(#[0-9a-fA-F]{6});");

    private static String css() throws IOException {
        try (InputStream in = BlockPaletteContrastTest.class.getResourceAsStream("/css/blocks.css")) {
            assertNotNull(in, "blocks.css must be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The text of one theme's declaration block. None of them nests braces, so the next '}' ends it. */
    private static String themeBlock(String css, String selector) {
        int open = css.indexOf(selector + " {");
        assertTrue(open >= 0, "blocks.css must declare " + selector);
        int close = css.indexOf('}', open);
        assertTrue(close > open, selector + " must be a closed declaration block");
        return css.substring(open, close);
    }

    private static Map<String, String> tokens(String block, Pattern pattern) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = pattern.matcher(block);
        while (m.find()) found.put(m.group(1), m.group(2));
        return found;
    }

    /**
     * Resolve one level of token indirection. The light themes declare {@code -bm-fill-X: -bm-cat-X} rather
     * than repeating the hex, which is the point of a token — so measuring them means following the reference.
     * One level is all this file uses, and a second would be worth objecting to rather than supporting.
     */
    private static String resolve(String value, String themeBlock, String rootBlock) {
        if (value.startsWith("#")) return value;
        String resolved = tokens(themeBlock, ANY_TOKEN).getOrDefault(
                value, tokens(rootBlock, ANY_TOKEN).get(value));
        assertNotNull(resolved, value + " must resolve to a hex literal in its own theme or in .root");
        return resolved;
    }

    // WCAG 2.1 relative luminance and contrast ratio.

    private static double luminance(String hex) {
        double[] channel = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            channel[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
    }

    private static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    @Test
    void everyCategoryFillHasAReadableOnColourInEveryTheme() throws IOException {
        String css = css();
        String root = themeBlock(css, ".root");
        for (String theme : THEMES) {
            String block = themeBlock(css, theme);
            Map<String, String> fills = tokens(block, FILL);
            Map<String, String> onColours = tokens(block, ON_FILL);

            assertTrue(fills.size() >= 10, theme + " must define the full category palette, got " + fills);
            assertEquals(fills.keySet(), onColours.keySet(),
                    theme + " must pair every -bm-fill-* with an -bm-on-fill-*");

            fills.forEach((name, declared) -> {
                String fill = resolve(declared, block, root);
                String on = onColours.get(name);
                assertTrue(NEAR_BLACK.equalsIgnoreCase(on) || WHITE.equalsIgnoreCase(on),
                        theme + " -bm-on-fill-" + name + " must be one of the two audited on-colours, was " + on);

                double ratio = contrast(fill, on);
                assertTrue(ratio >= AA, () -> String.format(
                        "%s: %s on %s scores %.2f, below AA %.1f. The other on-colour scores %.2f — either use "
                                + "it or change the fill.",
                        theme, on, fill, ratio, AA,
                        contrast(fill, NEAR_BLACK.equalsIgnoreCase(on) ? WHITE : NEAR_BLACK)));
            });
        }
    }

    /**
     * The fill is only safe because the same rule re-points {@code -bm-text-on-color} at that category's
     * on-colour: JavaFX looked-up colours cascade, so that one line is what fixes every descendant label. A
     * rule that painted a fill and forgot it would render white-on-yellow and nothing else would complain.
     */
    @Test
    void everyFilledCategoryRuleRepointsTheTextToken() throws IOException {
        String css = css();
        for (BlockCategory category : BlockCategory.values()) {
            // The selector appears twice: once ending the shared geometry group, once as its own filled rule.
            String rule = "." + category.styleClass() + " {";
            String filled = null;
            for (int open = css.indexOf(rule); open >= 0; open = css.indexOf(rule, open + 1)) {
                String body = css.substring(open, css.indexOf('}', open));
                if (body.contains("-fx-background-color: -bm-fill-")) filled = body;
            }

            assertNotNull(filled, category.styleClass() + " must have a rule filling it with its category token");
            assertTrue(filled.contains("-bm-text-on-color: -bm-on-fill-"),
                    category.styleClass() + " fills but never re-points -bm-text-on-color: " + filled);
        }
    }

    /**
     * The label rules sit on those fills and must take their colour from the token rather than naming one.
     * Each of these was a literal white, and each was the reported "block text is unreadable".
     */
    @Test
    void theLabelsOnAColouredSurfaceNameNoColourOfTheirOwn() throws IOException {
        String css = css();
        for (String rule : new String[]{
                ".collapse-button", ".header-name-label", ".header-params-label",
                ".switch-case-break", ".method-returns-label", ".matches-case-mode"}) {
            int open = css.indexOf(rule + " {");
            assertTrue(open >= 0, "blocks.css must style " + rule);
            String body = css.substring(open, css.indexOf('}', open));

            assertTrue(body.contains("-fx-text-fill: -bm-text-on-color"),
                    rule + " must take the surface's on-colour: " + body);
        }
    }

    /**
     * The stylesheet parses. JavaFX does not fail a scene over a bad rule — it logs the error and drops that
     * rule, so a typo in a 1,100-line file shows up as one block that quietly kept the old look. Parsing it
     * here needs no toolkit and no window.
     *
     * <p>This catches <em>syntax</em> only: a missing colon or brace. An unknown colour name parses fine and
     * fails later at conversion, so this is a floor, not a full check of the file.
     */
    @Test
    void theStylesheetParsesWithoutErrors() throws IOException {
        CssParser.errorsProperty().clear();
        Stylesheet parsed = new CssParser().parse(
                BlockPaletteContrastTest.class.getResource("/css/blocks.css"));

        assertNotNull(parsed, "blocks.css must parse into a stylesheet");
        assertTrue(CssParser.errorsProperty().isEmpty(),
                "blocks.css must parse cleanly: " + CssParser.errorsProperty());
    }
}
