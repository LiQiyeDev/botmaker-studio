package com.botmaker.palette;

import com.botmaker.palette.BlockType.ControlFlow;
import com.botmaker.palette.BlockType.ControlFlow.Kind;
import com.botmaker.palette.BlockType.EnumDecl;
import com.botmaker.palette.BlockType.LambdaCall;
import com.botmaker.palette.BlockType.LibraryCall;
import com.botmaker.palette.BlockType.MethodMember;
import com.botmaker.palette.BlockType.ScannerRead;
import com.botmaker.palette.BlockType.VarDecl;
import com.botmaker.palette.Initializer.BoolLit;
import com.botmaker.palette.Initializer.DoubleLit;
import com.botmaker.palette.Initializer.EnumConst;
import com.botmaker.palette.Initializer.IntLit;
import com.botmaker.palette.Initializer.NewInstance;
import com.botmaker.palette.Initializer.NullLit;
import com.botmaker.palette.Initializer.StrLit;

import java.util.List;
import java.util.Optional;

import static com.botmaker.palette.BlockCategory.*;

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
    public static final BlockType WAIT = cf("WAIT", "Wait (ms)", CONTROL, Kind.WAIT);

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
    public static final BlockType CLICK_ANY =
            new LibraryCall("CLICK_ANY", "Click Any Image", VISION, "ImageClicker", "clickAny", List.of());
    public static final BlockType WHILE_IMAGE_EXISTS = new LambdaCall("WHILE_IMAGE_EXISTS", "While Image Exists",
            VISION, "ImageFinder", "whileExists", List.of(), "match");
    public static final BlockType IF_IMAGE_EXISTS = new LambdaCall("IF_IMAGE_EXISTS", "If Image Exists",
            VISION, "ImageFinder", "ifExists", List.of(), "match");
    public static final BlockType UNTIL_IMAGE_EXISTS = new LambdaCall("UNTIL_IMAGE_EXISTS", "Repeat Until Image Appears",
            VISION, "ImageFinder", "untilExists", List.of(), null);
    public static final BlockType DECLARE_POINT = new VarDecl("DECLARE_POINT", "Point", VISION, "Point", false, "p",
            new NewInstance("Point", List.of(new IntLit("0"), new IntLit("0"))));
    public static final BlockType DECLARE_RECT = new VarDecl("DECLARE_RECT", "Rect", VISION, "Rect", false, "r",
            new NewInstance("Rect", List.of(new IntLit("0"), new IntLit("0"), new IntLit("0"), new IntLit("0"))));
    public static final BlockType DECLARE_SIZE = new VarDecl("DECLARE_SIZE", "Size", VISION, "Size", false, "s",
            new NewInstance("Size", List.of(new IntLit("0"), new IntLit("0"))));
    public static final BlockType DECLARE_MATCH =
            new VarDecl("DECLARE_MATCH", "MatchResult", VISION, "MatchResult", false, "match", new NullLit());
    public static final BlockType DECLARE_TEMPLATE = new VarDecl("DECLARE_TEMPLATE", "ImageTemplate", VISION,
            "ImageTemplate", false, "template", new NewInstance("ImageTemplate", List.of(new StrLit("image.png"))));
    public static final BlockType DECLARE_DIRECTION = new VarDecl("DECLARE_DIRECTION", "Direction", VISION,
            "Direction", false, "dir", new EnumConst("Direction", "NORTH"));

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
            FIND_IMAGE, CLICK_IMAGE, WAIT_FOR_IMAGE, CLICK_ANY,
            WHILE_IMAGE_EXISTS, IF_IMAGE_EXISTS, UNTIL_IMAGE_EXISTS,
            DECLARE_POINT, DECLARE_RECT, DECLARE_SIZE, DECLARE_MATCH, DECLARE_TEMPLATE, DECLARE_DIRECTION,
            LAUNCH_GAME, LAUNCH_STEAM_GAME,
            COMMENT);

    /** All insertable blocks in palette/menu display order. */
    public static List<BlockType> all() {
        return ALL;
    }

    /**
     * The bot-first actions promoted to the very top of the insert menu (rendered flat, no submenu) so the most
     * common automation building blocks — find / click / wait — are the first thing reached for.
     */
    private static final List<BlockType> BOT_ACTIONS = List.of(
            FIND_IMAGE, CLICK_IMAGE, CLICK_ANY, WAIT_FOR_IMAGE, CLICK, WAIT);

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
