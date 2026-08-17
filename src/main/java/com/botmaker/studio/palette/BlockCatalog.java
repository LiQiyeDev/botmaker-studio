package com.botmaker.studio.palette;

import com.botmaker.studio.palette.BlockType.ControlFlow;
import com.botmaker.studio.palette.BlockType.ControlFlow.Kind;
import com.botmaker.studio.palette.BlockType.EnumDecl;
import com.botmaker.studio.palette.BlockType.LambdaCall;
import com.botmaker.studio.palette.BlockType.LibraryCall;
import com.botmaker.studio.palette.BlockType.MethodMember;
import com.botmaker.studio.palette.BlockType.ScannerRead;
import com.botmaker.studio.palette.BlockType.VarDecl;
import com.botmaker.studio.palette.Initializer.BoolLit;
import com.botmaker.studio.palette.Initializer.DoubleLit;
import com.botmaker.studio.palette.Initializer.EnumConst;
import com.botmaker.studio.palette.Initializer.IntLit;
import com.botmaker.studio.palette.Initializer.NewInstance;
import com.botmaker.studio.palette.Initializer.StaticCall;
import com.botmaker.studio.palette.Initializer.StrLit;
import com.botmaker.studio.services.ImageTemplateLibrary;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.botmaker.studio.palette.BlockCategory.*;

/**
 * The canonical set of insertable {@link BlockType}s — the single source of truth that replaces
 * {@code AddableBlock.values()}. Each {@code id()} equals the former enum constant name, so the drag-and-drop
 * serialization protocol is unchanged. The declaration order is the palette/menu display order (grouped by category
 * downstream).
 */
public final class BlockCatalog {

    private BlockCatalog() {}

    // --- Output ---
    public static final BlockType PRINT = cf("PRINT", "Print", OUTPUT, Kind.PRINT);

    // --- Flow control ---
    public static final BlockType IF = cf("IF", "If Statement", FLOW, Kind.IF);
    public static final BlockType SWITCH = cf("SWITCH", "Switch", FLOW, Kind.SWITCH);
    // Named for what it does, not for the Java it emits: the user is choosing between combinations of images,
    // and "switch" is the mechanism. It only builds something meaningful inside a group-lambda body (where a
    // Matches variable is in scope), which is exactly where StatementFactory looks for its subject — and that
    // is why, like FIND_IMAGE_ACTIONS below, it is deliberately NOT listed in the statement menu (excluded
    // from ALL): offered there it can be dropped anywhere, and everywhere except a group find is a place it
    // cannot compile. It reaches the canvas the way it should instead — seeded into a group form's body by
    // LambdaCallHandler.seedIfReady — and is still parsed, still round-tripped, and still built by
    // StatementFactory for that seed.
    public static final BlockType MATCHES_SWITCH =
            cf("MATCHES_SWITCH", "Check Image Combinations", FLOW, Kind.MATCHES_SWITCH);

    // --- Loops ---
    public static final BlockType WHILE = cf("WHILE", "While Loop", LOOPS, Kind.WHILE);
    public static final BlockType FOR = cf("FOR", "For Each Loop", LOOPS, Kind.FOR);
    public static final BlockType DO_WHILE = cf("DO_WHILE", "Do While", LOOPS, Kind.DO_WHILE);

    // --- Control commands ---
    public static final BlockType BREAK = cf("BREAK", "Break", CONTROL, Kind.BREAK);
    public static final BlockType CONTINUE = cf("CONTINUE", "Continue", CONTROL, Kind.CONTINUE);
    public static final BlockType RETURN = cf("RETURN", "Return", CONTROL, Kind.RETURN);
    // Activity enable/disable and stop-the-bot are standard SDK facade calls now — Activity.enable/disable("X")
    // and Bot.stop() come from the Activity/Bot facade submenus and render with the normal SDK-block chrome, so
    // there are no bespoke CONTROL blocks for them (they used to be DISABLE_ACTIVITY/ENABLE_ACTIVITY/STOP_BOT).
    // "Wait" is a standard SDK block on the Wait facade, so the user gets the class/method/overload chrome —
    // not a raw Thread.sleep. (Existing Thread.sleep bots still round-trip via WaitBlock; this only changes
    // what the menu inserts.) It inserts the Duration overload rather than milliseconds(int) because that is
    // the one the Studio gives a real editor to — a unit dropdown and the random-range toggle — where a bare
    // int is only ever a text pill whose unit lives in the method name.
    public static final BlockType WAIT = new LibraryCall("WAIT", "Wait", CONTROL, SdkType.WAIT, "time",
            List.of(new StaticCall("Duration", "ofSeconds", List.of(new IntLit("1")))));

