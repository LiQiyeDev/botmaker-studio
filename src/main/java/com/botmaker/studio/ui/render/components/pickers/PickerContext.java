package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;

/**
 * Everything a {@link SpecialTypePicker} needs to decide whether it applies and to build its editor:
 * the slot being edited ({@code arg} — a {@link ValueSlot}, so a picker works as well over a variable's
 * initializer as over a block on the canvas), the expected {@code paramType}, the {@code argIndex} of this
 * argument within the enclosing call, and — for method-specific pickers (e.g. the Steam game picker for
 * {@code Game.launchSteam}) — the enclosing call's {@code className}/{@code methodName}. Use {@link #of}
 * when there is no call context (e.g. a header slot or list element), which leaves the class/method null
 * and the index {@code -1}.
 */
public record PickerContext(CodeEditorService context, ValueSlot arg, ResolvedType paramType,
                            String className, String methodName, int argIndex) {

    /** A context with no enclosing-call info (class/method null, index -1) — for header slots and list elements. */
    public static PickerContext of(CodeEditorService context, ValueSlot arg, ResolvedType paramType) {
        return new PickerContext(context, arg, paramType, null, null, -1);
    }

    /** True when {@code paramType} is the SDK type {@code sdkType}. */
    public boolean isType(Class<?> sdkType) {
        return paramType != null && paramType.is(sdkType);
    }

    /** True when {@code paramType} is (by simple or qualified name) {@code simpleName} — for JDK types. */
    public boolean isType(String simpleName) {
        return paramType != null
                && (paramType.simpleName().equals(simpleName)
                    || paramType.qualifiedName().endsWith("." + simpleName));
    }

    // The four Game predicates — the program path, the trailing launch options, and the Steam and Epic launch
    // ids — went with their editors on 2026-08-28 (plugin platform, phase 12c). They are
    // that plugin's own call-site matchers now, written against SlotContext's enclosingClass /
    // enclosingMethod / argIndex, which is the same three facts this record carries and is what the contract
    // exposes them for.

    // isEmulatorNameArg and isEmulatorMethod went on 2026-08-31 with the picker they selected. The same four
    // calls are matched by
    // the SDK's CallSites.EMULATOR_NAME now, through the toolkit's own call-site matcher — which is where a
    // predicate about somebody else's API belonged all along.

    // The Time facade has no entry here: every one of its arguments is a java.time type (LocalTime, DayOfWeek,
    // Month) and dispatches on that. It used to need an isTimeHourArg() hook for the bare hours of
    // isBetween(int, int) / isBetweenUtc(int, int); those overloads were removed in favour of the LocalTime
    // pair, and the hook went with them.

    // The BotSettings predicate went the same way, and with it the one thing it duplicated: the list of which
    // setters are bounded now lives beside the ranges it selects (SettingsEditors.bounds), so a setter cannot
    // be claimed by a predicate that a table has no entry for.
}
