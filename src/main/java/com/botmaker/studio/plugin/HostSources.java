package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.Sources;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.BotSources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Studio's own side of {@link Sources} — the token-aware find-and-replace a plugin renames with.
 *
 * <p><b>Nothing here is new capability.</b> The walk is {@link BotSources}, which already visits every file
 * the bot owns with the open buffer preferred over the disk; the review mark is {@link ReviewMarker}; the
 * snapshot is {@link ProjectVcs}. What this class adds is the shape — the same job {@code TemplateReferences}
 * did, with every mention of what a picture is called taken out of it, so the half that is genuinely the
 * editor's can be reached by a plugin that owns the other half.
 *
 * <p><b>Installed per project, static, one at a time</b>, exactly as {@link HostRuns} is and for the same
 * reason: {@link HostServices} is built ad hoc from a {@code ProjectConfig} at call sites with no
 * {@link ProjectState} in scope, and Studio holds one open project. Between projects a plugin asking gets
 * {@link Sources#NONE}, so a rename attempted with nothing open finds nothing rather than reaching into the
 * project the user just left.
 *
 * <h2>The needle matcher</h2>
 *
 * <p>A needle is tokenized and rebuilt as a pattern: identifiers get word boundaries, string literals are
 * quoted whole, and any two tokens may be separated by whitespace. So {@code Templates.ORE} becomes
 * {@code \bTemplates\b\s*\.\s*\bORE\b} — which is character for character what {@code TemplateReferences}
 * wrote by hand for that one case, now derived from the needle instead of known in advance.
 *
 * <p><b>Line by line, and that is a property rather than a shortcut.</b> A token sequence a rename cares
 * about cannot span a line in any source a person wrote, and working line by line is what lets the review
 * mark be worked out afterwards from line numbers ({@link ReviewMarker#markLines}) — on a file that does not
 * parse, which is the file the mark matters most on.
 */
public final class HostSources implements Sources {

    /** The live project's sources, or null between projects. */
    private static volatile HostSources current;

    private final ProjectConfig config;
    private final ProjectState state;

    private HostSources(ProjectConfig config, ProjectState state) {
        this.config = config;
        this.state = state;
    }

    /** Makes this project's sources the ones a plugin reaches, replacing whatever was installed before. */
    public static synchronized void install(ProjectConfig config, ProjectState state) {
        if (config == null || state == null) {
            clear();
            return;
        }
        current = new HostSources(config, state);
    }

    /** No project: a plugin asking now gets {@link Sources#NONE}. */
    public static synchronized void clear() {
        current = null;
    }

    /** The live sources, or {@link Sources#NONE} when no project is open. Never null. */
    public static Sources live() {
        HostSources live = current;
        return live == null ? Sources.NONE : live;
    }

    @Override
    public List<Use> find(List<String> needles) {
        Pattern any = anyOf(needles);
        if (any == null) return List.of();

        List<Use> uses = new ArrayList<>();
        BotSources.forEach(config, state, (file, source) -> {
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (any.matcher(lines[i]).find()) uses.add(new Use(file, i + 1, lines[i].trim()));
            }
            return null;   // reading only
        });
        return List.copyOf(uses);
    }

    @Override
    public List<Path> replace(Map<String, String> replacements, String historyLabel, String reviewNote) {
        Map<Pattern, String> compiled = compile(replacements);
        if (compiled.isEmpty()) return List.of();

        snapshot(historyLabel);

        List<Path> changed = new ArrayList<>();
        BotSources.forEach(config, state, (file, source) -> {
            String[] lines = source.split("\n", -1);
            List<Integer> touched = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                String rewritten = lines[i];
                for (Map.Entry<Pattern, String> e : compiled.entrySet()) {
                    rewritten = e.getKey().matcher(rewritten).replaceAll(e.getValue());
                }
                if (rewritten.equals(lines[i])) continue;
                lines[i] = rewritten;
                touched.add(i + 1);
            }
            if (touched.isEmpty()) return null;
            changed.add(file);
            String rewritten = String.join("\n", lines);
            if (reviewNote == null || !ReviewMarker.marksSurvive(config, state, file)) return rewritten;
            return ReviewMarker.markLines(rewritten, touched, config.mainPackage(), reviewNote);
        });
        return List.copyOf(changed);
    }

    /**
     * Commits the project to its history before anything is rewritten, so the whole rename is one undo.
     *
     * <p>Best-effort and never fatal: a project that is not a repository, or a commit that fails, must not
     * stop a rename the user asked for. The alternative — refusing to rewrite because the safety net could
     * not be hung — protects nothing and blocks the work.
     */
    private void snapshot(String label) {
        if (label == null || label.isBlank()) return;
        try {
            ProjectVcs vcs = new ProjectVcs(config.projectPath());
            vcs.ensureInitialized();
            vcs.commit(label);
        } catch (Exception e) {
            System.err.println("Warning: could not snapshot before a source rewrite: " + e);
        }
    }

    // ── needles ─────────────────────────────────────────────────────────────────────────────────────────

    /** Each needle as a pattern, mapped to its quoted replacement, in the caller's own iteration order. */
    private static Map<Pattern, String> compile(Map<String, String> replacements) {
        Map<Pattern, String> compiled = new LinkedHashMap<>();
        if (replacements == null) return compiled;
        for (Map.Entry<String, String> e : replacements.entrySet()) {
            Pattern needle = patternFor(e.getKey());
            if (needle == null || e.getValue() == null) continue;
            compiled.put(needle, Matcher.quoteReplacement(e.getValue()));
        }
        return compiled;
    }

    /** One pattern matching any of {@code needles}, or null when none of them is usable. */
    private static Pattern anyOf(List<String> needles) {
        if (needles == null) return null;
        StringBuilder alternatives = new StringBuilder();
        for (String needle : needles) {
            Pattern one = patternFor(needle);
            if (one == null) continue;
            if (!alternatives.isEmpty()) alternatives.append('|');
            alternatives.append(one.pattern());
        }
        return alternatives.isEmpty() ? null : Pattern.compile(alternatives.toString());
    }

    /**
     * {@code needle}'s tokens, joined so any whitespace may separate them and an identifier may not be part
     * of a longer one. Null for a needle with no tokens in it at all.
     */
    static Pattern patternFor(String needle) {
        if (needle == null || needle.isBlank()) return null;
        StringBuilder pattern = new StringBuilder();
        int count = 0;
        for (String token : tokenize(needle)) {
            if (count++ > 0) pattern.append("\\s*");
            boolean word = Character.isJavaIdentifierStart(token.charAt(0)) || Character.isDigit(token.charAt(0));
            if (word) pattern.append("\\b");
            pattern.append(Pattern.quote(token));
            if (word) pattern.append("\\b");
        }
        return count == 0 ? null : Pattern.compile(pattern.toString());
    }

    /**
     * {@code needle} split into Java tokens: identifiers (and numbers) run together, a string literal is one
     * token including its quotes, and every other non-space character stands alone.
     *
     * <p>It is not a Java lexer and does not need to be — a needle is a fragment somebody would type, not a
     * compilation unit. What it has to get right is exactly two things: that {@code ORE} is one token so it
     * can be guarded against matching inside {@code OREX}, and that a string literal is one token so a path
     * inside it is never mistaken for the identifiers and dots it happens to contain.
     */
    private static List<String> tokenize(String needle) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < needle.length()) {
            char c = needle.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                int end = i + 1;
                while (end < needle.length() && needle.charAt(end) != '"') {
                    // A needle carrying an escaped quote is written as the user's source has it, so the
                    // backslash pair is skipped whole rather than ending the literal one character early.
                    end += needle.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, needle.length());
                tokens.add(needle.substring(i, end));
                i = end;
            } else if (Character.isJavaIdentifierStart(c) || Character.isDigit(c)) {
                int end = i;
                while (end < needle.length()
                        && (Character.isJavaIdentifierPart(needle.charAt(end)) || Character.isDigit(needle.charAt(end)))) {
                    end++;
                }
                tokens.add(needle.substring(i, end));
                i = end;
            } else {
                tokens.add(String.valueOf(c));
                i++;
            }
        }
        return tokens;
    }
}
