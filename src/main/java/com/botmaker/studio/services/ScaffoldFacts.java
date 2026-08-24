package com.botmaker.studio.services;

import com.botmaker.studio.project.scaffold.ScaffoldCheck;
import com.botmaker.studio.project.scaffold.ScaffoldSurface;
import com.botmaker.studio.services.SdkApiModel.ApiClass;
import com.botmaker.studio.services.SdkApiModel.ApiMember;
import com.botmaker.studio.services.SdkApiModel.Claim;
import com.botmaker.studio.sharing.SemVer;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ScaffoldCheck.SdkFacts} over one SDK jar — the bridge between the scaffold, which knows what Studio
 * writes, and {@link SdkApiModel}, which knows how to read a jar's bytecode and its pointer annotations.
 *
 * <p>It lives here rather than in {@code project.scaffold} for one reason: the pointer reader is
 * package-private to {@code services} on purpose, being the model half of {@link SdkUpgradeService}. Rather
 * than widen it, the scaffold declares the three questions it has as an interface and this answers them.
 *
 * <h2>Only the back edge</h2>
 *
 * <p>An upgrade reads both halves of the pointer pair, because it holds both jars. This holds <b>one</b> — the
 * jar the project pins — and the half that lives on it is {@code @Replaces}, on the survivor. The other half
 * would be on an element that, by definition, this jar no longer has. So there is nothing to read forward and
 * no {@code before} snapshot to read it from, which is also why this is a much smaller thing than
 * {@link SdkPairing}: no eras, no bot version, no modernisation hop.
 *
 * <p>Chains still compose. {@code a}→{@code b} claimed in one release and {@code b}→{@code c} in the next both
 * sit on this one jar (the second claim is on {@code c}, the first on nothing that survives — so the walk
 * below expands claimants transitively and returns every reachable spelling, nearest first).
 */
public final class ScaffoldFacts implements ScaffoldCheck.SdkFacts {

    /** How many survivors one lookup may return. Chains are short by nature; this only bounds a pathology. */
    private static final int MAX_HOPS = 8;

    private final Map<String, ApiClass> byFqn;
    /** old spelling → what claims it, in the order the jar was scanned. */
    private final Map<String, List<String>> claimants;

    private ScaffoldFacts(Map<String, ApiClass> byFqn, Map<String, List<String>> claimants) {
        this.byFqn = byFqn;
        this.claimants = claimants;
    }

    /**
     * Facts about the SDK jar for {@code version}, resolving (and if need be downloading) it exactly as the
     * upgrade dialog does. An unresolvable jar yields {@link #unindexed()} rather than an error — see
     * {@link ScaffoldCheck}'s fail-open rule.
     *
     * <p><b>Blocking</b>: may hit the network. Call it off the FX thread.
     *
     * @param projectDir the project whose {@code <repositories>} to resolve through, or null at creation time,
     *                   when there is no project yet and the built-in repositories are what its pom would
     *                   declare anyway
     */
    public static ScaffoldCheck.SdkFacts forVersion(Path projectDir, String version) {
        Optional<Path> jar = projectDir == null
                ? MavenService.resolveSdkJar(version)
                : MavenService.resolveSdkJar(projectDir, version);
        return jar.map(ScaffoldFacts::forJar).orElseGet(ScaffoldFacts::unindexed);
    }

    /**
     * The same, but only when {@code version} is <b>newer than Studio's own baseline</b> — otherwise
     * {@link #unindexed()}, which fails open and costs nothing.
     *
     * <p>This gate is why the ordinary path is free. {@code ScaffoldSurfaceTest} already proves that every
     * element of {@code ScaffoldSurface} exists in the SDK Studio was built against, so an equal or older
     * version <em>cannot</em> fail the check and asking would only buy a jar resolve — possibly a download —
     * on every project creation and every save of the activity flow. Studio's baseline is
     * {@link MavenService#SDK_FALLBACK_VERSION}: it is what a fresh project pins, it moves on every SDK
     * release, and {@code release.sh check_sdk_floor} keeps it in step. A version {@link SemVer} cannot read
     * ({@code 0.0.0-SNAPSHOT}, a local dev build) is not probed either — a reactor build <em>is</em> the SDK
     * the test ran against.
     */
    public static ScaffoldCheck.SdkFacts forVersionNewerThanStudio(Path projectDir, String version) {
        if (version == null || version.isBlank()) return unindexed();
        String v = version.trim();
        if (!SemVer.isValid(v) || !SemVer.isValid(MavenService.SDK_FALLBACK_VERSION)
                || SemVer.compare(v, MavenService.SDK_FALLBACK_VERSION) <= 0) {
            return unindexed();
        }
        return forVersion(projectDir, v);
    }

