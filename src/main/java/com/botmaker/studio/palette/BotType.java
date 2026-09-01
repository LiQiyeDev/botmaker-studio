package com.botmaker.studio.palette;

import com.botmaker.sdk.api.capture.CaptureSource;
import com.botmaker.sdk.api.capture.Source;
import com.botmaker.sdk.api.geometry.Direction;
import com.botmaker.sdk.api.geometry.Point;
import com.botmaker.sdk.api.geometry.Rect;
import com.botmaker.sdk.api.geometry.Size;
import com.botmaker.sdk.api.interaction.Key;
import com.botmaker.sdk.api.interaction.MouseButton;
import com.botmaker.sdk.api.vision.ColorMatch;
import com.botmaker.sdk.api.vision.ImageTemplate;
import com.botmaker.sdk.api.vision.ImageTemplateGroup;
import com.botmaker.sdk.api.vision.MatchResult;
import com.botmaker.sdk.api.vision.Matches;
import com.botmaker.sdk.api.vision.Precision;
import com.botmaker.sdk.api.vision.TextMatch;
import com.botmaker.sdk.api.vision.Vision;
import com.botmaker.studio.palette.Initializer.BoolLit;
import com.botmaker.studio.palette.Initializer.CharLit;
import com.botmaker.studio.palette.Initializer.DoubleLit;
import com.botmaker.studio.palette.Initializer.EnumConst;
import com.botmaker.studio.palette.Initializer.IntLit;
import com.botmaker.studio.palette.Initializer.NewInstance;
import com.botmaker.studio.palette.Initializer.StaticCall;
import com.botmaker.studio.palette.Initializer.StrLit;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.PrimitiveKind;

import java.util.List;
import java.util.Optional;

/**
 * The types a bot author can <b>write into source</b>: what "Declare Bot Variable" offers, and what the Add
 * Function dialog offers as a return type or a parameter type. One curated list, in one place, because the two
 * features are the same question asked twice — "which types does this editor let you write down?" — and they
 * answered it differently: the declare menu knew five ({@code Point}, {@code Rect}, {@code Size},
 * {@code MatchResult}, {@code ImageTemplate}) and Add Function knew one ({@code void}, hard-coded, with no
 * dialog to change it).
 *
 * <h2>What this is <em>not</em>, since phase 10b</h2>
 *
 * <p>It is no longer the vocabulary a <em>project variable</em> is typed by. That is
 * {@link com.botmaker.plugin.api.value.ValueType} — an open registry the loaded plugins fill — and this enum
 * was two things wearing one name until they were separated: the persisted vocabulary, and the editor's list
 * of types a signature may name. The second is what is left, and it stays Studio's, because it is a list
 * <em>Studio</em> curates about the source <em>Studio</em> writes; nothing here is stored in a project file,
 * so nothing here has to be extensible by a plugin. The members that answered the first question —
 * {@code storable()}, {@code storableTypes()}, {@code isClosedSet()}, {@code shapeable()} — are gone, and with
 * them the two set-shapes: fixing the set a value may come from is a project-variable idea, and a signature has
 * nobody to ask.
 *
 * <h2>An allow-list, not a filter</h2>
 *
 * <p>Every SDK entry names a real class literal, so the set is checked by the compiler: a type that leaves
 * the SDK breaks this file, and a type that <em>joins</em> the SDK appears here only when someone adds it.
 * That direction matters more than it sounds — deriving the list from the plugin catalog would have put
 * every new class in front of the user automatically, and most of what the SDK ships is not something a bot
 * author should be declaring a variable of.
 *
 * <p><b>What is deliberately absent.</b> {@code BotMaker}, {@code BotStuckException} and {@code StartMode} are
 * plumbing; {@code EmulatorSource} is chosen through the capture-target dialog and never named by type —
 * {@link #CAPTURE_SOURCE}, the interface, is the one a variable holds; {@code Emulator}/{@code EmulatorRef}
 * come from {@code Emulators.named(…)} and {@code LaunchTarget} from the launch dialog; and {@code Session}
 * and {@code Time} are facades, not values to hold.
 *
 * <p>That list used to be longer. SDK 1.1.0 moved the {@code CaptureSource} implementations
 * ({@code Desktop}, {@code Monitor}, {@code NamedWindow}, {@code SessionSource}) and the observation stack
 * ({@code Bots}, {@code BotObserver}, {@code Surface} and the event records) into
 * {@code com.botmaker.sdk.internal}, on the rule that a type a bot can only ever <em>receive</em> is not
 * public API. They stopped being nameable here with that move, so this file no longer has to exclude by hand
 * what the SDK's own package boundary now excludes.
 *
 * <p>{@link #COLOR} is the JDK's {@code java.awt.Color}, not an SDK type: it is what the block editor's colour
 * picker already commits ({@code ColorArgPicker}), so a variable holding one is the same value a block holds.
 * The SDK's colour-bearing types — {@link #COLOR_MATCH} and {@link #TEXT_MATCH} — are vision <em>results</em>
 * and remain unstorable. And there is no {@code float} — one decimal type is
 * enough, and it is {@code double}, which is what every SDK method that takes one uses.
 *
 * <h2>Every entry compiles on the spot</h2>
 *
 * <p>Each carries the {@link Initializer} a fresh declaration is seeded with, so choosing a type from the menu
 * produces a statement that builds — {@code Matches.none()}, {@code Precision.DEFAULT},
 * {@code Source.current()} — rather than a {@code null} the user has to notice and replace. {@link #NOTHING}
 * is the one entry with none: {@code void} is a return type and cannot be a variable, which is what
 * {@link #declarable()} answers.
 */
