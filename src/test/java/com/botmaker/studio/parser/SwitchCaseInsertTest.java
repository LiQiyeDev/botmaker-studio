package com.botmaker.studio.parser;

import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adding a block into a {@code switch} branch — in both label forms.
 *
 * <p>The colon form ({@code case X:}) puts a case's statements in the <em>switch's</em> statement list, as
 * siblings of the label; the arrow form ({@code case X -> { … }}) puts them in one {@link Block} of their own.
 * Studio's write path was built for the first and applied to both, so a block dropped into an arrow branch was
 * inserted <b>in front of</b> that branch's body rather than into it:
 *
 * <pre>{@code
 * switch (found) { case Matches m when … -> ImageClicker.click(…);
 * {}
 * default -> {} }
 * }</pre>
 *
 * <p>A bare block among arrow rules doesn't parse, so the branch's contents vanished from the canvas. Both
 * forms are asserted here because the fix moves the arrow form onto a different rewrite path and the colon
 * form has to stay exactly where it was.
 */
class SwitchCaseInsertTest {

    private static String source(String body) {
        return """
                package com.mybot;
                public class Subject {
                    public void run() {
                        int value = 1;
                %s
                    }
                }
                """.formatted(body.indent(8));
    }

    private static final String ARROW_SWITCH = """
            switch (value) {
                case 1 -> {
                }
                default -> {
                }
            }
            """;

    private static final String COLON_SWITCH = """
            switch (value) {
                case 1:
                    break;
                default:
                    break;
            }
            """;

    private static final String GUARDED_SWITCH = """
            switch (value) {
                case Integer m when m.equals(1) -> {
                }
                default -> {
                }
            }
            """;

    @Test
    void aBlockDroppedIntoAnArrowBranchLandsInsideIt() {
        assertLandsInBranch(ARROW_SWITCH);
    }

    /** The shape the combination block writes: an arrow rule whose label is a guarded pattern. */
    @Test
    void aBlockDroppedIntoAGuardedArrowBranchLandsInsideIt() {
        assertLandsInBranch(GUARDED_SWITCH);
    }

    /** The form the write path was built for. It has to keep behaving exactly as it does today. */
    @Test
    void theColonFormStillAppendsToTheCase() {
        EditorFixture fixture = new EditorFixture(source(COLON_SWITCH));

        fixture.editor.addStatement(firstCaseBody(fixture), BlockCatalog.PRINT, 0);

        assertNotNull(fixture.lastCode, "nothing was inserted");
        assertParses(fixture.lastCode);
        assertTrue(fixture.lastCode.contains("BotMaker.print"), fixture.lastCode);
        // Before the case's own closing break, which is chrome the body doesn't contain.
        assertTrue(fixture.lastCode.indexOf("BotMaker.print") < fixture.lastCode.indexOf("break"),
                () -> "inserted after the case ended: " + fixture.lastCode);
    }

    /**
     * Inserted, parses, and is <em>inside</em> the branch — the three separate things that were wrong. Text
     * alone isn't enough: the broken output contained the statement too, just in the wrong list.
     */
    private static void assertLandsInBranch(String switchBody) {
        EditorFixture fixture = new EditorFixture(source(switchBody));

        fixture.editor.addStatement(firstCaseBody(fixture), BlockCatalog.PRINT, 0);

        assertNotNull(fixture.lastCode, "nothing was inserted — the edit was refused or dropped");
        assertParses(fixture.lastCode);
        assertTrue(fixture.lastCode.contains("BotMaker.print"), fixture.lastCode);
        // The statement has to be between the arrow and the brace that closes the rule, not after it.
        int arrow = fixture.lastCode.indexOf("->");
        int inserted = fixture.lastCode.indexOf("BotMaker.print");
        int defaultRule = fixture.lastCode.indexOf("default");
        assertTrue(arrow < inserted && inserted < defaultRule,
                () -> "landed outside the branch it was dropped in: " + fixture.lastCode);
    }

    private static void assertParses(String code) {
        assertFalse(SourceParser.hasSyntaxErrors(SourceParser.parse(code)),
                () -> "the edit published source that doesn't parse: " + code);
    }

    /** The body of the switch's first non-default case — the branch a user drops into. */
    private static BodyBlock firstCaseBody(EditorFixture fixture) {
        for (CodeBlock block : flatten(fixture.root)) {
            if (!(block instanceof BodyBlock body)) continue;
            SwitchCase label = labelOf(body);
            if (label != null && !label.isDefault()) return body;
        }
        throw new AssertionError("no case body in the fixture");
    }

    /**
     * The {@link SwitchCase} a body belongs to, whichever way it is backed — the colon form backs a body with
     * the label itself, the arrow form with the {@link Block} that follows it.
     */
    private static SwitchCase labelOf(BodyBlock body) {
        if (body.getAstNode() instanceof SwitchCase label) return label;
        if (body.getAstNode() instanceof Block block
                && block.getParent() instanceof org.eclipse.jdt.core.dom.SwitchStatement parent) {
            SwitchCase last = null;
            for (Object statement : parent.statements()) {
                if (statement == block) return last;
                if (statement instanceof SwitchCase label) last = label;
            }
        }
        return null;
    }

    private static List<CodeBlock> flatten(CodeBlock from) {
        List<CodeBlock> out = new ArrayList<>();
        out.add(from);
        if (from instanceof BlockWithChildren parent) {
            for (CodeBlock child : parent.getChildren()) out.addAll(flatten(child));
        }
        return out;
    }
}
