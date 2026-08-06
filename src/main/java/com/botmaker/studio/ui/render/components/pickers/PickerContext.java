package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;

/**
 * Everything a {@link SpecialTypePicker} needs to decide whether it applies and to build its editor:
 * the expression being edited ({@code arg}), the expected {@code paramType}, the {@code argIndex} of this
 * argument within the enclosing call, and — for method-specific pickers (e.g. the Steam game picker for
 * {@code Game.launchSteam}) — the enclosing call's {@code className}/{@code methodName}. Use {@link #of}
 * when there is no call context (e.g. a header slot or list element), which leaves the class/method null
 * and the index {@code -1}.
 */
public record PickerContext(CodeEditorService context, ExpressionBlock arg, ResolvedType paramType,
                            String className, String methodName, int argIndex) {

    /** A context with no enclosing-call info (class/method null, index -1) — for header slots and list elements. */
    public static PickerContext of(CodeEditorService context, ExpressionBlock arg, ResolvedType paramType) {
        return new PickerContext(context, arg, paramType, null, null, -1);
    }

    /** True when {@code paramType} is the SDK type {@code sdkType}. */
    public boolean isType(SdkType sdkType) {
        return paramType != null && paramType.is(sdkType);
    }

    /** True when {@code paramType} is (by simple or qualified name) {@code simpleName} — for JDK types. */
    public boolean isType(String simpleName) {
        return paramType != null
                && (paramType.simpleName().equals(simpleName)
                    || paramType.qualifiedName().endsWith("." + simpleName));
    }

    /** True when the enclosing call is on the SDK {@code Game} facade and names {@code method}. */
    public boolean isGameMethod(String method) {
        String game = SdkType.GAME.simpleName();
        return method.equals(methodName)
                && className != null && (className.equals(game) || className.endsWith("." + game));
    }

    /**
     * The program-path argument (index 0) of a Game launch method: {@code launch(path, args...)},
     * {@code launchIfNotRunning(path, source, args...)}, or {@code launchAndWait(path, source, timeout, args...)}.
     */
    public boolean isGameLaunchProgramArg() {
        return argIndex == 0
                && (isGameMethod("launch") || isGameMethod("launchIfNotRunning") || isGameMethod("launchAndWait"));
    }

    /**
     * A trailing command-line-argument (varargs) of a Game launch method. The varargs start after the fixed
     * parameters, which differ per overload: {@code launch(path, …)} → index ≥ 1;
     * {@code launchIfNotRunning(path, source, …)} → index ≥ 2; {@code launchAndWait(path, source, timeout, …)} → index ≥ 3.
     */
    public boolean isGameLaunchOptionArg() {
        if (isGameMethod("launch")) return argIndex >= 1;
        if (isGameMethod("launchIfNotRunning")) return argIndex >= 2;
        if (isGameMethod("launchAndWait")) return argIndex >= 3;
        return false;
    }

    /**
     * The Steam appId argument (index 0) of {@code Game.launchSteam(appId)} or
     * {@code Game.launchSteamIfNotRunning(appId, source)} — offered the cover-art game picker.
     */
    public boolean isGameSteamAppIdArg() {
        return argIndex == 0 && (isGameMethod("launchSteam") || isGameMethod("launchSteamIfNotRunning"));
    }

    /**
     * The Epic app-name argument (index 0) of {@code Game.launchEpic(appName)} or
     * {@code Game.launchEpicIfNotRunning(appName, source)} — offered the cover-art game picker.
     */
    public boolean isGameEpicAppIdArg() {
        return argIndex == 0 && (isGameMethod("launchEpic") || isGameMethod("launchEpicIfNotRunning"));
    }

    /** True when the enclosing call is on the SDK {@code Emulators} facade and names {@code method}. */
    public boolean isEmulatorMethod(String method) {
        return method.equals(methodName)
                && className != null && (className.equals("Emulators") || className.endsWith(".Emulators"));
    }

    /**
     * The instance-name argument (index 0) of {@code Emulators.use(name)} / {@code named(name)} /
     * {@code launch(name)} / {@code stop(name)} — offered the discovered-instance dropdown
     * ({@code EmulatorArgPicker}).
     */
    public boolean isEmulatorNameArg() {
        return argIndex == 0 && (isEmulatorMethod("use") || isEmulatorMethod("named")
                || isEmulatorMethod("launch") || isEmulatorMethod("stop"));
    }

    // The Time facade has no entry here: every one of its arguments is a java.time type (LocalTime, DayOfWeek,
    // Month) and dispatches on that. It used to need an isTimeHourArg() hook for the bare hours of
    // isBetween(int, int) / isBetweenUtc(int, int); those overloads were removed in favour of the LocalTime
    // pair, and the hook went with them.

    /** True when the enclosing call is on the SDK {@code BotSettings} facade and names {@code method}. */
    public boolean isBotSettingsMethod(String method) {
        return method.equals(methodName)
                && className != null && (className.equals("BotSettings") || className.endsWith(".BotSettings"));
    }

    /**
     * The single (index-0) argument of a bounded {@code BotSettings} setter — a delay/retry count, a 0–1
     * confidence or margin, or a boolean toggle. Offered the bounded {@code BotSettingsArgPicker} (spinner /
     * slider / checkbox) instead of a free-typed number so the value stays in the setter's accepted range.
     */
    public boolean isBotSettingsArg() {
        return argIndex == 0 && (isBotSettingsMethod("setFoundDelay") || isBotSettingsMethod("setNotFoundDelay")
                || isBotSettingsMethod("setMaxRetryAttempts") || isBotSettingsMethod("setDefaultConfidence")
                || isBotSettingsMethod("setCompareMargin")
                || isBotSettingsMethod("enableRandomClicks") || isBotSettingsMethod("enableDebugMode"));
    }
}
