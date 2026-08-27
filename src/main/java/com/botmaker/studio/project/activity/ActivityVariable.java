package com.botmaker.studio.project.activity;

import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueShape;
import com.botmaker.studio.plugin.PluginHost;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

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
 * <p><b>The value is text.</b> {@link #value()} is the wire form described by {@link ValueWire}: a list of
 * strings, one entry for an ordinary variable and one per item for a {@code List of …} one. One shape on disk
 * means one reader, one writer and one normaliser — see that class for why that is worth the {@code ["90s"]}
 * a duration reads as in the file.
 *
 * <p>{@link #type()} is a {@link ValueChoice} — a type out of <b>the plugin contract's open vocabulary</b>,
 * crossed with a {@link ValueShape}. It was Studio's own closed enum until 2026-08-27, and the difference is
 * the whole point of the platform: the seventeen types a variable can hold are registered by the SDK the same
 * way any other plugin would register a type of its own, and a type nothing registers still opens, still
 * shows its stored text and simply cannot be edited. The shape says whether the variable holds one value, one
 * out of a set the author wrote down, or several.
 *
 * @param name        the generated field name on {@code Activities}; a valid Java identifier
 * @param type        what kind of value this is, and in what {@link ValueShape shape} — one value, one of
 *                    a declared set, or any number of them
 * @param value       the wire form of the current value
 * @param description an optional human-readable note explaining what it is for (may be empty)
 * @param tag         the group it is filed under, or blank for {@link #GENERAL}
 * @param visibility  whether the bot's user is offered this at all; absent ⇒ {@link ParamVisibility#PUBLIC}
 * @param options     the declared set of values, for a shape that {@link ValueChoice#hasOptions has one}
 * @param bounds      the declared range, for a bounded number
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityVariable(String name,
                               @JsonSerialize(converter = ChoiceWire.ToWire.class) ValueChoice type,
                               List<String> value, String description,
                               String tag, ParamVisibility visibility, List<String> options, Bounds bounds) {

    /** The heading a variable with no tag is listed under. Not a real tag: nothing declares it. */
    public static final String GENERAL = "General";

    public ActivityVariable {
        if (type == null) type = ValueChoice.of(PluginHost.valueTypes().text());
        if (description == null) description = "";
        if (tag == null) tag = "";
        // A variable exists to be configured, so it is offered to whoever runs the bot unless the editor says
        // otherwise. The old default was the reverse, which meant every new knob was invisible in the Runner
        // until somebody remembered a dropdown existed.
        if (visibility == null) visibility = ParamVisibility.PUBLIC;
        if (bounds == null) bounds = Bounds.NONE;
        // The declared set is normalised before the value is measured against it, so the choice a radio button
        // is labelled with and the choice the value holds are the same string.
        options = ValueWire.normalizeOptions(options, type, bounds);
        value = ValueWire.normalize(value, type, options, bounds);
    }

    /**
     * Reads the persisted form, settling the one question {@link ValueChoice#fromWire} cannot.
     *
     * <p>{@code ANY_OF} used to mean two things — tick boxes over the author's choices, or a free list the
     * user filled in — and which one it was showed only in whether any choices were written down. Now that
     * they are two shapes, a file written before the split has to be read the way it used to <em>render</em>,
     * or a project full of "List of text" parameters opens with a column of tick boxes over nothing.
     *
     * <p>So: a stored {@code ANY_OF} keeps its shape when there is a set behind it — the author's choices, or
     * the type's own constants for a closed set like {@code Direction} — and becomes {@link
     * ValueShape#OPEN_LIST} when there is not. A newly created "Many of…" with nothing declared yet is
     * indistinguishable from that on disk and reads back as an open list, which is the shape it was drawn as
     * anyway; declaring a choice on it is what makes the distinction real.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    static ActivityVariable fromJson(
            @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
            @com.fasterxml.jackson.annotation.JsonProperty("type") ChoiceWire.Wire type,
            @com.fasterxml.jackson.annotation.JsonProperty("value") List<String> value,
            @com.fasterxml.jackson.annotation.JsonProperty("description") String description,
            @com.fasterxml.jackson.annotation.JsonProperty("tag") String tag,
            @com.fasterxml.jackson.annotation.JsonProperty("visibility") ParamVisibility visibility,
            @com.fasterxml.jackson.annotation.JsonProperty("options") List<String> options,
            @com.fasterxml.jackson.annotation.JsonProperty("bounds") Bounds bounds) {
        return new ActivityVariable(name, listShapeOf(ChoiceWire.toChoice(type), options), value, description,
                tag, visibility, options, bounds);
    }

    /** {@link #fromJson}'s rule, alone so it can be read — and tested — without a file. */
    static ValueChoice listShapeOf(ValueChoice type, List<String> options) {
        if (type == null || type.shape() != ValueShape.ANY_OF) return type;
        boolean hasSet = (options != null && !options.isEmpty())
                || !ValueWire.fixedOptions(type.type()).isEmpty();
        return hasSet ? type : new ValueChoice(type.type(), ValueShape.OPEN_LIST);
    }

    /** A fresh variable of {@code type}, with that type's default value. */
    public static ActivityVariable create(String name, ValueChoice type) {
        return create(name, type, "");
    }

    /** A fresh variable of {@code type} with a description. */
    public static ActivityVariable create(String name, ValueChoice type, String description) {
        return new ActivityVariable(name, type, ValueWire.defaultWire(type), description, "",
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
    public ActivityVariable withType(ValueChoice newType) {
        // Options survive a change of shape (one of ↔ any of, over the same values), because that is a
        // question about how many may be picked and not about what may be picked. They do not survive a
        // change of the base type, whose values they no longer are.
        //
        // Compared by id, never by identity: a ValueType's identity *is* its persisted id, and two plugin
        // classloaders each holding their own copy of a class would make `==` mean nothing.
        List<String> keptOptions =
                newType.hasOptions() && newType.type().equals(type.type()) ? options : List.of();
        return new ActivityVariable(name, newType, ValueWire.defaultWire(newType), description, tag,
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
