package com.botmaker.studio.services;

import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.MemberEntry;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.sharing.SemVer;
import com.botmaker.studio.util.MethodSignature;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What <em>this project's</em> SDK offers the editor: the two halves of that answer, and the one place they
 * are composed.
 *
 * <p>A bot pins one SDK version in its pom and compiles against it; Studio ships on its own release train,
 * so the two are routinely different and nothing used to notice — a bot on an older SDK was offered palette
 * entries for classes its jar does not have, and the only feedback was a javac line in the console after the
 * user had already built the block.
 *
 * <p>The two halves, and why they come from different places:
 *
 * <ul>
 *   <li><b>Presence</b> — "is this class/member in the jar this bot resolves?" — is answered by scanning
 *       that jar, through {@link TypeSummaryManager}. Nothing else can answer it: a catalog is written with
 *       method references, so the catalog for an older version cannot name a member since deleted.</li>
 *   <li><b>Curation</b> — "which of them is worth proposing, in what order, under which glyph?" — is
 *       answered by the plugin, through {@link PluginHost#catalogFor} at the version this project pins.
 *       Nothing enumerable answers it: a member the analyzer resolves perfectly well may still not be one
 *       the SDK proposes.</li>
 * </ul>
 *
 * <p>They compose as an <b>intersection</b>, and it fails in the safe direction: a bot pinned to an old
 * version may occasionally be offered slightly less than that version truly had, and never more. Being
 * offered a member that does not exist is a bot that will not compile; being offered one fewer is a menu
 * entry somebody types by hand.
 *
 * <p><b>It parses nothing.</b> {@link TypeSummaryManager} already ClassGraph-scans the bot's <em>resolved</em>
 * plugin jars, restricted to the packages those plugins catalogue; this reads the {@link ClassInfo} it
 * already holds. The
 * {@code @Deprecated} flag likewise comes straight from bytecode (it is {@code RUNTIME}-retained), <b>not</b>
 * from parsing Javadoc — the Javadoc {@code @deprecated} <em>text</em> naming the replacement is a separate
 * concern and stays with {@link SdkDocsService}.
 *
 * <p><b>Hiding is not deprecating.</b> An unoffered method is public, supported and migrated like any
 * other; it is simply not proposed. The curation queries below ({@link #isCurated},
 * {@link #offeredSignatures}, {@link #retainOffered}) are about the menus and nothing else.
 *
 * <p><b>Fail-open, deliberately.</b> When the index holds no catalogued types at all — the
 * jar has not resolved yet, the scan failed, the user is offline on first open — every lookup answers "yes,
 * present". A degraded probe must never hide a block the user legitimately has: a missing menu entry reads as
 * a Studio bug and has no diagnosis, whereas the pre-existing failure mode (offering something that will not
 * compile) is at least visible and recoverable. {@link #isIndexed()} exposes which mode is in force.
 */
public final class SdkSurfaceService {

    /** Facts about one SDK class, as its <em>own</em> jar declares it. Presence only — never curation. */
    private record TypeFacts(boolean deprecated, Map<String, Boolean> memberDeprecated) {}

    private final ProjectConfig config;
    private final TypeSummaryManager typeIndex;

    /** simple name → facts. Empty means "not indexed" (see fail-open above), never "the SDK is empty". */
    private volatile Map<String, TypeFacts> surface = Map.of();
    private volatile String sdkVersion = "";
    /**
     * What the plugins offer at the version this project pins. Empty means <em>uncurated</em> — a pin no
     * plugin recognises, or one released before catalogs existed — and nothing below filters anything.
     */
    private volatile PaletteCatalog catalog = PaletteCatalog.empty();

    public SdkSurfaceService(ProjectConfig config, TypeSummaryManager typeIndex, EventBus eventBus) {
        this.config = config;
        this.typeIndex = typeIndex;
        // The SDK version — and therefore the whole surface — changes when the user edits libraries.
        eventBus.subscribe(CoreApplicationEvents.LibrariesChangedEvent.class, e -> refresh(), false);
        refresh();
    }

    // =========================================================================
    // SNAPSHOT
    // =========================================================================

    /**
     * Rebuilds the snapshot from the current index. Cheap (a walk of the already-scanned catalogued
     * classes) and safe to call from any thread — the snapshot is swapped in
     * whole, so a reader sees either the old map or the new one, never a half-built one.
     */
    public void refresh() {
        // A project that declares no SDK reads as "", which PluginHost.catalogFor treats as an unrecognised
        // pin — no catalog, so nothing is curated and the menus widen. That is the same fail-open a pin
        // released before catalogs existed gets, and it is right for the same reason: with no plugin bound
        // there is nothing to offer anyway, and narrowing on a version nobody declared would be a guess.
        this.sdkVersion = MavenService.readSdkVersion(config.projectPath()).orElse("");
        Map<String, TypeFacts> built = new HashMap<>();
        for (ClassInfo ci : typeIndex.getAllTypes()) {
            built.put(ci.getSimpleName(), factsOf(ci));
        }
        this.surface = Map.copyOf(built);
        // Curation is the plugin's answer for THIS project's pin, never for the jar Studio bundles.
        this.catalog = PluginHost.catalogFor(this.sdkVersion);
    }

    private static TypeFacts factsOf(ClassInfo ci) {
        // A name, not an overload: the menus collapse overloads to one entry, so the entry is only struck
        // through when EVERY overload carrying that name is deprecated. Striking a name where one overload is
        // still perfectly good would be a lie about the code the user is looking at.
        Map<String, List<MethodInfo>> byName = new LinkedHashMap<>();
        for (MethodInfo mi : ci.getMethodInfo()) {
            if (mi.isPublic()) byName.computeIfAbsent(mi.getName(), k -> new ArrayList<>()).add(mi);
        }
        Map<String, Boolean> members = new HashMap<>();
        for (Map.Entry<String, List<MethodInfo>> e : byName.entrySet()) {
            members.put(e.getKey(), e.getValue().stream().allMatch(SdkSurfaceService::isDeprecated));
        }
        return new TypeFacts(ci.hasAnnotation(Deprecated.class.getName()), Map.copyOf(members));
    }

    private static boolean isDeprecated(MethodInfo mi) {
        return mi.hasAnnotation(Deprecated.class.getName());
    }

    // =========================================================================
    // QUERIES
    // =========================================================================

    /**
     * False when the SDK jar has not been scanned (unresolved / offline / scan failure), in which case every
     * presence query answers optimistically. Callers that want to <em>say</em> something to the user — a
     * banner, a diagnostic — should check this first; callers that merely filter a menu need not.
     */
    public boolean isIndexed() {
        return !surface.isEmpty();
    }

    /** True when this project's SDK declares a class with this simple name. Optimistic when not indexed. */
    public boolean hasType(String simpleName) {
        return !isIndexed() || surface.containsKey(simpleName);
    }

    /** True when {@code simpleName} declares a public method called {@code member}. Optimistic when not indexed. */
    public boolean hasMember(String simpleName, String member) {
        if (!isIndexed()) return true;
        TypeFacts facts = surface.get(simpleName);
        return facts != null && facts.memberDeprecated().containsKey(member);
    }

    /** True when the class itself is {@code @Deprecated}. Never optimistic — an unknown class is not deprecated. */
    public boolean isTypeDeprecated(String simpleName) {
        TypeFacts facts = surface.get(simpleName);
        return facts != null && facts.deprecated();
    }

    /**
     * True when every public overload named {@code member} is {@code @Deprecated} — or when its whole class
     * is. Never optimistic: an unknown member is reported as fine, so a degraded index strikes nothing
     * through rather than striking everything through.
     */
    public boolean isMemberDeprecated(String simpleName, String member) {
        TypeFacts facts = surface.get(simpleName);
        if (facts == null) return false;
        if (facts.deprecated()) return true;
        return Boolean.TRUE.equals(facts.memberDeprecated().get(member));
    }

    // =========================================================================
    // CURATION — what this project's SDK says is worth *offering*
    // =========================================================================

    /**
     * True when a plugin served a catalog for the version this project pins.
     *
     * <p>Everything below degrades to "offer everything" when this is false, which is the whole of the
     * old-jar story: an SDK released before catalogs existed answers {@code PaletteCatalog.empty()}, and a
     * strict reader would empty every menu instead. <b>Empty means "declined to curate", never "offers
     * nothing"</b> — the fail-open is at the <em>version</em> level and only there.
     */
    public boolean isPaletteAware() {
        return !catalog.isEmpty();
    }

    /**
     * The catalogued type {@code typeName} names, or empty for one no plugin owns — "not ours, offer
     * everything".
     *
     * <p>Callers speak two vocabularies: the facade menus pass {@code "Window"} while a variable scope, an
     * in-scope type and a library class all pass a fully-qualified name. Both are accepted, and the qualified
     * half is now matched <b>exactly</b> rather than by package prefix — a user's own
     * {@code com.mybot.Window} cannot collide with the SDK's, because the catalog holds the real class.
     *
     * <p><b>A bare simple name is trusted, and that is a real if narrow hole.</b> {@code MethodInvocationBlock}'s
     * class scope can be a class the user wrote, so a user class named exactly {@code Window} would be curated
     * by the SDK's answer. It predates the catalog (the ⚙ picker has always keyed this way), it cannot be
     * closed from here — the caller is the only one holding the binding that would settle it — and its cost is
     * a couple of the user's own methods missing from one dropdown, never a wrong edit.
     */
    private Optional<FacadeEntry> curatedType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return Optional.empty();
        return typeName.indexOf('.') < 0
                ? catalog.facadeBySimpleName(typeName)
                : catalog.facade(typeName);
    }

    /**
     * True when {@code typeName} is a type a plugin catalogues — in which case it offers exactly the members
     * the catalog lists and nothing else. <b>Present means curated:</b> a type the catalog does not name is
     * not curated by it, and is offered whole.
     *
     * <p>Takes a simple name or an FQN; see {@link #curatedType}.
     */
    public boolean isCurated(String typeName) {
        return curatedType(typeName).isPresent();
    }

    /**
     * The signature keys of {@code member}'s offered overloads, or {@code null} meaning <b>"all of them"</b> —
     * an uncurated pin or an uncatalogued type. The null is not an error case and callers must not treat it as
     * one: it is the answer for every SDK released before 1.2.0.
     *
     * <p>The keys are {@link MethodSignature#signatureKey()} spellings, so a caller filters its own
     * {@code List<MethodSignature>} with them directly. An <em>empty</em> set is the other verdict: the type
     * is curated and this member is not among what it offers.
     */
    public Set<String> offeredSignatures(String typeName, String member) {
        Optional<FacadeEntry> entry = curatedType(typeName);
        if (entry.isEmpty()) return null;
        return entry.get().overloads(member).stream()
                .map(MemberEntry::id)
                .map(MethodSignature::signatureKeyOf)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * True when {@code member} earns a menu entry — any of its overloads is offered. Optimistic exactly where
     * {@link #offeredSignatures} is: an uncurated jar or class offers every name it has.
     */
    public boolean isOffered(String simpleName, String member) {
        Set<String> keys = offeredSignatures(simpleName, member);
        return keys == null || !keys.isEmpty();
    }

    /**
     * {@code sigs} reduced to the overloads this SDK offers, with {@code keep} kept whatever the answer.
     *
     * <p>{@code keep} is the rule that makes curation safe: <b>Studio filters what it *offers*, never what it
     * *resolves*</b>. A bot already calling an overload that is no longer proposed must still render, still
     * type its arguments and still compile — so the ⚙ picker shows the offered set <em>plus wherever this call
     * actually is</em>, and a block never finds itself on an overload its own menu denies. Pass {@code null}
     * where there is no current call (a fresh insert).
     */
    public List<MethodSignature> retainOffered(String simpleName, String member, List<MethodSignature> sigs,
                                               MethodSignature keep) {
        Set<String> keys = offeredSignatures(simpleName, member);
        if (keys == null || sigs == null) return sigs;
        String keepKey = keep == null ? null : keep.signatureKey();
        List<MethodSignature> out = sigs.stream()
                .filter(s -> keys.contains(s.signatureKey()) || s.signatureKey().equals(keepKey))
                .toList();
        // Never hand back nothing: a class curated in a way that hides every overload of a name the caller is
        // already looking at would leave an empty picker, which reads as a broken block rather than a choice.
        return out.isEmpty() ? sigs : out;
    }

    /**
     * {@code names} reduced to the members offered on {@code typeName}, with {@code keep} kept whatever the
     * answer — the <em>name</em>-level twin of {@link #retainOffered}, for the surfaces that list members
     * before they list overloads (a member submenu, a method dropdown). Pass {@code keep = null} where
     * nothing is currently selected.
     *
     * <p><b>Deliberately without {@link #retainOffered}'s never-hand-back-nothing guard.</b> That guard exists
     * because an empty ⚙ picker on a block that plainly <em>has</em> an overload reads as breakage. An empty
     * member list does not: the caller drops the whole submenu, which is the same thing it already does for a
     * type with nothing compatible with the slot, and is a correct answer rather than a confusing one.
     */
    public List<String> retainOfferedNames(String typeName, List<String> names, String keep) {
        if (names == null || !isCurated(typeName)) return names;
        return names.stream().filter(n -> isOffered(typeName, n) || n.equals(keep)).toList();
    }

    // =========================================================================
    // PALETTE INTERSECTION — the served catalog ∩ this project's SDK
    // =========================================================================

    /**
     * The catalogued facades this project's SDK actually has, in the order the catalog declared them — the
     * intersection the class Javadoc describes, and the list the insert menus walk.
     *
     * <p>Empty when the pin is uncurated, which the menus read as "no per-facade submenus" rather than as an
     * empty editor: {@link #isPaletteAware} is the flag that tells them apart.
     */
    public List<FacadeEntry> menuFacades() {
        return catalog.offeredFacades().stream()
                .filter(f -> hasType(f.simpleName()))
                .toList();
    }

    /**
     * The simple names of every catalogued facade this project's SDK has, hidden ones included — the
     * recognition set, and what the class dropdowns list.
     */
    public List<String> facadeNames() {
        return catalog.facades().stream()
                .map(FacadeEntry::simpleName)
                .filter(this::hasType)
                .toList();
    }

    // =========================================================================
    // VERSION FLOOR
    // =========================================================================

    /**
     * The SDK version this project's pom pins — or {@link MavenService#SDK_FALLBACK_VERSION} when the pom
     * cannot be read or names no SDK dependency, which is what {@link MavenService#readSdkVersion} answers.
     */
    public String sdkVersion() {
        return sdkVersion;
    }

    // isBelowMinimum() is gone (2026-08-25), with MavenService.MIN_SDK_VERSION and the banner it drew. See
    // the note where that constant used to be: with no generation in Studio there is nothing for a version
    // floor to protect, and per-element presence — which this class answers from the project's own jar — is
    // the better question anyway.

    /**
     * The facades Studio knows about that this project's SDK does not have — what the upgrade banner names.
     * Empty when the index is unavailable, so a degraded probe never invents a scary list.
     */
    public Set<String> missingFacades() {
        if (!isIndexed()) return Set.of();
        return PluginHost.bundled().offeredFacades().stream()
                .map(FacadeEntry::simpleName)
                .filter(n -> !surface.containsKey(n))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