public enum BotType {

    // --- Basics: the four literals a bot mostly counts, flags and labels with, plus void. -----------------

    TEXT(Group.BASICS, "Text", JdkType.STRING, "text", new StrLit("")),
    YES_NO(Group.BASICS, "Yes/No", PrimitiveKind.BOOLEAN, JdkType.BOOLEAN, "flag", new BoolLit(false)),
    WHOLE_NUMBER(Group.BASICS, "Whole number", PrimitiveKind.INT, JdkType.INTEGER, "number", new IntLit("0")),
    DECIMAL_NUMBER(Group.BASICS, "Decimal number", PrimitiveKind.DOUBLE, JdkType.DOUBLE, "decimal",
            new DoubleLit("0.0")),
    CHARACTER(Group.BASICS, "Character", PrimitiveKind.CHAR, JdkType.CHARACTER, "letter", new CharLit('a')),
    /**
     * A colour, as {@code java.awt.Color} — the JDK type, written fully qualified so it needs no import, and
     * the very type the block editor's colour picker reads and writes. White by default, matching the seed
     * {@code InitializerFactory} gives a colour-typed argument slot.
     */
    COLOR(Group.BASICS, "Color", "java.awt.Color", "color",
            new NewInstance("java.awt.Color",
                    List.of(new IntLit("255"), new IntLit("255"), new IntLit("255")))),
    /** {@code void} — offered as a return type only. See {@link #declarable()}. */
    NOTHING(Group.BASICS, "Nothing", PrimitiveKind.VOID, null, null, null),

    // --- Date & time: the three java.time values a bot schedules itself with. ----------------------------

    /**
     * Written fully qualified, here and in the generated {@code Activities} class, for the same reason the
     * generated class has a fixed import block: a type that needs no import cannot be forgotten from one.
     */
    DATE(Group.WHEN, "Date", "java.time.LocalDate", "date",
            new StaticCall("java.time.LocalDate", "now", List.of())),
    TIME_OF_DAY(Group.WHEN, "Time of day", "java.time.LocalTime", "time",
            new StaticCall("java.time.LocalTime", "of", List.of(new IntLit("0"), new IntLit("0")))),
    /** A length of time — {@code 90s}, {@code 5m}, {@code 1h30m} in the editor; a {@code Duration} in the bot. */
    DURATION(Group.WHEN, "Duration", "java.time.Duration", "howLong",
            new StaticCall("java.time.Duration", "ofSeconds", List.of(new IntLit("0")))),

    // --- Vision ------------------------------------------------------------------------------------------