    // --- Variables ---
    public static final BlockType DECLARE_INT =
            new VarDecl("DECLARE_INT", "Int Variable", VARIABLES, "int", true, "number", new IntLit("0"));
    public static final BlockType DECLARE_DOUBLE =
            new VarDecl("DECLARE_DOUBLE", "Double Variable", VARIABLES, "double", true, "decimal", new DoubleLit("0.0"));
    public static final BlockType DECLARE_BOOLEAN =
            new VarDecl("DECLARE_BOOLEAN", "Bool Variable", VARIABLES, "boolean", true, "flag", new BoolLit(false));
    public static final BlockType DECLARE_STRING =
            new VarDecl("DECLARE_STRING", "String Variable", VARIABLES, "String", false, "text", new StrLit(""));
    public static final BlockType DECLARE_ARRAY = cf("DECLARE_ARRAY", "Create List", VARIABLES, Kind.ARRAY);
    public static final BlockType ASSIGNMENT = cf("ASSIGNMENT", "Set Variable", VARIABLES, Kind.ASSIGNMENT);

    // --- Input & interaction ---
    public static final BlockType CLICK = new LibraryCall("CLICK", "Mouse Click", INPUT, SdkType.MOUSE, "click",
            List.of(new NewInstance(SdkType.POINT.simpleName(), List.of(new IntLit("0"), new IntLit("0")))));
    public static final BlockType TYPE_TEXT = new LibraryCall("TYPE_TEXT", "Type Text", INPUT, SdkType.KEYBOARD, "type",
            List.of(new StrLit("")));
    public static final BlockType PRESS_KEY = new LibraryCall("PRESS_KEY", "Press Key", INPUT, SdkType.KEYBOARD, "tap",
            List.of(new EnumConst(SdkType.KEY.simpleName(), "ENTER")));
    public static final BlockType READ_LINE =
            new ScannerRead("READ_LINE", "Read Text", INPUT, InputKind.LINE, "input");
    public static final BlockType READ_INT =
            new ScannerRead("READ_INT", "Read Int", INPUT, InputKind.INT, "num");
    public static final BlockType READ_DOUBLE =
            new ScannerRead("READ_DOUBLE", "Read Double", INPUT, InputKind.DOUBLE, "num");

    // --- Functions ---
    public static final BlockType FUNCTION_CALL = cf("FUNCTION_CALL", "Call Function", FUNCTIONS, Kind.FUNCTION_CALL);
    public static final BlockType METHOD_DECLARATION =
            new MethodMember("METHOD_DECLARATION", "Declare Function", FUNCTIONS);
    public static final BlockType DECLARE_ENUM = new EnumDecl("DECLARE_ENUM", "Define Enum", VARIABLES);

    // --- Vision (find/click/wait promoted as bot actions; no dedicated "Vision" submenu) ---
    public static final BlockType FIND_IMAGE =
            new LibraryCall("FIND_IMAGE", "Find Image", INPUT, SdkType.IMAGE_FINDER, "find", List.of());
    public static final BlockType CLICK_IMAGE =
            new LibraryCall("CLICK_IMAGE", "Click Image", INPUT, SdkType.IMAGE_CLICKER, "click", List.of());
    public static final BlockType WAIT_FOR_IMAGE =
            new LibraryCall("WAIT_FOR_IMAGE", "Wait For Image", INPUT, SdkType.IMAGE_WAITER, "waitFor", List.of());
    // A single body-carrying find block: renders like an SDK ImageFinder call with a method dropdown
    // (ifFind/whileFind/untilFind × single/any/all) plus a droppable action body — see LambdaCallBlock. The
    // block implementation is retained (round-trips existing ImageFinder.ifFind lambdas, and is reused by the
    // Phase 2 overlay method palette), but it is intentionally NOT listed in the statement menu — hence it is
    // excluded from ALL below.
    public static final BlockType FIND_IMAGE_ACTIONS = new LambdaCall("FIND_IMAGE_ACTIONS", "Find Image → Do Actions",
            INPUT, SdkType.IMAGE_FINDER, VisionLoop.IF_FIND.methodName(), List.of(), VisionLoop.IF_FIND.defaultParamName());
    // The "Declare Bot Variable" submenu is generated from BotType — the same curated list the Add Function
    // dialog picks a return type and parameter types from. It was five hand-written entries (Point, Rect,
    // Size, MatchResult, ImageTemplate) while the dialog knew a different set again, so a type you could take
    // as a parameter was not necessarily one you could declare. Each entry's seed value is the type's own
    // default, which is why every one of them compiles the moment it is dropped: VisionContext.getLastMatch()
    // rather than null, ImageTemplateGroup.of() rather than an empty group that throws, and the built-in
    // template (shipped by ProjectCreator) rather than a missing "image.png".
    private static final List<BlockType> BOT_VARIABLES = BotType.declarableTypes().stream()
            .filter(t -> t.group() != BotType.Group.BASICS)
            .map(BlockCatalog::declareBlock)
            .toList();

    public static final BlockType DECLARE_POINT = declared(BotType.POINT);
    public static final BlockType DECLARE_RECT = declared(BotType.RECT);
    public static final BlockType DECLARE_SIZE = declared(BotType.SIZE);
    public static final BlockType DECLARE_MATCH = declared(BotType.MATCH_RESULT);
    public static final BlockType DECLARE_TEMPLATE = declared(BotType.IMAGE_TEMPLATE);
    // (No hardcoded DECLARE_DIRECTION block: a Direction variable is declared through the generic
    // "declare variable → pick type Direction" flow, whose initializer is seeded dynamically from the
    // index-resolved first enum constant (InitializerFactory) and edited via the EnumPicker — so there's a
    // single, index-driven source of truth instead of a stale hardcoded `Direction.NORTH` duplicate.)

