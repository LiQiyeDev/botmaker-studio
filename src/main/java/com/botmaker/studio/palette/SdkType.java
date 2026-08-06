package com.botmaker.studio.palette;

import com.botmaker.sdk.api.BotMaker;
import com.botmaker.sdk.api.BotSettings;
import com.botmaker.sdk.api.Debug;
import com.botmaker.sdk.api.Point;
import com.botmaker.sdk.api.Rect;
import com.botmaker.sdk.api.Session;
import com.botmaker.sdk.api.Size;
import com.botmaker.sdk.api.Time;
import com.botmaker.sdk.api.bot.Activity;
import com.botmaker.sdk.api.bot.Bot;
import com.botmaker.sdk.api.bot.BotStuckException;
import com.botmaker.sdk.api.bot.PopupGuard;
import com.botmaker.sdk.api.bot.StartMode;
import com.botmaker.sdk.api.bot.Watchdog;
import com.botmaker.sdk.api.capture.CaptureSource;
import com.botmaker.sdk.api.capture.Desktop;
import com.botmaker.sdk.api.capture.Monitor;
import com.botmaker.sdk.api.capture.NamedWindow;
import com.botmaker.sdk.api.capture.Screen;
import com.botmaker.sdk.api.capture.SessionSource;
import com.botmaker.sdk.api.capture.Source;
import com.botmaker.sdk.api.capture.Window;
import com.botmaker.sdk.api.core.Direction;
import com.botmaker.sdk.api.emulator.Emulator;
import com.botmaker.sdk.api.emulator.EmulatorRef;
import com.botmaker.sdk.api.emulator.Emulators;
import com.botmaker.sdk.api.emulator.EmulatorSource;
import com.botmaker.sdk.api.interaction.Key;
import com.botmaker.sdk.api.interaction.Keyboard;
import com.botmaker.sdk.api.interaction.Mouse;
import com.botmaker.sdk.api.interaction.MouseButton;
import com.botmaker.sdk.api.interaction.Wait;
import com.botmaker.sdk.api.launch.Game;
import com.botmaker.sdk.api.launch.LaunchTarget;
import com.botmaker.sdk.api.launch.Target;
import com.botmaker.sdk.api.observe.BotObserver;
import com.botmaker.sdk.api.observe.Bots;
import com.botmaker.sdk.api.observe.ClickEvent;
import com.botmaker.sdk.api.observe.MatchEvent;
import com.botmaker.sdk.api.observe.Surface;
import com.botmaker.sdk.api.vision.ColorMatch;
import com.botmaker.sdk.api.vision.ImageClicker;
import com.botmaker.sdk.api.vision.ImageFinder;
import com.botmaker.sdk.api.vision.ImageTemplate;
import com.botmaker.sdk.api.vision.ImageTemplateGroup;
import com.botmaker.sdk.api.vision.ImageWaiter;
import com.botmaker.sdk.api.vision.MatchResult;
import com.botmaker.sdk.api.vision.Matches;
import com.botmaker.sdk.api.vision.Pixel;
import com.botmaker.sdk.api.vision.Precision;
import com.botmaker.sdk.api.vision.Text;
import com.botmaker.sdk.api.vision.TextMatch;
import com.botmaker.sdk.api.vision.VisionContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The BotMaker-SDK public API surface as a closed, compiler-checked set — every class under
 * {@code com.botmaker.sdk.api}, named by a real class literal rather than a string.
 *
 * <p>This replaces the old {@code SdkApi}, which mirrored the facade names as a hand-maintained
 * {@code List<String>} that nothing verified. Three things follow from holding the {@link Class} instead:
 *
 * <ul>
 *   <li><b>Drift is a compile error.</b> An SDK class renamed, moved or deleted breaks this file, rather
 *       than silently breaking a menu and an import at runtime.</li>
 *   <li><b>{@link #qualifiedName()} is free and correct.</b> The facades live in <em>sub-packages</em>
 *       ({@code api.vision.ImageFinder}, {@code api.interaction.Mouse}, {@code api.capture.Window}), so
 *       the FQN can never be derived from the simple name. Before this, every SDK import went through
 *       {@code ImportManager.addImportForSimpleName}, which resolves by <em>searching</em> the analyzer
 *       index and silently no-ops on a miss.</li>
 *   <li><b>{@code ImportManager} can say "the SDK owns this name".</b> That is what lets the JDK fallback
 *       be deduced rather than hard-coded: {@code Point}, {@code Window}, {@code Desktop} and {@code Text}
 *       all collide with {@code java.awt}, and this enum is consulted first.</li>
 * </ul>
 *
 * <p><b>Type identity only.</b> Studio compiles against the SDK to know <em>which classes exist</em>. It
 * deliberately does not read <em>methods</em> or Javadoc from that jar: a generated bot compiles against the
 * SDK version <em>it</em> pins, which may be older than Studio's. Method-level knowledge stays with
 * {@code ProjectAnalyzer} (ClassGraph over the bot's resolved classpath) and {@code SdkDocsService} (the
 * bot's {@code botmaker-sdk:<version>:sources} jar), so adding a method to an existing facade still needs
 * no Studio change — only a new <em>class</em> does, and then this file won't compile until it is added.
 *
 * <p><b>Not a parse boundary.</b> Facade names cross into generated bot source as string literals
 * ({@code Mouse.click(…)}) and come back as plain identifiers, so anything reading user source calls
 * {@link #byName(String)} and handles the empty case — never {@code valueOf}.
 */
public enum SdkType {

    // ---------------------------------------------------------------------------------------------------
    // Facades — the static utility classes the specialized "SDK call" block switches between and that
    // BlockConverter recognizes as library calls.
    //
    // Declaration order IS the display order of the per-class submenus in the statement insert menu
    // (StatementMenu.rebuildItems). The intent of the order: interaction (Mouse/Keyboard/Wait), then vision
    // (find/click/wait/pixel/text + the last-match contexts and click config + the global Debug switch), then
    // launch/emulator (Game/Target/Emulators), then bot lifecycle (Bot/Watchdog/Activity), then capture
    // wiring (Source/Window) and observation (Bots).
    // ---------------------------------------------------------------------------------------------------

    MOUSE(Mouse.class, Role.FACADE, "🖱"),
    KEYBOARD(Keyboard.class, Role.FACADE, "⌨"),
    WAIT(Wait.class, Role.FACADE, "⏱"),
    IMAGE_FINDER(ImageFinder.class, Role.FACADE, "🔍"),
    IMAGE_CLICKER(ImageClicker.class, Role.FACADE, "👆"),
    IMAGE_WAITER(ImageWaiter.class, Role.FACADE, "⏳"),
    PIXEL(Pixel.class, Role.FACADE, "🎨"),
    TEXT(Text.class, Role.FACADE, "🔤"),
    /**
     * Exposes the {@code MatchResult} stored by the last find/click/wait call (the vision API returns
     * {@code boolean}/{@code int} now, not {@code MatchResult}), the {@code Matches} stored by the last group
     * check ({@code getLastMatches()}) and, likewise, the {@code ColorMatch} stored by the last {@code Pixel}
     * call. It is the out-of-band escape hatch: a group lambda hands its body the {@code Matches} directly,
     * as the named parameter {@code LambdaCallBlock} renders — prefer that.
     */
    VISION_CONTEXT(VisionContext.class, Role.FACADE, "👁"),
    BOT_SETTINGS(BotSettings.class, Role.FACADE, "⚙"),
    DEBUG(Debug.class, Role.FACADE_HIDDEN, "🐞"),
    SESSION(Session.class, Role.FACADE_HIDDEN),
    GAME(Game.class, Role.FACADE, "🎮"),
    /** The current launch target holder ({@code start()}/{@code restart()}). */
    TARGET(Target.class, Role.FACADE, "🚀"),
    EMULATORS(Emulators.class, Role.FACADE, "📱"),
    BOT(Bot.class, Role.FACADE, "🤖"),
    WATCHDOG(Watchdog.class, Role.FACADE_HIDDEN, "🐕"),
    POPUP_GUARD(PopupGuard.class, Role.FACADE_HIDDEN),
    ACTIVITY(Activity.class, Role.FACADE, "◎"),
    SOURCE(Source.class, Role.FACADE, "🎯"),
    WINDOW(Window.class, Role.FACADE_HIDDEN, "🪟"),
    BOTS(Bots.class, Role.FACADE_HIDDEN, "🤖"),
    TIME(Time.class, Role.FACADE),

    // ---------------------------------------------------------------------------------------------------
    // Values — everything else the api package ships. Never a menu entry, but an import target: these are
    // the names the block factories and handlers write into generated source (ImageTemplate, Matches, Point,
    // Rect, the enums a picker offers), and the names ImportManager must resolve to the SDK, not to java.awt.
    // ---------------------------------------------------------------------------------------------------

    BOT_MAKER(BotMaker.class, Role.VALUE),
    POINT(Point.class, Role.VALUE),
    RECT(Rect.class, Role.VALUE),
    SIZE(Size.class, Role.VALUE),
    BOT_STUCK_EXCEPTION(BotStuckException.class, Role.VALUE),
    START_MODE(StartMode.class, Role.VALUE),
    CAPTURE_SOURCE(CaptureSource.class, Role.VALUE),
    DESKTOP(Desktop.class, Role.VALUE),
    MONITOR(Monitor.class, Role.VALUE),
    NAMED_WINDOW(NamedWindow.class, Role.VALUE),
    /** Intentionally not a facade — it is no longer a user-facing {@code CaptureSource}. */
    SCREEN(Screen.class, Role.VALUE),
    SESSION_SOURCE(SessionSource.class, Role.VALUE),
    DIRECTION(Direction.class, Role.VALUE),
    EMULATOR(Emulator.class, Role.VALUE),
    EMULATOR_REF(EmulatorRef.class, Role.VALUE),
    EMULATOR_SOURCE(EmulatorSource.class, Role.VALUE),
    KEY(Key.class, Role.VALUE),
    MOUSE_BUTTON(MouseButton.class, Role.VALUE),
    LAUNCH_TARGET(LaunchTarget.class, Role.VALUE),
    BOT_OBSERVER(BotObserver.class, Role.VALUE),
    CLICK_EVENT(ClickEvent.class, Role.VALUE),
    MATCH_EVENT(MatchEvent.class, Role.VALUE),
    SURFACE(Surface.class, Role.VALUE),
    COLOR_MATCH(ColorMatch.class, Role.VALUE),
    IMAGE_TEMPLATE(ImageTemplate.class, Role.VALUE),
    IMAGE_TEMPLATE_GROUP(ImageTemplateGroup.class, Role.VALUE),
    MATCHES(Matches.class, Role.VALUE),
    MATCH_RESULT(MatchResult.class, Role.VALUE),
    PRECISION(Precision.class, Role.VALUE),
    TEXT_MATCH(TextMatch.class, Role.VALUE);

    /** What a type is to the editor. */
    public enum Role {
        /** A facade shown as a submenu in the statement/expression insert menus. */
        FACADE,
        /**
         * A facade <em>recognized</em> as an SDK call — so existing calls render with the standard SDK-block
         * chrome and are excluded from the generic "Library (static)" listings — but hidden from the insert
         * menus. {@code Bots}/{@code Window}/{@code Watchdog} are internal wiring the user shouldn't reach
         * for directly: bot supervision is driven by {@code Bot.start}, capture by the capture-source picker,
         * and the watchdog by the generated loop. {@code PopupGuard} is the same kind of wiring — the entry
         * point installs it and the flow driver toggles it from each activity's "check for popups" tick; what
         * the user edits is {@code Popups.run()}, not the guard.
         */
        FACADE_HIDDEN,
        /** Not a facade: a value type, enum or interface. An import target only. */
        VALUE
    }

    private final Class<?> type;
    private final Role role;
    private final String icon;

    SdkType(Class<?> type, Role role) {
        this(type, role, null);
    }

    SdkType(Class<?> type, Role role, String icon) {
        this.type = type;
        this.role = role;
        this.icon = icon;
    }

    /** Simple name as it appears in generated bot source — {@code "ImageFinder"}. */
    public String simpleName() {
        return type.getSimpleName();
    }

    /** Fully-qualified name, for imports — {@code "com.botmaker.sdk.api.vision.ImageFinder"}. */
    public String qualifiedName() {
        return type.getName();
    }

    public Role role() {
        return role;
    }

    /** True for {@link Role#FACADE} and {@link Role#FACADE_HIDDEN} — the recognition set. */
    public boolean isFacade() {
        return role != Role.VALUE;
    }

    /** Menu glyph, or {@code null} when this type has none (menus substitute their own fallback). */
    public String icon() {
        return icon;
    }

    /** Every facade, hidden ones included — what "is this an SDK call?" means. */
    public static final List<SdkType> FACADES =
            Arrays.stream(values()).filter(SdkType::isFacade).toList();

    /** The facades shown as submenus, in declaration order. {@link #FACADES} minus the hidden ones. */
    public static final List<SdkType> MENU_FACADES =
            FACADES.stream().filter(t -> t.role == Role.FACADE).toList();

    /**
     * Simple names of {@link #FACADES}, in declaration order — for the class dropdowns, which stay
     * {@code String}-valued on purpose: the scope they display can also be a class the user wrote, which no
     * enum constant can name.
     */
    public static final List<String> FACADE_NAMES = FACADES.stream().map(SdkType::simpleName).toList();

    /**
     * Simple name → type. Built eagerly so a future SDK class whose simple name collides with an existing one
     * fails at class-init rather than resolving to whichever constant happened to be declared last — a
     * collision would otherwise show up as a wrong import, which is exactly the bug this enum exists to stop.
     */
    private static final Map<String, SdkType> BY_NAME = indexBySimpleName();

    private static Map<String, SdkType> indexBySimpleName() {
        Map<String, SdkType> index = new HashMap<>();
        for (SdkType t : values()) {
            SdkType clash = index.put(t.simpleName(), t);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two SDK types share the simple name '" + t.simpleName() + "': "
                                + clash.qualifiedName() + " and " + t.qualifiedName()
                                + ". ImportManager and the menus key on the simple name; disambiguate before"
                                + " adding it here.");
            }
        }
        return Map.copyOf(index);
    }

    /** The SDK type with this simple name, if any. Total — this is the boundary with user source. */
    public static Optional<SdkType> byName(String simpleName) {
        return simpleName == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(simpleName.trim()));
    }

    /** True when {@code simpleClassName} names an SDK facade (recognition — hidden ones included). */
    public static boolean isFacadeClass(String simpleClassName) {
        return byName(simpleClassName).filter(SdkType::isFacade).isPresent();
    }
}