    /**
     * Seeded with an empty path, which is what opens the picture picker on the freshly declared variable.
     *
     * <p>It used to be seeded with the shipped placeholder, named by its generated {@code Templates} constant
     * — which meant this enum reading {@code TemplateNames} and {@code ImageTemplateLibrary} to say what a
     * fresh picture is. That is the SDK plugin's sentence, and since 2026-09-01 the plugin writes it, as the
     * {@code SourceSeed} for {@code ImageTemplate}. This entry says the same thing the seed does.
     */
    IMAGE_TEMPLATE(Group.VISION, ImageTemplate.class, "template",
            new NewInstance(ImageTemplate.class.getSimpleName(), List.of(new StrLit("")))),
    /** An empty group is legal and means "nothing to look for" — the SDK is explicit about it. */
    IMAGE_TEMPLATE_GROUP(Group.VISION, ImageTemplateGroup.class, "group",
            new StaticCall(ImageTemplateGroup.class.getSimpleName(), "of", List.of())),
    MATCH_RESULT(Group.VISION, MatchResult.class, "match",
            new StaticCall(Vision.class.getSimpleName(), "lastMatch", List.of())),
    MATCHES(Group.VISION, Matches.class, "matches",
            new StaticCall(Matches.class.getSimpleName(), "none", List.of())),
    COLOR_MATCH(Group.VISION, ColorMatch.class, "color",
            new StaticCall(Vision.class.getSimpleName(), "lastColorMatch", List.of())),
    TEXT_MATCH(Group.VISION, TextMatch.class, "textMatch",
            new StaticCall(Vision.class.getSimpleName(), "lastTextMatch", List.of())),
    PRECISION(Group.VISION, Precision.class, "precision",
            new EnumConst(Precision.class.getSimpleName(), "DEFAULT")),

    // --- Geometry ----------------------------------------------------------------------------------------

    POINT(Group.GEOMETRY, Point.class, "point",
            new NewInstance(Point.class.getSimpleName(), List.of(new IntLit("0"), new IntLit("0")))),
    RECT(Group.GEOMETRY, Rect.class, "area",
            new NewInstance(Rect.class.getSimpleName(),
                    List.of(new IntLit("0"), new IntLit("0"), new IntLit("0"), new IntLit("0")))),
    SIZE(Group.GEOMETRY, Size.class, "size",
            new NewInstance(Size.class.getSimpleName(), List.of(new IntLit("0"), new IntLit("0")))),
    DIRECTION(Group.GEOMETRY, Direction.class, "direction",
            new EnumConst(Direction.class.getSimpleName(), "NORTH")),

    // --- Input -------------------------------------------------------------------------------------------

    KEY(Group.INPUT, Key.class, "key", new EnumConst(Key.class.getSimpleName(), "A")),
    MOUSE_BUTTON(Group.INPUT, MouseButton.class, "button",
            new EnumConst(MouseButton.class.getSimpleName(), "LEFT")),

    // --- Capture -----------------------------------------------------------------------------------------

    /** Seeded from whatever the bot is currently pointed at, which is the only source known to exist. */
    CAPTURE_SOURCE(Group.CAPTURE, CaptureSource.class, "source",
            new StaticCall(Source.class.getSimpleName(), "current", List.of()));

    /** How the list is grouped in a menu or a dropdown. Declaration order is display order. */
    public enum Group {
        BASICS("Basics"),
        WHEN("Date & time"),
        VISION("Vision"),
        GEOMETRY("Geometry"),
        INPUT("Input"),
        CAPTURE("Capture");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Group group;
    private final String label;
    private final String typeName;
    /** The name this type takes inside {@code List<…>}; null when it can't go in one. */
    private final String boxedName;
    private final boolean primitive;
    private final Class<?> sdk;
    private final String varName;
    private final Initializer init;

    /** An SDK type: its label, and the name it is written under, are its own simple name. */
    BotType(Group group, Class<?> sdk, String varName, Initializer init) {
        this(group, sdk.getSimpleName(), sdk.getSimpleName(), sdk.getSimpleName(), false, sdk, varName, init);
    }

    /** A {@code java.lang} type written by its simple name — {@code String}. */
    BotType(Group group, String label, JdkType type, String varName, Initializer init) {
        this(group, label, type.simpleName(), type.simpleName(), false, null, varName, init);
    }

