package com.botmaker.studio.palette;

import com.botmaker.studio.blocks.flow.MatchesGroupScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vision-loop set, pinned where the literals used to be: the nine method names the dropdown offers, and
 * the four that hand their lambda a {@code Matches}.
 */
class VisionLoopTest {

    @Test
    void theNineFormsAreTheOnesTheDropdownOffered() {
        assertEquals(
                List.of("ifFind", "ifFindAny", "ifFindAll",
                        "whileFind", "whileFindAny", "whileFindAll",
                        "untilFind", "untilFindAny", "untilFindAll"),
                java.util.Arrays.stream(VisionLoop.values()).map(VisionLoop::methodName).toList(),
                "the dropdown lists values() in order, so a reordering is a UI change");
    }

    @Test
    void theGroupLambdaSetIsExactlyWhatMatchesGroupScopeUsedToHardcode() {
        // The literal that lived in MatchesGroupScope, kept here as the assertion it never had.
        Set<String> hardcoded = Set.of("ifFindAny", "whileFindAny", "ifFindAll", "whileFindAll");

        Set<String> derived = java.util.Arrays.stream(VisionLoop.values())
                .filter(VisionLoop::handsOverMatches)
                .map(VisionLoop::methodName)
                .collect(Collectors.toSet());

        assertEquals(hardcoded, derived);
        for (String method : hardcoded) {
            assertTrue(MatchesGroupScope.isGroupLambdaCall(method), method + " hands over a Matches");
        }
        assertAll(
                () -> assertFalse(MatchesGroupScope.isGroupLambdaCall("whileFind"), "a single hit is not a Matches"),
                () -> assertFalse(MatchesGroupScope.isGroupLambdaCall("untilFindAll"), "until… hands over nothing"),
                () -> assertFalse(MatchesGroupScope.isGroupLambdaCall("hasAny"), "not a find call at all"));
    }

    @Test
    void theShapeOfEachFormFollowsItsName() {
        for (VisionLoop loop : VisionLoop.values()) {
            String name = loop.methodName();
            assertAll(
                    () -> assertEquals(name.endsWith("Any") || name.endsWith("All"), loop.group(), name),
                    () -> assertEquals(name.startsWith("if"), loop.returnsBoolean(), name),
                    () -> assertEquals(!name.startsWith("until"), loop.hasParam(), name));
        }
    }

    @Test
    void theParameterNamesTheValueItsTypeCarries() {
        assertEquals("match", VisionLoop.WHILE_FIND.defaultParamName(), "a single MatchResult");
        assertEquals("found", VisionLoop.WHILE_FIND_ANY.defaultParamName(), "a whole Matches");
        assertEquals(null, VisionLoop.UNTIL_FIND.defaultParamName(), "nothing is handed over");
    }

    @Test
    void theParseIsTotalBecauseTheSourceMayCallAnything() {
        assertSame(VisionLoop.UNTIL_FIND_ALL, VisionLoop.fromMethodName("untilFindAll").orElseThrow());
        assertEquals(Optional.empty(), VisionLoop.fromMethodName("ifFindNearest"));
        assertEquals(Optional.empty(), VisionLoop.fromMethodName(null));
    }

    @Test
    void aMatchesCheckKnowsItsMethodAndItsWords() {
        assertAll(
                () -> assertEquals("hasAny", MatchesCheck.ANY.methodName()),
                () -> assertEquals("hasAll", MatchesCheck.ALL.methodName()),
                () -> assertEquals("any of", MatchesCheck.ANY.label()),
                () -> assertSame(MatchesCheck.ALL, MatchesCheck.of(true)),
                () -> assertSame(MatchesCheck.ANY, MatchesCheck.of(false)),
                () -> assertSame(MatchesCheck.ALL, MatchesCheck.fromMethodName("hasAll").orElseThrow()),
                () -> assertEquals(Optional.empty(), MatchesCheck.fromMethodName("has")));
    }

    /** The companion of the check: how several of them combine into one branch's condition. */
    @Test
    void aMatchesJoinKnowsItsWordAndItsOperator() {
        assertAll(
                () -> assertEquals("and", MatchesJoin.AND.label()),
                () -> assertEquals("&&", MatchesJoin.AND.symbol()),
                () -> assertEquals("||", MatchesJoin.OR.symbol()),
                () -> assertSame(MatchesJoin.OR, MatchesJoin.AND.flipped()),
                () -> assertSame(MatchesJoin.AND, MatchesJoin.OR.flipped()),
                () -> assertSame(MatchesJoin.OR, MatchesJoin.fromSymbol("||").orElseThrow()),
                () -> assertEquals(Optional.empty(), MatchesJoin.fromSymbol("&")));
    }
}