    // --- Game ---
    public static final BlockType LAUNCH_GAME = new LibraryCall("LAUNCH_GAME", "Launch Program", GAME,
            SdkType.GAME, "launch", List.of(new StrLit("")));
    public static final BlockType LAUNCH_STEAM_GAME = new LibraryCall("LAUNCH_STEAM_GAME", "Launch Steam Game", GAME,
            SdkType.GAME, "launchSteam", List.of(new StrLit("")));
    public static final BlockType LAUNCH_EPIC_GAME = new LibraryCall("LAUNCH_EPIC_GAME", "Launch Epic Game", GAME,
            SdkType.GAME, "launchEpic", List.of(new StrLit("")));

    // --- Emulator (Android) ---
    // "Use Emulator As Source" is the common one-block flow: Emulators.use("<instance>") connects to the
    // running emulator and points the whole bot at it (Source.set), so every no-source vision/click/OCR call
    // then targets the emulator. The instance-name arg gets the EmulatorArgPicker (discovered-instance dropdown).
    public static final BlockType USE_EMULATOR = new LibraryCall("USE_EMULATOR", "Use Emulator As Source", GAME,
            SdkType.EMULATORS, "use", List.of(new StrLit("")));
    // "Connect Emulator" keeps a handle: Emulator emulator = Emulators.named("<instance>"); — for bots that
    // want to call emulator-native verbs (tap/swipe/startApp) or pass it as an explicit CaptureSource.
    public static final BlockType CONNECT_EMULATOR = new VarDecl("CONNECT_EMULATOR", "Connect Emulator", GAME,
            SdkType.EMULATOR.simpleName(), false, "emulator", new StaticCall(SdkType.EMULATORS.simpleName(), "named", List.of(new StrLit(""))));

    // --- Utility ---
    public static final BlockType COMMENT = cf("COMMENT", "Comment", UTILITY, Kind.COMMENT);

    private static final List<BlockType> ALL = Stream.of(
                    List.of(PRINT,
                            IF, SWITCH,
                            WHILE, FOR, DO_WHILE,
                            BREAK, CONTINUE, RETURN, WAIT,
                            DECLARE_INT, DECLARE_DOUBLE, DECLARE_BOOLEAN, DECLARE_STRING, DECLARE_ARRAY,
                            ASSIGNMENT,
                            CLICK, TYPE_TEXT, PRESS_KEY, READ_LINE, READ_INT, READ_DOUBLE,
                            FUNCTION_CALL, METHOD_DECLARATION, DECLARE_ENUM,
                            FIND_IMAGE, CLICK_IMAGE, WAIT_FOR_IMAGE),
                    BOT_VARIABLES,
                    List.of(LAUNCH_GAME, LAUNCH_STEAM_GAME, LAUNCH_EPIC_GAME,
                            USE_EMULATOR, CONNECT_EMULATOR,
                            COMMENT))
            .flatMap(List::stream)
            .toList();

    /** All insertable blocks in palette/menu display order. */
    public static List<BlockType> all() {
        return ALL;
    }

    /**
     * The bot-first actions promoted to the very top of the insert menu (rendered flat, no submenu) so the most
     * common automation building blocks are the first thing reached for. Game launch is promoted here (rather than
     * a "Game" submenu) per its prominence; promoting a category's blocks empties its submenu, so no "Game" group
     * is shown (see {@code ExpressionMenu.addCategoryMenu}, which skips empty categories).
     */
    private static final List<BlockType> BOT_ACTIONS = List.of(
            FIND_IMAGE, CLICK_IMAGE, WAIT_FOR_IMAGE, CLICK, WAIT, LAUNCH_GAME, LAUNCH_STEAM_GAME, LAUNCH_EPIC_GAME,
            USE_EMULATOR);

    public static List<BlockType> botActions() {
        return BOT_ACTIONS;
    }

    /** Resolves a block from its {@link BlockType#id()} (used to deserialize a dragboard payload). */
    public static Optional<BlockType> byId(String id) {
        if (id == null) return Optional.empty();
        return ALL.stream().filter(b -> b.id().equals(id)).findFirst();
    }

    private static ControlFlow cf(String id, String displayName, BlockCategory category, Kind kind) {
        return new ControlFlow(id, displayName, category, kind);
    }

    /** The declare-a-variable block for one curated type. */
    private static BlockType declareBlock(BotType type) {
        return new VarDecl("DECLARE_" + type.name(), type.label(), BOT_VARIABLE,
                type.typeName(), type.isPrimitive(), type.suggestedName(),
                type.defaultValue().orElseThrow(
                        () -> new IllegalStateException(type + " is declarable but has no default value")));
    }

    /** The generated declare-block for {@code type} — the named handles the rest of the app refers to. */
    private static BlockType declared(BotType type) {
        return BOT_VARIABLES.stream()
                .filter(b -> b.id().equals("DECLARE_" + type.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no declare block for " + type));
    }
}
