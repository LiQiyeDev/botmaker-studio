package com.botmaker.studio.project;

import com.botmaker.sdk.authoring.Authoring;
import com.botmaker.sdk.authoring.AuthoringUnsupported;
import com.botmaker.sdk.authoring.ProjectSpec;
import com.botmaker.sdk.authoring.SdkVersion;
import com.botmaker.sdk.api.geometry.Size;
import com.botmaker.studio.services.MavenService;

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
 *
 * <h2>What used to be here, and is not coming back</h2>
 *
 * <p>{@code generatedFileNames} and {@code generatedSource} answered "which {@code .java} must a project of
 * this shape have, and what is in it", so a repair could notice one missing and write it back. Both halves
 * went on 2026-08-29: nothing writes a project's Java, so nothing knows what it should contain, and nothing
 * may put it back. A project's structure belongs to its owner — a file they deleted is a file they meant to
 * delete.
 *
 * <p>The note that stood on the first of those said it went through one named plugin and ought to go through
 * the contract instead, over every plugin that writes into a project. That surface was built the day before
 * and deleted the day after; see {@code botmaker-studio-api}'s {@code CLAUDE.md}. The right number of plugins
 * to ask turned out to be none.
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

}
