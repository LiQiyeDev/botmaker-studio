package com.botmaker.studio.services;

import com.botmaker.studio.project.settings.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Settings dialog's tag rail shows, and which settings each row holds — the parts of that dialog that
 * are a decision rather than a widget, kept free of JavaFX so they can be tested headlessly.
 *
 * <p>It is deliberately the same shape as {@link TemplateGalleryModel}: <em>All</em>, then the activity tags
 * under a heading, then the custom ones under theirs. A tag means the same thing in both places because it
 * <em>is</em> the same tag — one {@link TagCatalog}, so renaming an activity renames its group in the gallery
 * and here at once, and there is no second vocabulary to drift.
 *
 * <p><b>Nothing is ever unreachable.</b> A setting filed under a tag the catalog no longer declares — the
 * activity it named was deleted — would otherwise have no row at all. Rather than inventing one, it is listed
 * under {@link Setting#GENERAL}, which is where a setting with no tag lives and where this one effectively now
 * is. The dialog's tag picker then shows it as unfiled, so the fix is a visible choice rather than a silent
 * relabel.
 */
public final class SettingsRailModel {

    /** The row that holds everything. Not a tag: no setting carries it and nothing may declare it. */
    public static final String ALL = "All settings";

    private SettingsRailModel() {}

    /** A rail entry: either a group heading or a selectable tag. */
    public sealed interface Row permits Heading, TagRow {}

    /** A non-selectable group label ("Activities", "Custom"). */
    public record Heading(String text) implements Row {}

    /** A selectable bucket, including the computed {@link #ALL} and {@link Setting#GENERAL} rows. */
    public record TagRow(String tag, int count) implements Row {}

    /**
     * The rail for {@code settings} over {@code catalog}: All, General, then each group of declared tags. Both
     * computed rows are always present — All because it is the way to see everything, General because it is
     * where a new setting lands before anyone files it, and a bucket you cannot select is a bucket you cannot
     * put anything in.
     */
    public static List<Row> rows(List<Setting> settings, TagCatalog catalog) {
        List<Setting> all = settings == null ? List.of() : settings;
        TagCatalog safe = catalog == null ? TagCatalog.empty() : catalog;

        List<Row> rows = new ArrayList<>();
        rows.add(new TagRow(ALL, all.size()));
        rows.add(new TagRow(Setting.GENERAL, in(all, Setting.GENERAL, safe).size()));
        addGroup(rows, "Activities", safe.namesOf(TagCatalog.Kind.ACTIVITY), all, safe);
        addGroup(rows, "Custom", safe.namesOf(TagCatalog.Kind.CUSTOM), all, safe);
        return List.copyOf(rows);
    }

    /**
     * The settings row {@code tag} holds, in the order they were declared.
     *
     * <p>{@link #ALL} is everything. {@link Setting#GENERAL} is everything untagged <em>plus</em> everything
     * whose tag the catalog does not declare — see the class note. Any other row is an exact, case-insensitive
     * tag match.
     */
    public static List<Setting> in(List<Setting> settings, String tag, TagCatalog catalog) {
        if (settings == null || settings.isEmpty()) return List.of();
        TagCatalog safe = catalog == null ? TagCatalog.empty() : catalog;
        if (tag == null || ALL.equals(tag)) return List.copyOf(settings);
        if (Setting.GENERAL.equals(tag)) {
            return settings.stream().filter(s -> !safe.isDeclared(s.tag())).toList();
        }
        return settings.stream().filter(s -> s.tag().equalsIgnoreCase(tag)).toList();
    }

    private static void addGroup(List<Row> rows, String heading, List<String> tags, List<Setting> settings,
                                 TagCatalog catalog) {
        if (tags.isEmpty()) return;  // no heading over nothing
        rows.add(new Heading(heading));
        for (String tag : tags) rows.add(new TagRow(tag, in(settings, tag, catalog).size()));
    }
}
