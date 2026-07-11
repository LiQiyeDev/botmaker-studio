package com.botmaker.studio.services.record;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.BlockType.LibraryCall;
import com.botmaker.studio.palette.Initializer.EnumConst;
import com.botmaker.studio.palette.Initializer.IntLit;
import com.botmaker.studio.palette.Initializer.StaticCall;
import com.botmaker.studio.palette.Initializer.StrLit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.botmaker.studio.palette.BlockCategory.CONTROL;
import static com.botmaker.studio.palette.BlockCategory.INPUT;

/**
 * Pure translation of a recorded {@link InputEvent} stream into a flat list of {@link BlockType} leaf blocks —
 * no JavaFX, no native, no side effects, so it is fully unit-testable. The macro recorder buffers events while
 * recording and calls {@link #translate} on stop; the caller inserts the returned blocks in order.
 *
 * <p>v1 scope (leaf actions only, all coordinates <b>window-relative</b> or position-independent):
 * <ul>
 *   <li>Left click inside the window → {@code Mouse.click(CaptureSource.window("title"), relX, relY)}.</li>
 *   <li>Printable keys → coalesced into one {@code Keyboard.type("…")} per burst (Backspace edits the buffer).</li>
 *   <li>Named keys (Enter/Tab/arrows/F-keys/…) → {@code Keyboard.tap(Key.NAME)}.</li>
 *   <li>Wheel → {@code Mouse.scroll(±n)} (accumulated per direction).</li>
 *   <li>An idle gap ≥ {@value #GAP_MS} ms between gestures → a {@code Wait.milliseconds(n)} in between.</li>
 * </ul>
 * Right/middle/double clicks and drags are intentionally deferred (they lack a window-relative SDK overload);
 * a left-button drag is detected only to <em>suppress</em> a spurious click, not to emit a drag block. Standalone
 * modifier keys (Shift/Ctrl/Alt/…) are dropped. Clicks outside the window bounds are dropped.
 */
public final class MacroTranslator {

    /** Idle gap between gestures that produces a Wait block. */
    static final long GAP_MS = 400;
    /** Waits are rounded to this granularity for tidy values. */
    private static final long WAIT_ROUND_MS = 100;
    /** Pointer travel (px) between a left press and release beyond which we treat it as a drag, not a click. */
    private static final int DRAG_THRESHOLD_PX = 5;

    /** The window a recording targets: its title substring and current absolute origin + size. */
    public record WindowRef(String title, int originX, int originY, int width, int height) {}

    private MacroTranslator() {}

    /** X keysym → SDK {@code Key} enum constant name for non-printable named keys (mirrors the SDK's Key enum). */
    private static final Map<Long, String> NAMED_KEYS = buildNamedKeys();

    // X keysyms for modifiers we drop (keysymdef.h). Also covers the *_Lock keys.
    private static final long[] MODIFIER_KEYSYMS = {
            0xFFE1L, 0xFFE2L,           // Shift_L/R
            0xFFE3L, 0xFFE4L,           // Control_L/R
            0xFFE9L, 0xFFEAL,           // Alt_L/R
            0xFFE7L, 0xFFE8L,           // Meta_L/R
            0xFFEBL, 0xFFECL,           // Super_L/R
            0xFFEDL, 0xFFEEL,           // Hyper_L/R
            0xFFE5L, 0xFFE6L, 0xFF7FL,  // Caps_Lock, Shift_Lock, Num_Lock
            0xFE03L                     // ISO_Level3_Shift (AltGr)
    };

    /**
     * Translates the buffered events for a recording of {@code window} into leaf blocks.
     */
    public static List<BlockType> translate(List<InputEvent> events, WindowRef window) {
        List<Timed> gestures = collectGestures(events, window);
        return withWaits(gestures);
    }

    /** A gesture block paired with the wall-clock time it happened, for later gap→Wait insertion. */
    private record Timed(BlockType block, long time) {}

