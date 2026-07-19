package com.botmaker.studio.palette;

import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the BotMaker-SDK public API facade classes — the static utility classes
 * (vision + interaction) that the specialized "SDK call" block can switch between and that
 * {@code BlockConverter} recognizes as library calls.
 *
 * <p>These live under {@code com.botmaker.sdk.api.*} and expose only {@code public static} methods
 * (see the SDK repo). Keeping the list here means the converter, the palette, and the SDK-class dropdown
 * all agree on exactly which classes are "SDK calls".
 */
public final class SdkApi {

    private SdkApi() {}

    /**
     * The complete, ordered facade list — this is also the display order of the per-class submenus in the
     * statement insert menu ({@code ExpressionMenuFactory.rebuildStatementItems}). The intent of the order:
     * interaction (Mouse/Keyboard/Wait), then vision (find/click/wait/pixel/text + the last-match contexts and
     * click config + the global {@code Debug} switch), then launch/emulator (Game/Target/Emulators), then bot
     * lifecycle (Bot/Watchdog/Activity),
     * then capture wiring (Source/Window) and observation (Bots).
     *
     * <p>{@code VisionContext} exposes the {@code MatchResult} stored by the last find/click/wait call
     * (the vision API returns {@code boolean}/{@code int} now, not {@code MatchResult}) and, likewise, the
     * {@code ColorMatch} stored by the last {@code Pixel} call. {@code Target} is the current launch target
     * holder ({@code start()}/{@code restart()}). {@code Screen} is intentionally absent — it is no longer a
     * user-facing {@code CaptureSource} facade.
     *
     * <p>Only <em>facade classes</em> belong here. Adding a new <em>method</em> to an existing facade needs
     * no change: method-level knowledge is discovered at runtime by {@code ProjectAnalyzer} scanning the
     * resolved SDK jar with ClassGraph.
     */
    public static final List<String> FACADE_CLASSES = List.of(
            "Mouse",
            "Keyboard",
            "Wait",
            "ImageFinder",
            "ImageClicker",
            "ImageWaiter",
            "Pixel",
            "Text",
            "VisionContext",
            "ClickConfig",
            "Debug",
            "Game",
            "Target",
            "Emulators",
            "Bot",
            "Watchdog",
            "Activity",
            "Source",
            "Window",
            "Bots");

    private static final Set<String> FACADE_SET = Set.copyOf(FACADE_CLASSES);

    /**
     * Facades intentionally hidden from the insert menus while still being <em>recognized</em> as SDK calls
     * (so existing calls to them render with the standard SDK-block chrome and are excluded from the generic
     * "Library (static)" listings). {@code Bots}/{@code Window}/{@code Watchdog} are internal wiring the user
     * shouldn't reach for directly: bot supervision is driven by {@code Bot.start}, capture by the
     * capture-source picker, and the watchdog by the generated loop.
     */
    private static final Set<String> MENU_HIDDEN = Set.of("Bots", "Window", "Watchdog");

    /**
     * The facades shown as submenus in the statement/expression insert menus — {@link #FACADE_CLASSES} minus
     * {@link #MENU_HIDDEN}, preserving order. Menu builders iterate this; recognition still uses the full set.
     */
    public static final List<String> MENU_FACADE_CLASSES =
            FACADE_CLASSES.stream().filter(c -> !MENU_HIDDEN.contains(c)).toList();

    /** True when {@code simpleClassName} is one of the SDK facade classes (recognition — the full set). */
    public static boolean isFacadeClass(String simpleClassName) {
        return simpleClassName != null && FACADE_SET.contains(simpleClassName);
    }
}
