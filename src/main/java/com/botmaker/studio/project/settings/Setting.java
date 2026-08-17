package com.botmaker.studio.project.settings;

import com.botmaker.studio.project.activity.ParamVisibility;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One project-wide setting: a named, typed value the bot reads while it runs.
 *
 * <p>This replaces {@link com.botmaker.studio.project.activity.ActivityVariable}, and the difference is not
 * the fields — it is who owns one. An {@code ActivityVariable} belonged to an <em>activity</em>, so a knob two
 * activities both needed had to exist twice, and one belonging to the bot as a whole had to live in a second,
 * parallel "globals" list. A {@code Setting} belongs to the project. What organises them for a reader is
 * {@link #tag()}, the same tag vocabulary image templates use
 * ({@link com.botmaker.studio.services.TagCatalog}) — so "the Mining settings" is a view, not an ownership.
 *
 * <p><b>The value is text.</b> {@link #value()} is the wire form described by {@link SettingType}: a list of
 * strings, one entry for every type but {@link SettingType#MULTI_CHOICE}. It is text because the generated
 * file is the store — Studio writes the value into an annotation element and reads it straight back out,
 * with no expression parsing in between. See {@code SettingsClassWriter} for the other half of that.
 *
 * @param name       the generated field name; a valid Java identifier
 * @param type       what kind of value this is
 * @param tag        the group it is filed under, or blank for {@link #GENERAL}
 * @param label      an optional human-readable note; the dialog and the Runner show it instead of the name
 * @param visibility whether the bot's user is offered this at all; absent ⇒ {@link ParamVisibility#EDITOR_ONLY}
 * @param value      the wire form of the current value
 * @param options    the declared choices, for an {@link SettingType#hasOptions() option-bearing} type
 * @param bounds     the declared range, for a bounded number
 */
public record Setting(String name, SettingType type, String tag, String label, ParamVisibility visibility,
                      List<String> value, List<String> options, Setting.Bounds bounds) {

    /** The heading a setting with no tag is listed under. Not a real tag: nothing declares it. */
    public static final String GENERAL = "General";

    /**
     * The declared range of a number setting — all three optional, all three stored as text so a
     * {@link SettingType#DURATION} bound can be written the way a duration is ({@code "30s"}).
     *
     * <p>They are advice to the widget and a clamp at generation time, never a validation that can fail: a
     * value outside the range is pulled to the nearest bound, because the alternative is a project that
     * refuses to save because of a limit somebody tightened after the fact.
     */
    public record Bounds(String min, String max, String step) {

        /** No range declared — the state every number setting starts in. */
        public static final Bounds NONE = new Bounds(null, null, null);

        public Bounds {
            min = blankToNull(min);
            max = blankToNull(max);
            step = blankToNull(step);
        }

        public boolean isEmpty() {
            return min == null && max == null && step == null;
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    public Setting {
        name = name == null ? "" : name.trim();
        if (type == null) type = SettingType.TEXT;
        tag = tag == null ? "" : tag.trim();
        label = label == null ? "" : label;
        if (visibility == null) visibility = ParamVisibility.EDITOR_ONLY;
        value = value == null ? List.of() : value.stream().filter(Objects::nonNull).toList();
        options = options == null ? List.of() : options.stream().filter(Objects::nonNull).toList();
        if (bounds == null) bounds = Bounds.NONE;
    }

    /** A fresh setting of {@code type} under {@code tag}, holding that type's default value. */
    public static Setting create(String name, SettingType type, String tag) {
        SettingType safe = type == null ? SettingType.TEXT : type;
        return new Setting(name, safe, tag, "", ParamVisibility.EDITOR_ONLY,
                safe.defaultWire(), List.of(), Bounds.NONE).normalized();
    }

    /**
     * The enable flag of {@code activityName} — the one setting the editor never creates by hand. Named
     * exactly after its activity, which is what makes it findable from the flow canvas and what
     * {@code Settings.Mining} means in generated code.
     */
    public static Setting enableFlag(String activityName, boolean enabled) {
        return new Setting(activityName, SettingType.ENABLE, activityName, "", ParamVisibility.EDITOR_ONLY,
                List.of(Boolean.toString(enabled)), List.of(), Bounds.NONE);
    }

    /** True when the bot's user is offered this in the Runner window. */
    public boolean isShared() {
        return visibility == ParamVisibility.PUBLIC;
    }

    /** True for the flag that says whether an activity runs — not a knob anyone added. */
    public boolean isEnableFlag() {
        return type == SettingType.ENABLE;
    }

    /** The group this is listed under: its tag, or {@link #GENERAL} when it has none. */
    public String tagOrGeneral() {
        return tag.isBlank() ? GENERAL : tag;
    }

    /** What to call this in the dialog and the Runner: the label if there is one, else the field name. */
    public String displayLabel() {
        return label.isBlank() ? name : label;
    }

    /** The single value, for every type but {@link SettingType#MULTI_CHOICE}. Blank when nothing is stored. */
    public String singleValue() {
        return value.isEmpty() ? "" : value.getFirst();
    }

    /** The choices actually in force — the SDK enum's for {@code KEY}/{@code MOUSE_BUTTON}, else the declared. */
    public List<String> effectiveOptions() {
        return type.effectiveOptions(options);
    }

    /** The Java expression this setting's value compiles to, in the generated static block. */
    public String literal() {
        return type.literal(normalized().value);
    }

    /**
     * This setting with its value pulled into range — the only value that should ever be written out.
     *
     * <p>Idempotent by contract: normalising twice changes nothing, which is what lets the writer normalise
     * on the way out and the reader normalise on the way in without the file churning between two spellings
     * of the same value.
     */
    public Setting normalized() {
        List<String> fixed = type.normalize(value, options, bounds);
        return fixed.equals(value) ? this : new Setting(name, type, tag, label, visibility, fixed, options, bounds);
    }

    public Setting withName(String newName) {
        return new Setting(newName, type, tag, label, visibility, value, options, bounds);
    }

    public Setting withTag(String newTag) {
        return new Setting(name, type, newTag, label, visibility, value, options, bounds);
    }

    public Setting withLabel(String newLabel) {
        return new Setting(name, type, tag, newLabel, visibility, value, options, bounds);
    }

    public Setting withVisibility(ParamVisibility newVisibility) {
        return new Setting(name, type, tag, label, newVisibility, value, options, bounds);
    }

    /** This setting holding {@code newValue}, normalised. Use {@link #withValues} for a multi-valued type. */
    public Setting withValue(String newValue) {
        return withValues(newValue == null ? List.of() : List.of(newValue));
    }

    public Setting withValues(List<String> newValues) {
        return new Setting(name, type, tag, label, visibility, newValues, options, bounds).normalized();
    }

    /**
     * This setting retyped, its value reset to the new type's default.
     *
     * <p>Retyping does not try to carry the old value across, for the reason
     * {@code ActivityVariable.withType} already gave: a date is not a number, and pretending otherwise stores
     * something that only looks like it survived.
     */
    public Setting withType(SettingType newType) {
        SettingType safe = newType == null ? SettingType.TEXT : newType;
        return new Setting(name, safe, tag, label, visibility, safe.defaultWire(),
                safe.hasOptions() ? options : List.of(), safe == type ? bounds : Bounds.NONE).normalized();
    }

    /** This setting's choices replaced, its value pruned to what is still on offer. */
    public Setting withOptions(List<String> newOptions) {
        return new Setting(name, type, tag, label, visibility, value, newOptions, bounds).normalized();
    }

    /** This setting's range replaced, its value clamped into it. */
    public Setting withBounds(Bounds newBounds) {
        return new Setting(name, type, tag, label, visibility, value, options, newBounds).normalized();
    }

    /**
     * {@code raw} as a field name this can be generated under, or {@code null} when it cannot be one.
     *
     * <p>Total and conservative: it uppercases and folds separators, so "give up after" becomes
     * {@code GIVE_UP_AFTER}, but it refuses rather than mangles anything that would not be a legal Java
     * identifier. A caller shows the refusal; it never invents a name the editor did not ask for.
     */
    public static String toFieldName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String folded = raw.trim().replaceAll("[\\s-]+", "_").toUpperCase(Locale.ROOT);
        if (!Character.isJavaIdentifierStart(folded.charAt(0)) || folded.charAt(0) == '$') return null;
        for (int i = 1; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c != '_' && !Character.isLetterOrDigit(c)) return null;
            if (c > 127) return null;
        }
        return folded;
    }
}
