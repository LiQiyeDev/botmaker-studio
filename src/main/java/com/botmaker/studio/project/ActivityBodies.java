package com.botmaker.studio.project;

import com.botmaker.studio.services.BotSources;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where an activity's behaviour is written — found by looking, because nothing put it anywhere.
 *
 * <p>Until 2026-08-29 an activity was a file: {@code src/main/java/com/<pkg>/activities/Mining.java}, written
 * when the activity was created and renamed when it was renamed. Studio could therefore compute the path.
 * Nothing writes a user's sources now, so an activity's body is an
 * {@code Activities.define("Mining", ctx -> …)} call in whatever file its author chose, and the only way to
 * open it is to go and find it.
 *
 * <p><b>Finding nothing is an ordinary answer, not a failure.</b> An activity with no {@code define} takes
 * its {@code DISABLED} wire and the flow runs without it — the SDK's own rule — so a caller reports "no body
 * yet" and never offers to write one. That is the whole difference between this and the
 * <em>Recover Project Files</em> the old path could suggest.
 */
public final class ActivityBodies {

    private ActivityBodies() {}

    /**
     * The file holding {@code Activities.define("<activity>", …)}, or {@code null} when nothing declares it.
     *
     * <p>Matched on the text rather than on a syntax tree, and the tolerance is deliberate: the method name
     * alone, so a {@code static import} of {@code define} is found too, with any spacing between the call and
     * its name. What it costs is that the same call inside a comment counts — which opens the file the user
     * was looking for anyway.
     */
    public static Path find(ProjectConfig config, ProjectState state, String activity) {
        if (config == null || activity == null || activity.isBlank()) return null;
        Pattern call = Pattern.compile("\\bdefine\\s*\\(\\s*\"" + Pattern.quote(activity) + "\"");
        return BotSources.firstMatch(config, state, (file, source) -> {
            Matcher found = call.matcher(source);
            return found.find();
        });
    }
}
