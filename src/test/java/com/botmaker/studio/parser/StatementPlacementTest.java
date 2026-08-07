package com.botmaker.studio.parser;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where {@code break} and {@code continue} may be dropped.
 *
 * <p>The case that brought this here: {@code MatchesSwitchBlock} emits a Java 21 <b>arrow</b> switch, and
 * {@code break} is a compile error inside an arrow rule — but the rule below whitelisted it in any
 * {@link SwitchStatement}, so the matchswitch block offered a jump that broke the build the moment it landed.
 * The colon form, where {@code break} is what closes a case, has to keep working, so both are asserted.
 *
 * <p>The two subtler ones are the nesting cases. A loop <em>inside</em> an arrow rule makes {@code break}
 * legal again (it targets the loop, not the switch), while a loop <em>outside</em> the switch does not — an
 * unlabelled jump cannot escape a switch rule at all (JLS 14.15/14.16), which is why the walk stops at an
 * arrow switch rather than continuing outward. Getting that backwards would look right in the common case and
 * be wrong in exactly the shape a bot's frame loop produces.
 */
class StatementPlacementTest {

    private static ASTNode bodyOfMarkerIn(String methodBody) {
        String source = """
                package com.mybot;
                public class Subject {
                    public void run() {
                        int value = 1;
                %s
                    }
                }
                """.formatted(methodBody.indent(8));
        CompilationUnit unit = SourceParser.parse(source);
        assertFalse(SourceParser.hasSyntaxErrors(unit), "fixture must parse: " + source);

        // The drop target is the block enclosing the `marker();` call — the same node the drag handler
        // resolves before asking StatementPlacement whether the dragged jump may land there.
        ASTNode[] found = new ASTNode[1];
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(ExpressionStatement node) {
                if (node.toString().startsWith("marker()")) found[0] = node.getParent();
                return true;
            }
        });
        assertNotNull(found[0], "fixture must contain a marker() call: " + source);
        return found[0];
    }

    private static boolean allowsBreak(String methodBody) {
        return StatementPlacement.allows(BlockCatalog.BREAK, bodyOfMarkerIn(methodBody));
    }

    private static boolean allowsContinue(String methodBody) {
        return StatementPlacement.allows(BlockCatalog.CONTINUE, bodyOfMarkerIn(methodBody));
    }

    @Test
    void anArrowSwitchDoesNotTakeABreak() {
        assertFalse(allowsBreak("""
                switch (value) {
                    case 1 -> {
                        marker();
                    }
                    default -> {
                    }
                }
                """));
    }

    /** The shape MatchesSwitchBlock writes: an arrow rule whose label is a guarded pattern. */
    @Test
    void aGuardedArrowSwitchDoesNotTakeABreak() {
        assertFalse(allowsBreak("""
                switch (value) {
                    case Integer m when m.equals(1) -> {
                        marker();
                    }
                    default -> {
                    }
                }
                """));
    }

    @Test
    void aColonSwitchStillTakesABreak() {
        assertTrue(allowsBreak("""
                switch (value) {
                    case 1:
                        marker();
                        break;
                    default:
                        break;
                }
                """));
    }

    @Test
    void neitherFormTakesAContinue() {
        String arrow = """
                switch (value) {
                    case 1 -> {
                        marker();
                    }
                    default -> {
                    }
                }
                """;
        String colon = """
                switch (value) {
                    case 1:
                        marker();
                        break;
                    default:
                        break;
                }
                """;
        assertFalse(allowsContinue(arrow), "continue never belongs to a switch");
        assertFalse(allowsContinue(colon), "continue never belongs to a switch");
    }

    /** A loop inside the rule body is a legal target — the jump never reaches the switch. */
    @Test
    void aLoopInsideAnArrowRuleTakesBothJumps() {
        String body = """
                switch (value) {
                    case 1 -> {
                        while (value > 0) {
                            marker();
                        }
                    }
                    default -> {
                    }
                }
                """;
        assertTrue(allowsBreak(body));
        assertTrue(allowsContinue(body));
    }

    /**
     * A loop <em>outside</em> the switch is not a legal target: an unlabelled jump cannot leave a switch rule.
     * This is the case the naive "keep walking up until you find a loop" reading gets wrong.
     */
    @Test
    void aLoopOutsideAnArrowSwitchIsNotReachable() {
        String body = """
                while (value > 0) {
                    switch (value) {
                        case 1 -> {
                            marker();
                        }
                        default -> {
                        }
                    }
                }
                """;
        assertFalse(allowsBreak(body), "break cannot escape an arrow rule to the enclosing while");
        assertFalse(allowsContinue(body), "continue cannot escape an arrow rule to the enclosing while");
    }

    @Test
    void anOrdinaryLoopBodyStillTakesBothAndAMethodBodyTakesNeither() {
        String loop = """
                while (value > 0) {
                    marker();
                }
                """;
        assertTrue(allowsBreak(loop));
        assertTrue(allowsContinue(loop));

        assertFalse(allowsBreak("marker();"));
        assertFalse(allowsContinue("marker();"));
    }

    /** Any non-jump block is placeable anywhere; a null target is placeable nowhere. */
    @Test
    void onlyJumpsAreConstrained() {
        assertTrue(StatementPlacement.allows(BlockCatalog.PRINT, bodyOfMarkerIn("marker();")));
        assertFalse(StatementPlacement.allows(BlockCatalog.BREAK, (ASTNode) null));
    }

    /** The rejection message names the colon form, since that is the one that would have taken the block. */
    @Test
    void theBreakRejectionNamesTheFormThatWouldWork() {
        assertTrue(StatementPlacement.Jump.BREAK.rejectionMessage().contains("case"),
                StatementPlacement.Jump.BREAK.rejectionMessage());
    }
}
