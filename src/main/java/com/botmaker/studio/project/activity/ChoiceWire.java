package com.botmaker.studio.project.activity;

import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.studio.plugin.PluginHost;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.util.StdConverter;

/**
 * How a {@link ValueChoice} is written into {@code activities.json}, and read back out of it.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>The vocabulary is the plugin contract's now, and <b>the contract carries no Jackson annotations</b> — its
 * one dependency is {@code javafx-controls} at {@code provided}, and adding a JSON library there would impose
 * it on every plugin. So the contract declares the wire <em>form</em> and whoever owns the file supplies the
 * parser; for {@code activities.json} that owner is Studio, and this is Studio's half.
 *
 * <p>The shape on disk is unchanged from when the type was an enum — a nested object beside the variable's
 * other fields:
 *
 * <pre>{@code "type": { "type": "TEXT", "shape": "ONE_OF" }}</pre>
 *
 * <p>Two legacy keys are read and no longer written. {@code "list": true} was the shape axis before there was
 * one, and {@code "void"} was a property of the block editor's type list that a stored value never had —
 * {@link ValueChoice#fromWire} settles the first and {@link Wire} simply ignores the second, along with
 * anything else a newer writer adds.
 */
public final class ChoiceWire {

    private ChoiceWire() {}

    /**
     * The persisted object, verbatim. Every field is a {@code String} or a {@code Boolean} because every one
     * of them is read through a total factory — an id nothing registers and a shape nobody has heard of both
     * have to open, and neither can be typed as something that would refuse them.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Wire(String type, String shape, Boolean list) {}

    /** The stored object as a choice, resolving its id against every loaded plugin's vocabulary. */
    public static ValueChoice toChoice(Wire wire) {
        if (wire == null) return null;
        return ValueChoice.fromWire(PluginHost.valueTypes(), wire.type(), wire.shape(), wire.list());
    }

    /** A choice as it is stored. {@code list} is not written — the shape says it. */
    public static Wire toWire(ValueChoice choice) {
        if (choice == null) return null;
        return new Wire(choice.type().id(), choice.shape().name(), null);
    }

    /** Serialises a {@link ValueChoice} record component; see {@link ActivityVariable#type()}. */
    public static final class ToWire extends StdConverter<ValueChoice, Wire> {
        @Override
        public Wire convert(ValueChoice value) {
            return toWire(value);
        }
    }
}
