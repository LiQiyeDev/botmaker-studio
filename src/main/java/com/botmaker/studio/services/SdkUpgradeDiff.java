package com.botmaker.studio.services;

import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner;
import com.botmaker.studio.parser.refactor.SignatureMigration.ArgumentEdit;
import com.botmaker.studio.services.SdkApiModel.ApiClass;
import com.botmaker.studio.services.SdkApiModel.ApiMember;
import com.botmaker.studio.services.SdkUpgradeService.Break;
import com.botmaker.studio.services.SdkUpgradeService.BreakKind;
import com.botmaker.studio.services.SdkUpgradeService.Call;
import com.botmaker.studio.services.SdkUpgradeService.CallSite;
import com.botmaker.studio.services.SdkUpgradeService.Candidate;
import com.botmaker.studio.services.SdkUpgradeService.Choice;
import com.botmaker.studio.services.SdkUpgradeService.Deprecation;
import com.botmaker.studio.services.SdkUpgradeService.Site;
import com.botmaker.studio.services.SdkUpgradeService.TypeUse;
import com.botmaker.studio.services.SdkUpgradeService.Uses;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.botmaker.studio.services.SdkApiModel.CTOR;
import static com.botmaker.studio.services.SdkApiModel.compareVersions;
import static com.botmaker.studio.services.SdkApiModel.declares;
import static com.botmaker.studio.services.SdkApiModel.offers;
import static com.botmaker.studio.services.SdkApiModel.signatures;
import static com.botmaker.studio.services.SdkApiModel.strip;
import static com.botmaker.studio.services.SdkRedirects.fittingAt;
import static com.botmaker.studio.services.SdkRedirects.redirectFor;
import static com.botmaker.studio.services.SdkRedirects.redirectsFor;
import static com.botmaker.studio.services.SdkRedirects.returnTypeOf;

/**
 * The two jars intersected with this bot's own source — every list a {@link SdkUpgradeService.Report}
 * carries, and the sentences the dialog shows beside them.
 *
 * <p>Nothing here decides a redirect: it asks {@link SdkRedirects}, which is the single place that does,
 * so a row promising a move is a row the repair pass will actually make.
 */
final class SdkUpgradeDiff {

    private SdkUpgradeDiff() {
    }

    /**
     * New classes, and new members on classes that already existed — display text, sorted, and <b>grouped by
     * the release each arrived in</b>.
     *
     * <p>The era comes from {@code @Since}, asked of the element itself and falling back to its declaring
     * class (a whole new class carries one annotation, not one per member). Everything that answers nothing
     * lands in the {@code ""} bucket, which is sorted last and rendered without a heading — a jar predating
     * {@code @Since} therefore produces exactly the flat alphabetical list this used to return.
     */
    static Map<String, List<String>> additions(Map<String, ApiClass> before, Map<String, ApiClass> after) {
        Map<String, Set<String>> byEra = new LinkedHashMap<>();
        for (ApiClass now : after.values()) {
            ApiClass then = before.get(now.simpleName());
            if (then == null) {
                byEra.computeIfAbsent(now.since(), k -> new TreeSet<>())
                        .add(now.simpleName() + " (new class)");
                continue;
            }
            for (Map.Entry<String, List<ApiMember>> entry : now.byName().entrySet()) {
                String name = entry.getKey();
                if (then.byName().containsKey(name)) continue;
                // A constant is read as a value, so it is shown as one: Key.ENTER, not Key.ENTER(…).
                String call = now.declaresCallable(name) ? "(…)" : "";
                String era = entry.getValue().stream().map(ApiMember::since)
                        .filter(s -> !s.isBlank()).findFirst().orElse(now.since());
                byEra.computeIfAbsent(era, k -> new TreeSet<>())
                        .add(CTOR.equals(name)
                                ? "new " + now.simpleName() + "(…)"
                                : now.simpleName() + "." + name + call);
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        byEra.entrySet().stream()
                // Newest first, and the era nobody declared last: it is the answer "we do not know", which
                // belongs after every answer we do have.
                .sorted((a, b) -> a.getKey().isBlank() ? 1 : b.getKey().isBlank() ? -1
                        : compareVersions(strip(b.getKey()), strip(a.getKey())))
                .forEach(e -> out.put(e.getKey(), List.copyOf(e.getValue())));
        // Not Map.copyOf: that is a hash map, and the sort immediately above is the whole point.
        return Collections.unmodifiableMap(out);
    }

    /**
     * What this release does to the members Studio writes into a bot's <em>generated</em> files, said up front.
     *
     * <p>Those files are never migrated — they are rendered from Studio's own templates, so rewriting one
     * would be overwritten at the next regeneration and regenerating it would reproduce the same old-SDK code.
     * When a repair touches one, {@code SdkMigrationRunner} refuses the whole upgrade, and until now it did so
     * <b>mid-apply</b>: the user read a report, pressed the button and only then learned the answer was no.
     * This is the same fact, stated before they commit to anything.
     *
     * <p>It is read from the <b>old</b> jar, which is the one whose spelling the generated files currently
     * use, and it says nothing about whether the case is repairable — that is a question for the phases that
     * make regeneration verify-then-emit. Its job is to be honest and to arrive early.
     */
    static List<String> scaffolding(Map<String, ApiClass> before, List<Deprecation> deprecated,
                                    List<Break> breaks) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Break b : breaks) {
            if (isScaffolding(before, b.type(), b.member())) out.put(b.display(), b.display());
        }
        for (Deprecation d : deprecated) {
            if (isScaffolding(before, d.type(), d.member())) out.put(d.display(), d.display());
        }
        return List.copyOf(out.values());
    }

