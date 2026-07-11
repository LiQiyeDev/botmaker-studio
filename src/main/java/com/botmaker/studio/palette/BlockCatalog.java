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

    // --- Loops ---
    public static final BlockType WHILE = cf("WHILE", "While Loop", LOOPS, Kind.WHILE);
    public static final BlockType FOR = cf("FOR", "For Each Loop", LOOPS, Kind.FOR);
    public static final BlockType DO_WHILE = cf("DO_WHILE", "Do While", LOOPS, Kind.DO_WHILE);

    // --- Control commands ---
    public static final BlockType BREAK = cf("BREAK", "Break", CONTROL, Kind.BREAK);
    public static final BlockType CONTINUE = cf("CONTINUE", "Continue", CONTROL, Kind.CONTINUE);
    public static final BlockType RETURN = cf("RETURN", "Return", CONTROL, Kind.RETURN);
    // "Wait" is a standard SDK block on the Wait facade (Wait.milliseconds/seconds), so the user gets the
    // class/method/overload chrome — not a raw Thread.sleep. (Existing Thread.sleep bots still round-trip via
    // WaitBlock; this only changes what the menu inserts.)
    public static final BlockType WAIT = new LibraryCall("WAIT", "Wait", CONTROL, "Wait", "milliseconds",
            List.of(new IntLit("1000")));

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
    public static final BlockType CLICK = new LibraryCall("CLICK", "Mouse Click", INPUT, "Mouse", "click",
            List.of(new NewInstance("Point", List.of(new IntLit("0"), new IntLit("0")))));
    public static final BlockType TYPE_TEXT = new LibraryCall("TYPE_TEXT", "Type Text", INPUT, "Keyboard", "type",
            List.of(new StrLit("")));
    public static final BlockType PRESS_KEY = new LibraryCall("PRESS_KEY", "Press Key", INPUT, "Keyboard", "tap",
            List.of(new EnumConst("Key", "ENTER")));
    public static final BlockType READ_LINE =
            new ScannerRead("READ_LINE", "Read Text", INPUT, "readLine", "String", false, "input");
    public static final BlockType READ_INT =
            new ScannerRead("READ_INT", "Read Int", INPUT, "readInt", "int", true, "num");
    public static final BlockType READ_DOUBLE =
            new ScannerRead("READ_DOUBLE", "Read Double", INPUT, "readDouble", "double", true, "num");

    // --- Functions ---
    public static final BlockType FUNCTION_CALL = cf("FUNCTION_CALL", "Call Function", FUNCTIONS, Kind.FUNCTION_CALL);
    public static final BlockType METHOD_DECLARATION =
            new MethodMember("METHOD_DECLARATION", "Declare Function", FUNCTIONS);
    public static final BlockType DECLARE_ENUM = new EnumDecl("DECLARE_ENUM", "Define Enum", VARIABLES);

    // --- Vision ---
    public static final BlockType FIND_IMAGE =
            new LibraryCall("FIND_IMAGE", "Find Image", VISION, "ImageFinder", "find", List.of());
    public static final BlockType CLICK_IMAGE =
            new LibraryCall("CLICK_IMAGE", "Click Image", VISION, "ImageClicker", "click", List.of());
    public static final BlockType WAIT_FOR_IMAGE =
            new LibraryCall("WAIT_FOR_IMAGE", "Wait For Image", VISION, "ImageWaiter", "waitFor", List.of());
    // A single body-carrying find block: renders like an SDK ImageFinder call with a method dropdown
    // (ifFind/whileFind/untilFind × single/any/all) plus a droppable action body — see LambdaCallBlock. It
    // replaces the former per-variant "If/While Image Exists" / "Repeat Until…" menu entries (the method
    // dropdown now selects among those forms). Defaults to ifFind.
    public static final BlockType FIND_IMAGE_ACTIONS = new LambdaCall("FIND_IMAGE_ACTIONS", "Find Image → Do Actions",
            VISION, "ImageFinder", "ifFind", List.of(), "match");
    public static final BlockType DECLARE_POINT = new VarDecl("DECLARE_POINT", "Point", BOT_VARIABLE, "Point", false, "p",
            new NewInstance("Point", List.of(new IntLit("0"), new IntLit("0"))));
    public static final BlockType DECLARE_RECT = new VarDecl("DECLARE_RECT", "Rect", BOT_VARIABLE, "Rect", false, "r",
            new NewInstance("Rect", List.of(new IntLit("0"), new IntLit("0"), new IntLit("0"), new IntLit("0"))));
    public static final BlockType DECLARE_SIZE = new VarDecl("DECLARE_SIZE", "Size", BOT_VARIABLE, "Size", false, "s",
            new NewInstance("Size", List.of(new IntLit("0"), new IntLit("0"))));
    // Vision calls now return boolean/int; the MatchResult lives in VisionContext, so seed the
    // declaration with VisionContext.getLastMatch() (always non-null) rather than a bare null.
    public static final BlockType DECLARE_MATCH =
            new VarDecl("DECLARE_MATCH", "MatchResult", BOT_VARIABLE, "MatchResult", false, "match",
                    new StaticCall("VisionContext", "getLastMatch", List.of()));
    // Seed with the built-in default template (shipped by ProjectCreator) so a freshly-declared ImageTemplate
    // points at a real file and compiles immediately, rather than a missing "image.png".
    public static final BlockType DECLARE_TEMPLATE = new VarDecl("DECLARE_TEMPLATE", "ImageTemplate", BOT_VARIABLE,
            "ImageTemplate", false, "template",
            new NewInstance("ImageTemplate", List.of(new StrLit(ImageTemplateLibrary.DEFAULT_TEMPLATE_PATH))));
    // (No hardcoded DECLARE_DIRECTION block: a Direction variable is declared through the generic
    // "declare variable → pick type Direction" flow, whose initializer is seeded dynamically from the
    // index-resolved first enum constant (InitializerFactory) and edited via the EnumPicker — so there's a
    // single, index-driven source of truth instead of a stale hardcoded `Direction.NORTH` duplicate.)

    // --- Game ---
    public static final BlockType LAUNCH_GAME = new LibraryCall("LAUNCH_GAME", "Launch Program", GAME,
            "Game", "launch", List.of(new StrLit("")));
    public static final BlockType LAUNCH_STEAM_GAME = new LibraryCall("LAUNCH_STEAM_GAME", "Launch Steam Game", GAME,
            "Game", "launchSteam", List.of(new StrLit("")));

    // --- Utility ---
    public static final BlockType COMMENT = cf("COMMENT", "Comment", UTILITY, Kind.COMMENT);

    private static final List<BlockType> ALL = List.of(
            PRINT,
            IF, SWITCH,
            WHILE, FOR, DO_WHILE,
            BREAK, CONTINUE, RETURN, WAIT,
            DECLARE_INT, DECLARE_DOUBLE, DECLARE_BOOLEAN, DECLARE_STRING, DECLARE_ARRAY, ASSIGNMENT,
            CLICK, TYPE_TEXT, PRESS_KEY, READ_LINE, READ_INT, READ_DOUBLE,
            FUNCTION_CALL, METHOD_DECLARATION, DECLARE_ENUM,
            FIND_IMAGE, CLICK_IMAGE, WAIT_FOR_IMAGE, FIND_IMAGE_ACTIONS,
            DECLARE_POINT, DECLARE_RECT, DECLARE_SIZE, DECLARE_MATCH, DECLARE_TEMPLATE,
            LAUNCH_GAME, LAUNCH_STEAM_GAME,
            COMMENT);

    /** All insertable blocks in palette/menu display order. */
    public static List<BlockType> all() {
        return ALL;
    }

    /**
     * The bot-first actions promoted to the very top of the insert menu (rendered flat, no submenu) so the most
     * common automation building blocks are the first thing reached for. Game launch is promoted here (rather than
     * a "Game" submenu) per its prominence; promoting a category's blocks empties its submenu, so no "Game" group
     * is shown (see {@code ExpressionMenuFactory.addCategoryMenu}, which skips empty categories).
     */
    private static final List<BlockType> BOT_ACTIONS = List.of(
            FIND_IMAGE, CLICK_IMAGE, WAIT_FOR_IMAGE, CLICK, WAIT, LAUNCH_GAME, LAUNCH_STEAM_GAME);

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
}
