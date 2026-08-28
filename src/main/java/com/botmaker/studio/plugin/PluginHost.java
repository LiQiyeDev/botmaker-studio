package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.plugin.api.SlotEditor;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.host.PluginLoader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plugins this Studio has loaded, and the one place their contributions are composed.
 *
 * <p><b>Plugins are discovered, never named.</b> Nothing in this package writes down an implementation
 * class: {@link ServiceLoader} reads each jar's own {@code META-INF/services} declaration and hands back
 * instances typed as {@link StudioPlugin}, so every call from here on is javac-checked against the contract.
 * The SDK reaches this list exactly as a third-party plugin would — it is Studio's plugin #1 and gets no
 * back door.
 *
 * <p><b>The set is bound per project, and that is what makes the pin real.</b> {@link #bind} builds a
 * {@link PluginLoader} over the project's <em>resolved artifacts</em>, so the plugin answering for a bot
 * pinned to SDK 1.1.0 is the one inside that jar. That loader lived in this package until 2026-08-28 and is
 * now {@code botmaker-plugin-host}: Studio is no longer the only host, and the delegation split it makes is
 * the last code here that should exist in two copies. What stayed is everything below — the bundled
 * fallback, the swap, and the two catalogs — because all of it is about <em>Studio's</em> open project. With no project open — an import repair, a paste, the
 * project selection screen — the answer comes from {@link #bundled}, the plugins on Studio's own
 * classloader, and that is also the fallback for every way binding can fail.
 *
 * <p><b>Fail-open, in one direction only.</b> An empty classpath, a jar with no services file, a plugin
 * whose constructor throws: each is logged and answered with the bundled set. Menus widen, never empty; a
 * value type nothing registers resolves to {@code ValueType.unknown} and its stored value is preserved
 * read-only. {@code ActivityVariable}'s constructor reads {@link #valueTypes()} and must never see an
 * exception, whatever the state of the loader.
 *
 * <h2>Two catalogs, and the difference matters</h2>
 *
 * <p>{@link #catalogFor(String)} answers for <em>the version a project pins</em>: a bot compiles against the
 * SDK named in its pom, which is routinely older than the one this Studio bundles, and the palette must
 * describe that jar rather than this one. That is the inversion's rule — a bot gets its answers from its own
 * version — and it is why the pinned version is an argument at every level of this stack.
 *
 * <p>{@link #bundled()} answers for the plugin jars Studio itself was compiled against, and has exactly one
 * legitimate use: resolving a bare simple name to a fully-qualified one when there is no project in hand
 * (an import repair, a paste, a rewrite built from a bare {@link java.lang.String}). It replaced
 * {@code palette.SdkType}, a hand-mirrored enum of the SDK's class list which had the same scope and the
 * same job but no author but us — a class renamed in the SDK broke a menu at runtime instead of this build.
 *
 * <p>Both are memoised: building a catalog resolves every entry's method reference through a
 * {@code SerializedLambda}, which is cheap but not free, and the menus ask on every open.
 */
public final class PluginHost {

    private PluginHost() {}

    /**
     * The plugins on Studio's own classloader — the fallback, and the whole of the no-project path.
     *
     * <p>Built once and never rebound: it is a property of this build, not of anything a user does. It is
     * also why this phase needed no separate step for "discovery without a project" — the no-project path
     * <em>is</em> plain {@link ServiceLoader}, over the same declaration a project's jar carries.
     */
    private static final List<StudioPlugin> BUNDLED = discover(PluginHost.class.getClassLoader());

    /**
     * The plugin set answering right now: the bound project's, or {@link #BUNDLED}. Volatile because
     * {@link #bind} runs off the FX thread (the classpath resolve it follows does) while the readers below
     * are called from wherever a variable is constructed.
     */
    private static volatile List<StudioPlugin> plugins = BUNDLED;

    /** The loader behind a bound set, held only so it can be closed. Null whenever {@link #BUNDLED} is live. */
    private static volatile PluginLoader loader;

    /** pinned version → the merged catalog for it. Keyed by the raw pin, since that is what a caller holds. */
    private static final Map<String, PaletteCatalog> CACHE = new ConcurrentHashMap<>();

    /**
     * The blank pin — "whatever this jar is". Every plugin is asked to read it as its own version rather
     * than as an unknown one, which is what makes {@link #bundled()} a question a plugin can answer without
     * Studio having to know how that plugin spells its versions.
     */
    private static final String BUNDLED_PIN = "";

    /**
     * Memoised on the same reasoning as the palette caches — the merge walks every plugin and the variable
     * editors ask on every keystroke — and rebuilt on every bind, which is why it is a field rather than a
     * constant.
     */
    private static volatile ValueCatalog valueTypes = mergeValueTypes();

    /** Memoised beside the value catalog, and rebuilt on the same bind, for the same reason. */
    private static volatile List<SlotEditor> slotEditors = mergeSlotEditors(BUNDLED);

    /** pinned version → the sections of the Parameters window at it. Cleared with {@link #CACHE}. */
    private static final Map<String, List<ParameterGroup>> GROUPS = new ConcurrentHashMap<>();

    public static List<StudioPlugin> plugins() {
        return plugins;
    }

    /**
     * Binds the plugins declared on {@code resolvedClasspath}, replacing whatever was bound before.
     *
     * <p>Called immediately after a project's classpath is resolved — on open, and again whenever the
     * libraries or the SDK pin change. Anything that goes wrong leaves {@link #BUNDLED} bound: see the
     * fail-open note in the class javadoc.
     */
    public static synchronized void bind(List<String> resolvedClasspath) {
        PluginLoader opened = PluginLoader.open(resolvedClasspath);
        if (opened == null) {
            unbind();
            return;
        }
        swap(opened, opened.plugins());
    }

    /**
     * Falls back to the bundled plugins and releases the project's jars.
     *
     * <p>Closing is required rather than tidy: an open {@code URLClassLoader} keeps every jar it read open,
     * and on Windows that makes the file unreplaceable — so a project switch that skipped this would break
     * the <em>next</em> project's dependency resolve rather than this one's.
     */
    public static synchronized void unbind() {
        swap(null, BUNDLED);
    }

    private static void swap(PluginLoader opened, List<StudioPlugin> bound) {
        ValueCatalog merged;
        try {
            merged = mergeValueTypes(bound);
        } catch (RuntimeException | Error e) {
            // A clash between two of a project's own plugins refuses the binding, not the project. The same
            // clash among the bundled ones is a build error and is left to throw at class-init, which is
            // where a developer can act on it.
            System.err.println("Warning: the project's plugins do not compose; using the bundled set: " + e);
            if (opened != null) opened.close();
            if (bound != BUNDLED) swap(null, BUNDLED);
            return;
        }
        PluginLoader previous = loader;
        loader = opened;
        plugins = bound;
        valueTypes = merged;
        slotEditors = mergeSlotEditors(bound);
        CACHE.clear();
        GROUPS.clear();
        if (previous != null) previous.close();
    }

    /**
     * Every {@link StudioPlugin} {@code classLoader} can see, or an empty list.
     *
     * <p>Total by construction: a missing service file and a provider that will not instantiate are both
     * ordinary states here — a bot's classpath legitimately carries no plugin at all — and neither may take
     * Studio down on the way to a project screen.
     */
    private static List<StudioPlugin> discover(ClassLoader classLoader) {
        List<StudioPlugin> found = new ArrayList<>();
        try {
            // An explicit loop, not a stream: providers instantiate lazily and one that throws must not
            // cost the ones already found.
            for (StudioPlugin plugin : ServiceLoader.load(StudioPlugin.class, classLoader)) {
                found.add(plugin);
            }
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: could not discover bundled plugins: " + e);
        }
        return List.copyOf(found);
    }

    /**
     * Every value type every loaded plugin registers, merged by id.
     *
     * <p><b>This does not vary by pin, and that is not an oversight.</b> A palette entry is an <em>offer</em>
     * — a member this build knows about that an older jar may not contain — so it is narrowed against the
     * bot's own bytecode. A value type is a <em>reading</em>: the project file already says {@code DURATION},
     * and what that word means has to be answered whatever the pin, or the value cannot be shown at all. A
     * type an older jar never had simply never appears in an older project's file.
     *
     * <p>Two plugins claiming one id is refused loudly at startup rather than resolved. First-wins would make
     * a project open differently depending on load order, which is the one failure a user could never
     * diagnose; and the ids are what {@code activities.json} holds, so a silent winner silently retypes their
     * variables.
     */
    public static ValueCatalog valueTypes() {
        return valueTypes;
    }

    /**
     * Every loaded plugin's slot editors, in plugin order, for a value the host is about to render.
     *
     * <p>Consulted <b>after</b> the host's own built-in editors, which is the rule
     * {@link StudioPlugin#slotEditors()} states: a slot holding a project variable stays a variable no matter
     * what a plugin claims about its type.
     *
     * <p>Memoised for the same reason the value catalog is — this is asked once per row of the Parameters
     * window and once per slot the code editor draws — and cleared on {@link #bind}, since a plugin that has
     * just been unloaded must stop offering editors that would run on a dead classloader.
     */
    public static List<SlotEditor> slotEditors() {
        return slotEditors;
    }

    private static List<SlotEditor> mergeSlotEditors(List<StudioPlugin> set) {
        List<SlotEditor> merged = new ArrayList<>();
        for (StudioPlugin plugin : set) {
            List<SlotEditor> offered = plugin.slotEditors();
            if (offered != null) merged.addAll(offered);
        }
        return List.copyOf(merged);
    }

    /**
     * The sections of the Parameters window, in plugin order — the default plugin's first.
     *
     * <p>One window, one section per group. Two plugins claiming one {@link ParameterGroup#className()} is a
     * composition error, and it is refused the same way a value-type id clash is: the later claimant is
     * dropped with a warning rather than the project being refused, because a user whose project will not
     * open has no way to act on the problem, while a section that is missing is visible and diagnosable.
     *
     * @param pinnedSdkVersion the version the open project's pom names; passed through, never interpreted
     */
    public static List<ParameterGroup> parameterGroups(String pinnedSdkVersion) {
        String pin = pinnedSdkVersion == null ? BUNDLED_PIN : pinnedSdkVersion;
        return GROUPS.computeIfAbsent(pin, PluginHost::buildGroups);
    }

    /** The group a variable's {@code group} id names, or null when no loaded plugin claims that id. */
    public static ParameterGroup parameterGroup(String pinnedSdkVersion, String groupId) {
        String wanted = groupId == null ? ParameterGroup.DEFAULT_ID : groupId.trim();
        for (ParameterGroup group : parameterGroups(pinnedSdkVersion)) {
            if (group.id().equals(wanted)) return group;
        }
        return null;
    }

    private static List<ParameterGroup> buildGroups(String pin) {
        List<ParameterGroup> merged = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> classNames = new LinkedHashSet<>();
        for (StudioPlugin plugin : plugins) {
            List<ParameterGroup> offered = plugin.parameters(pin);
            if (offered == null) continue;
            for (ParameterGroup group : offered) {
                if (!ids.add(group.id()) || !classNames.add(group.className())) {
                    System.err.println("Warning: plugin " + plugin.id() + " claims a parameter group ("
                            + group.id() + " / " + group.className() + ") another plugin already owns;"
                            + " its section is not shown.");
                    continue;
                }
                merged.add(group);
            }
        }
        return List.copyOf(merged);
    }

    private static ValueCatalog mergeValueTypes() {
        return mergeValueTypes(plugins);
    }

    private static ValueCatalog mergeValueTypes(List<StudioPlugin> set) {
        ValueCatalog merged = ValueCatalog.empty();
        for (StudioPlugin plugin : set) {
            ValueCatalog offered = plugin.valueTypes();
            if (offered == null) continue;
            List<String> clashes = merged.clashesWith(offered);
            if (!clashes.isEmpty()) {
                throw new IllegalStateException("plugin " + plugin.id()
                        + " registers value type ids another plugin already claims: "
                        + String.join(", ", clashes));
            }
            merged = merged.merge(offered);
        }
        return merged;
    }

    /**
     * The palette every loaded plugin offers at the versions this project pins, merged.
     *
     * <p>Today one plugin means one version string. When a second plugin arrives this takes a map — the
     * merge itself already handles several catalogs, and {@link PaletteCatalog#mergedWith} is additive on
     * purpose: a plugin curates its own surface and has no business removing another's.
     *
     * @param pinnedSdkVersion the SDK version the open project's pom names; never interpreted here
     */
    public static PaletteCatalog catalogFor(String pinnedSdkVersion) {
        String pin = pinnedSdkVersion == null ? BUNDLED_PIN : pinnedSdkVersion;
        return CACHE.computeIfAbsent(pin, PluginHost::build);
    }

    /**
     * The palette of the plugin jars Studio itself bundles — the newest surface this build knows about.
     *
     * <p>Read it as "which names does a plugin own?", never as "which members should we offer": the second
     * question belongs to the project's own pin and is {@link #catalogFor}'s. Using this one for curation
     * would offer a bot on an older SDK members its jar has never had, which is the bug the pinned catalog
     * exists to prevent.
     */
    public static PaletteCatalog bundled() {
        return catalogFor(BUNDLED_PIN);
    }

    /**
     * The bundled facade with this simple name, if exactly one plugin offers it.
     *
     * <p>The import path's question, and the reason a catalog holds real {@link Class} objects rather than
     * names: this is what decides that {@code Point} in a bot's source means the SDK's and not
     * {@code java.awt}'s. Empty when no plugin owns the name — the honest answer, and the one that leaves an
     * unrecognised import alone rather than repointing it at a plausible guess.
     */
    public static Optional<FacadeEntry> ownerOf(String simpleName) {
        return simpleName == null || simpleName.isBlank()
                ? Optional.empty()
                : bundled().facadeBySimpleName(simpleName.trim());
    }

    /** The fully-qualified name a plugin owns for {@code simpleName}, or {@code null}. */
    public static String qualifiedName(String simpleName) {
        return ownerOf(simpleName).map(FacadeEntry::qualifiedName).orElse(null);
    }

    /**
     * True when {@code simpleClassName} names a facade — a class a call can be <em>made on</em>, hidden ones
     * included. This is <b>recognition</b>, not curation: it is what tells a placed {@code Mouse.click(…)}
     * apart from a call on a class the user wrote, so it reads the bundled catalog and never a project's pin.
     * A call whose facade this build has never heard of renders as a generic library call, which is right.
     */
    public static boolean isFacadeClass(String simpleClassName) {
        return ownerOf(simpleClassName).isPresent();
    }

    /** The facades the insert menus show, in declaration order — the bundled superset. */
    public static List<FacadeEntry> menuFacades() {
        return bundled().offeredFacades();
    }

    /**
     * Simple names of every facade, hidden ones included, in declaration order — for the class dropdowns,
     * which stay {@code String}-valued on purpose: the scope they display can also be a class the user wrote,
     * which no catalog entry can name.
     */
    public static List<String> facadeNames() {
        return bundled().facades().stream()
                .map(FacadeEntry::simpleName)
                .toList();
    }

    private static PaletteCatalog build(String pin) {
        PaletteCatalog merged = PaletteCatalog.empty();
        for (StudioPlugin plugin : plugins) {
            merged = merged.mergedWith(plugin.catalog(pin));
        }
        return merged;
    }
}
