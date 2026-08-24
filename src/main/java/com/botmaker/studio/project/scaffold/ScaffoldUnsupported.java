package com.botmaker.studio.project.scaffold;

import java.io.IOException;

/**
 * The SDK a project pins cannot carry the files Studio generates, and its own pointers do not say what to
 * write instead — so nothing was written.
 *
 * <p>Its own type rather than a plain {@link IOException} because it is <b>not a failure</b>. Nothing went
 * wrong, no disk filled up, no parse broke: the answer to "can this be generated against that SDK?" is simply
 * no. A caller shows {@link #getMessage()} as it stands rather than wrapping it in "failed to create project"
 * or "failed to save activities", because the sentence already names the element and the way out.
 *
 * <p>It is thrown before anything is written, at both sites that write generated code — {@code ProjectCreator}
 * at creation and {@code ActivityService} on every regeneration — which is what makes "the projects directory
 * is untouched" and "the file that was on disk is still the file on disk" true rather than hoped for.
 */
public class ScaffoldUnsupported extends IOException {

    public ScaffoldUnsupported(String message) {
        super(message);
    }
}