    /**
     * A JDK type written fully qualified — {@code java.time.Duration}. Verbose at the use site and worth it:
     * every file that can hold one of these is generated with a fixed import block, and the qualified form is
     * the only one that cannot be left out of it.
     */
    BotType(Group group, String label, String qualifiedName, String varName, Initializer init) {
        this(group, label, qualifiedName, qualifiedName, false, null, varName, init);
    }

    /** A primitive, plus the box it takes inside a {@code List<…>}. A null box means "no list form". */
    BotType(Group group, String label, PrimitiveKind kind, JdkType box, String varName, Initializer init) {
        this(group, label, kind.keyword(), box == null ? null : box.simpleName(), true, null, varName, init);
    }

    BotType(Group group, String label, String typeName, String boxedName, boolean primitive, Class<?> sdk,
            String varName, Initializer init) {
        this.group = group;
        this.label = label;
        this.typeName = typeName;
        this.boxedName = boxedName;
        this.primitive = primitive;
        this.sdk = sdk;
        this.varName = varName;
        this.init = init;
    }

    public Group group() {
        return group;
    }

    /** What the user is shown — "Whole number" for {@code int}, and the class's own name for an SDK type. */
    public String label() {
        return label;
    }

    /** The name as it is written in source: {@code int}, {@code String}, {@code MatchResult}. */
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

    /** The SDK type this names, or empty for a primitive or a {@code java.lang} type. */
    public Optional<Class<?>> sdkType() {
        return Optional.ofNullable(sdk);
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

    /** The types offered in {@code group}, in declaration order. */
    public static List<BotType> in(Group group) {
        return java.util.Arrays.stream(values()).filter(t -> t.group == group).toList();
    }

    /** Every type a variable can be declared of, in declaration order — {@link #NOTHING} excluded. */
    public static List<BotType> declarableTypes() {
        return java.util.Arrays.stream(values()).filter(BotType::declarable).toList();
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
     * A type as chosen in a dialog: one of the curated types, in one of the two {@link Shape}s.
     *
     * <p>The shape is an axis rather than twice as many constants because it composes with all of them and
     * carries no information of its own — {@code List<Point>} needs nothing from the catalogue that
     * {@code Point} did not already supply, beyond the box a primitive takes inside the angle brackets.
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
         * for a type the catalogue seeds nothing for.
         *
         * <p>It says what {@code MethodHandler} actually writes, which is the point: a function retyped to
         * give back {@code Text} gets {@code return "";}, and the preview that announced the change has to
         * name that same value rather than a second guess at it.
         */
        public String defaultText() {
            if (isList()) return "List.of()";
            return type.defaultValue().map(Initializer::sourceText).orElse("null");
        }

        /** What the user is shown — "Point", "One of Point", "Many of Point", or "List of Point". */
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
         * name is not one of the curated types.
         *
         * <p>For the one caller that reads a signature back out of a file instead of out of a dialog: the
         * Edit button on a method header, which has to pre-fill the Add Function dialog from what is written.
         * Matching is on the <em>simple</em> name, so {@code Duration} and {@code java.time.Duration} both
         * land on {@link BotType#DURATION} — a method someone typed by hand and one this editor generated
         * name the same type differently, and only one of the two spellings is in the catalogue.
         *
         * <p>Empty is a real answer and not a failure: a parameter of a type outside the catalogue
         * ({@code String[] args}) cannot be rendered in the dialog, and the caller is expected to say so
         * rather than to guess a replacement.
         */
        public static Optional<Choice> fromSourceName(String sourceName) {
            String name = sourceName == null ? "" : sourceName.trim();
            if (name.startsWith("List<") && name.endsWith(">")) {
                String element = simple(name.substring(5, name.length() - 1));
                return java.util.Arrays.stream(values())
                        .filter(t -> element.equals(t.boxedName))
                        .findFirst().map(Choice::listOf);
            }
            String simple = simple(name);
            return java.util.Arrays.stream(values())
                    .filter(t -> simple.equals(simple(t.typeName)))
                    .findFirst().map(Choice::of);
        }

        private static String simple(String typeName) {
            String trimmed = typeName.trim();
            int dot = trimmed.lastIndexOf('.');
            return dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
        }
    }
}
