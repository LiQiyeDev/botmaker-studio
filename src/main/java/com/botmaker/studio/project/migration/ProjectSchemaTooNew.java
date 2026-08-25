package com.botmaker.studio.project.migration;

import java.io.IOException;

/**
 * A project data file records a schema version this Studio does not know, so the project was not opened.
 *
 * <p>Its own type rather than a plain {@link IOException} for the same reason as
 * {@code ScaffoldUnsupported}: it is <b>not a failure</b>. Nothing broke and no disk filled up — a newer
 * Studio wrote a shape this one cannot read, and reading it anyway would mean guessing at fields whose
 * meaning changed, which is how a project gets quietly written half away. A caller shows
 * {@link #getMessage()} as it stands, because the sentence already names the file, both numbers and the way
 * out.
 *
 * <p>Raised before anything is migrated or opened, which is what makes "the project on disk is untouched"
 * true rather than hoped for. It is the mirror image of {@code TemplateStore.requireFloor}, which refuses the
 * other direction — an SDK too old for what Studio wants to write.
 */
public class ProjectSchemaTooNew extends IOException {

    public ProjectSchemaTooNew(String message) {
        super(message);
    }
}
