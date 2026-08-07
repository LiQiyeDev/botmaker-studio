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
 * <p>So the contract is checked, not trusted: for every {@code -bm-cat-*} token there is an
 * {@code -bm-on-cat-*} beside it in the same theme block, it is one of the two audited on-colours, and the
 * measured contrast ratio clears 4.5:1. A new category, a new theme, or a designer nudging one hex all fail
 * here rather than in a screenshot.
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

    private static final Pattern CAT = Pattern.compile("-bm-cat-([a-z-]+):\\s*(#[0-9a-fA-F]{6});");
    private static final Pattern ON_CAT = Pattern.compile("-bm-on-cat-([a-z-]+):\\s*(#[0-9a-fA-F]{6});");

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
        for (String theme : THEMES) {
            String block = themeBlock(css, theme);
            Map<String, String> fills = tokens(block, CAT);
            Map<String, String> onColours = tokens(block, ON_CAT);

            assertTrue(fills.size() >= 10, theme + " must define the full category palette, got " + fills);
            assertEquals(fills.keySet(), onColours.keySet(),
                    theme + " must pair every -bm-cat-* with an -bm-on-cat-*");

            fills.forEach((name, fill) -> {
                String on = onColours.get(name);
                assertTrue(NEAR_BLACK.equalsIgnoreCase(on) || WHITE.equalsIgnoreCase(on),
                        theme + " -bm-on-cat-" + name + " must be one of the two audited on-colours, was " + on);

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
                if (body.contains("-fx-background-color: -bm-cat-")) filled = body;
            }

            assertNotNull(filled, category.styleClass() + " must have a rule filling it with its category token");
            assertTrue(filled.contains("-bm-text-on-color: -bm-on-cat-"),
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