    private static List<Timed> collectGestures(List<InputEvent> events, WindowRef window) {
        List<Timed> out = new ArrayList<>();
        StringBuilder typing = new StringBuilder();
        long[] typingStart = {0};

        // Pending wheel run, accumulated per direction.
        int[] scrollAccum = {0};
        long[] scrollTime = {0};

        // Left-button drag detection: press coords + whether the pointer travelled far while held.
        boolean[] leftDown = {false};
        int[] downX = {0}, downY = {0};
        boolean[] dragged = {false};

        for (InputEvent e : events) {
            switch (e) {
                case InputEvent.KeyPress k -> {
                    long sym = k.keysym();
                    if (isModifier(sym)) continue;
                    if (sym == 0xFF08L && typing.length() > 0) { // Backspace edits the in-progress text
                        typing.deleteCharAt(typing.length() - 1);
                        continue;
                    }
                    Character ch = printableChar(sym);
                    if (ch != null) {
                        flushScroll(out, scrollAccum, scrollTime);
                        if (typing.length() == 0) typingStart[0] = k.timestampMs();
                        typing.append(ch.charValue());
                    } else {
                        String name = NAMED_KEYS.get(sym);
                        if (name == null) continue; // unknown / unsupported key — skip rather than emit garbage
                        flushTyping(out, typing, typingStart);
                        flushScroll(out, scrollAccum, scrollTime);
                        out.add(new Timed(tap(name), k.timestampMs()));
                    }
                }
                case InputEvent.ButtonPress b -> {
                    switch (b.button()) {
                        case 1 -> { leftDown[0] = true; downX[0] = b.x(); downY[0] = b.y(); dragged[0] = false; }
                        case 4, 5 -> { // wheel up / down — one notch per press
                            int dir = b.button() == 4 ? 1 : -1;
                            flushTyping(out, typing, typingStart);
                            if (scrollAccum[0] != 0 && Integer.signum(scrollAccum[0]) != dir) {
                                flushScroll(out, scrollAccum, scrollTime);
                            }
                            if (scrollAccum[0] == 0) scrollTime[0] = b.timestampMs();
                            scrollAccum[0] += dir;
                        }
                        default -> { // middle/right — deferred in v1
                            flushTyping(out, typing, typingStart);
                            flushScroll(out, scrollAccum, scrollTime);
                        }
                    }
                }
                case InputEvent.Motion m -> {
                    if (leftDown[0] && travelledFar(downX[0], downY[0], m.x(), m.y())) dragged[0] = true;
                }
                case InputEvent.ButtonRelease b -> {
                    if (b.button() == 1 && leftDown[0]) {
                        leftDown[0] = false;
                        if (dragged[0]) continue;           // a drag, not a click — suppress (drag deferred)
                        int relX = b.x() - window.originX();
                        int relY = b.y() - window.originY();
                        if (!inside(relX, relY, window)) continue; // e.g. a click on the recorder toolbar
                        flushTyping(out, typing, typingStart);
                        flushScroll(out, scrollAccum, scrollTime);
                        out.add(new Timed(click(window.title(), relX, relY), b.timestampMs()));
                    }
                }
                case InputEvent.KeyRelease ignored -> { /* shift state tracked natively; nothing to emit */ }
            }
        }
        flushTyping(out, typing, typingStart);
        flushScroll(out, scrollAccum, scrollTime);
        return out;
    }

    /** Inserts a Wait between gestures separated by a gap ≥ {@link #GAP_MS}. */
    private static List<BlockType> withWaits(List<Timed> gestures) {
        List<BlockType> out = new ArrayList<>();
        long prevTime = -1;
        for (Timed g : gestures) {
            if (prevTime >= 0) {
                long gap = g.time() - prevTime;
                if (gap >= GAP_MS) out.add(waitMs(roundWait(gap)));
            }
            out.add(g.block());
            prevTime = g.time();
        }
        return out;
    }

