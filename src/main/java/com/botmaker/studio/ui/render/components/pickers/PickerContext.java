package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ExpressionBlock;
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

    /** True when {@code paramType} is (by simple or qualified name) {@code simpleName}. */
    public boolean isType(String simpleName) {
        return paramType != null
                && (paramType.simpleName().equals(simpleName)
                    || paramType.qualifiedName().endsWith("." + simpleName));
    }

    /** True when the enclosing call is on the SDK {@code Game} facade and names {@code method}. */
    public boolean isGameMethod(String method) {
        return method.equals(methodName)
                && className != null && (className.equals("Game") || className.endsWith(".Game"));
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
}
