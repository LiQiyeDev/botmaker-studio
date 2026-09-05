package com.botmaker.studio.services;

import com.botmaker.shared.github.SemVer;
import com.botmaker.studio.services.SdkApiModel.ApiClass;
import com.botmaker.studio.services.SdkApiModel.ApiMember;
import com.botmaker.studio.services.SdkApiModel.Claim;
import com.botmaker.studio.services.SdkApiModel.Pointer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.botmaker.studio.services.SdkApiModel.lastSegment;
import static com.botmaker.studio.services.SdkApiModel.memberPart;
import static com.botmaker.studio.services.SdkApiModel.strip;
import static com.botmaker.studio.services.SdkApiModel.typePart;

/**
 * Which type in the target jar takes each old type's place, and what each member is now called.
 *
 * <p>It is a walk over a tiny graph of <b>old spelling → newer spelling</b>, built once from both jars.
 * A node is a spelling in the grammar the pointers use: {@code fqn} for a type, {@code fqn#member} for a
 * member. Two things put edges in it:
 *
 * <ul>
 *   <li>the <b>old</b> jar's {@code @ReplacedBy}, which is the author of the element the bot actually
 *       calls saying where it went;</li>
 *   <li>the <b>new</b> jar's {@code @Replaces}, read backwards — each entry is an edge from the old
 *       spelling it names to the element carrying it — and <b>filtered by era</b>: an entry is consulted
 *       only for a bot pinned at or below the version the entry records, since a bot already past that
 *       release cannot still be spelling it the old way.</li>
 * </ul>
 *
 * <p>The walk follows edges until it reaches a spelling the target jar actually has, which is what makes
 * a <b>chain</b> resolve: {@code a}→{@code b} announced in 2.0 and {@code b}→{@code c} in 3.0 lands a bot
 * still spelling it {@code a} on {@code c}, with the 2.0 jar never fetched. A visited set bounds it — a
 * rename undone by a later release is a cycle, and a cycle that reaches nothing live is simply unpaired.
 *
 * <p>Three things it deliberately does not do. It does not follow a pointer for a spelling the target
 * <em>still has</em>: the live element wins, which is why an accumulated entry can never go stale into a
 * wrong answer. It does not resolve an ambiguous claim — two survivors claiming one old spelling at one
 * version leave it unpaired, with a line in {@code Report.problems()}. And it never invents a pairing:
 * unpaired is an answer, and a wrongly paired element is a bot that compiles and does something else.
 *
 * <p><b>Members are paired independently of types</b>, and a member pointer may cross types. Two readers
 * ask for different halves of that, deliberately: {@link #memberName} answers "what is this called on the
 * type this one paired with", so nothing but the type pairing decides which type a site writes, while
 * {@link #targetOf} hands back the endpoint whole — for {@link SdkRedirects}, which is about to move the
 * receiver as well and is the only caller entitled to see a member that left.
 */
record SdkPairing(Map<String, String> types, Map<String, List<SdkPairing.Member>> members) {

    /**
     * Where a member pointer ended up: the simple name of the owning type, the member's own name, and
     * the sentence that distinguishes this candidate from the others when there are several.
     */
    record Member(String type, String name, String when) {}

    /** One edge, and the {@code whens()} sentence the author wrote beside it. Blank for a back edge. */
    private record Target(String spelling, String when) {}

