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
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * The bot scaffold, as the SDK ships it — the templates under {@code botmaker-templates/} in an SDK jar, and
 * the project's own data dropped into the holes they leave.
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
 * <p>A hole is a pair of fenced comments with a compiling default between them:
 *
 * <pre>{@code
 * private static final int MAX_STEPS = /*<STUDIO:MAX_STEPS>*&#47; 1000 /*</STUDIO:MAX_STEPS>*&#47;;
 * }</pre>
 *
 * <p>Filling one replaces the whole region, fences included. Leaving one alone leaves the default, and the
 * file still compiles — which is the entire forward-compatibility rule in the additive direction: a
 * <em>newer</em> SDK may add holes this Studio has never heard of, and this Studio simply does not fill them.
 * The reverse is a refusal: {@link #render} names the hole when the template has nowhere to put a fragment
 * Studio produced, because a fragment with nowhere to go is data silently dropped from the user's project.
 *
 * <h2>What this class no longer does</h2>
 *
 * <p>There was a negotiation here: per-hole generation numbers in the fences, a surface each side declared,
 * a verify-then-emit pass over the SDK's own jar, and a refusal when the two disagreed. All of it existed
 * because a generated file had <b>two authors</b> — the SDK owned the frame, Studio owned the fragments — and
 * neither repository could read the other. The SDK is becoming the generator, so there is no second author
 * left to disagree with, and the fill is a plain {@code name → text} map again. Between here and there a
 * scaffold element that moves under a Studio too old for it produces a compile error in a generated file
 * rather than a refusal before one is written; that is a known and deliberate interim cost.
 *
 * <h2>Which jar</h2>
 *
 * <p>{@link #forVersionNewerThanStudio} gates on Studio's own baseline: below it the answer cannot differ
 * from {@link #bundled()}, so asking would buy a jar resolve — possibly a download — on every project
 * creation and every save of the flow. Above it, the pinned jar's templates are the right ones by
 * construction: the SDK owns the frame, so a newer SDK's frame is what a bot pinned to it should be built
 * from.
 *
 * <p>Every path that cannot produce templates falls back to {@link #bundled()} rather than failing — an
 * unresolvable jar, an SDK older than the templates, a manifest in a format this Studio cannot parse. What
 * makes that safe is the floor: an SDK that predates the scaffold is refused by {@link #requireFloor} before
 * any of this is reached, so the fallback only ever lands on templates the pinned jar could actually carry.
 */
public final class TemplateStore {

    /** The directory an SDK jar ships its templates in, manifest included. */
    public static final String ROOT = "botmaker-templates";

    /** The manifest, relative to {@link #ROOT}. */
    private static final String MANIFEST = "manifest.txt";

    /** The manifest shape this Studio can read. A higher one means the columns changed. */
    private static final int FORMAT = 3;

    private static final Pattern OPEN = Pattern.compile("/\\*<STUDIO:([A-Z_]+)>\\*/");

    /** A whole fenced region with its default still in it: {@code /*<X>*}{@code / … /*</X>*}{@code /}. */
    private static final Pattern FENCED =
            Pattern.compile("/\\*<STUDIO:([A-Z_]+)>\\*/(.*?)/\\*</STUDIO:\\1>\\*/", Pattern.DOTALL);

    /** A line of nothing but spaces — what the indent in front of an emptied hole's fences leaves. */
    private static final Pattern BLANK_LINE = Pattern.compile("(?m)^[ \t]+$");

    /** Three or more newlines: what a hole filled with nothing leaves behind. See {@link #render}. */
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
     * @param source    the template's Java, verbatim
     */
    public record Template(String id, Kind kind, String target, String className, String source) {

        /** Every hole this template actually declares, read from its own fences. */
        public Set<String> holes() {
            return unfilledTokens(source);
        }
    }

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
     * project whose jar has never heard of {@code FlowGraph} — a bot that does not compile, produced silently.
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
     * @throws IOException naming the version and the way out. Thrown before anything is written.
     */
    public static void requireFloor(String version) throws IOException {
        if (version == null || version.isBlank()) return;
        String v = version.trim();
        if (!SemVer.isValid(v) || !SemVer.isValid(MavenService.MIN_SDK_VERSION)
                || SemVer.compare(v, MavenService.MIN_SDK_VERSION) >= 0) {
            return;
        }
        throw new IOException("This bot uses SDK " + v + ", and the files BotMaker generates for you"
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
     * written once and which is rewritten, and what the bot calls the file — is exactly what a {@code .java}
     * file cannot say about itself.
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
                    if (parts.length < 5) return Optional.empty();
                    Optional<String> java = source.read(parts[3]);
                    if (java.isEmpty()) return Optional.empty();
                    String file = parts[3].substring(parts[3].lastIndexOf('/') + 1);
                    byId.put(parts[1], new Template(parts[1], Kind.valueOf(parts[2]), parts[4],
                            file.substring(0, file.length() - ".java".length()), java.get()));
                }
                default -> { /* a record a newer SDK added: ignored, exactly as an unknown hole is */ }
            }
        }
        return byId.isEmpty() ? Optional.empty()
                : Optional.of(new TemplateStore(templatePackage, Map.copyOf(byId)));
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

    /** Every template this SDK declares. */
    public Collection<Template> templates() {
        return byId.values();
    }

    /**
     * The template {@code id} names.
     *
     * @throws IOException when this SDK has no such template — an honest refusal before a file exists,
     *                     rather than a bot missing one of its files
     */
    public Template require(String id) throws IOException {
        Template template = byId.get(id);
        if (template == null) {
            throw new IOException("The SDK this project pins ships no \"" + id + "\" scaffold "
                    + "template, so Studio cannot write that file. Pick an SDK version this Studio knows, or "
                    + "update Studio (Help ▸ Check for updates).");
        }
        return template;
    }

    // ---- filling one ----------------------------------------------------------------------------------

    /**
     * {@code template}, with the project's own data in it: every {@code fills} entry filled, the template
     * package rewritten to the bot's, and the class renamed when the bot calls it something else.
     *
     * <p>The three steps are ordered, and the order matters. Holes first, because a fragment Studio emits is
     * already written in the project's own names and must not be rewritten again. The package next, which
     * also repoints the {@code activities} sub-package and any import of a sibling template. The class name
     * last, when {@code className} differs from the template's own — the entry point and every activity stub
     * are the same template under the name the user chose.
     *
     * @param className the class the bot calls it, or null to keep the template's own
     * @param fills     hole name → the text to put between (and instead of) its fences
     * @throws IOException when a hole Studio needs is not one this template declares. That is not ignorable
     *                     in the direction an <em>unknown</em> hole is: an unfilled hole leaves a compiling
     *                     default, whereas a fragment with nowhere to go is the user's own flow or parameters
     *                     silently dropped
     */
    public String render(Template template, String packageName, String className,
                         Map<String, String> fills) throws IOException {
        Set<String> holes = template.holes();
        List<String> missing = new ArrayList<>();
        for (String name : fills.keySet()) {
            if (!holes.contains(name)) missing.add(name);
        }
        if (!missing.isEmpty()) {
            throw new IOException("The SDK this project pins ships a \"" + template.id()
                    + "\" scaffold template with nowhere to put " + String.join(", ", missing)
                    + ", so part of your project could not be written into it. Pick an SDK version this "
                    + "Studio knows, or update Studio (Help ▸ Check for updates).");
        }

        String source = template.source();
        for (Map.Entry<String, String> fill : fills.entrySet()) {
            source = fill(source, fill.getKey(), fill.getValue());
        }
        source = stripUnfilled(source);
        source = source.replace(templatePackage, "com." + packageName);
        if (className != null && !className.equals(template.className())) {
            source = source.replaceAll("\\b" + Pattern.quote(template.className()) + "\\b",
                    Matcher.quoteReplacement(className));
        }
        // A hole filled with nothing — no activities, so no import and no singletons — leaves the line its
        // fences sat on behind, holding the indent that was in front of them. Tidying that here is cheaper
        // than teaching every emitter whether its own region spans whole lines, and it is why a project with
        // no activities produces the same file shape as one with three.
        return BLANK_RUN.matcher(BLANK_LINE.matcher(source).replaceAll("")).replaceAll("\n\n");
    }

    /**
     * Drops the fences of every hole that was not filled, keeping the default between them.
     *
     * <p>Ignoring a hole means "the default stands", and the default standing is what makes an older Studio
     * safe against a newer SDK's holes. The <em>markers</em> standing is not part of that: a bot's own source
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

    /** Replaces one hole's whole region, fences included, leaving a hole that is not {@code name} alone. */
    private static String fill(String source, String name, String value) {
        String open = "/*<STUDIO:" + name + ">*/";
        String close = "/*</STUDIO:" + name + ">*/";
        int from = source.indexOf(open);
        int to = source.indexOf(close);
        // Neither can be absent: render() has already checked the name against the template's own fences,
        // and the SDK's build fails when a fence is not one matched pair. Checked anyway, because the
        // alternative to noticing here is a substring call on -1.
        if (from < 0 || to < from) return source;
        return source.substring(0, from) + value + source.substring(to + close.length());
    }

    /** Every hole {@code OPEN} finds in {@code source} — what a template declares, read from the text. */
    static Set<String> unfilledTokens(String source) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = OPEN.matcher(source);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
