package com.botmaker.studio.project.migration;

import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner;
import com.botmaker.studio.parser.refactor.SdkReferences;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.VariableHolder;
import com.botmaker.studio.project.activity.VariableWire;
import com.botmaker.studio.services.ActivityService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code activities.json} <b>1 → 2</b>: the project's values move out of {@code Activities} and into
 * {@code Parameters}, and every place the bot's own source said {@code Activities.<value>} is repointed.
 *
 * <p>Two files used to be one. An activity's on/off tick and the delay it waits for were declared side by
 * side in one flat namespace, spelled identically, and nothing in the name said which was which — while the
 * two are governed differently at every level above the field: a flag is written by the Activity Flow and
 * read by a stub, a value is the user's and is what the Runner offers. The 2026-08-25 SDK splits the frame
 * into two templates; this step is what an existing bot needs so that the split is not a break.
 *
 * <h2>Why this is a complete repair</h2>
 *
 * <p>Every field keeps its name, its type and its stored value — only the class it is declared on moves. So
 * the bot does afterwards exactly what it did before and <b>no {@code @NeedsReview} mark is written</b>: a
 * rename is not a thing to look at, and burying the sites that changed meaning under the ones that did not is
 * how a review list stops being read. A Project History snapshot is taken all the same, because this rewrites
 * files the user is not looking at.
 *
 * <h2>What it will not do</h2>
 *
 * <p>The redirects are carried by {@link SdkMigrationRunner}, which rewrites a field read's <em>qualifier</em>
 * and refuses where the source names no qualifier at all — a bare {@code import static} or a {@code case}
 * label. That refusal is the whole run, not one file: half a split leaves a project that does not compile.
 * The step then throws, the file stays unstamped, and the next open tries again — which is the right outcome
 * for something the user can fix by hand in one line.
 *
 * <p>The two generated classes are written <b>first</b> and from the same model, so the project is never in
 * the state where its source names a {@code Parameters} that does not exist.
 */
final class ParametersSplit {

    private ParametersSplit() {}

    /**
     * Runs the split for {@code config}, or answers null when there is nothing to split.
     *
     * @return the line for the status bar, or null when this project never had a generated {@code Activities}
     */
    static String apply(ProjectConfig config) throws IOException {
        Path activitiesFile = config.activitiesSourceFile();
        if (!Files.isRegularFile(activitiesFile)) return null;   // never generated one; nothing to move

        ActivitiesConfig model = ActivitiesConfig.read(config.resourcesRoot());
        List<ActivityVariable> values = model.variables();

        // Rendered before anything is written, and both of them: a refusal here (an SDK below the template
        // floor) must leave the project exactly as it was.
        ActivityService service = new ActivityService(config, null, null);
        String activitiesSource = service.generateActivitiesSource(model);
        String parametersSource = service.generateParametersSource(model);

        List<Rewrite> rewrites = values.isEmpty() ? List.of() : repointValues(config, values);

        ReviewMarker.snapshot(config, "Before splitting Parameters out of Activities");
        Files.writeString(activitiesFile, activitiesSource);
        Files.writeString(config.parametersSourceFile(), parametersSource);
        for (Rewrite rewrite : rewrites) Files.writeString(rewrite.file(), rewrite.source());

        if (values.isEmpty()) {
            return "Split this project's Activities class into Activities and Parameters.";
        }
        return "Moved " + values.size() + " project value" + (values.size() == 1 ? "" : "s")
               + " from Activities into Parameters"
               + (rewrites.isEmpty() ? "." : ", and repointed " + rewrites.size() + " source file"
                                             + (rewrites.size() == 1 ? "." : "s."));
    }

    /** One of the bot's own files and what it should say afterwards. */
    private record Rewrite(Path file, String source) {}

    /**
     * Every {@code Activities.<value>} in the bot's own source, rewritten to name {@code Parameters}.
     *
     * <p>Only the values are redirected. An activity's enable flag stays exactly where it is, so a stub's
     * {@code Activities.Mining} must go on saying {@code Activities} — which is why the redirect list is built
     * from {@link ActivitiesConfig#variables()} and never from {@code allVariables()}.
     */
    private static List<Rewrite> repointValues(ProjectConfig config, List<ActivityVariable> values)
            throws IOException {
        String parameters = config.mainPackage() + "." + VariableHolder.PARAMETERS.className();
        List<SdkMigrationRunner.Redirect> redirects = new ArrayList<>(values.size());
        Map<String, List<String>> fieldOwners = new LinkedHashMap<>();
        for (ActivityVariable v : values) {
            // Same name, same type, no arguments to move: shapeChanged() is false, so nothing is marked.
            String type = VariableWire.javaType(v.type());
            redirects.add(new SdkMigrationRunner.Redirect(
                    VariableHolder.ACTIVITIES.className(), v.name(), SdkReferences.FIELD_READ,
                    parameters, v.name(), List.of(), type, type, true));
            fieldOwners.put(v.name(), List.of(VariableHolder.ACTIVITIES.className()));
        }

        List<ProjectFile> editable = botSources(config);
        if (editable.isEmpty()) return List.of();

        SdkMigrationRunner.Outcome outcome = SdkMigrationRunner.run(
                new SdkMigrationRunner.Repairs(List.of(), redirects, List.of()),
                editable, List.of(),
                Set.of(VariableHolder.ACTIVITIES.className()), fieldOwners,
                null,                       // a rename is a complete repair — nothing to review
                null, null);                // no analyzer, no editor state: this runs before either exists
        if (outcome.isRefusal()) throw new IOException(outcome.refusal());

        List<Rewrite> rewrites = new ArrayList<>(outcome.files().size());
        for (var rewritten : outcome.files()) {
            rewrites.add(new Rewrite(rewritten.file().getPath(), rewritten.newSource()));
        }
        return rewrites;
    }

    /**
     * The bot's own {@code .java} files, read from disk.
     *
     * <p>Disk and not the editor's buffers, unlike every other sweep over these files: this runs on the open
     * path before the editor has loaded anything, so there is no buffer that could be more current than the
     * file. The two generated holder classes are excluded — they are rewritten from the model above, and
     * feeding them to the rewriter would have it repoint the very declarations it is moving.
     */
    private static List<ProjectFile> botSources(ProjectConfig config) throws IOException {
        Path root = config.sourceRoot();
        if (root == null || !Files.isDirectory(root)) return List.of();
        Path activities = config.activitiesSourceFile().toAbsolutePath().normalize();
        Path parameters = config.parametersSourceFile().toAbsolutePath().normalize();

        List<ProjectFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> p.toAbsolutePath().normalize()).toList()) {
                if (file.equals(activities) || file.equals(parameters)) continue;
                files.add(new ProjectFile(file, Files.readString(file)));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return files;
    }
}
