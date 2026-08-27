package com.botmaker.studio.palette;

import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueShape;
import com.botmaker.sdk.authoring.TemplateNames;
import com.botmaker.studio.project.activity.ValueWire;
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
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.PrimitiveKind;

import java.util.List;
import java.util.Optional;

/**
 * The types a bot author can name: what "Declare Bot Variable" offers, and what the Add Function dialog offers
 * as a return type or a parameter type. One curated list, in one place, because the two features are the same
 * question asked twice — "which types does this editor let you write down?" — and they answered it
 * differently: the declare menu knew five ({@code Point}, {@code Rect}, {@code Size}, {@code MatchResult},
 * {@code ImageTemplate}) and Add Function knew one ({@code void}, hard-coded, with no dialog to change it).
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
     * Seeded with the template every project ships, so a fresh declaration points at a file that exists — and
     * named by its {@code Templates} constant rather than by a raw path, so renaming that template rewrites
     * the reference instead of leaving a string literal pointing at a file that has moved.
     */
    IMAGE_TEMPLATE(Group.VISION, ImageTemplate.class, "template",
            new NewInstance(ImageTemplate.class.getSimpleName(),
                    List.of(new EnumConst(TemplateNames.CLASS_NAME,
                            TemplateNames.constantForPath(ImageTemplateLibrary.DEFAULT_TEMPLATE_PATH))))),
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

    /**
     * Whether this type's values <em>are</em> a set, one the editor already shows in full.
     *
     * <p>Yes/No is two states of one tick box; a direction is eight arrows on a pad; a mouse button is a
     * labelled diagram; a key is the SDK's own list. In every one of them the control the user meets already
     * offers every value the type has, which is what makes {@link Shape#ONE_OF} over them nonsense — "one of
     * yes and no" is a boolean, said twice and worse. It is exactly the set
     * {@link com.botmaker.studio.project.activity.VariableWire#fixedOptions} answers, plus {@link #YES_NO},
     * whose two values are the two states of one box rather than a list.
     *
     * <p>{@link Shape#ANY_OF} stays available: "any of UP, DOWN" is a genuine list of a closed-set type, and
     * its tick boxes come from the type's own constants rather than from anything the author writes down.
     */
    public boolean isClosedSet() {
        return switch (this) {
            case YES_NO, DIRECTION, KEY, MOUSE_BUTTON -> true;
            case TEXT, WHOLE_NUMBER, DECIMAL_NUMBER, CHARACTER, COLOR, DATE, TIME_OF_DAY, DURATION,
                 IMAGE_TEMPLATE, PRECISION, POINT, RECT, SIZE,
                 NOTHING, IMAGE_TEMPLATE_GROUP, MATCH_RESULT, MATCHES, COLOR_MATCH, TEXT_MATCH,
                 CAPTURE_SOURCE -> false;
        };
    }

    /**
     * Whether the author can write down a <em>set</em> of values of this type — whether {@link Shape#ONE_OF}
     * means anything for it.
     *
     * <p>Mostly derived rather than switched: an option is a wire string
     * ({@link com.botmaker.studio.project.activity.VariableWire}), so a type that can be stored can be listed
     * as a choice, and a type that can go inside {@code List<…>} can be chosen several times. The one thing
     * that has to be said out loud is {@link #isClosedSet()} — a type whose values the editor already shows in
     * full has nothing left for an author-written subset to add.
     */
    public boolean shapeable() {
        return storable() && listable() && !isClosedSet();
    }

    /**
     * Whether a <em>project variable</em> can hold this type — whether it has a value somebody can write down
     * in the Parameters dialog and store in {@code activities.json}.
     *
     * <p>A switch with no {@code default} on purpose: a type added to this enum must be classified here or
     * the build stops, which is the whole reason the two lists are one enum. What is excluded is what has no
     * value to write: {@code void}, and the vision types that are <em>results</em> — a {@code MatchResult}
     * is something the bot found a moment ago, not something anyone configures. A group of templates is
     * excluded too, because {@code List of Image template} already says it and says it better.
     */
    public boolean storable() {
        return switch (this) {
            case TEXT, YES_NO, WHOLE_NUMBER, DECIMAL_NUMBER, CHARACTER, COLOR,
                 DATE, TIME_OF_DAY, DURATION,
                 IMAGE_TEMPLATE, PRECISION,
                 POINT, RECT, SIZE, DIRECTION,
                 KEY, MOUSE_BUTTON -> true;
            case NOTHING, IMAGE_TEMPLATE_GROUP, MATCH_RESULT, MATCHES, COLOR_MATCH, TEXT_MATCH,
                 CAPTURE_SOURCE -> false;
        };
    }

    /** Every type a project variable can hold, in declaration order. */
    public static List<BotType> storableTypes() {
        return java.util.Arrays.stream(values()).filter(BotType::storable).toList();
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
     * How many values of a type there are, and whether the author fixes the set they come from.
     *
     * <p>This is the axis that used to be a {@code boolean list} beside a {@code CHOICE} pseudo-type — a
     * modelling that could not say "one of these three whole numbers" at all, and whose {@code List of …}
     * ignored its own element type in every editor. Three shapes crossed with the type catalogue say
     * everything the two of them said and the cases they could not reach, and a choice of choices is
     * unrepresentable rather than merely discouraged.
     *
     * <p>{@link #ONE_OF} and {@link #ANY_OF} are <em>project variable</em> ideas and nothing else: fixing the
     * set a value may come from is a question about something somebody configures, and a method parameter has
     * nobody to ask. In a signature the axis has only two positions, {@code T} and {@code List<T>} — which is
     * why {@link Choice#sourceName()} treats {@code ONE} and {@code ONE_OF} identically, and {@link #ANY_OF}
     * and {@link #OPEN_LIST} identically.
     *
     * <p><b>Why there are two list shapes.</b> {@code ANY_OF} used to be both of them, and which one it meant
     * was decided by data the user could not see: the Parameters dialog drew tick boxes when the author had
     * written choices down and a free-text box when they had not, under one label reading "List of…". So the
     * same shape changed behaviour the moment a choice was added, and there was no way at all to say "a list
     * the user fills in themselves" about a variable that happened to have choices. Splitting them makes the
     * question the shape asks the same question the widget answers.
     */
    public enum Shape {
        /** One value, free within its type. */
        ONE("One value", ""),
        /** One value, out of a set the author writes down. Radio buttons in the Parameters dialog. */
        ONE_OF("One of…", "One of "),
        /** Several values out of that set — {@code List<T>} in source. Tick boxes. */
        ANY_OF("Many of…", "Many of "),
        /** A list the user writes themselves, out of no set at all — {@code List<T>} too. Growable rows. */
        OPEN_LIST("List of…", "List of ");

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

        /**
         * Whether the author writes the set of values down — the two set-shaped ones.
         *
         * <p>It used to read {@code this != ONE}, which was true of every shape that was not one free value
         * and is the reading {@link #OPEN_LIST} breaks: an open list has as many values as the user likes and
         * no set behind them.
         */
        public boolean hasOptions() {
            return this == ONE_OF || this == ANY_OF;
        }

        /** Whether this is written {@code List<T>} — the two many-valued ones, which spell the same. */
        public boolean isList() {
            return this == ANY_OF || this == OPEN_LIST;
        }
    }

    /**
     * A type as chosen in a dialog: one of the curated types, in one of the four {@link Shape}s.
     *
     * <p>The shape is an axis rather than four times as many constants because it composes with all of them
     * and carries no information of its own — {@code List<Point>} needs nothing from the catalogue that
     * {@code Point} did not already supply, beyond the box a primitive takes inside the angle brackets.
     */
    public record Choice(BotType type, Shape shape) {

        public Choice {
            if (type == null) throw new IllegalArgumentException("a type choice needs a type");
            if (shape == null) shape = Shape.ONE;
            // A list shape is `List<T>` in source and only needs a box. ONE_OF is not a type at all — it is a
            // restriction on a stored value — so it needs a type somebody can store a set of. That asymmetry
            // is why `List<MatchResult>` is a fine return type while "one of a set of match results" is not a
            // sentence.
            if (shape.isList() && !type.listable()) {
                throw new IllegalArgumentException("there is no list of " + type.typeName());
            }
            if (shape == Shape.ONE_OF && !type.shapeable()) {
                throw new IllegalArgumentException(type.typeName() + " cannot carry a set of choices");
            }
        }

        /** One value of {@code type} — {@link Shape#ONE}. */
        public static Choice of(BotType type) {
            return new Choice(type, Shape.ONE);
        }

        /**
         * {@code List<type>} — {@link Shape#OPEN_LIST}, the form a signature writes.
         *
         * <p>The open one and not {@link Shape#ANY_OF}: a {@code List<Point>} read back out of a method
         * declaration has no declared set behind it and nobody to ask for one, so calling it "Many of Point"
         * would name a set that does not exist. The two spell the same in source, so which one a signature
         * carries is invisible to the generated bot and visible only in the label.
         */
        public static Choice listOf(BotType type) {
            return new Choice(type, Shape.OPEN_LIST);
        }

        /** True when this is written {@code List<…>}: several values, not one. */
        public boolean isList() {
            return shape.isList();
        }

        /** True when the author writes down the set of values this may take. */
        public boolean hasOptions() {
            return shape.hasOptions();
        }

        /**
         * The name as written in source — {@code Point}, or {@code List<Point>}.
         *
         * <p>{@link Shape#ONE_OF} spells the same as {@link Shape#ONE} on purpose: restricting which values
         * are offered is the editor's business, and nothing about the option set reaches the generated code.
         */
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

        /**
         * The same pair in the plugin contract's vocabulary — and the whole of the bridge between the two,
         * deliberately parked on the class that is going away rather than on the one that is staying.
         *
         * <p>A stored variable is typed by {@link ValueChoice} since phase 10b began; the pickers still
         * enumerate this enum. The two line up by <b>name</b> — phase 10a registered the SDK's seventeen
         * types under the old constant names, which is what keeps every project ever written readable — so
         * the crossing is a lookup by id and never a mapping table. Phase 10b narrows this enum to the
         * declarable types and deletes both methods with the last picker that needs them.
         */
        public ValueChoice toValue() {
            return new ValueChoice(ValueWire.type(type.name()), ValueShape.valueOf(shape.name()));
        }

        /** The contract's pair read back as this enum's; an id this enum has no constant for is {@link #TEXT}. */
        public static Choice fromValue(ValueChoice choice) {
            if (choice == null) return new Choice(TEXT, Shape.ONE);
            BotType type;
            try {
                type = BotType.valueOf(choice.type().id());
            } catch (IllegalArgumentException e) {
                type = TEXT;
            }
            Shape shape = type.shapeable() ? Shape.valueOf(choice.shape().name())
                    : choice.shape().isList() ? Shape.OPEN_LIST : Shape.ONE;
            return new Choice(type, shape);
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
         * Reads the persisted form, including the one this replaced.
         *
         * <p>A variable's type is the one part of {@code activities.json} whose <em>vocabulary</em> changed:
         * files written before the shape axis say {@code {"type":"CHOICE","list":false}}, and {@code CHOICE}
         * is no longer a constant this enum has. Migrating here rather than in an open-time pass means every
         * reader gets it — the project loader, a hand-copied file, a test fixture — and that a project written
         * by the previous Studio opens without a step anyone can forget to run.
         *
         * <p>The shape arrives as a {@code String} and not as the enum so that the parse is <b>total</b>: a
         * file written by a newer Studio, naming a shape this one has never heard of, loads as one free value
         * rather than failing the whole project open. That is the repo's rule for a persisted closed set —
         * keep the wire name stable, and never throw on an unrecognised one.
         *
         * <p>What this method cannot decide is {@link Shape#ANY_OF} versus {@link Shape#OPEN_LIST} for a file
         * written before they were split: the answer is whether the variable declares choices, and the choices
         * are a sibling field this creator never sees. {@link
         * com.botmaker.studio.project.activity.ActivityVariable} settles it, where both are in hand.
         */
        @com.fasterxml.jackson.annotation.JsonCreator
        static Choice fromJson(@com.fasterxml.jackson.annotation.JsonProperty("type") String type,
                               @com.fasterxml.jackson.annotation.JsonProperty("shape") String shape,
                               @com.fasterxml.jackson.annotation.JsonProperty("list") Boolean list) {
            boolean wasChoice = "CHOICE".equals(type);
            BotType base = wasChoice ? TEXT : parse(type);
            Shape resolved = shape != null ? parseShape(shape)
                    : Boolean.TRUE.equals(list) ? Shape.ANY_OF
                    : wasChoice ? Shape.ONE_OF
                    : Shape.ONE;
            // Per shape, not "anything but ONE": the two have different conditions, and conflating them turned
            // `List of Direction` — perfectly expressible — into a single direction the day ONE_OF stopped
            // being offered for a closed set. A stored `One of Yes/No` becomes `Yes/No`, keeping its value.
            if (resolved == Shape.ONE_OF && !base.shapeable()) resolved = Shape.ONE;
            if (resolved.isList() && !base.listable()) resolved = Shape.ONE;
            return new Choice(base, resolved);
        }

        private static Shape parseShape(String name) {
            for (Shape candidate : Shape.values()) {
                if (candidate.name().equals(name)) return candidate;
            }
            return Shape.ONE;   // a shape a newer Studio invented: one free value holds the stored text
        }

        private static BotType parse(String name) {
            for (BotType candidate : values()) {
                if (candidate.name().equals(name)) return candidate;
            }
            return TEXT;   // a type a newer Studio invented: text holds anything, and nothing is lost
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
