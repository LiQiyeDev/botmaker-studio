package com.botmaker.studio.project;

import com.botmaker.sdk.authoring.ActivityModel;
import com.botmaker.sdk.authoring.Authoring;
import com.botmaker.sdk.authoring.ProjectModel;
import com.botmaker.sdk.authoring.ProjectSpec;
import com.botmaker.sdk.authoring.SdkVersion;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place Studio asks a project's own SDK to produce that project's generated Java again.
 *
 * <p>Phase 0b left five callers refusing by name — saving an Activity Flow, the {@code Parameters} split, a
 * recovery, an SDK upgrade, a template capture — because the generator had left Studio and had not yet
 * arrived in the SDK. It has (inversion phase 2), and this is what reconnects them. Every one of them wants
 * the same three steps in the same order, and none of them should be deciding those steps for itself.
 *
 * <h2>The model is read back off disk, not converted</h2>
 *
 * <p>Studio still keeps its own record set ({@link com.botmaker.studio.project.activity.ActivitiesConfig} and
 * siblings) and the SDK keeps its {@link ProjectModel}; the switchover that deletes the first is a separate,
 * much larger change. What this class deliberately does <b>not</b> do is bridge the two in memory — that
 * adapter was written once, in phase 1, and rejected the same day: a second model kept in step with the first
 * is exactly the duplication the inversion exists to remove.
 *
 * <p>Instead the model comes from {@code activities.json} itself, through {@link Authoring#readModel}. The
 * file is the contract, it already has one owner, and both record sets parse it. That makes the ordering rule
 * explicit rather than incidental: <b>persist first, then regenerate</b> — the generator emits what was
 * actually stored, never what a caller believed it had stored.
 *
 * <h2>There is nothing left to regenerate</h2>
 *
 * <p>{@link #render} and {@link #writeTemplatesClass} answer with nothing, and the five files they owned no
 * longer exist in a new project: a tick, a value, a picture and a wire are all read at run time from the
 * project's own {@code activities.json}. Their callers are unchanged and still correct — persisting the model
 * and then asking whether any source must follow is the right shape; the answer is simply no.
 *
 * <p>What survives is {@link #ensureStubs} and {@link #renderEverything}, both of which are about files the
 * user owns: a stub that has never been written, and what the generator <em>would</em> write, which is how a
 * repair knows a seed has gone missing.
 *
 * <h2>All of it or none of it</h2>
 *
 * <p>Every path here still renders into memory before it opens a file for writing. That is the rule phase 0b
 * recorded as the one its successor inherits, and it is honoured here rather than at each call site.
 *
 * <h2>The version is the project's</h2>
 *
 * <p>Always {@link ProjectSpecs#readerVersionFor}, from the pom's pin: a file regenerated against Studio's
 * newest SDK could name a member the jar this bot actually resolves does not have. Reading rather than
 * writing semantics, because a regeneration must not be the thing that stops an old project opening — an
 * unknown pin falls back to the newest generator here rather than refusing.
 */
public final class Regeneration {

    private Regeneration() {}

    /**
     * The files a change to the model forces to be rewritten. <b>There are none, and there is no longer any
     * such thing.</b>
     *
     * <p>Five files used to be here — {@code Activities}, {@code Parameters}, {@code Templates},
     * {@code ActivityRegistry}, {@code FlowDriver} — and every one of them followed entirely from the
     * project's own data. They are reads now: {@code Wire.enabled}, {@code Wire.whole} and friends,
     * {@code Wire.image}, {@code FlowGraph.load}. A tick, a value, a picture or a wire changes
     * {@code activities.json} and no Java at all.
     *
     * <p>It stays as an empty answer rather than being deleted with its callers, because those callers are
     * right to ask: saving an Activity Flow, splitting parameters and finishing an SDK upgrade all still have
     * to persist the model, and asking afterwards whether anything must be regenerated is the correct shape
     * for them to keep. What has gone is the answer, not the question.
     *
     * <p><b>A project generated before this keeps its five files as ordinary source.</b> Nothing rewrites
     * them and nothing deletes them, so a bot that compiles today still compiles.
     */
    public static Map<Path, String> render(ProjectConfig config) throws IOException {
        return Map.of();
    }

    /**
     * Renders and writes them, and says which were written.
     *
     * <p>Nothing is written until everything has been rendered; see the class javadoc.
     */
    public static List<Path> write(ProjectConfig config) throws IOException {
        return commit(render(config));
    }

    /**
     * Writes a stub for every activity in the stored model that has not got one, and says which appeared.
     *
     * <p><b>The one generated file that is never rewritten.</b> {@code run()}'s body is the whole point of a
     * stub, so this creates and never overwrites; keeping the BotMaker-owned parts of an existing one in step
     * is {@code ActivityStubSync}'s job, by AST edit rather than by re-render. That is why it is not part of
     * {@link #write}'s all-or-none set — those files hold no user code and this one holds nothing else.
     *
     * <p>It is still all-or-none in the sense that matters: every missing stub is rendered before any is
     * written, so a model the generator cannot serve leaves the package exactly as it found it.
     */
    public static List<Path> ensureStubs(ProjectConfig config) throws IOException {
        ProjectTemplate template = templateOf(config);
        if (template != ProjectTemplate.GAME_BOT) return List.of();
        String pin = MavenService.readSdkVersion(config.projectPath());
        SdkVersion version = ProjectSpecs.readerVersionFor(pin);
        ProjectSpec spec = spec(config, template, pin);

        Map<String, String> relative = new LinkedHashMap<>();
        for (ActivityModel activity : Authoring.readModel(version, config.resourcesRoot()).activities()) {
            relative.putAll(Authoring.activityStub(version, spec, activity));
        }
        Map<Path, String> absent = new LinkedHashMap<>();
        for (Map.Entry<Path, String> stub : absolute(config, relative).entrySet()) {
            if (!Files.exists(stub.getKey())) absent.put(stub.getKey(), stub.getValue());
        }
        return commit(absent);
    }

    /**
     * Nothing, since a picture stopped being a compiled constant.
     *
     * <p>{@code Templates} held one {@code public static final String} per file in the images folder and was
     * rewritten on every capture, rename and delete. {@code Wire.image("ore")} names the file instead, so
     * adding a picture to a project is no longer a source edit and there is nothing to keep in step.
     *
     * <p>Kept, empty, for the same reason as {@link #render}: a capture asking whether it owes the project a
     * file is a correct question with a new answer.
     */
    public static List<Path> writeTemplatesClass(ProjectConfig config) throws IOException {
        return List.of();
    }

    /**
     * Writes one generated file back, whatever kind it is — a scaffold file, a holder class, an activity's
     * stub.
     *
     * <p>This is what a {@code ProjectRepair} restorer calls, and why it takes a path rather than a name: the
     * repair found the file missing by path, and asking the generator for <em>that</em> path is what keeps
     * the two from disagreeing about where a file belongs. A path the generator does not claim is an
     * {@link IOException} and not a silent no-op — a recovery that reports a file restored and writes nothing
     * is worse than one that says it could not.
     */
    public static void restore(ProjectConfig config, Path file) throws IOException {
        Map<Path, String> everything = renderEverything(config);
        String source = everything.get(file.toAbsolutePath().normalize());
        if (source == null) {
            throw new IOException(file.getFileName() + " is not a file this project's SDK generates");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    /** True when the generator claims {@code file} — what a repair asks before offering to restore it. */
    public static boolean isGenerated(ProjectConfig config, Path file) {
        try {
            return renderEverything(config).containsKey(file.toAbsolutePath().normalize());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Every file the generator would write for this project as it stands: the creation set plus one stub per
     * activity in the stored model.
     *
     * <p>The stubs are included because a deleted stub is one of the things recovery most needs to bring
     * back, and {@link Authoring#sources} already emits them — but only for activities the model still names,
     * which is the correct boundary: a stub whose activity is gone is not missing, it is deleted.
     *
     * <p>Public because it is also the <em>canonical</em> text a damage check compares against
     * ({@code ProjectRepair.findDamaged}): "what would the generator write here today" is one question, and
     * a second renderer answering it would be a second generator.
     */
    public static Map<Path, String> renderEverything(ProjectConfig config) throws IOException {
        ProjectTemplate template = templateOf(config);
        String pin = MavenService.readSdkVersion(config.projectPath());
        SdkVersion version = ProjectSpecs.readerVersionFor(pin);
        ProjectSpec spec = spec(config, template, pin);
        ProjectModel model = Authoring.readModel(version, config.resourcesRoot());

        Map<String, String> relative = new LinkedHashMap<>(Authoring.sources(version, spec, model));
        for (ActivityModel activity : model.activities()) {
            relative.putAll(Authoring.activityStub(version, spec, activity));
        }
        return absolute(config, relative);
    }

    // ---- the shared plumbing ---------------------------------------------------------------------------

    private static ProjectSpec spec(ProjectConfig config, ProjectTemplate template, String pin) {
        return ProjectSpecs.of(config, template, pin, null);
    }

    /**
     * Which shape of project this is, from what was recorded at creation, falling back to two pieces of
     * evidence on disk.
     *
     * <p>Resolved here rather than passed in, so that five callers cannot answer it five ways — and because
     * two of them ({@code ParametersSplit}, a background regeneration) run with no project state to ask.
     *
     * <p><b>The stored model is consulted before the scaffold heuristic, and that order is the point.</b>
     * {@link ProjectRepair#looksLikeGameBot} asks whether the generated files are <em>there</em>, which is
     * exactly the question a regeneration cannot rely on: the first save of an Activity Flow, and every
     * recovery of a deleted scaffold, run on a project where they are not. A project whose
     * {@code activities.json} names an activity is a game bot whatever is or is not on disk beside it —
     * nothing else in Studio can produce that file.
     */
    private static ProjectTemplate templateOf(ProjectConfig config) {
        ProjectTemplate recorded = StudioProjectSettings.read(config.resourcesRoot()).template();
        if (recorded != null) return recorded;
        if (storedModelHasActivities(config)) return ProjectTemplate.GAME_BOT;
        return ProjectRepair.looksLikeGameBot(config) ? ProjectTemplate.GAME_BOT : ProjectTemplate.EMPTY;
    }

    /**
     * Whether {@code activities.json} exists and names at least one activity.
     *
     * <p>Unreadable or absent is {@code false} rather than a throw: this is one of two fallbacks for a
     * question the project was supposed to have answered at creation, and a project that cannot say what it
     * is must still open.
     */
    private static boolean storedModelHasActivities(ProjectConfig config) {
        try {
            SdkVersion version =
                    ProjectSpecs.readerVersionFor(MavenService.readSdkVersion(config.projectPath()));
            return !Authoring.readModel(version, config.resourcesRoot()).activities().isEmpty();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Map<Path, String> absolute(ProjectConfig config, Map<String, String> relative) {
        Map<Path, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : relative.entrySet()) {
            out.put(config.projectPath().resolve(file.getKey()).toAbsolutePath().normalize(),
                    file.getValue());
        }
        return out;
    }

    private static List<Path> commit(Map<Path, String> files) throws IOException {
        List<Path> written = new ArrayList<>(files.size());
        for (Map.Entry<Path, String> file : files.entrySet()) {
            Files.createDirectories(file.getKey().getParent());
            Files.writeString(file.getKey(), file.getValue());
            written.add(file.getKey());
        }
        return written;
    }
}
