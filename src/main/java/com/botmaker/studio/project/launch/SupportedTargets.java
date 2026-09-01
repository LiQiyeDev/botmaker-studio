package com.botmaker.studio.project.launch;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The launch targets a bot's author says it <em>works on</em> — the {@code launch.supported} key, and the
 * publish-time half of a distinction the project only had one side of.
 *
 * <p>{@code launch.target} answers "what does <b>this machine</b> start?" and is therefore the publisher's own
 * machine talking: their Steam appId, their emulator instance. A bot, though, targets a <em>game</em>, and the
 * same game is a different launch on each platform — a Steam appId is meaningless to someone who owns it on
 * Epic, and useless to someone playing the Android build. So the two facts are stored separately: this set
 * travels with a published bot, {@code launch.target} is stripped from the archive
 * ({@link com.botmaker.studio.sharing.ProjectArchive}) and re-chosen by whoever installs it.
 *
 * <p><b>Empty means "not declared", not "nothing works".</b> Every project predating this key has no
 * declaration, and an undeclared bot must stay fully usable — so {@link #supports} answers {@code true} for
 * everything until an author says otherwise, and only a declared set narrows anything.
 *
 * <p><b>And a declared set is advice, not a gate.</b> An author can only test the launchers they own, so this
 * set is the ones they <em>tried</em> — never a statement that the rest fail. Every reader of it says "tested
 * on" and marks the undeclared kinds; none of them refuses one. {@link #supports} is therefore a question
 * about the <em>declaration</em> ("did they say they tested this?"), not about the launch, and a caller that
 * turns a {@code false} into a disabled control is reading it wrong.
 *
 * <p>Typed over {@link LaunchKind} (the repo's closed-set rule) rather than free-form strings, so a kind that
 * is launchable is also declarable and the two cannot drift. The persisted and JSON forms are still the
 * enum's stable wire {@link LaunchKind#id()}s, and both parses are <b>total</b>: a kind a newer Studio knows
 * and this one doesn't is dropped rather than throwing, because this value arrives from a hand-editable
 * properties file and from a gallery index every Studio version reads.
 *
 * @param kinds the declared kinds, or empty for "not declared"
 */
public record SupportedTargets(Set<LaunchKind> kinds) {

    /**
     * The {@code botmaker-project.properties} key this is persisted under — deliberately <em>not</em> in
     * shared's {@code ProjectProperties} with the rest of the file's keys, because no bot ever reads it. It is
     * written by the publish dialog and read by Studio's launch-target dialog, both of which live here;
     * putting it in shared would buy nothing and cost an ordered shared release.
     */
    public static final String KEY = "launch.supported";

    public SupportedTargets {
        Set<LaunchKind> clean = EnumSet.noneOf(LaunchKind.class);
        if (kinds != null) {
            for (LaunchKind kind : kinds) {
                if (kind != null && kind != LaunchKind.UNKNOWN) clean.add(kind);
            }
        }
        kinds = clean;
    }

    /** No declaration: every launch kind is allowed. The state every project starts in. */
    public static SupportedTargets any() {
        return new SupportedTargets(Set.of());
    }

    public static SupportedTargets of(Collection<LaunchKind> kinds) {
        return new SupportedTargets(kinds == null ? Set.of() : Set.copyOf(kinds));
    }

    /** The kinds an author can declare — every real one, in menu order. {@code UNKNOWN} is not a choice. */
    public static List<LaunchKind> selectable() {
        return Arrays.stream(LaunchKind.values()).filter(k -> k != LaunchKind.UNKNOWN).toList();
    }

    /** True when an author has narrowed the set; false for a project that never said. */
    public boolean declared() {
        return !kinds.isEmpty();
    }

    /** Whether {@code kind} is allowed — always true while nothing is declared. */
    public boolean supports(LaunchKind kind) {
        return !declared() || (kind != null && kinds.contains(kind));
    }

    /** Whether a raw {@code launch.target} spec names an allowed kind. An unparseable spec is allowed. */
    public boolean supportsSpec(String spec) {
        LaunchSpec parsed = LaunchSpec.parse(spec);
        return parsed == null || supports(parsed.kind());
    }

    /** The wire ids, in enum order — the JSON form in the gallery index and the halves of {@link #spec()}. */
    @JsonValue
    public List<String> ids() {
        return selectable().stream().filter(kinds::contains).map(LaunchKind::id).toList();
    }

    /** The comma-separated {@code launch.supported} value, or {@code null} when nothing is declared. */
    public String spec() {
        return declared() ? String.join(",", ids()) : null;
    }

    /** Human-readable, e.g. {@code "Steam game, Emulator app"} — {@code "any launch target"} when undeclared. */
    public String describe() {
        return declared()
                ? selectable().stream().filter(kinds::contains).map(LaunchKind::displayName)
                        .collect(Collectors.joining(", "))
                : "any launch target";
    }

    /** Parses a list of wire ids (the gallery-index form). Unknown ids are dropped; {@code null} → undeclared. */
    @JsonCreator
    public static SupportedTargets fromIds(List<String> ids) {
        if (ids == null) return any();
        List<LaunchKind> parsed = new ArrayList<>();
        for (String id : ids) parsed.add(LaunchKind.fromId(id));
        return of(parsed);
    }

    /** Parses the comma-separated properties value. Blank or {@code null} → undeclared. */
    public static SupportedTargets parse(String spec) {
        if (spec == null || spec.isBlank()) return any();
        return fromIds(Arrays.asList(spec.split(",")));
    }
}
