package com.botmaker.studio.palette;

import com.botmaker.sdk.api.bot.BotStuckException;
import com.botmaker.sdk.api.bot.Session;
import com.botmaker.sdk.api.bot.StartMode;
import com.botmaker.sdk.api.capture.CaptureSource;
import com.botmaker.sdk.api.emulator.Emulator;
import com.botmaker.sdk.api.emulator.EmulatorRef;
import com.botmaker.sdk.api.emulator.EmulatorSource;
import com.botmaker.sdk.api.launch.LaunchTarget;
import com.botmaker.sdk.api.util.BotMaker;
import com.botmaker.sdk.api.util.Time;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curated type list: what it contains, and — more usefully — what it leaves out.
 *
 * <p>The list is an allow-list over the SDK's own types rather than a filter of them, so this test's job is to fail
 * when someone widens it by accident. The absences are the whole design: a bot author choosing a variable type
 * should not be offered {@code BotStuckException}, and should not be offered the six {@code CaptureSource}
 * implementations when the capture-target dialog is what picks between those.
 */
class BotTypeTest {

    @Test
    void everySdkEntryNamesARealSdkType() {
        // The compiler already guarantees this — the point of asserting it is the reverse direction below.
        for (BotType type : BotType.values()) {
            type.sdkType().ifPresent(sdk -> assertEquals(sdk.getSimpleName(), type.typeName()));
        }
    }

    @Test
    void theSdkTypesDeliberatelyLeftOutStayOut() {
        Set<Class<?>> offered = BotType.declarableTypes().stream()
                .flatMap(t -> t.sdkType().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // Plumbing and the target dialogs' business. If one of these ever belongs in a variable declaration,
        // that is a decision to take deliberately — not by widening a filter.
        //
        // The list is shorter than it was because SDK 1.1.0 settled several of these at the source: the
        // CaptureSource implementations and the whole observation stack moved to com.botmaker.sdk.internal,
        // so a bot cannot even write their names down and no filter can offer them. What is left here is the
        // set that is still public API and still excluded by this file's judgement rather than by the SDK's
        // package boundary.
        for (Class<?> excluded : List.of(BotMaker.class, BotStuckException.class, StartMode.class,
                EmulatorSource.class, Emulator.class, EmulatorRef.class, LaunchTarget.class,
                Session.class, Time.class)) {
            assertFalse(offered.contains(excluded),
                    excluded.getSimpleName() + " should not be offered as a bot type");
        }
        // The interface is offered; its implementations are not.
        assertTrue(offered.contains(CaptureSource.class));
    }

    @Test
    void everyDeclarableTypeSeedsAValueThatCompiles() {
        // The reason the declare menu can offer eighteen types: each one knows what a fresh value of it looks
        // like, so dropping the block never produces a null the user has to notice.
        for (BotType type : BotType.declarableTypes()) {
            assertTrue(type.defaultValue().isPresent(), type + " needs a default value");
            assertNotNull(type.suggestedName(), type + " needs a variable name");
        }
        assertTrue(BotType.NOTHING.defaultValue().isEmpty(), "void is a return type, not a variable");
        assertFalse(BotType.NOTHING.declarable());
    }

    @Test
    void aListOfAPrimitiveIsWrittenWithTheBox() {
        assertEquals("List<Integer>", BotType.Choice.listOf(BotType.WHOLE_NUMBER).sourceName());
        assertEquals("int", BotType.Choice.of(BotType.WHOLE_NUMBER).sourceName());
        assertEquals("List<Point>", BotType.Choice.listOf(BotType.POINT).sourceName());
        assertEquals("List of Point", BotType.Choice.listOf(BotType.POINT).label());
    }

    @Test
    void thereIsNoListOfNothing() {
        assertThrows(IllegalArgumentException.class, () -> BotType.Choice.listOf(BotType.NOTHING));
        assertTrue(BotType.Choice.of(BotType.NOTHING).isVoid());
    }

    /**
     * {@code List<T>} is a type a signature can name, so it needs only a box. The three other shapes this enum
     * once carried — "one of a declared set", "any of", an open list — restricted a value somebody
     * <em>configures</em> rather than naming a type javac accepts, and phase 10b moved them to
     * {@link com.botmaker.plugin.api.value.ValueShape} with the rest of the stored-value vocabulary. What is
     * left here is the axis a signature can spell, which is why {@code List<MatchResult>} is expressible.
     */
    @Test
    void theShapeAxisIsTheOneASignatureCanSpell() {
        assertEquals("List<MatchResult>", BotType.Choice.listOf(BotType.MATCH_RESULT).sourceName());
        assertEquals(2, BotType.Shape.values().length, "a signature's shapes are T and List<T>, nothing else");
    }

    /**
     * A type whose values are already a closed set is still listable. The rule that refused such a type a
     * "one of…" was about a stored value and travelled to the contract with it; conflating the two once turned
     * {@code List of Direction} — perfectly expressible — into a single direction.
     */
    @Test
    void aTypeThatIsAlreadyASetIsStillListable() {
        for (BotType closed : List.of(BotType.YES_NO, BotType.DIRECTION, BotType.KEY, BotType.MOUSE_BUTTON)) {
            assertTrue(closed.listable(), closed + " must still be listable");
            assertTrue(BotType.Choice.listOf(closed).sourceName().startsWith("List<"));
        }
    }

    @Test
    void theDeclareMenuOffersTheCuratedTypesAndNotFive() {
        // What "Declare Bot Variable" used to hold: Point, Rect, Size, MatchResult, ImageTemplate. The menu is
        // generated from this list now, so the two features cannot know different sets of types.
        List<BlockType> declares = BlockCatalog.all().stream()
                .filter(b -> b.category() == BlockCategory.BOT_VARIABLE)
                .toList();

        long expected = BotType.declarableTypes().stream()
                .filter(t -> t.group() != BotType.Group.BASICS).count();
        assertEquals(expected, declares.size());
        assertTrue(declares.size() > 5, "the point of the change was that it is no longer five");
        assertTrue(declares.contains(BlockCatalog.DECLARE_POINT));
    }
}
