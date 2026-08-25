package com.botmaker.studio.project;

import com.botmaker.sdk.api.authoring.Authoring;
import com.botmaker.sdk.api.authoring.AuthoringUnsupported;
import com.botmaker.sdk.api.authoring.ProjectModel;
import com.botmaker.sdk.api.authoring.ProjectSpec;
import com.botmaker.sdk.api.authoring.SdkVersion;
import com.botmaker.sdk.api.geometry.Size;
import com.botmaker.studio.services.MavenService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place Studio's project vocabulary is put into the SDK's.
 *
 * <p>Since 2026-08-25 the SDK writes every bot-facing file — the pom, the sources, {@code activities.json},
 * the project properties — and it takes a {@link ProjectSpec} to do it. Studio's own words for the same
 * project are spread across {@link ProjectConfig} (paths, package, class name), {@link ProjectTemplate}
 * (which starting shape) and the pom's SDK pin, so <em>something</em> has to put them together. Doing it once,
 * here, is what stops four callers each deciding for themselves whether the package is {@code mybot} or
 * {@code com.mybot} — a disagreement that produces a project which compiles into the wrong package and is
 * only noticed at run time.
 *
 * <p>This is not the adapter that was rejected in phase 1. That one mirrored the SDK's <em>data model</em>
 * record for record, giving {@code activities.json} two owners; this converts three identifiers and a size at
 * the one boundary where Studio hands work over, and it disappears when nothing on this side is spelled
 * differently any more.
 */
public final class ProjectSpecs {

    private ProjectSpecs() {}

    /**
     * The spec for a project Studio is about to create or repair.
     *
     * @param sdkPin the version the pom pins, or blank for {@link MavenService#SDK_FALLBACK_VERSION} — what a
     *               <em>new</em> pom pins is Studio's call, and deliberately not derived from
     *               {@link SdkVersion#latest()} (see {@code MavenService.SDK_FALLBACK_VERSION})
     */
    public static ProjectSpec of(ProjectConfig cfg, ProjectTemplate template, String sdkPin,
                                 StudioProjectSettings.Resolution referenceResolution) {
        return new ProjectSpec(
                cfg.projectName(),
                // The SDK is told the *full* package. Studio stores the last segment and prefixes "com."
                // wherever it needs the real one, which is exactly the kind of half-name a boundary must not
                // pass on.
                "com." + cfg.packageName(),
                cfg.className(),
                template == ProjectTemplate.GAME_BOT ? ProjectSpec.Kind.GAME_BOT : ProjectSpec.Kind.EMPTY,
                sdkPin == null || sdkPin.isBlank() ? MavenService.SDK_FALLBACK_VERSION : sdkPin.trim(),
                referenceResolution == null
                        ? null : new Size(referenceResolution.width(), referenceResolution.height()));
    }

    /**
     * The version to generate a project's files at: the one its pom pins.
     *
     * <p>It refuses rather than falling back, because every caller of this is about to <b>write bot code</b>,
     * and generating against a version the bot does not use is how a project gets a file its own SDK cannot
     * compile. The refusal is written for the user — see {@link AuthoringUnsupported}.
     */
    public static SdkVersion versionFor(String sdkPin) throws AuthoringUnsupported {
        return Authoring.require(sdkPin);
    }

    /**
     * The same question for a caller that is only <em>reading</em> — listing which files a project should
     * have, telling scaffolding from user code — where an unknown pin must not stop the project opening.
     *
     * <p>The asymmetry is deliberate and matches the standing decision that there is no SDK version floor:
     * any pinned SDK opens. A name is a far weaker claim than a body, and answering it from the newest
     * generator this build has is better than refusing to open a project over it.
     */
    public static SdkVersion readerVersionFor(String sdkPin) {
        return SdkVersion.ofPin(sdkPin).orElseGet(SdkVersion::latest);
    }

    /**
     * The file names — not paths — of the generated {@code .java} a project of this shape must have.
     *
     * <p>Studio's own checks resolve them against the package directory they already hold, which is why the
     * last segment is what comes back. The list itself is the generator's, so a file that stops being emitted
     * stops being looked for on the same day.
     */
    public static List<String> generatedFileNames(ProjectConfig cfg, ProjectTemplate template, String sdkPin) {
        List<String> names = new ArrayList<>();
        for (String path : Authoring.generatedFileNames(readerVersionFor(sdkPin),
                of(cfg, template, sdkPin, null))) {
            names.add(Path.of(path).getFileName().toString());
        }
        return names;
    }

    /**
     * The text of one generated file, rendered <b>against an empty model</b>, or {@code null} when this shape
     * of project does not have a file by that name.
     *
     * <p>The empty model is what bounds this to honest callers: it is the right answer for a file that says
     * nothing about the project's activities — an empty project's entry point — and the wrong one for every
     * file that does. Restoring those is inversion phase 4's job, once Studio builds a {@link ProjectModel}
     * of its own; until then a caller that needs one gets a null restorer and a report rather than a guess.
     */
    public static String generatedSource(ProjectConfig cfg, ProjectTemplate template, String sdkPin,
                                         String fileName) {
        for (var entry : Authoring.sources(readerVersionFor(sdkPin), of(cfg, template, sdkPin, null),
                ProjectModel.empty(), List.of()).entrySet()) {
            if (Path.of(entry.getKey()).getFileName().toString().equals(fileName)) return entry.getValue();
        }
        return null;
    }
}
