package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.FacadeRole;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.sdk.plugin.SdkPlugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plugins this Studio has loaded, and the one place their contributions are composed.
 *
 * <p><b>The binding is static, and deliberately so for now.</b> There is exactly one plugin —
 * {@link SdkPlugin} — and Studio's own pom declares the dependency, so it is bound with {@code new} rather
 * than discovered. A {@code ServiceLoader} over a {@code URLClassLoader} built from the project's resolved
 * artifacts is the shape this grows into, and it is a loader rather than a redesign precisely because
 * everything downstream of here already speaks {@link StudioPlugin} and nothing speaks {@code SdkPlugin}.
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
     * Every loaded plugin, in merge order — the host's own contributions win over a plugin's, and an earlier
     * plugin's over a later one's.
     */
    private static final List<StudioPlugin> PLUGINS = List.of(new SdkPlugin());

    /** pinned version → the merged catalog for it. Keyed by the raw pin, since that is what a caller holds. */
    private static final Map<String, PaletteCatalog> CACHE = new ConcurrentHashMap<>();

    /**
     * The blank pin — "whatever this jar is". Every plugin is asked to read it as its own version rather
     * than as an unknown one, which is what makes {@link #bundled()} a question a plugin can answer without
     * Studio having to know how that plugin spells its versions.
     */
    private static final String BUNDLED_PIN = "";

    /**
     * Built once, on the same reasoning as the palette caches: the merge walks every plugin and the variable
     * editors ask on every keystroke.
     */
    private static final ValueCatalog VALUE_TYPES = mergeValueTypes();

    public static List<StudioPlugin> plugins() {
        return PLUGINS;
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
        return VALUE_TYPES;
    }

    private static ValueCatalog mergeValueTypes() {
        ValueCatalog merged = ValueCatalog.empty();
        for (StudioPlugin plugin : PLUGINS) {
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
        return ownerOf(simpleClassName).filter(FacadeEntry::isFacade).isPresent();
    }

    /** The facades the insert menus show, in declaration order — the bundled superset. */
    public static List<FacadeEntry> menuFacades() {
        return bundled().withRole(FacadeRole.MENU);
    }

    /**
     * Simple names of every facade, hidden ones included, in declaration order — for the class dropdowns,
     * which stay {@code String}-valued on purpose: the scope they display can also be a class the user wrote,
     * which no catalog entry can name.
     */
    public static List<String> facadeNames() {
        return bundled().facades().stream()
                .filter(FacadeEntry::isFacade)
                .map(FacadeEntry::simpleName)
                .toList();
    }

    private static PaletteCatalog build(String pin) {
        PaletteCatalog merged = PaletteCatalog.empty();
        for (StudioPlugin plugin : PLUGINS) {
            merged = merged.mergedWith(plugin.catalog(pin));
        }
        return merged;
    }
}
