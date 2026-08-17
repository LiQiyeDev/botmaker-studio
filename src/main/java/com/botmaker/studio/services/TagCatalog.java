package com.botmaker.studio.services;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which tags a project has — the declared set, assembled from its two halves and consulted before any tag is
 * shown or assigned.
 *
 * <p><b>Why declared.</b> Tags used to come into existence by being typed: a batch capture started inside an
 * activity pre-filled that activity's name, a free-text field accepted anything, and the set of tags was
 * whatever happened to be in {@code templates.json} — so "Minning" was a tag, "mining" and "Mining" raced to
 * be the canonical spelling, and a tag disappeared when its last template was retagged. Declaring them first
 * makes the set finite and knowable: every assignment UI is a picklist over <em>this</em>, never a text
 * field, so a tag cannot be invented by a typo and cannot vanish by accident.
 *
 * <p><b>Two kinds, one of them managed.</b> An {@link Kind#ACTIVITY} tag is not stored anywhere — it is the
 * name of an activity in {@code activities.json}, derived on every read. It appears when the activity is
 * created and is gone when the activity is, which is exactly the lifecycle a hand-managed copy would fail to
 * keep. It cannot be renamed or deleted here; rename the activity. A {@link Kind#CUSTOM} tag is the user's
 * own, declared in {@link TemplateManifest#customTags()} and edited in one place.
 *
 * <p>Archived activities keep their tag. Archiving is reversible and the templates are still the ones that
 * activity uses, so hiding the tag would strand them under nothing; only deleting the activity removes it.
 *
 * <p>Assignments to tags this catalog doesn't declare are inert rather than erroneous — see
 * {@link TemplateManifest#byTag(Collection, TagCatalog)} for what that buys.
 */
public record TagCatalog(List<Tag> tags) {

    /** Who owns a tag's existence: the activity list, or the user. */
    public enum Kind {
        /** Named after an activity; created and removed with it, never edited by hand. */
        ACTIVITY,
        /** Declared by the user in the tag manager, and theirs to rename or delete. */
        CUSTOM
    }

    /** One declared tag. */
    public record Tag(String name, Kind kind) {
        /** True when the tag's existence is not the user's to change — an activity tag. */
        public boolean isManaged() {
            return kind == Kind.ACTIVITY;
        }
    }

    public TagCatalog {
        tags = List.copyOf(tags == null ? List.of() : tags);
    }

    public static TagCatalog empty() {
        return new TagCatalog(List.of());
    }

    /**
     * The catalog for a project: one tag per activity (in the order {@code activities.json} lists them, which
     * is the order the flow editor shows), then the declared custom tags alphabetically.
     *
     * <p>A custom tag that collides with an activity name is dropped rather than listed twice — the activity
     * owns the name, and the user's copy would be the one that couldn't be kept in step.
     */
    public static TagCatalog of(ActivitiesConfig activities, Collection<String> customTags) {
        Set<String> taken = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<Tag> all = new ArrayList<>();
        if (activities != null) {
            for (ActivityDefinition a : activities.activities()) {
                if (a.name() == null || a.name().isBlank() || !taken.add(a.name())) continue;
                all.add(new Tag(a.name(), Kind.ACTIVITY));
            }
        }
        if (customTags != null) {
            Set<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            sorted.addAll(customTags);
            for (String tag : sorted) {
                if (tag == null || tag.isBlank() || !taken.add(tag)) continue;
                all.add(new Tag(tag, Kind.CUSTOM));
            }
        }
        return new TagCatalog(all);
    }

    /** Every declared tag name, activity tags first. */
    public List<String> names() {
        return tags.stream().map(Tag::name).toList();
    }

    /** The declared tags of {@code kind}, in catalog order. */
    public List<String> namesOf(Kind kind) {
        return tags.stream().filter(t -> t.kind() == kind).map(Tag::name).toList();
    }

    /** The declared tag spelled like {@code name} (case-insensitively), or {@code null} if there is none. */
    public Tag find(String name) {
        if (name == null) return null;
        String clean = TemplateManifest.sanitizeTag(name);
        return tags.stream().filter(t -> t.name().equalsIgnoreCase(clean)).findFirst().orElse(null);
    }

    /** True when {@code name} is a tag this project has. */
    public boolean isDeclared(String name) {
        return find(name) != null;
    }

    /** True when {@code name} is declared and not the user's to rename or delete. */
    public boolean isManaged(String name) {
        Tag tag = find(name);
        return tag != null && tag.isManaged();
    }

    /**
     * {@code names} narrowed to the tags this catalog declares, spelled the way the catalog spells them and
     * ordered the way it orders them — what every assignment path saves, so a stale selection or a
     * hand-edited manifest can't put an undeclared tag back into the file.
     */
    public List<String> declaredOnly(Collection<String> names) {
        if (names == null) return List.of();
        Set<String> wanted = new LinkedHashSet<>();
        for (String name : names) {
            Tag tag = find(name);
            if (tag != null) wanted.add(tag.name());
        }
        return names().stream().filter(wanted::contains).toList();
    }
}
