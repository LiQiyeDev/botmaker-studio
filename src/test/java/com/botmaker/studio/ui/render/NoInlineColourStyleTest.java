package com.botmaker.studio.ui.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No block or renderer names a colour in an inline style.
 *
 * <p>In JavaFX an inline style beats an author stylesheet outright — no specificity contest, the node's own
 * {@code setStyle} wins. So a single {@code setStyle("-fx-text-fill: black")} is immune to every design token
 * in {@code blocks.css} and to every theme, permanently. That is not a hypothetical: the class/library
 * dropdown rendered black-on-black in the Dark and Black themes, the enum constant field rendered white text
 * on a white field, and the statement/expression menu glyphs were a flat {@code #555} against a nearly-#555
 * menu — three separate reports, one cause. Around twenty-five such strings had accumulated, and none of them
 * could be fixed by editing the palette.
 *
 * <p>So the rule is mechanical: under {@code blocks/}, {@code core/} and {@code ui/render/}, an inline style
 * may carry <em>geometry</em> (padding, radius, font weight, cursor) but never a colour. A colour goes in
 * {@code blocks.css} against a {@code -bm-*} or {@code -fx-*} token, where a theme can reach it. There is no
 * allowlist below because nothing needed one — if you are about to add an entry, the honest question is
 * whether the rule belongs in the stylesheet instead.
 *
 * <p>What this cannot see: a style string assembled at runtime, which is why
 * {@code ui/render/theme/StyleBuilder} passes this test while emitting colours. That is deliberate and
 * separately owned — it mirrors the editor's own accent colours for the drag-and-drop feedback — but a *new*
 * runtime-built colour style would slip past here, so it is worth knowing this test's floor.
 */
class NoInlineColourStyleTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/botmaker/studio");
    private static final Set<String> SCANNED = Set.of("blocks", "core", "ui/render", "ui/app/flow");

    /** The literal argument of a {@code setStyle("…")} call, including concatenated continuations. */
    private static final Pattern SET_STYLE = Pattern.compile("setStyle\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\")");

    /** A colour written into the style rather than named as a token: a hex, an rgb/a, or a CSS colour name. */
    private static final Pattern COLOUR = Pattern.compile(
            "#[0-9a-fA-F]{3,8}\\b|\\brgba?\\s*\\(|\\b(?:white|black|gray|grey|red|green|blue|silver|orange)\\b");

    private record Offence(Path file, int line, String style) {
        @Override
        public String toString() {
            return file + ":" + line + "  " + style;
        }
    }

    @Test
    void noInlineStyleUnderBlocksCoreOrRenderNamesAColour() throws IOException {
        List<Offence> offences = new ArrayList<>();

        for (String area : SCANNED) {
            Path root = SOURCE_ROOT.resolve(area);
            assertTrue(Files.isDirectory(root), root + " must exist — has the package moved?");

            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher m = SET_STYLE.matcher(source);
                    while (m.find()) {
                        String style = m.group(1);
                        if (COLOUR.matcher(style).find()) {
                            int line = (int) source.substring(0, m.start()).lines().count();
                            offences.add(new Offence(file, line, style));
                        }
                    }
                }
            }
        }

        assertTrue(offences.isEmpty(), () -> offences.size()
                + " inline style(s) name a colour, which no theme can override. Move each to a rule in "
                + "css/blocks.css over a -bm-* token and put the style class on the node instead:\n  "
                + String.join("\n  ", offences.stream().map(Offence::toString).toList()));
    }
}