    /** Whether the old jar marks this element {@code @Scaffolding} — the type itself, or any overload of it. */
    private static boolean isScaffolding(Map<String, ApiClass> before, String type, String member) {
        ApiClass klass = before.get(type);
        if (klass == null) return false;
        if (member == null || member.isEmpty()) return klass.scaffolding();
        return klass.scaffolding()
                || klass.byName().getOrDefault(member, List.of()).stream().anyMatch(ApiMember::scaffolding);
    }

    /**
     * Members this bot calls that the target marks {@code @Deprecated}, each with what the SDK itself says
     * to use instead.
     *
     * <p>The replacement is read the same way every other answer here is — through
     * {@link SdkRedirects#redirectFor}, against the same jar — so a row that promises a move is a row the
     * repair pass will actually make. It is empty in two cases that read alike and are not alike: the member
     * points nowhere, or it points somewhere the shapes refuse. Both leave the user to it, which is what a
     * deprecation is for.
     */
    static List<Deprecation> deprecations(Map<String, ApiClass> before, Map<String, ApiClass> after,
                                          List<Call> calls, SdkPairing pairing) {
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();
        Map<String, String[]> moves = new LinkedHashMap<>();
        for (Call call : calls) {
            ApiClass then = before.get(call.type());
            // Through the pairing, so a renamed-but-deprecated type is still reported: the bot writes the old
            // name, and looking that up in the new jar would find nothing and say nothing.
            ApiClass now = then == null ? after.get(call.type()) : pairing.pairedTo(then, after);
            if (now == null) continue;
            String member = then == null ? call.member() : pairing.memberName(then, now, call.member());
            // Both ends of the pairing are asked, and the origin has to be, because modernising follows the
            // pointer *past* the deprecated element: what the bot writes is the deprecated half, and what it
            // is paired with is precisely the half that is not. Asking only the destination would report
            // nothing at all in the one case this list exists for.
            ApiClass origin = after.get(call.type());
            boolean deprecated = now.deprecated() || now.deprecatedNames().contains(member)
                    || (origin != null
                    && (origin.deprecated() || origin.deprecatedNames().contains(call.member())));
            if (!deprecated) continue;
            String key = call.type() + "#" + call.member();
            sites.computeIfAbsent(key, k -> new ArrayList<>()).add(call.site());
            if (then != null) moves.computeIfAbsent(key, k -> moveText(then, now, call, after, pairing));
        }
        return sites.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("#", 2);
                    String[] move = moves.getOrDefault(e.getKey(), new String[]{"", ""});
                    return new Deprecation(parts[0], parts[1], move[0], move[1], sorted(e.getValue()));
                })
                .sorted(Comparator.comparing(Deprecation::display))
                .toList();
    }

    /**
     * Where a deprecated call would go and what that would cost, as {@code {becomes, repair}} — both empty
     * when the answer is "nowhere".
     *
     * <p>A deprecated <em>type</em> that was replaced has no redirect of its own: nothing about the call
     * changes but the name it is reached through, and that is the file-wide rename's job. It is still an
     * answer the user wants to read, so it is written here in the type sweep's own words.
     */
    private static String[] moveText(ApiClass then, ApiClass now, Call call,
                                     Map<String, ApiClass> after, SdkPairing pairing) {
        SdkMigrationRunner.Redirect redirect = redirectFor(then, now, call, after, pairing);
        if (redirect != null) return new String[]{redirect.display(), repairText(redirect)};
        if (!now.simpleName().equals(then.simpleName())) {
            return new String[]{now.simpleName(),
                    "every use of \"" + then.simpleName() + "\" becomes \"" + now.simpleName() + "\""};
        }
        return new String[]{"", ""};
    }

    /**
     * Calls that would stop compiling, and what Studio will write in their place. Only members the
     * <em>old</em> jar actually had are judged: a call to something neither jar declares is the bot's own code
     * (or an unindexed library), not a break this upgrade causes.
     *
     * <p>A renamed type yields <b>two</b> findings where both apply — one {@link BreakKind#TYPE_RENAMED} for
     * the type, and, for a member that also went, its own finding under the new type. That is the pairing
     * rule made visible: a pointer pairs the element it is written on, and the members of a paired type are
     * still resolved one at a time.
     *
     * <p>A member the target still offers <em>somewhere</em> is listed too, with the redirect as its repair.
     * It is a break — the bot does not compile until the call moves — and one Studio makes itself, which is
     * exactly what the {@code repair} sentence is for.
     */
    static List<Break> breaks(Map<String, ApiClass> before, Map<String, ApiClass> after,
                              Uses uses, SdkPairing pairing) {
        Map<String, Break> found = new LinkedHashMap<>();
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();

        // The type verdict first, and from the places the bot writes the type *name* — which a call scan
        // never sees. Without this a bot whose only contact with a removed class is `ImageTemplate t;` got no
        // finding at all and was upgraded into something that does not compile.
        for (TypeUse use : uses.types()) {
            ApiClass then = before.get(use.type());
            if (then != null) typeVerdict(found, sites, then, after, pairing, use.site());
        }

        for (Call call : uses.calls()) {
            ApiClass then = before.get(call.type());
            // In the shape the bot uses it: a name the old jar had only as a method is not evidence that
            // this file's `Foo.NAME` was ever SDK, and vice versa.
            if (then == null || !declares(then, call.isField(), call.member())) continue;

            if (typeVerdict(found, sites, then, after, pairing, call.site())) continue;
            ApiClass now = pairing.pairedTo(then, after);

            // A call that still resolves under the same spelling is no break at all — deprecated or not,
            // pointed somewhere or not, it compiles, and a deprecation is not a break. One that resolves
            // only somewhere else is a break with a redirect for a repair, and is still listed, because the
            // bot does not compile until the redirect is made.
            if (offers(now, call.member(), call.argCount())) continue;
            SdkMigrationRunner.Redirect redirect = redirectFor(then, now, call, after, pairing);

            BreakKind kind;
            String detail = "";
            if (redirect != null
                    && (!call.member().equals(redirect.toMember()) || redirect.toTypeFqn() != null)) {
                // The old spelling is gone; the redirect says where it went, which is what the user needs
                // to read even though nothing here has to be changed by hand.
                kind = call.isField() ? BreakKind.FIELD_REMOVED : BreakKind.MEMBER_REMOVED;
                detail = "now " + redirect.display();
            } else if (declares(now, call.isField(), call.member())) {
                kind = BreakKind.SIGNATURE_CHANGED;
                detail = "was " + signatures(then, call.member()) + " — now "
                        + signatures(now, call.member());
            } else {
                // Covers a field turned into a method (and the reverse) as well as an outright removal —
                // every one of them stops this call site compiling.
                kind = call.isField() ? BreakKind.FIELD_REMOVED : BreakKind.MEMBER_REMOVED;
            }
            record(found, sites, new Break(call.type(), call.member(), kind, detail,
                    redirect == null ? repairText(returnTypeOf(then, call)) : repairText(redirect),
                    List.of()), call.site());
        }

        return found.entrySet().stream()
                .map(e -> {
                    Break b = e.getValue();
                    return new Break(b.type(), b.member(), b.kind(), b.detail(), b.repair(),
                            sorted(sites.get(e.getKey())));
                })
                .sorted(Comparator.comparing(Break::display))
                .toList();
    }

    /**
     * The members this bot calls that became <b>two</b>, and every call of them — see {@link Choice}.
     *
     * <p>It asks the same question {@link #breaks} and {@link #deprecations} ask, of the same graph, and
     * keeps only the answers with more than one candidate: a member with one is not a question. So a split
     * appears in this list <em>as well as</em> in whichever of those two describes what happened to it, and
     * neither of their verdicts moves.
     */
    static List<Choice> splits(Map<String, ApiClass> before, Map<String, ApiClass> after,
                               Uses uses, SdkPairing pairing) {
        Map<String, List<Call>> byMember = new LinkedHashMap<>();
        for (Call call : uses.calls()) {
            byMember.computeIfAbsent(call.type() + "#" + call.member() + "#" + call.argCount(),
                    k -> new ArrayList<>()).add(call);
        }

        List<Choice> out = new ArrayList<>();
        byMember.values().forEach(calls -> {
            Call first = calls.getFirst();
            ApiClass then = before.get(first.type());
            if (then == null || !declares(then, first.isField(), first.member())) return;
            ApiClass now = pairing.pairedTo(then, after);
            if (now == null) return;                        // a removed type refuses the upgrade outright

            // The candidates are a property of the member, so they are worked out once: only which of them
            // *fit* varies from site to site, and that is the filter below.
            List<Candidate> candidates = redirectsFor(then, now, first, after, pairing);
            if (candidates.size() < 2) return;

            List<Site> sites = calls.stream()
                    .map(call -> new Site(call.site(), fittingAt(candidates, call)))
                    .sorted(Comparator.comparing(s -> s.site().toString()))
                    .toList();
            out.add(new Choice(first.type(), first.member(), first.argCount(), candidates,
                    candidates.getFirst().redirect().note(), sites));
        });
        return out.stream().sorted(Comparator.comparing(Choice::display)).toList();
    }

    /**
     * Records what became of {@code then} as a type, at one site — true when it is <b>gone</b>, which is the
     * caller's cue that there is nothing further to say about that site.
     *
     * <p>One place builds the two type findings because two loops now reach them: a call written on the type,
     * and a place the type is written on its own. Both are the same fact about the same class, so both file
     * into the same finding and the sites simply accumulate.
     */
    private static boolean typeVerdict(Map<String, Break> found, Map<String, List<CallSite>> sites,
                                       ApiClass then, Map<String, ApiClass> after, SdkPairing pairing,
                                       CallSite site) {
        ApiClass now = pairing.pairedTo(then, after);
        if (now == null) {
            record(found, sites, new Break(then.simpleName(), "", BreakKind.TYPE_REMOVED, "",
                    "nothing — this one has to be changed by hand", List.of()), site);
            return true;
        }
        // A type paired elsewhere while its own name survives in the target is not a break — the bot goes on
        // compiling. That is a modernisation (a deprecated class pointed at its successor), and it belongs on
        // the deprecation list, where the rename is offered rather than announced.
        if (!now.simpleName().equals(then.simpleName()) && !after.containsKey(then.simpleName())) {
            record(found, sites, new Break(then.simpleName(), "", BreakKind.TYPE_RENAMED,
                    "now " + now.simpleName(),
                    "every use of \"" + then.simpleName() + "\" becomes \"" + now.simpleName() + "\"",
                    List.of()), site);
        }
        return false;
    }

    private static void record(Map<String, Break> found, Map<String, List<CallSite>> sites,
                               Break unsited, CallSite site) {
        String key = unsited.type() + "#" + unsited.member() + "#" + unsited.kind();
        found.putIfAbsent(key, unsited);
        sites.computeIfAbsent(key, k -> new ArrayList<>()).add(site);
    }

    /**
     * What the user is told a redirected call becomes.
     *
     * <p>Three sentences, because there are three outcomes and the difference matters to whoever reads the
     * dialog: a plain rename is complete and says so; a redirect that changed shape is complete but wants
     * looking at; and one that could not be used everywhere says <em>where</em> it could not, since that is
     * the half the user has to finish.
     *
     * <p><b>The author's own sentence, where there is one, is appended verbatim and never rewritten.</b>
     * Everything above it is Studio restating what the diff already showed; the note is the one thing in the
     * dialog that says <em>why</em>, and it is the only text here Studio did not write.
     */
    static String repairText(SdkMigrationRunner.Redirect redirect) {
        String repair = mechanicsOf(redirect);
        return redirect.note().isBlank() ? repair : repair + " — " + redirect.note();
    }

    /** The half of {@link #repairText(SdkMigrationRunner.Redirect)} that is Studio's own reading of the jars. */
    private static String mechanicsOf(SdkMigrationRunner.Redirect redirect) {
        String was = CTOR.equals(redirect.member())
                ? "new " + redirect.type() : redirect.type() + "." + redirect.member();
        List<String> what = new ArrayList<>();
        if (!redirect.display().equals(was)) what.add("becomes " + redirect.display());

        long gained = redirect.arguments().stream().filter(ArgumentEdit.Literal.class::isInstance).count();
        long dropped = Math.max(0, redirect.argCount())
                - redirect.arguments().stream().filter(ArgumentEdit.Keep.class::isInstance).count();
        if (gained > 0) what.add("gains " + inputs(gained) + ", filled in with a default value");
        if (dropped > 0) what.add("loses " + inputs(dropped) + " this call passes");
        // Nothing about the call changes but the type it is reached through, which the file-wide type
        // rename is already doing — so there is nothing more to promise here.
        if (what.isEmpty()) what.add("is carried across as it is");

        String head = String.join(" and ", what);
        if (!redirect.expressionSafe()) {
            return head + " where it stands on its own, and becomes " + defaultTextOf(redirect.returnType())
                    + " where its result is used — those functions are marked for your review";
        }
        return redirect.needsReview() ? head + ", and the function is marked for your review" : head;
    }

    private static String inputs(long count) {
        return count + (count == 1 ? " input" : " inputs");
    }

    /** What the user is told will stand in — the one sentence that says the model out loud. */
    static String repairText(String returnType) {
        return "void".equals(returnType)
                ? "the call is removed, and the function is marked for your review"
                : "replaced with " + defaultTextOf(returnType) + ", and the function is marked for your review";
    }

    /** The default this type gets, spelled as the rewrite will spell it — the rewriter's own switch. */
    private static String defaultTextOf(String type) {
        return CallMigrator.literalDefaultText(type);
    }

    private static List<CallSite> sorted(Collection<CallSite> sites) {
        // Deduped by **line**, not by the whole record: these lists are read, and `ImageTemplate t = new
        // ImageTemplate();` is one place to look however many uses it holds. A site carries its offset for
        // the one reader that needs to tell two calls on one line apart — a Choice's menu — and that list is
        // built separately, precisely because it must not dedupe.
        Map<String, CallSite> byLine = new LinkedHashMap<>();
        sites.stream()
                .sorted(Comparator.comparing(CallSite::file).thenComparingInt(CallSite::line))
                .forEach(site -> byLine.putIfAbsent(site.file() + ":" + site.line(), site));
        return List.copyOf(byLine.values());
    }
}
