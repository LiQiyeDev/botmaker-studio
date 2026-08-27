package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.TypeRef;
import com.botmaker.plugin.api.ValueContext;
import com.botmaker.plugin.api.value.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link ValueContext} over one value the host is editing, with no call site behind it.
 *
 * <p>This is the Parameters window's half of "one editor, both places". A row of that window holds a
 * {@link ValueType} — a wire id out of the plugin vocabulary — while an editor is chosen by the <em>Java</em>
 * type the value has, which is what a slot in a bot's source has too. {@link ValueType#sourceName()} and
 * {@link ValueType#importName()} are the bridge: a plugin that claims {@code com.acme.Channel} therefore
 * matches in both places, having written the predicate once.
 *
 * <p><b>The value is held here, not read back out of the widget.</b> An editor writes through
 * {@link #set(List)} whenever the user changes something, and the host reads {@link #value()} when it is time
 * to store — which is the same discipline the built-in editors follow with their {@code read} suppliers, and
 * the reason a plugin's editor needs no lifecycle of its own.
 */
public final class HostValueContext implements ValueContext {

    private final TypeRef type;
    private final StudioServices services;
    private final Consumer<List<String>> onChange;
    private List<String> value;

    public HostValueContext(TypeRef type, List<String> value, StudioServices services,
                            Consumer<List<String>> onChange) {
        this.type = type;
        this.services = services;
        this.onChange = onChange;
        this.value = value == null ? List.of() : List.copyOf(value);
    }

    /**
     * The context for a value of {@code type} — the vocabulary type translated into the Java type a plugin
     * editor's predicate is written against.
     */
    public static HostValueContext of(ValueType type, List<String> value, StudioServices services,
                                      Consumer<List<String>> onChange) {
        return new HostValueContext(typeRef(type), value, services, onChange);
    }

    /**
     * A {@link ValueType} as the Java type it generates.
     *
     * <p>{@link ValueType#importName()} is the fully-qualified name and is blank for a primitive or for a
     * type nothing registered, in which case the simple name is all there is — which {@link TypeRef} already
     * models as "unresolved", and which a plugin predicate written with {@code isNamed} still matches.
     */
    public static TypeRef typeRef(ValueType type) {
        String simple = type == null ? "" : type.sourceName();
        String qualified = type == null ? "" : type.importName();
        return new TypeRef() {
            @Override public String simpleName() { return simple == null ? "" : simple; }

            @Override public String qualifiedName() { return qualified == null ? "" : qualified; }
        };
    }

    @Override
    public TypeRef type() {
        return type;
    }

    @Override
    public List<String> value() {
        return value;
    }

    @Override
    public void set(List<String> newValue) {
        value = newValue == null ? List.of() : List.copyOf(new ArrayList<>(newValue));
        if (onChange != null) onChange.accept(value);
    }

    @Override
    public StudioServices services() {
        return services;
    }
}
