package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.SemVer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * The bot scaffold, as the SDK ships it — the templates under {@code botmaker-templates/} in an SDK jar, and
 * the tokens Studio is allowed to fill in.
 *
 * <h2>Why the templates are over there</h2>
 *
 * <p>Studio used to hold the scaffold as text blocks, and <b>a text block cannot be asked what it names</b>.
 * Everything built to work around that — a declared surface, a JDT visitor to reconstruct it, a file two
 * repositories had to keep in step — existed to recover by parsing the answer a compiler gives for free. The
 * templates are compiling Java in the SDK's own build now, so the SDK's compiler is what proves the frame is
 * right, and this class only has to fetch the text and drop the project's own data into it.
 *
 * <h2>Fill known, ignore unknown</h2>
 *
 * <p>A hole is a pair of fenced comments with a compiling default between them, carrying the generation of
 * that hole's shape:
 *
 * <pre>{@code
 * private static final int MAX_STEPS = /*<STUDIO:MAX_STEPS:1>*&#47; 1000 /*</STUDIO:MAX_STEPS:1>*&#47;;
 * }</pre>
 *
 * <p>Filling one replaces the whole region, fences included. Leaving one alone leaves the default, and the
 * file still compiles — which is the entire forward-compatibility rule in the additive direction: a
 * <em>newer</em> SDK may add holes this Studio has never heard of, and this Studio simply does not fill them.
 * The reverse is a refusal: {@link #render} names the hole when the manifest does not declare one Studio
 * needs, because a fragment with nowhere to go is data silently dropped from the user's project.
 *
 * <h2>The generation, and why the match is exact</h2>
 *
 * <p>The number covers the direction that is not additive. A hole whose <em>shape</em> changed — different
 * arguments, a different arrangement — keeps its name and takes the next generation. Every name in a fragment
 * still resolving is exactly what made that change invisible: the API pointer pair covers a member being
 * renamed and says nothing about an arrangement moving, so an older Studio wrote last year's text into this
 * year's frame and it compiled. {@link ScaffoldToken} carries the generations this Studio can produce and the
 * lookup is an exact {@code name:generation} match — never a range, never a nearest-older fallback, whose
 * gaps are precisely what would fill silently wrong.
 *
 * <h2>Which jar</h2>
 *
 * <p>{@link #forVersionNewerThanStudio} mirrors {@code ScaffoldFacts}' gate for the same reason: below
 * Studio's own baseline the answer cannot differ from {@link #bundled()}, so asking would buy a jar resolve —
 * possibly a download — on every project creation and every save of the flow. Above it, the pinned jar's
 * templates are the right ones by construction: the SDK owns the frame, so a newer SDK's frame is what a bot
 * pinned to it should be built from.
 *
 * <p>Every path that cannot produce templates falls back to {@link #bundled()} rather than failing — an
 * unresolvable jar, an SDK older than the templates, a manifest in a format this Studio cannot parse. That is
 * the same fail-open rule {@code ScaffoldCheck} follows, and what makes it safe is the floor: an SDK that
 * predates the scaffold is refused by {@link #requireFloor} before any of this is reached, so the fallback
 * only ever lands on templates the pinned jar could actually carry.
 */
public final class TemplateStore {

    /** The directory an SDK jar ships its templates in, manifest included. */
    public static final String ROOT = "botmaker-templates";

    /** The manifest, relative to {@link #ROOT}. */
    private static final String MANIFEST = "manifest.txt";

    /** The manifest shape this Studio can read. A higher one means the columns changed. */
    private static final int FORMAT = 2;

    private static final Pattern OPEN = Pattern.compile("/\\*<STUDIO:([A-Z_]+:\\d+)>\\*/");

    /** A whole fenced region with its default still in it: {@code /*<X:1>*}{@code / … /*</X:1>*}{@code /}. */
    private static final Pattern FENCED =
            Pattern.compile("/\\*<STUDIO:([A-Z_]+:\\d+)>\\*/(.*?)/\\*</STUDIO:\\1>\\*/", Pattern.DOTALL);

    /** A line of nothing but spaces — what the indent in front of an emptied token's fences leaves. */
    private static final Pattern BLANK_LINE = Pattern.compile("(?m)^[ \t]+$");

    /** Three or more newlines: what a token filled with nothing leaves behind. See {@link #render}. */
    private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");

    /** Whether Studio writes a template once or on every model change. */
    public enum Kind {
        /** Written at creation and the user's thereafter: the entry point, GoHome, Popups, the stubs. */
        SEED,
        /** Rewritten wholesale on every model change: Activities, ActivityRegistry, FlowDriver. */
        REGENERATED
    }

    /**
     * One template as the manifest declares it, with the source it points at already read.
     *
     * @param id        the manifest's name for it — what {@link TemplateStore#require} is asked for
     * @param kind      written once, or rewritten
     * @param target    the file name in the bot, with {@code ${CLASS}} / {@code ${ACTIVITY}} still in it
     * @param className the template's own class, taken from its file name — the name {@link #render}
     *                  rewrites when the target says the bot calls it something else
     * @param holes     every hole the template declares, as {@code NAME:generation}; a fill of anything else
     *                  is a refusal
     * @param source    the template's Java, verbatim
     */
    public record Template(String id, Kind kind, String target, String className,
                           Set<String> holes, String source) {

        public Template {
            holes = Set.copyOf(holes);
        }

        /**
         * Which generation of {@code token}'s shape this template wants, or empty when it has no such hole.
         *
         * <p>One generation per hole, never a range: a project pins one SDK, so this template is the only
         * frame that answers, and there is nothing to choose between.
         */
        public OptionalInt generationOf(ScaffoldToken token) {
            String prefix = token.name() + ":";
            for (String hole : holes) {
                if (hole.startsWith(prefix)) {
                    try {
                        return OptionalInt.of(Integer.parseInt(hole.substring(prefix.length())));
                    } catch (NumberFormatException ignored) {
                        return OptionalInt.empty();
                    }
                }
            }
            return OptionalInt.empty();
        }
    }

    /**
     * A test seam: every fill that actually reached a template, as {@code NAME:generation → text}.
     *
     * <p>{@code ScaffoldHolesTest} has to answer "is every hole the SDK declares ever <em>compiled</em>, or
     * merely produced" — and it cannot get that from the rendered files. {@link #stripUnfilled} drops the
     * fences whether the hole was filled or not, precisely so a bot's source carries none of our machinery,
     * so a filled hole and its untouched default are indistinguishable in the output. The only place the
     * answer exists is here, between the two.
     *
     * <p>Null in every real run, and never read by anything under {@code main}.
     */
    static volatile BiConsumer<Template, Map<String, String>> fillObserver;

    private final String templatePackage;
    private final Map<String, Template> byId;

    private TemplateStore(String templatePackage, Map<String, Template> byId) {
        this.templatePackage = templatePackage;
        this.byId = byId;
    }

    // ---- the floor ------------------------------------------------------------------------------------

    /**
     * Refuses, by name, an SDK too old to carry the scaffold — the one gate in front of every path that
     * writes a generated file.
     *
     * <p>The fallbacks above are all fail-open, and that is right for them: an unresolvable jar or an
     * unreadable manifest is a degraded probe, and degrading to the templates Studio ships with produces a
     * correct bot in every case where the SDK is recent enough to compile them. This one is not a probe. An
     * SDK below {@code MavenService.MIN_SDK_VERSION} ships no {@code botmaker-templates/} <em>and</em> none of
     * the injection API those templates call, so falling open would write {@code FlowGraph.of(…)} into a
     * project whose jar has never heard of {@code FlowGraph} — a bot that does not compile, produced silently,
     * which is the one outcome the whole verify-then-emit path exists to prevent.
     *
     * <p>It is stated as a version comparison rather than discovered by looking in the jar on purpose. The
     * absence of a template directory is also what a fixture, a stub or the wrong artifact looks like, and
     * those are exactly the cases {@link #forJar} must keep falling open on. The pom's own number is the only
     * evidence that distinguishes "this SDK predates the scaffold" from "this file is not the SDK".
     *
     * <p>Anything {@link SemVer} cannot parse passes, for the same reason {@code SdkSurfaceService
     * .isBelowMinimum} lets it: the unparseable version that occurs in practice is {@code 0.0.0-SNAPSHOT},
     * the local dev build a maintainer pins deliberately, and refusing it would break every dev-run.
     *
     * @throws ScaffoldUnsupported naming the version and the way out. Thrown before anything is written.
     */
    public static void requireFloor(String version) throws ScaffoldUnsupported {
        if (version == null || version.isBlank()) return;
        String v = version.trim();
        if (!SemVer.isValid(v) || !SemVer.isValid(MavenService.MIN_SDK_VERSION)
                || SemVer.compare(v, MavenService.MIN_SDK_VERSION) >= 0) {
            return;
        }
        throw new ScaffoldUnsupported("This bot uses SDK " + v + ", and the files BotMaker generates for you"
                + " — Activities, ActivityRegistry and FlowDriver — are built from templates that SDK "
                + MavenService.MIN_SDK_VERSION + " was the first to ship. Nothing has been changed. Use"
                + " Project ▸ Upgrade SDK… to move this bot to " + MavenService.MIN_SDK_VERSION
                + " or newer; everything you wrote yourself is left exactly as it is.");
    }

    // ---- where the templates come from ----------------------------------------------------------------

    /**
     * The templates of the SDK jar Studio itself was built against — the ones on its own classpath.
     *
     * <p>This is the baseline in the same sense {@code MavenService.SDK_FALLBACK_VERSION} is: it is what a
     * fresh project pins, and what every fallback below lands on.
     */
    public static TemplateStore bundled() {
        return read(path -> {
            try (InputStream in = TemplateStore.class.getResourceAsStream("/" + ROOT + "/" + path)) {
                return in == null ? Optional.empty()
                        : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).orElseGet(() -> new TemplateStore("", Map.of()));
    }

    /**
     * The pinned SDK's templates when {@code version} is newer than Studio's baseline, and {@link #bundled()}
     * otherwise — see the class javadoc for why the gate is worth having.
     *
     * <p><b>Blocking</b> above the gate: may resolve, and so may download, a jar. Call it off the FX thread.
     *
     * @param projectDir the project whose {@code <repositories>} to resolve through, or null at creation time
     */
    public static TemplateStore forVersionNewerThanStudio(Path projectDir, String version) {
        if (version == null || version.isBlank()) return bundled();
        String v = version.trim();
        if (!SemVer.isValid(v) || !SemVer.isValid(MavenService.SDK_FALLBACK_VERSION)
                || SemVer.compare(v, MavenService.SDK_FALLBACK_VERSION) <= 0) {
            return bundled();
        }
        Optional<Path> jar = projectDir == null
                ? MavenService.resolveSdkJar(v)
                : MavenService.resolveSdkJar(projectDir, v);
        return jar.map(TemplateStore::forJar).orElseGet(TemplateStore::bundled);
    }

    /**
     * The templates in one SDK jar, or {@link #bundled()} when it has none — the seam the tests build
     * against jars made on the spot, and the path an SDK older than the templates takes.
     */
    public static TemplateStore forJar(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return read(path -> {
                ZipEntry entry = file.getEntry(ROOT + "/" + path);
                if (entry == null) return Optional.empty();
                try (InputStream in = file.getInputStream(entry)) {
                    return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).orElseGet(TemplateStore::bundled);
        } catch (IOException | RuntimeException e) {
            System.out.println("   (no scaffold templates in " + jar.getFileName()
                    + ": " + e.getMessage() + " — using the ones Studio ships with)");
            return bundled();
        }
    }

    // ---- the manifest ---------------------------------------------------------------------------------

    /**
     * Parses the manifest {@code source} points at and reads every template it names, or empty when there is
     * no manifest to read or it is in a shape this Studio does not know.
     *
     * <p>The manifest is read rather than the directory listed, and that is the point of having one: a
     * listing would give the files and nothing else, whereas the frame Studio has to know — which template is
     * written once and which is rewritten, what the bot calls the file, and which tokens exist at all — is
     * exactly what a {@code .java} file cannot say about itself. It is also the one thing checked at both
     * ends: the SDK's {@code ScaffoldTemplatesTest} fails its own build when a declared token is not a
     * matched pair of fences, or a fence is not declared.
     */
    private static Optional<TemplateStore> read(Reader source) {
        Optional<String> manifest = source.read(MANIFEST);
        if (manifest.isEmpty()) return Optional.empty();

        String templatePackage = "";
        Map<String, Template> byId = new LinkedHashMap<>();
        for (String line : manifest.get().split("\n")) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            String[] parts = text.split("\\s+");
            switch (parts[0]) {
                case "format" -> {
                    if (parts.length < 2 || !parts[1].equals(Integer.toString(FORMAT))) {
                        System.out.println("   (scaffold template manifest is format " + text
                                + ", this Studio reads " + FORMAT + " — using the ones it ships with)");
                        return Optional.empty();
                    }
                }
                case "package" -> {
                    if (parts.length >= 2) templatePackage = parts[1];
                }
                case "template" -> {
                    if (parts.length < 6) return Optional.empty();
                    Optional<String> java = source.read(parts[3]);
                    if (java.isEmpty()) return Optional.empty();
                    String file = parts[3].substring(parts[3].lastIndexOf('/') + 1);
                    byId.put(parts[1], new Template(parts[1], Kind.valueOf(parts[2]), parts[4],
                            file.substring(0, file.length() - ".java".length()),
                            holesOf(parts[5]), java.get()));
                }
                default -> { /* a record a newer SDK added: ignored, exactly as an unknown token is */ }
            }
        }
        return byId.isEmpty() ? Optional.empty()
                : Optional.of(new TemplateStore(templatePackage, Map.copyOf(byId)));
    }

    /** The manifest's hole column: {@code -} for none, otherwise comma-separated {@code NAME:generation}. */
    private static Set<String> holesOf(String column) {
        if (column.equals("-")) return Set.of();
        return new LinkedHashSet<>(List.of(column.split(",")));
    }

    /** One template's text by its path under {@link #ROOT}, or empty when the jar has no such entry. */
    @FunctionalInterface
    private interface Reader {
        Optional<String> read(String path);
    }

    // ---- asking for one -------------------------------------------------------------------------------

    /** Whether any templates were found at all — false only for an SDK jar that predates them. */
    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** Every template this SDK declares — what {@code ScaffoldHolesTest} walks to collect the hole keys. */
    public Collection<Template> templates() {
        return byId.values();
    }

    /**
     * The template {@code id} names.
     *
     * @throws ScaffoldUnsupported when this SDK has no such template — an honest refusal before a file
     *                             exists, rather than a bot missing one of its files
     */
    public Template require(String id) throws ScaffoldUnsupported {
        Template template = byId.get(id);
        if (template == null) {
            throw new ScaffoldUnsupported("The SDK this project pins ships no \"" + id + "\" scaffold "
                    + "template, so Studio cannot write that file. Pick an SDK version this Studio knows, or "
                    + "update Studio (Help ▸ Check for updates).");
        }
        return template;
    }

    // ---- filling one ----------------------------------------------------------------------------------

    /**
     * {@code template}, with the project's own data in it: every {@code tokens} entry filled, the template
     * package rewritten to the bot's, and the class renamed when the bot calls it something else.
     *
     * <p>The three steps are ordered, and the order matters. Tokens first, because a fragment Studio emits is
     * already written in the project's own names and must not be rewritten again. The package next, which
     * also repoints the {@code activities} sub-package and any import of a sibling template. The class name
     * last, when {@code className} differs from the template's own — the entry point and every activity stub
     * are the same template under the name the user chose.
     *
     * @param className the class the bot calls it, or null to keep the template's own
     * @param fills     hole → the text to put between (and instead of) its fences
     * @throws ScaffoldUnsupported when a hole Studio needs is not one this template declares, or declares at
     *                             a <em>generation</em> Studio cannot produce. Neither is ignorable in the
     *                             direction an <em>unknown</em> hole is: an unfilled hole leaves a compiling
     *                             default, whereas a fragment with nowhere to go is the user's own flow or
     *                             parameters silently dropped — and a fragment written into a shape it no
     *                             longer fits is worse still, because it compiles
     */
    public String render(Template template, String packageName, String className,
                         Map<ScaffoldToken, String> fills) throws ScaffoldUnsupported {
        List<String> missing = new ArrayList<>();
        List<String> unknownShape = new ArrayList<>();
        Map<String, String> keyed = new LinkedHashMap<>();
        for (Map.Entry<ScaffoldToken, String> fill : fills.entrySet()) {
            ScaffoldToken token = fill.getKey();
            OptionalInt generation = template.generationOf(token);
            if (generation.isEmpty()) {
                missing.add(token.name());
            } else if (!token.canFill(generation.getAsInt())) {
                unknownShape.add(token.key(generation.getAsInt()));
            } else {
                keyed.put(token.key(generation.getAsInt()), fill.getValue());
            }
        }
        if (!missing.isEmpty()) {
            throw new ScaffoldUnsupported("The SDK this project pins ships a \"" + template.id()
                    + "\" scaffold template with nowhere to put " + String.join(", ", missing)
                    + ", so part of your project could not be written into it. Pick an SDK version this "
                    + "Studio knows, or update Studio (Help ▸ Check for updates).");
        }
        if (!unknownShape.isEmpty()) {
            throw new ScaffoldUnsupported("The SDK this project pins has changed the shape of "
                    + String.join(", ", unknownShape) + " in its \"" + template.id() + "\" scaffold template,"
                    + " and this Studio only knows how to write the older one. Nothing has been changed —"
                    + " writing what this Studio produces would fit the frame and mean something else."
                    + " Update Studio (Help ▸ Check for updates), or pin an SDK version this Studio knows.");
        }

        BiConsumer<Template, Map<String, String>> observer = fillObserver;
        if (observer != null) observer.accept(template, keyed);

        String source = template.source();
        for (Map.Entry<String, String> token : keyed.entrySet()) {
            source = fill(source, token.getKey(), token.getValue());
        }
        source = stripUnfilled(source);
        source = source.replace(templatePackage, "com." + packageName);
        if (className != null && !className.equals(template.className())) {
            source = source.replaceAll("\\b" + Pattern.quote(template.className()) + "\\b",
                    Matcher.quoteReplacement(className));
        }
        // A token filled with nothing — no activities, so no import and no singletons — leaves the line its
        // fences sat on behind, holding the indent that was in front of them. Tidying that here is cheaper
        // than teaching every emitter whether its own region spans whole lines, and it is why a project with
        // no activities produces the same file shape as one with three.
        return BLANK_RUN.matcher(BLANK_LINE.matcher(source).replaceAll("")).replaceAll("\n\n");
    }

    /**
     * Drops the fences of every token that was not filled, keeping the default between them.
     *
     * <p>Ignoring a token means "the default stands", and the default standing is what makes an older Studio
     * safe against a newer SDK's tokens. The <em>markers</em> standing is not part of that: a bot's own source
     * has no business carrying Studio's machinery, and a seed file is the user's to edit from the moment it is
     * written. An inline default is trimmed ({@code = 1000;}, not {@code =  1000 ;}); a multi-line one keeps
     * its indentation, since that is what its shape depends on.
     */
    private static String stripUnfilled(String source) {
        return FENCED.matcher(source).replaceAll(match -> {
            String content = match.group(2);
            return Matcher.quoteReplacement(content.contains("\n") ? content : content.strip());
        });
    }

    /**
     * Replaces one hole's whole region, fences included, leaving a hole that is not {@code key} alone.
     *
     * @param key {@code NAME:generation} — the exact spelling in the fences, never a name on its own
     */
    private static String fill(String source, String key, String value) {
        String open = "/*<STUDIO:" + key + ">*/";
        String close = "/*</STUDIO:" + key + ">*/";
        int from = source.indexOf(open);
        int to = source.indexOf(close);
        // Neither can be absent: the manifest declared the token, and the SDK's own build fails when a
        // declared token is not one matched pair of fences. Checked anyway, because the alternative to
        // noticing here is a substring call on -1.
        if (from < 0 || to < from) return source;
        return source.substring(0, from) + value + source.substring(to + close.length());
    }

    /** Everything {@code OPEN} finds in {@code source}: the holes still unfilled, for the tests to assert on. */
    static Set<String> unfilledTokens(String source) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = OPEN.matcher(source);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
