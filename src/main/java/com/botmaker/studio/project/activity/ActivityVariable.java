package com.botmaker.studio.project.activity;

import com.botmaker.studio.palette.BotType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One project variable: a named, typed value the bot reads while it runs.
 *
 * <p><b>Every variable belongs to the project, not to an activity.</b> A delay two activities both wait for
 * is one variable they both read, rather than a copy each. What organises them for a reader is {@link #tag()}
 * — the same tag vocabulary image templates use ({@link com.botmaker.studio.services.TagCatalog}), so "the
 * Mining variables" is a <em>view</em> and never a scope. A variable tagged after an activity is readable
 * from anywhere, and renaming the activity renames the tag in the gallery and here at once.
 *
 * <p><b>The value is text.</b> {@link #value()} is the wire form described by {@link VariableWire}: a list of
 * strings, one entry for an ordinary variable and one per item for a {@code List of …} one. One shape on disk
 * means one reader, one writer and one normaliser — see that class for why that is worth the {@code ["90s"]}
 * a duration reads as in the file.
 *
 * <p>{@link #type()} is a {@link BotType.Choice} — the same curated vocabulary the Add Function dialog offers
 * for a parameter or a return type, restricted to what {@link BotType#storable()} allows. There is one list
 * of types in this editor, so a variable can hold anything a method can take.
 *
 * @param name        the generated field name on {@code Activities}; a valid Java identifier
 * @param type        what kind of value this is, and whether it is a list of them
 * @param value       the wire form of the current value
 * @param description an optional human-readable note explaining what it is for (may be empty)
 * @param tag         the group it is filed under, or blank for {@link #GENERAL}
 * @param visibility  whether the bot's user is offered this at all; absent ⇒ {@link ParamVisibility#PUBLIC}
 * @param options     the declared choices, for {@link VariableWire#hasOptions an option-bearing} type
 * @param bounds      the declared range, for a bounded number
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityVariable(String name, BotType.Choice type, List<String> value, String description,
                               String tag, ParamVisibility visibility, List<String> options, Bounds bounds) {

    /** The heading a variable with no tag is listed under. Not a real tag: nothing declares it. */
    public static final String GENERAL = "General";

    public ActivityVariable {
        if (type == null) type = BotType.Choice.of(BotType.TEXT);
        if (description == null) description = "";
        if (tag == null) tag = "";
        // A variable exists to be configured, so it is offered to whoever runs the bot unless the editor says
        // otherwise. The old default was the reverse, which meant every new knob was invisible in the Runner
        // until somebody remembered a dropdown existed.
        if (visibility == null) visibility = ParamVisibility.PUBLIC;
        options = options == null ? List.of() : List.copyOf(options);
        if (bounds == null) bounds = Bounds.NONE;
        value = VariableWire.normalize(value, type, options, bounds);
    }

    /** A fresh variable of {@code type}, with that type's default value. */
    public static ActivityVariable create(String name, BotType.Choice type) {
        return create(name, type, "");
    }

    /** A fresh variable of {@code type} with a description. */
    public static ActivityVariable create(String name, BotType.Choice type, String description) {
        return new ActivityVariable(name, type, VariableWire.defaultWire(type), description, "",
                ParamVisibility.PUBLIC, List.of(), Bounds.NONE);
    }

    /** True when the bot's user is offered this variable in the Runner window. */
    @JsonIgnore
    public boolean isPublic() {
        return visibility == ParamVisibility.PUBLIC;
    }

    /** The tag this is filed under, or {@link #GENERAL} when it carries none. */
    @JsonIgnore
    public String tagOrGeneral() {
        return tag.isBlank() ? GENERAL : tag;
    }

    /** What the editor calls this — its {@link #description()} when it has one, else its name. */
    @JsonIgnore
    public String displayLabel() {
        return description.isBlank() ? name : description;
    }

    /** The single value, for the types that have exactly one; the first item of a list. */
    @JsonIgnore
    public String singleValue() {
        return value.isEmpty() ? "" : value.getFirst();
    }

    public ActivityVariable withValue(List<String> newValue) {
        return new ActivityVariable(name, type, newValue, description, tag, visibility, options, bounds);
    }

    /** Convenience for the single-valued types, which is most of them. */
    public ActivityVariable withValue(String newValue) {
        return withValue(List.of(newValue == null ? "" : newValue));
    }

    public ActivityVariable withName(String newName) {
        return new ActivityVariable(newName, type, value, description, tag, visibility, options, bounds);
    }

    public ActivityVariable withDescription(String newDescription) {
        return new ActivityVariable(name, type, value, newDescription, tag, visibility, options, bounds);
    }

    public ActivityVariable withTag(String newTag) {
        return new ActivityVariable(name, type, value, description, newTag, visibility, options, bounds);
    }

    public ActivityVariable withVisibility(ParamVisibility newVisibility) {
        return new ActivityVariable(name, type, value, description, tag, newVisibility, options, bounds);
    }

    public ActivityVariable withBounds(Bounds newBounds) {
        return new ActivityVariable(name, type, value, description, tag, visibility, options, newBounds);
    }

    /**
     * This variable retyped, its value reset to the new type's default and its options dropped unless the new
     * type declares any.
     *
     * <p>Retyping deliberately does not carry the old value across: a date is not a number, and pretending
     * otherwise stores something the editor would have to explain away on the next open. The old value going
     * is also what makes the value <em>widget</em> safe to rebuild wholesale, which is what the dialog does.
     */
    public ActivityVariable withType(BotType.Choice newType) {
        List<String> keptOptions = VariableWire.hasOptions(newType.type()) ? options : List.of();
        return new ActivityVariable(name, newType, VariableWire.defaultWire(newType), description, tag,
                visibility, keptOptions, Bounds.NONE);
    }

    /**
     * This variable with its declared choices replaced, its value pruned to what is still on offer.
     *
     * <p>Pruning is the point: an option the editor has just deleted must stop being a stored value, or the
     * bot runs on a setting that no longer appears anywhere in the UI that set it. The compact constructor
     * does the pruning, since normalising is exactly that.
     */
    public ActivityVariable withOptions(List<String> newOptions) {
        return new ActivityVariable(name, type, value, description, tag, visibility, newOptions, bounds);
    }
}