    static SdkPairing of(Map<String, ApiClass> before, Map<String, ApiClass> after, String botVersion,
                         List<String> problems, boolean throughDeprecations) {
        Map<String, List<Target>> edges = forwardEdges(before);
        backwardEdges(after, botVersion, problems).forEach(edges::putIfAbsent);
        // Modernising walks one hop further than an upgrade does, so it needs the pointers the *target*
        // jar's own deprecated elements carry. They are the same shape of edge; only the stopping rule
        // below differs, which is the whole of what "also move off deprecated members" means.
        if (throughDeprecations) forwardEdges(after).forEach(edges::putIfAbsent);

        Map<String, String> types = new LinkedHashMap<>();
        Map<String, List<Member>> members = new LinkedHashMap<>();
        for (ApiClass then : before.values()) {
            // A *type* is never a split: the rename is applied file-wide, so a file cannot write two
            // different names for one class and there is no call site to ask. The first live endpoint
            // is the answer, which is what a single-target pointer has always given.
            Target typeEnd = follow(then.name(), edges, after, throughDeprecations).getFirst();
            ApiClass target = typeEnd.spelling().contains("#")
                    ? null : resolveType(typeEnd.spelling(), after);
            if (target != null) {
                types.put(then.simpleName(), target.simpleName());
            } else if (after.containsKey(then.simpleName())) {
                // The name survives even if the package moved under it. Nothing pointed anywhere, so the
                // same spelling is the answer — this is the short-circuit an upgrade takes almost always.
                types.put(then.simpleName(), then.simpleName());
            }
            for (String member : then.byName().keySet()) {
                String key = then.name() + "#" + member;
                List<Member> ends = new ArrayList<>();
                for (Target end : follow(key, edges, after, throughDeprecations)) {
                    if (end.spelling().equals(key)) continue;   // nothing pointed anywhere
                    ApiClass owner = resolveType(typePart(end.spelling()), after);
                    if (owner != null && owner.byName().containsKey(memberPart(end.spelling()))) {
                        ends.add(new Member(owner.simpleName(), memberPart(end.spelling()), end.when()));
                    }
                }
                if (!ends.isEmpty()) members.put(then.simpleName() + "#" + member, List.copyOf(ends));
            }
        }
        return new SdkPairing(Map.copyOf(types), Map.copyOf(members));
    }

    /**
     * What the old jar's own elements say about where they went. No target = nothing took my place.
     *
     * <p>A pointer may name <b>several</b> candidates — a <em>split</em> — and all of them become edges,
     * in the author's declared order, each carrying the {@code whens()} sentence written beside it. Every
     * reader that wants one answer takes the first, which is what "ordered, first preferred" means; the
     * reader that offers the user a choice takes the list.
     */
    private static Map<String, List<Target>> forwardEdges(Map<String, ApiClass> before) {
        Map<String, List<Target>> edges = new LinkedHashMap<>();
        for (ApiClass then : before.values()) {
            List<Target> onType = targetsOf(then.replacedBy());
            if (!onType.isEmpty()) edges.put(then.name(), onType);
            then.byName().forEach((member, overloads) -> overloads.stream()
                    .map(m -> targetsOf(m.replacedBy()))
                    .filter(targets -> !targets.isEmpty())
                    // Overloads share a name and a call site is attributed by name, so the first pointer
                    // any of them carries answers for all of them. Two overloads pointing different ways
                    // is a distinction this cannot act on either way.
                    .findFirst()
                    .ifPresent(targets -> edges.putIfAbsent(then.name() + "#" + member, targets)));
        }
        return edges;
    }

    /**
     * A pointer's candidates paired with the sentence declared beside each, in the author's own order.
     * Empty for no annotation and for one that named nowhere — the two are the same edge, namely none.
     *
     * <p>The two arrays are index-aligned by the SDK's gate ({@code whens()} is empty or exactly
     * {@code value()}'s length, and a blank target may not be mixed with non-blank ones), so a missing
     * sentence is read as "this candidate has none" rather than as a misalignment to repair.
     */
    private static List<Target> targetsOf(Pointer pointer) {
        if (pointer == null) return List.of();
        List<Target> out = new ArrayList<>();
        for (int i = 0; i < pointer.targets().size(); i++) {
            out.add(new Target(pointer.targets().get(i),
                    i < pointer.whens().size() ? pointer.whens().get(i) : ""));
        }
        return out;
    }