    /** Facts read straight out of {@code jar} — the seam the tests build against jars made on the spot. */
    public static ScaffoldCheck.SdkFacts forJar(Path jar) {
        Map<String, ApiClass> classes = SdkApiModel.snapshot(jar);
        if (classes.isEmpty()) return unindexed();
        // A jar that declares not one scaffold type is not the SDK: a fixture built on the spot for some
        // other question, a stub, the wrong artifact off a repository. Answering "everything Studio writes is
        // gone" about it would be technically true of the bytes and useless as a statement about the project
        // — so it takes the same fail-open path an unresolvable jar takes. One is enough: the surface spans
        // bot, vision, geometry and interaction, and a real SDK missing *all* of them is not a release this
        // Studio could repair its way onto anyway.
        if (ScaffoldSurface.all().stream().noneMatch(e -> classes.containsKey(e.type()))) return unindexed();

        Map<String, ApiClass> byFqn = new LinkedHashMap<>();
        Map<String, List<String>> claimants = new LinkedHashMap<>();
        for (ApiClass now : classes.values()) {
            byFqn.put(now.name(), now);
            for (Claim claim : now.replaces()) claim(claimants, claim, now.name());
            now.byName().forEach((member, overloads) -> {
                for (ApiMember overload : overloads) {
                    for (Claim claim : overload.replaces()) {
                        claim(claimants, claim, now.name() + "#" + member);
                    }
                }
            });
        }
        return new ScaffoldFacts(Map.copyOf(byFqn), Map.copyOf(claimants));
    }

    /**
     * Facts about nothing — every query answers "not indexed", so {@link ScaffoldCheck} fails open. This is
     * what an unresolved jar, an offline first run and a failed scan all produce.
     */
    public static ScaffoldCheck.SdkFacts unindexed() {
        return new ScaffoldFacts(Map.of(), Map.of());
    }

    private static void claim(Map<String, List<String>> claimants, Claim claim, String claimant) {
        // The claim's own version is not consulted, and that is the difference from an upgrade: there is no
        // bot era here, only "Studio's baseline names X and this jar does not have it". Every claim on X
        // describes that, whichever release it was written in.
        claimants.computeIfAbsent(claim.name(), k -> new ArrayList<>()).add(claimant);
    }

    @Override
    public boolean indexed() {
        return !byFqn.isEmpty();
    }

    @Override
    public boolean hasType(String fqn) {
        return byFqn.containsKey(fqn);
    }

    @Override
    public boolean hasMember(String fqn, String member, int arity) {
        ApiClass owner = byFqn.get(fqn);
        if (owner == null) return false;
        return owner.byName().getOrDefault(member, List.of()).stream()
                .anyMatch(m -> !m.field() && m.params().size() == arity);
    }

    @Override
    public List<String> survivorsOf(String ref) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(claimants.getOrDefault(ref, List.of()));
        seen.add(ref);
        while (!pending.isEmpty() && out.size() < MAX_HOPS) {
            String at = pending.poll();
            if (!seen.add(at)) continue;
            out.add(at);
            // A claimant may itself have been claimed by something later. Breadth-first, so the nearest
            // spelling is offered first and a chain only matters when the nearer one is gone. The visited
            // set bounds a cycle; the size cap bounds a jar that claims its way around a large graph.
            pending.addAll(claimants.getOrDefault(at, List.of()));
        }
        return List.copyOf(out);
    }
}
