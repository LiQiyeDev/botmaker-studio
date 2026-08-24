package com.botmaker.studio.services;

import com.botmaker.studio.services.SdkUpgradeService.Highlight;
import com.botmaker.studio.sharing.SemVer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * What the release itself says it gives you, read out of the jar the upgrade is moving to.
 *
 * <p>Every other list on the upgrade report is <b>derived</b> — a diff of two jars, stated as API names.
 * That is a cost sheet, and a cost sheet is not a reason to upgrade: "{@code Mouse.dragTo(…)} is new" tells
 * the user a symbol appeared, never why anybody added it. The one thing that can answer *that* is the
 * author's own sentence, and it exists — in {@code CHANGELOG.md} — so the SDK's build copies that file whole
 * into its own jar as {@value #ENTRY}. Studio already downloads the target jar to diff it, so the answer
 * arrives with no second fetch and, crucially, <b>offline</b>.
 *
 * <p><b>Whole file, not one section</b>, and that is the whole reason the copy is what it is: a bot may jump
 * several releases at once, so the jar has to be able to answer every span ending at its own version. The
 * span is applied here, on read, not there, on write.
 *
 * <h2>Degradation</h2>
 *
 * <p>A jar with no {@value #ENTRY} — every SDK up to v1.0.26, and any tag cut before Phase 5 landed — yields
 * an empty list, and the dialog then looks exactly as it did before this reader existed. That is the plan's
 * standing rule, and it needs no version check: the entry's presence <em>is</em> the probe.
 *
 * <p>The version bounds degrade the same way. A bound {@link SemVer} cannot parse — a project pinned to a
 * local {@code 0.0.0-SNAPSHOT} build is the case that actually happens — simply is not applied, so an
 * unparseable {@code from} shows every section up to the target rather than none. Showing too much is a
 * readable failure; showing nothing looks like a release that changed nothing.
 */
final class SdkWhatsNew {

    /** Where the SDK's own build puts its {@code CHANGELOG.md}. See {@code botmaker-sdk/pom.xml}. */
    static final String ENTRY = "META-INF/botmaker/whats-new.md";

    private SdkWhatsNew() {
    }

    /**
     * The sections of the target jar's changelog that fall in {@code (from, to]}, newest first.
     *
     * <p>Half-open below on purpose: the release the bot is already on is not news. Closed above because the
     * target release is the one being read about.
     */
    static List<Highlight> between(Path jar, String from, String to) {
        return readEntry(jar).map(text -> parse(text, from, to)).orElse(List.of());
    }

    /**
     * The parse, split out so it can be tested against text rather than against a jar.
     *
     * <p>The grammar is only what {@code CHANGELOG.md} actually is: {@code ## [1.0.26] — 2026-08-22} opens a
     * section and every line up to the next {@code ##} is its body. A heading whose bracket is not a version
     * — {@code ## [Unreleased]}, {@code ## Earlier} — opens a section that is simply never in range, so it
     * needs no special case beyond failing to parse.
     */
    static List<Highlight> parse(String markdown, String from, String to) {
        List<Highlight> out = new ArrayList<>();
        String version = null;
        String date = "";
        List<String> body = new ArrayList<>();
        for (String raw : markdown.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.startsWith("## ")) {
                if (version != null && inRange(version, from, to)) out.add(finish(version, date, body));
                String heading = line.substring(3).trim();
                version = versionIn(heading);
                date = dateIn(heading);
                body = new ArrayList<>();
            } else if (version != null) {
                body.add(display(line));
            }
        }
        if (version != null && inRange(version, from, to)) out.add(finish(version, date, body));

        // The file is written newest-first, but a changelog is a hand-edited file and a section inserted in
        // the wrong place must not silently reorder what the user reads. Sorting costs nothing here.
        out.sort((a, b) -> SemVer.compare(b.version(), a.version()));
        return List.copyOf(out);
    }

    /** {@code [1.0.26] — 2026-08-22} → {@code 1.0.26}; anything that is not a version → {@code null}. */
    private static String versionIn(String heading) {
        int open = heading.indexOf('[');
        int close = heading.indexOf(']', open + 1);
        if (open < 0 || close < 0) return null;
        String inside = heading.substring(open + 1, close).trim();
        return SemVer.isValid(inside) ? SdkApiModel.strip(inside) : null;
    }

    /** Everything after the {@code ]}, minus the dash the changelog separates them with. May be blank. */
    private static String dateIn(String heading) {
        int close = heading.indexOf(']');
        if (close < 0) return "";
        String rest = heading.substring(close + 1).trim();
        while (rest.startsWith("—") || rest.startsWith("-") || rest.startsWith("–")) {
            rest = rest.substring(1).trim();
        }
        return rest;
    }

    /**
     * A markdown line as a JavaFX {@link javafx.scene.control.Label} can show it.
     *
     * <p>Only the emphasis markers are removed, and only because a Label renders them literally — {@code
     * **this**} would read as three asterisks and a word. Nothing else is rewritten: the bullet, the
     * indentation and the author's wording all reach the user exactly as written, which is the same promise
     * {@code @ReplacedBy.note()} makes.
     */
    private static String display(String line) {
        return line.replace("**", "").replace("`", "");
    }

    /** Drops the blank lines that padded the section, keeping the ones inside it. */
    private static Highlight finish(String version, String date, List<String> body) {
        int first = 0;
        int last = body.size();
        while (first < last && body.get(first).isBlank()) first++;
        while (last > first && body.get(last - 1).isBlank()) last--;
        return new Highlight(version, date, List.copyOf(body.subList(first, last)));
    }

    /**
     * Whether {@code version} is in {@code (from, to]}, with a bound that does not parse not applied at all.
     *
     * <p>The asymmetry with the deleted {@code inRange} it descends from is deliberate: that one answered
     * {@code true} the moment <em>any</em> of the three failed to parse, which here would show a bot every
     * section in the file the moment its target was a snapshot. Each bound is now dropped on its own.
     */
    private static boolean inRange(String version, String from, String to) {
        String lo = SdkApiModel.strip(from);
        String hi = SdkApiModel.strip(to);
        if (SemVer.isValid(lo) && SemVer.compare(version, lo) <= 0) return false;
        return !SemVer.isValid(hi) || SemVer.compare(version, hi) <= 0;
    }

    /**
     * One entry out of a jar, or empty for every reason — no such entry, not a jar, gone since it resolved.
     *
     * <p>This reader was deleted with {@code migrations.json} and is back deliberately. What it reads is not
     * a mechanism the SDK asks Studio to execute (which is what that file was, and why it went); it is prose
     * the SDK asks Studio to <em>show</em>, and the two failure modes are nothing alike.
     */
    private static Optional<String> readEntry(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(ENTRY);
            if (entry == null) return Optional.empty();
            try (InputStream in = jarFile.getInputStream(entry)) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
