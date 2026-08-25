package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.palette.SdkType;
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
import java.util.Set;

/**
 * What <em>this project's</em> SDK actually contains — as opposed to {@link SdkType}, which is what
 * <em>Studio</em> was compiled against.
 *
 * <p>Those two are routinely different and nothing used to notice. A bot pins one SDK version in its pom and
 * compiles against it; Studio ships on its own release train. So a bot on an older SDK was offered palette
 * entries for classes its jar does not have, and the only feedback was a javac line in the console after the
 * user had already built the block. This service is the missing question — "is this class/member present
 * here?" — and the menus intersect {@link SdkType} with it.
 *
 * <p><b>It parses nothing.</b> {@link TypeSummaryManager} already ClassGraph-scans the bot's <em>resolved</em>
 * SDK jar, restricted to {@code com.botmaker.sdk.api}; this reads the {@link ClassInfo} it already holds. The
 * {@code @Deprecated} flag likewise comes straight from bytecode (it is {@code RUNTIME}-retained), <b>not</b>
 * from parsing Javadoc — the Javadoc {@code @deprecated} <em>text</em> naming the replacement is a separate
 * concern and stays with {@link SdkDocsService}.
 *
 * <p><b>It also answers a second question, from the same scan: what is worth *offering*.</b> Presence and
 * curation are different things and both come out of this jar. Until {@code @Palette} existed, "which methods
 * exist" and "which methods Studio proposes" were the same question, because the statement menu enumerates
 * every public static method of every facade — so a method could only leave the menu by leaving the API. The
 * curation queries below ({@link #isCurated}, {@link #offeredSignatures}, {@link #retainOffered}) separate
 * them. <b>Hiding is not deprecating:</b> an unoffered method is public, supported and migrated like any
 * other; it is simply not proposed.
 *
 * <p><b>Fail-open, deliberately.</b> When the index holds no {@code com.botmaker.sdk.api} types at all — the
 * jar has not resolved yet, the scan failed, the user is offline on first open — every lookup answers "yes,
 * present". A degraded probe must never hide a block the user legitimately has: a missing menu entry reads as
 * a Studio bug and has no diagnosis, whereas the pre-existing failure mode (offering something that will not
 * compile) is at least visible and recoverable. {@link #isIndexed()} exposes which mode is in force.
 */
public final class SdkSurfaceService {

    /**
     * Facts about one SDK class, as its <em>own</em> jar declares it.
     *
     * @param curated       the class carries {@code @Palette} — it is in strict mode, and only the overloads
     *                      listed in {@code memberOffered} are proposed. False means uncurated: every public
     *                      static method is offered, exactly as before the annotation existed.
     * @param memberOffered method name → the {@link MethodSignature#signatureKey()}s carrying {@code @Palette}.
     *                      Only read when {@code curated}; a name absent from it is not offered at all.
     */
    private record TypeFacts(boolean deprecated, boolean curated, Map<String, Boolean> memberDeprecated,
                             Map<String, Set<String>> memberOffered) {}

    /**
     * The annotation class itself, by FQN. Its <em>presence in the index</em> is how a jar that predates
     * curation is told from one curated down to nothing — see the class Javadoc.
     */
    private static final String PALETTE_ANNOTATION = "com.botmaker.sdk.api.meta.Palette";

    private final ProjectConfig config;
    private final TypeSummaryManager typeIndex;

