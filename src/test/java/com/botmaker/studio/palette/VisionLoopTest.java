package com.botmaker.studio.palette;

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

    // theGroupLambdaSetIsExactlyWhatMatchesGroupScopeUsedToHardcode stood here until 2026-09-01. It held
    // VisionLoop.handsOverMatches() against the four method names MatchesGroupScope hardcoded, and both
    // sides of that agreement are gone: the guarded switch the flag existed to seed was deleted, and
    // MatchesGroupScope with it. What replaced the construct is a chain of ordinary catalogued calls, which
    // needs nothing from this enum.

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

    // aMatchesCheckKnowsItsMethodAndItsWords and aMatchesJoinKnowsItsWordAndItsOperator stood here until
    // 2026-09-01. MatchesCheck and MatchesJoin were the vocabulary of the guard editor — hasAny/hasAll and
    // &&/|| as things a dropdown could offer — and they went with the switch block that offered them. A
    // predicate is written as ordinary Java in a slot now, so there is no closed set left to describe.
}
