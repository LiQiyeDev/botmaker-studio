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
     * Ordered for the class dropdown: vision first (the common bot actions), then interaction.
     *
     * <p>{@code VisionContext} exposes the {@code MatchResult} stored by the last find/click/wait call
     * (the vision API returns {@code boolean}/{@code int} now, not {@code MatchResult}). {@code Screen}
     * is intentionally absent — it is no longer a user-facing {@code CaptureSource} facade.
     */
    public static final List<String> FACADE_CLASSES = List.of(
            "ImageFinder",
            "ImageClicker",
            "ImageWaiter",
            "VisionContext",
            "ClickConfig",
            "Mouse",
            "Wait",
            "Game",
            "Bot",
            "Watchdog");

    private static final Set<String> FACADE_SET = Set.copyOf(FACADE_CLASSES);

    /** True when {@code simpleClassName} is one of the SDK facade classes. */
    public static boolean isFacadeClass(String simpleClassName) {
        return simpleClassName != null && FACADE_SET.contains(simpleClassName);
    }
}