    /**
     * What the target jar's survivors claim, read as edges pointing forward in time. Entries are grouped
     * by the old spelling they name; only those from the bot's own era or later can apply to it, and of
     * those the <b>earliest</b> is the next hop — a later one describes a rename this bot has not reached.
     *
     * <p><b>Two survivors claiming one old member is a split, not an error.</b> The back edge is the only
     * place a split survives the deletion of the element it split from, which is the entire reason
     * {@code @Replaces} exists — so a double claim on a <em>member</em> becomes both edges and the user is
     * asked per call site. A double claim on a <b>type</b> stays a {@code Report.problems()} line: a type
     * rename is applied file-wide ({@code CallMigrator.renameTypeIn}), so there is no per-site question to
     * ask and no way for one file to write two answers.
     */
    private static Map<String, List<Target>> backwardEdges(Map<String, ApiClass> after, String botVersion,
                                                           List<String> problems) {
        Map<String, Map<String, Set<String>>> claims = new LinkedHashMap<>();
        for (ApiClass now : after.values()) {
            for (Claim claim : now.replaces()) claim(claims, claim, now.name());
            now.byName().forEach((member, overloads) -> {
                for (ApiMember overload : overloads) {
                    for (Claim claim : overload.replaces()) {
                        claim(claims, claim, now.name() + "#" + member);
                    }
                }
            });
        }

        Map<String, List<Target>> edges = new LinkedHashMap<>();
        claims.forEach((oldSpelling, byVersion) -> byVersion.entrySet().stream()
                .filter(e -> appliesTo(botVersion, e.getKey()))
                .min(Map.Entry.comparingByKey(SdkApiModel::compareVersions))
                .ifPresent(e -> {
                    if (e.getValue().size() > 1 && !oldSpelling.contains("#")) {
                        problems.add("\"" + oldSpelling + "\" is claimed by more than one element of the "
                                + "target SDK (" + String.join(", ", e.getValue()) + "), so there is no "
                                + "one answer to what it became. Uses of it are left for you to change.");
                        return;
                    }
                    // A back edge carries no sentence: the survivor knows what it replaced, not why one
                    // call meant it rather than the other. A split read only backwards therefore reaches
                    // the user as a menu of member names, which is still a choice they can make.
                    edges.put(oldSpelling, e.getValue().stream().map(c -> new Target(c, "")).toList());
                }));
        return edges;
    }

    private static void claim(Map<String, Map<String, Set<String>>> claims, Claim claim, String claimant) {
        claims.computeIfAbsent(claim.name(), k -> new LinkedHashMap<>())
                .computeIfAbsent(strip(claim.version()), k -> new LinkedHashSet<>())
                .add(claimant);
    }

    /**
     * Whether an entry recorded as last existing in {@code entryVersion} can still describe a bot pinned
     * at {@code botVersion}. When either is not a version {@link SemVer} understands —
     * {@code 0.0.0-SNAPSHOT}, most often — the entry is consulted rather than dropped: a pointer for a
     * rename the bot has already had applied costs nothing, since the old name appears nowhere in it.
     */
    private static boolean appliesTo(String botVersion, String entryVersion) {
        String bot = strip(botVersion);
        if (!SemVer.isValid(bot) || !SemVer.isValid(entryVersion)) return true;
        return SemVer.compare(bot, entryVersion) <= 0;
    }

    /**
     * Walks the edges until the spelling is one the target jar has, or until there is nowhere to go.
     *
     * <p>{@code throughDeprecations} moves the finish line by one condition: a spelling the target does
     * have, but has marked {@code @Deprecated} <em>and</em> pointed somewhere, is walked past rather
     * than accepted. That is the only difference between an upgrade and a modernisation — the same
     * graph, read one hop further — and it is why a bot can move off a deprecated member with no version
     * change at all. A deprecated element with no pointer stops the walk like any other: there is
     * nothing to say about it.
     *
     * <p>An edge may fan out, so this returns a <b>list</b>: the walk expands candidates depth-first in
     * declared order and collects every endpoint the target jar actually has, deduped. A split composes
     * with a chain for free — {@code a}→{@code {b,c}} and {@code b}→{@code d} lands on {@code {d, c}} —
     * and a single-target pointer is the degenerate case, a one-element list, so the almost-always path
     * is what it has always been. When <b>nothing</b> live is reachable the start is handed back, which
     * is how every caller has always read "this pointed nowhere": the endpoint equals what went in.
     */
    private static List<Target> follow(String start, Map<String, List<Target>> edges,
                                       Map<String, ApiClass> after, boolean throughDeprecations) {
        List<Target> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<Target> pending = new ArrayDeque<>();
        pending.push(new Target(start, ""));
        while (!pending.isEmpty()) {
            Target at = pending.pop();
            if (!seen.add(at.spelling())) continue;             // the visited set still bounds cycles
            if (accepts(at.spelling(), edges, after, throughDeprecations)) {
                out.add(at);
                continue;
            }
            List<Target> next = edges.get(at.spelling());
            if (next == null) continue;
            // Pushed in reverse so the stack pops them in the author's declared order. A hop that
            // declares no sentence of its own inherits the one that reached it: a chain does not make
            // the reason a candidate was offered any less true.
            for (int i = next.size() - 1; i >= 0; i--) {
                Target hop = next.get(i);
                pending.push(new Target(hop.spelling(),
                        hop.when().isBlank() ? at.when() : hop.when()));
            }
        }
        return out.isEmpty() ? List.of(new Target(start, "")) : List.copyOf(out);
    }