    /** simple name → facts. Empty means "not indexed" (see fail-open above), never "the SDK is empty". */
    private volatile Map<String, TypeFacts> surface = Map.of();
    private volatile String sdkVersion = "";
    /** Whether this project's SDK knows about {@code @Palette} at all. False → nothing is filtered. */
    private volatile boolean paletteAware;

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
     * Rebuilds the snapshot from the current index. Cheap (a walk of the already-scanned
     * {@code com.botmaker.sdk.api} classes) and safe to call from any thread — the snapshot is swapped in
     * whole, so a reader sees either the old map or the new one, never a half-built one.
     */
    public void refresh() {
        this.sdkVersion = MavenService.readSdkVersion(config.projectPath());
        Map<String, TypeFacts> built = new HashMap<>();
        for (ClassInfo ci : typeIndex.getAllTypes()) {
            built.put(ci.getSimpleName(), factsOf(ci));
        }
        this.surface = Map.copyOf(built);
        // The probe. Exact, and free: the index already covers com.botmaker.sdk.api, and api.meta is under it.
        this.paletteAware = typeIndex.findByQualifiedName(PALETTE_ANNOTATION).isPresent();
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
        Map<String, Set<String>> offered = new HashMap<>();
        for (Map.Entry<String, List<MethodInfo>> e : byName.entrySet()) {
            members.put(e.getKey(), e.getValue().stream().allMatch(SdkSurfaceService::isDeprecated));
            // Curation IS per overload — the ×4 shape of the matcher families is precisely what a per-name
            // switch could not touch — so the key, not the name, is what gets recorded.
            Set<String> keys = e.getValue().stream()
                    .filter(mi -> mi.hasAnnotation(PALETTE_ANNOTATION))
                    .map(MethodSignature::signatureKeyOf)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (!keys.isEmpty()) offered.put(e.getKey(), Set.copyOf(keys));
        }
        return new TypeFacts(ci.hasAnnotation(Deprecated.class.getName()), ci.hasAnnotation(PALETTE_ANNOTATION),
                Map.copyOf(members), Map.copyOf(offered));
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
     * True when this project's SDK declares {@code @Palette} at all.
     *
     * <p>Everything below degrades to "offer everything" when this is false, which is the whole of the
     * old-jar story: an SDK released before curation existed carries no annotation anywhere, and a strict
     * reader would empty every menu. Studio does not compare versions to find out — the annotation class is
     * in {@code com.botmaker.sdk.api.meta}, which the index already covers, so its presence <em>is</em> the
     * answer.
     */
    public boolean isPaletteAware() {
        return paletteAware;
    }

    /**
     * The index key for {@code typeName}, or {@code null} when it does not name an SDK type at all —
     * meaning "not ours, offer everything".
     *
     * <p>The index is keyed by <b>simple name</b>, and the callers speak two vocabularies: the facade menus
     * pass {@code "Window"} while a variable scope, an in-scope type and a library class all pass a
     * fully-qualified name. Accepting both is not merely a convenience — for the qualified half it is what
     * makes a name collision safe. A user is free to write their own {@code Window}, and keying blindly on the
     * last segment would filter <em>their</em> methods by the SDK's verdicts about a class they never used, so
     * a qualified name is admitted only when it is genuinely under the SDK's api package.
     *
     * <p><b>A bare simple name is trusted, and that is a real if narrow hole.</b> Every menu that passes one
     * has already resolved it through {@link SdkType}, but {@code MethodInvocationBlock}'s class scope can also
     * be a class the user wrote, so a user class named exactly {@code Window} would be curated by the SDK's
     * answer. This predates the present change (the ⚙ picker has always keyed this way), it cannot be closed
     * from here — the caller is the only one holding the binding that would settle it — and its cost is a
     * couple of the user's own methods missing from one dropdown, never a wrong edit. Left as it is, on
     * purpose, rather than papered over with a guess.
     */
    private String paletteKey(String typeName) {
        if (typeName == null || typeName.isEmpty()) return null;
        int dot = typeName.lastIndexOf('.');
        if (dot < 0) return typeName;
        String pkg = typeName.substring(0, dot);
        return TypeSummaryManager.DEFAULT_ALLOWED_PACKAGE_PREFIXES.stream().anyMatch(pkg::startsWith)
                ? typeName.substring(dot + 1)
                : null;
    }

    /**
     * True when {@code typeName} is in strict mode — the class itself carries {@code @Palette}. A facade
     * that does not is uncurated and offers all of its public static methods, which is what lets the sweep
     * annotate one facade at a time without the half-done ones changing behaviour.
     *
     * <p>Takes a simple name or an FQN; see {@link #paletteKey}.
     */
    public boolean isCurated(String typeName) {
        if (!paletteAware || !isIndexed()) return false;
        String key = paletteKey(typeName);
        if (key == null) return false;
        TypeFacts facts = surface.get(key);
        return facts != null && facts.curated();
    }

    /**
     * The signature keys of {@code member}'s offered overloads, or {@code null} meaning <b>"all of them"</b> —
     * an uncurated jar, an uncurated class, or an unavailable index. The null is not an error case and callers
     * must not treat it as one: it is the answer for every SDK released so far.
     *
     * <p>The keys are {@link MethodSignature#signatureKey()} spellings, so a caller filters its own
     * {@code List<MethodSignature>} with them directly.
     */
    public Set<String> offeredSignatures(String typeName, String member) {
        if (!isCurated(typeName)) return null;
        TypeFacts facts = surface.get(paletteKey(typeName));
        Set<String> keys = facts.memberOffered().get(member);
        return keys == null ? Set.of() : keys;
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
    // PALETTE INTERSECTION — SdkType ∩ this project's SDK
    // =========================================================================

    /** {@link SdkType#MENU_FACADES} minus the facades this project's SDK does not have. */
    public List<SdkType> menuFacades() {
        return SdkType.MENU_FACADES.stream().filter(t -> hasType(t.simpleName())).toList();
    }

    /** {@link SdkType#FACADE_NAMES} minus the facades this project's SDK does not have. */
    public List<String> facadeNames() {
        return SdkType.FACADE_NAMES.stream().filter(this::hasType).toList();
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
        return SdkType.MENU_FACADES.stream()
                .map(SdkType::simpleName)
                .filter(n -> !surface.containsKey(n))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
