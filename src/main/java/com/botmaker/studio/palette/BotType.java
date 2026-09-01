package com.botmaker.studio.palette;

import com.botmaker.plugin.api.SourceSeed;
import com.botmaker.studio.palette.Initializer.BoolLit;
import com.botmaker.studio.palette.Initializer.CharLit;
import com.botmaker.studio.palette.Initializer.DoubleLit;
import com.botmaker.studio.palette.Initializer.IntLit;
import com.botmaker.studio.palette.Initializer.NewInstance;
import com.botmaker.studio.palette.Initializer.Raw;
import com.botmaker.studio.palette.Initializer.StaticCall;
import com.botmaker.studio.palette.Initializer.StrLit;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.PrimitiveKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The types a bot author can <b>write into source</b>: what "Declare Bot Variable" offers, and what the Add
 * Function dialog offers as a return type or a parameter type. One list, in one place, because the two
 * features are the same question asked twice — "which types does this editor let you write down?" — and they
 * answered it differently: the declare menu knew five and Add Function knew one ({@code void}, hard-coded).
 *
 * <h2>It is not an enum any more, and that is the whole of the 2026-09-01 change</h2>
 *
 * <p>Fourteen of these constants were SDK class literals — {@code Point.class}, {@code Precision.class},
 * {@code ImageTemplate.class} and eleven more — which made this file the largest single reason
 * {@code botmaker-studio} compiled against {@code botmaker-sdk} at all. They are gone. What is left as
 * <em>constants</em> is the JDK: four literals, a colour, {@code void}, and the three {@code java.time}
 * values. Everything else is <b>contributed</b>, from the loaded plugins' {@link SourceSeed}s, and joins the
 * list at {@link #values()}.
 *
 * <p>So this became a final class with static instances rather than an enum, for exactly the reason
 * {@code com.botmaker.plugin.api.value.ValueType} did in phase 10a: a closed set is right for one plugin and
 * wrong for two. A plugin wanting its type declarable would otherwise need a constant granted to it here,
 * which is the back door the platform exists to close.
 *
 * <h2>Why {@code SourceSeed} and not a new contribution surface</h2>
 *
 * <p>A seed already carries everything a declarable type needs: the type's name, the Java a fresh value of it
 * is written as, and the imports that expression wants. That is the same triple the deleted constants
 * carried — {@code Precision.class} plus {@code new EnumConst("Precision", "DEFAULT")} is
 * {@code SourceSeed.of("…Precision", "…Precision.DEFAULT")} with the label spelled twice. So nothing was
 * added to the contract: the surface that answers <em>what does a fresh one look like</em> already answers
 * <em>can I declare one</em>, and a plugin that seeds a type gets it offered for free.
 *
 * <p>The cost, stated plainly: a plugin can no longer be <em>curated</em> from here. This file used to be an
 * allow-list, and its javadoc argued for that — deriving the list from the catalog would put every new SDK
 * class in front of the user, and most of what the SDK ships is not something to declare a variable of. That
 * argument still holds and the answer moved rather than went: the seed list <em>is</em> the curation, and it
 * is written by the plugin that knows which of its types are worth holding. The SDK offering three types
 * where it used to offer fourteen is a decision for {@code SdkPlugin.sourceSeeds()}, not for this file.
 *
 * <h2>Every entry compiles on the spot</h2>
 *
 * <p>Each carries the {@link Initializer} a fresh declaration is seeded with, so choosing a type from the menu
 * produces a statement that builds — {@code java.time.LocalDate.now()}, {@code false} — rather than a
 * {@code null} the user has to notice and replace. {@link #NOTHING} is the one entry with none: {@code void}
 * is a return type and cannot be a variable, which is what {@link #declarable()} answers.
 *
 * <p>{@link #COLOR} is the JDK's {@code java.awt.Color} and stays here for that reason alone — it is what the
 * block editor's own colour picker commits, so a variable holding one is the same value a block holds. There
 * is no {@code float}: one decimal type is enough and it is {@code double}.
 */
public final class BotType {

    // --- Basics: the four literals a bot mostly counts, flags and labels with, plus void. -----------------

    public static final BotType TEXT =
            jdk("TEXT", Group.BASICS, "Text", JdkType.STRING, "text", new StrLit(""));
    public static final BotType YES_NO =
            primitive("YES_NO", Group.BASICS, "Yes/No", PrimitiveKind.BOOLEAN, JdkType.BOOLEAN, "flag",
                    new BoolLit(false));
    public static final BotType WHOLE_NUMBER =
            primitive("WHOLE_NUMBER", Group.BASICS, "Whole number", PrimitiveKind.INT, JdkType.INTEGER,
                    "number", new IntLit("0"));
    public static final BotType DECIMAL_NUMBER =
            primitive("DECIMAL_NUMBER", Group.BASICS, "Decimal number", PrimitiveKind.DOUBLE, JdkType.DOUBLE,
                    "decimal", new DoubleLit("0.0"));
    public static final BotType CHARACTER =
            primitive("CHARACTER", Group.BASICS, "Character", PrimitiveKind.CHAR, JdkType.CHARACTER, "letter",
                    new CharLit('a'));

    /**
     * A colour, as {@code java.awt.Color} — the JDK type, written fully qualified so it needs no import, and
     * the very type the block editor's colour picker reads and writes. White by default, matching the seed
     * {@code InitializerFactory} gives a colour-typed argument slot.
     */
    public static final BotType COLOR =
            qualified("COLOR", Group.BASICS, "Color", "java.awt.Color", "color",
                    new NewInstance("java.awt.Color",
                            List.of(new IntLit("255"), new IntLit("255"), new IntLit("255"))));

    /** {@code void} — offered as a return type only. See {@link #declarable()}. */
    public static final BotType NOTHING =
            new BotType("NOTHING", Group.BASICS, "Nothing", PrimitiveKind.VOID.keyword(), null, true, null,
                    null);

    // --- Date & time: the three java.time values a bot schedules itself with. ----------------------------

    /**
     * Written fully qualified for the same reason the others are: a type that needs no import cannot be
     * forgotten from one, and this factory has no rewriter to add one with.
     */
    public static final BotType DATE =
            qualified("DATE", Group.WHEN, "Date", "java.time.LocalDate", "date",
                    new StaticCall("java.time.LocalDate", "now", List.of()));
    public static final BotType TIME_OF_DAY =
            qualified("TIME_OF_DAY", Group.WHEN, "Time of day", "java.time.LocalTime", "time",
                    new StaticCall("java.time.LocalTime", "of", List.of(new IntLit("0"), new IntLit("0"))));
    /** A length of time — {@code 90s}, {@code 5m}, {@code 1h30m} in the editor; a {@code Duration} in the bot. */
    public static final BotType DURATION =
            qualified("DURATION", Group.WHEN, "Duration", "java.time.Duration", "howLong",
                    new StaticCall("java.time.Duration", "ofSeconds", List.of(new IntLit("0"))));

    /**
     * The types this editor declares for itself, in display order. Everything else in {@link #values()} comes
     * from a plugin.
     */
    private static final List<BotType> BUILT_IN = List.of(
            TEXT, YES_NO, WHOLE_NUMBER, DECIMAL_NUMBER, CHARACTER, COLOR, NOTHING,
            DATE, TIME_OF_DAY, DURATION);

    /** How the list is grouped in a menu or a dropdown. Declaration order is display order. */
    public enum Group {
        BASICS("Basics"),
        WHEN("Date & time"),
        /**
         * Everything the loaded plugins contribute, under one heading.
         *
         * <p>There were four more groups here — Vision, Geometry, Input, Capture — and every type in them was
         * the SDK's. They were this editor's own arrangement of one plugin's API, which is a thing it has no
         * standing to arrange: a second plugin's types would have had nowhere to go, and the SDK could not
         * have moved its own type between two of them. A plugin that wants its types grouped is asking for a
         * group on the contract, which is a decision to take when there are two plugins, not one.
         */
        FROM_PLUGINS("From plugins");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String id;
    private final Group group;
    private final String label;
    private final String typeName;
    /** The name this type takes inside {@code List<…>}; null when it can't go in one. */
    private final String boxedName;
    private final boolean primitive;
    private final String varName;
    private final Initializer init;

    private BotType(String id, Group group, String label, String typeName, String boxedName, boolean primitive,
                    String varName, Initializer init) {
        this.id = id;
        this.group = group;
        this.label = label;
        this.typeName = typeName;
        this.boxedName = boxedName;
        this.primitive = primitive;
        this.varName = varName;
        this.init = init;
    }

    /** A {@code java.lang} type written by its simple name — {@code String}. */
    private static BotType jdk(String id, Group group, String label, JdkType type, String varName,
                               Initializer init) {
        return new BotType(id, group, label, type.simpleName(), type.simpleName(), false, varName, init);
    }

    /** A type written fully qualified — {@code java.time.Duration}. Verbose, and needs no import. */
    private static BotType qualified(String id, Group group, String label, String qualifiedName,
                                     String varName, Initializer init) {
        return new BotType(id, group, label, qualifiedName, qualifiedName, false, varName, init);
    }

    /** A primitive, plus the box it takes inside a {@code List<…>}. A null box means "no list form". */
    private static BotType primitive(String id, Group group, String label, PrimitiveKind kind, JdkType box,
                                     String varName, Initializer init) {
        return new BotType(id, group, label, kind.keyword(), box == null ? null : box.simpleName(), true,
                varName, init);
    }

    /**
     * A declarable type read off one plugin's {@link SourceSeed}.
     *
     * <p>Written fully qualified, deliberately: the seed names its type either way and this factory has no
     * rewriter to add an import with, so the qualified form is the only one that always compiles. The label
     * and the suggested variable name are derived from the simple name, which is what the deleted SDK
     * constants did too — {@code Point.class.getSimpleName()} was both.
     */
    private static BotType fromSeed(SourceSeed seed) {
        String qualified = seed.typeName();
        int dot = qualified.lastIndexOf('.');
        String simple = dot >= 0 ? qualified.substring(dot + 1) : qualified;
        String varName = simple.isEmpty()
                ? "value"
                : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        return new BotType("SEED_" + qualified, Group.FROM_PLUGINS, simple, qualified, qualified, false,
                varName, new Raw(seed.expression()));
    }

    /**
     * A stable identifier — the name of the constant, or {@code SEED_} plus the seeded type's qualified name.
     *
     * <p>It was {@code Enum.name()} until 2026-09-01 and the built-in ids are unchanged, because
     * {@code BlockCatalog} builds a drag-and-drop block id out of it ({@code DECLARE_TEXT}) and that string
     * crosses a dragboard.
     */
    public String id() {
        return id;
    }

    public Group group() {
        return group;
    }

    /** What the user is shown — "Whole number" for {@code int}, and the class's own name for a plugin type. */
    public String label() {
        return label;
    }

    /** The name as it is written in source: {@code int}, {@code String}, {@code com.example.Point}. */
    public String typeName() {
        return typeName;
    }

    public boolean isPrimitive() {
        return primitive;
    }

    /** The name this type takes inside {@code List<…>} — {@code Integer} for {@code int}. */
    public String boxedName() {
        return boxedName;
    }

    /** A suggested variable name — the seed the declare-variable blocks have always carried. */
    public String suggestedName() {
        return varName;
    }

    /** The default value a fresh declaration of this type is seeded with; empty for {@link #NOTHING}. */
    public Optional<Initializer> defaultValue() {
        return Optional.ofNullable(init);
    }

    /** Whether a variable of this type can exist at all — false only for {@code void}. */
    public boolean declarable() {
        return init != null;
    }

    /** Whether {@code List<this>} is expressible — false for {@code void}, true for everything else. */
    public boolean listable() {
        return boxedName != null;
    }

    @Override
    public String toString() {
        return id;
    }

    /**
     * Every offered type: this editor's own, then whatever the loaded plugins seed.
     *
     * <p>Asked each time rather than cached, which is {@code SourceSeed}'s own rule — the SDK's capture-source
     * seed reads the project's <em>current</em> default target, and a list built at load would freeze it. A
     * plugin contributing a type this editor already declares is dropped rather than shadowing it: a duplicate
     * in a type menu is a menu the user cannot use, and the built-in is the one whose label is a sentence.
     */
    public static List<BotType> values() {
        List<BotType> all = new ArrayList<>(BUILT_IN);
        for (SourceSeed seed : PluginHost.sourceSeeds()) {
            if (seed.typeName() == null || seed.typeName().isBlank()) continue;
            BotType seeded = fromSeed(seed);
            boolean known = all.stream().anyMatch(t -> simple(t.typeName).equals(simple(seeded.typeName)));
            if (!known) all.add(seeded);
        }
        return List.copyOf(all);
    }

    /** The types offered in {@code group}, in display order. */
    public static List<BotType> in(Group group) {
        return values().stream().filter(t -> t.group == group).toList();
    }

    /** Every type a variable can be declared of, in display order — {@link #NOTHING} excluded. */
    public static List<BotType> declarableTypes() {
        return values().stream().filter(BotType::declarable).toList();
    }

    private static String simple(String typeName) {
        if (typeName == null) return "";
        String trimmed = typeName.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
    }

    /**
     * How many values of a type there are: one, or a list of them. <b>The whole axis a signature has.</b>
     *
     * <p>There were four shapes here until phase 10b, and the other three — {@code ONE_OF} ("one out of a set
     * the author writes down"), {@code ANY_OF} ("several out of that set") and {@code OPEN_LIST} — were
     * <em>project variable</em> ideas and nothing else: fixing the set a value may come from is a question
     * about something somebody configures, and a method parameter has nobody to ask. They live on in
     * {@link com.botmaker.plugin.api.value.ValueShape}, which is the vocabulary a stored value is typed by;
     * what was left here after removing them was {@code ONE} and two spellings of {@code List<T>} that
     * generated identical source, so they are one.
     */
    public enum Shape {
        /** One value, free within its type. */
        ONE("One value", ""),
        /** {@code List<T>} — several values, in a signature or a declaration. */
        LIST("List of…", "List of ");

        private final String label;
        private final String prefix;

        Shape(String label, String prefix) {
            this.label = label;
            this.prefix = prefix;
        }

        /** What the shape control calls this. */
        public String label() {
            return label;
        }

        /** Whether this is written {@code List<T>}. */
        public boolean isList() {
            return this == LIST;
        }
    }

    /**
     * A type as chosen in a dialog: one of the offered types, in one of the two {@link Shape}s.
     *
     * <p>The shape is an axis rather than twice as many constants because it composes with all of them and
     * carries no information of its own — {@code List<Point>} needs nothing from the list that {@code Point}
     * did not already supply, beyond the box a primitive takes inside the angle brackets.
     */
    public record Choice(BotType type, Shape shape) {

        public Choice {
            if (type == null) throw new IllegalArgumentException("a type choice needs a type");
            if (shape == null) shape = Shape.ONE;
            // Throwing, where the contract's ValueChoice corrects: this pair can only come from a dialog or a
            // parsed signature, never from a file, so an impossible one is a bug rather than a project to
            // rescue. `List<void>` is the only impossible one left.
            if (shape.isList() && !type.listable()) {
                throw new IllegalArgumentException("there is no list of " + type.typeName());
            }
        }

        /** One value of {@code type} — {@link Shape#ONE}. */
        public static Choice of(BotType type) {
            return new Choice(type, Shape.ONE);
        }

        /** {@code List<type>} — the form a signature writes. */
        public static Choice listOf(BotType type) {
            return new Choice(type, Shape.LIST);
        }

        /** True when this is written {@code List<…>}: several values, not one. */
        public boolean isList() {
            return shape.isList();
        }

        /** The name as written in source — {@code Point}, or {@code List<Point>}. */
        public String sourceName() {
            return isList() ? "List<" + type.boxedName + ">" : type.typeName;
        }

        /**
         * The default value's source text — {@code ""}, {@code false}, {@code List.of()} — or {@code "null"}
         * for a type nothing seeds.
         *
         * <p>It says what {@code MethodHandler} actually writes, which is the point: a function retyped to
         * give back {@code Text} gets {@code return "";}, and the preview that announced the change has to
         * name that same value rather than a second guess at it.
         */
        public String defaultText() {
            if (isList()) return "List.of()";
            return type.defaultValue().map(Initializer::sourceText).orElse("null");
        }

        /** What the user is shown — "Point", or "List of Point". */
        public String label() {
            return shape.prefix + type.label();
        }

        public String suggestedName() {
            return isList() ? type.suggestedName() + "s" : type.suggestedName();
        }

        /** The element type inside the angle brackets; meaningful only when {@link #isList()}. */
        public String elementName() {
            return type.boxedName();
        }

        public boolean isVoid() {
            return shape == Shape.ONE && type == NOTHING;
        }

        /**
         * The choice a source-level type name denotes — the inverse of {@link #sourceName()}, empty when the
         * name is not one of the offered types.
         *
         * <p>For the one caller that reads a signature back out of a file instead of out of a dialog: the
         * Edit button on a method header, which has to pre-fill the Add Function dialog from what is written.
         * Matching is on the <em>simple</em> name, so {@code Duration} and {@code java.time.Duration} both
         * land on {@link #DURATION} — a method someone typed by hand and one this editor generated name the
         * same type differently, and only one of the two spellings is in the list. That matters more now than
         * it did: a plugin-seeded type is written qualified, and the user's own source almost never is.
         *
         * <p>Empty is a real answer and not a failure: a parameter of a type nothing offers
         * ({@code String[] args}) cannot be rendered in the dialog, and the caller is expected to say so
         * rather than to guess a replacement.
         */
        public static Optional<Choice> fromSourceName(String sourceName) {
            String name = sourceName == null ? "" : sourceName.trim();
            if (name.startsWith("List<") && name.endsWith(">")) {
                String element = simple(name.substring(5, name.length() - 1));
                return values().stream()
                        .filter(t -> element.equals(simple(t.boxedName)))
                        .findFirst().map(Choice::listOf);
            }
            String simple = simple(name);
            return values().stream()
                    .filter(t -> simple.equals(simple(t.typeName)))
                    .findFirst().map(Choice::of);
        }
    }
}