    /** Whether the walk stops here: a spelling the target has, and — when modernising — is not moving on from. */
    private static boolean accepts(String spelling, Map<String, List<Target>> edges,
                                   Map<String, ApiClass> after, boolean throughDeprecations) {
        if (!exists(spelling, after)) return false;
        return !(throughDeprecations && edges.containsKey(spelling) && isDeprecated(spelling, after));
    }

    /** Whether the target jar marks this exact spelling {@code @Deprecated}. False for one it lacks. */
    private static boolean isDeprecated(String spelling, Map<String, ApiClass> after) {
        ApiClass owner = resolveType(typePart(spelling), after);
        if (owner == null) return false;
        return spelling.contains("#")
                ? owner.deprecatedNames().contains(memberPart(spelling))
                : owner.deprecated();
    }

    /** Whether the target jar declares this exact spelling — the same fully-qualified type, at that. */
    private static boolean exists(String spelling, Map<String, ApiClass> after) {
        ApiClass owner = resolveType(typePart(spelling), after);
        if (owner == null) return false;
        return !spelling.contains("#") || owner.byName().containsKey(memberPart(spelling));
    }

    /**
     * The target's class of that fully-qualified name, or null. The FQN is compared, not just the simple
     * name: a pointer that names a package the target does not have is a pointer to nothing, and pairing
     * it with a same-named class elsewhere would be the invented answer this refuses to give.
     */
    private static ApiClass resolveType(String fqn, Map<String, ApiClass> after) {
        ApiClass candidate = after.get(lastSegment(fqn));
        return candidate != null && candidate.name().equals(fqn) ? candidate : null;
    }

    /** The old type's counterpart in {@code after}, or null when nothing takes its place. */
    ApiClass pairedTo(ApiClass then, Map<String, ApiClass> after) {
        String name = types.get(then.simpleName());
        return name == null ? null : after.get(name);
    }

    /**
     * What {@code member} of {@code then} is called in the target — itself, unless a pointer says
     * otherwise <em>and</em> lands on the type this one paired with.
     *
     * <p>Which type the site now writes is the type pairing's answer, and having two sources for it is
     * how they come to disagree. A member sent somewhere else therefore reads as unpaired here.
     */
    String memberName(ApiClass then, ApiClass now, String member) {
        return targetOf(then, member).stream()
                .filter(paired -> paired.type().equals(now.simpleName()))
                .map(Member::name)
                .findFirst()
                .orElse(member);
    }

    /**
     * The endpoints themselves, type and all — empty when no pointer led anywhere the target jar has,
     * one element for the ordinary rename, and several for a split, in the author's preference order.
     *
     * <p>{@link #memberName} answers the narrower question and keeps the type pairing as the single
     * source of which type a site writes; this one is for the caller that is <em>about to move the
     * receiver too</em>, which is the only thing entitled to see an endpoint on another type.
     */
    List<Member> targetOf(ApiClass then, String member) {
        return members.getOrDefault(then.simpleName() + "#" + member, List.of());
    }
}