    private static void flushTyping(List<Timed> out, StringBuilder typing, long[] typingStart) {
        if (typing.length() == 0) return;
        out.add(new Timed(type(typing.toString()), typingStart[0]));
        typing.setLength(0);
    }

    private static void flushScroll(List<Timed> out, int[] scrollAccum, long[] scrollTime) {
        if (scrollAccum[0] == 0) return;
        out.add(new Timed(scroll(scrollAccum[0]), scrollTime[0]));
        scrollAccum[0] = 0;
    }

    // ── Block builders (ad-hoc LibraryCalls; not BlockCatalog entries) ───────────────────────────────────

    private static BlockType click(String title, int relX, int relY) {
        return new LibraryCall("REC_CLICK", "Mouse Click", INPUT, "Mouse", "click",
                List.of(new StaticCall("CaptureSource", "window", List.of(new StrLit(title))),
                        new IntLit(Integer.toString(relX)), new IntLit(Integer.toString(relY))));
    }

    private static BlockType type(String text) {
        return new LibraryCall("REC_TYPE", "Type Text", INPUT, "Keyboard", "type", List.of(new StrLit(text)));
    }

    private static BlockType tap(String keyName) {
        return new LibraryCall("REC_TAP", "Press Key", INPUT, "Keyboard", "tap",
                List.of(new EnumConst("Key", keyName)));
    }

    private static BlockType scroll(int notches) {
        return new LibraryCall("REC_SCROLL", "Scroll", INPUT, "Mouse", "scroll",
                List.of(new IntLit(Integer.toString(notches))));
    }

    private static BlockType waitMs(long ms) {
        return new LibraryCall("REC_WAIT", "Wait", CONTROL, "Wait", "milliseconds",
                List.of(new IntLit(Long.toString(ms))));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private static boolean inside(int relX, int relY, WindowRef w) {
        return relX >= 0 && relY >= 0 && relX < w.width() && relY < w.height();
    }

    private static boolean travelledFar(int x0, int y0, int x1, int y1) {
        return Math.abs(x1 - x0) > DRAG_THRESHOLD_PX || Math.abs(y1 - y0) > DRAG_THRESHOLD_PX;
    }

    private static long roundWait(long gap) {
        long rounded = Math.round((double) gap / WAIT_ROUND_MS) * WAIT_ROUND_MS;
        return Math.max(WAIT_ROUND_MS, rounded);
    }

    private static boolean isModifier(long keysym) {
        for (long m : MODIFIER_KEYSYMS) if (m == keysym) return true;
        return false;
    }

    /** The character to type for a keysym, or {@code null} when it isn't a printable Latin-1 symbol. */
    private static Character printableChar(long keysym) {
        // X keysyms for Latin-1 printable characters equal their Unicode code point.
        if ((keysym >= 0x20 && keysym <= 0x7E) || (keysym >= 0xA0 && keysym <= 0xFF)) {
            return (char) keysym;
        }
        return null;
    }

    private static Map<Long, String> buildNamedKeys() {
        Map<Long, String> m = new java.util.HashMap<>();
        m.put(0xFF0DL, "ENTER");
        m.put(0xFF8DL, "ENTER");   // KP_Enter
        m.put(0xFF1BL, "ESCAPE");
        m.put(0xFF09L, "TAB");
        m.put(0xFF08L, "BACKSPACE");
        m.put(0xFFFFL, "DELETE");
        m.put(0xFF51L, "LEFT");
        m.put(0xFF52L, "UP");
        m.put(0xFF53L, "RIGHT");
        m.put(0xFF54L, "DOWN");
        // Function keys F1..F12 (XK_F1 = 0xFFBE).
        for (int i = 0; i < 12; i++) m.put(0xFFBEL + i, "F" + (i + 1));
        // Keypad digits with NumLock on (XK_KP_0 = 0xFFB0) → the SDK's NUM0..NUM9 constants.
        for (int i = 0; i < 10; i++) m.put(0xFFB0L + i, "NUM" + i);
        return Map.copyOf(m);
    }
}
