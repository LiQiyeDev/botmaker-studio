package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.sharing.SemVer;
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
 * <p><b>Fail-open, deliberately.</b> When the index holds no {@code com.botmaker.sdk.api} types at all — the
 * jar has not resolved yet, the scan failed, the user is offline on first open — every lookup answers "yes,
 * present". A degraded probe must never hide a block the user legitimately has: a missing menu entry reads as
 * a Studio bug and has no diagnosis, whereas the pre-existing failure mode (offering something that will not
 * compile) is at least visible and recoverable. {@link #isIndexed()} exposes which mode is in force.
 */
public final class SdkSurfaceService {

    /** Facts about one SDK class, as its <em>own</em> jar declares it. */
    private record TypeFacts(boolean deprecated, Map<String, Boolean> memberDeprecated) {}

    private final ProjectConfig config;
    private final TypeSummaryManager typeIndex;

    /** simple name → facts. Empty means "not indexed" (see fail-open above), never "the SDK is empty". */
    private volatile Map<String, TypeFacts> surface = Map.of();
    private volatile String sdkVersion = "";

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

    /**
     * True when the pinned SDK is older than {@link MavenService#MIN_SDK_VERSION}.
     *
     * <p>Anything {@link SemVer} cannot parse is <b>not</b> below the floor. That is not laxity: the one
     * unparseable version that occurs in practice is {@code 0.0.0-SNAPSHOT}, the local dev build a maintainer
     * pins deliberately (see {@code MavenService.localSdkVersions}), and {@code SemVer} sorts unparseable
     * below everything — so the naive comparison would nag on every single dev-run.
     */
    public boolean isBelowMinimum() {
        return SemVer.isValid(sdkVersion) && SemVer.compare(sdkVersion, MavenService.MIN_SDK_VERSION) < 0;
    }

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
