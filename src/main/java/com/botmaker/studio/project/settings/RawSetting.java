package com.botmaker.studio.project.settings;

/**
 * A setting this Studio does not understand, kept as the source text that produced it.
 *
 * <p>It exists for one case: a {@code @Setting(type = "…")} naming a {@link SettingType} added after this
 * build. {@link SettingType#fromId} deliberately answers {@code null} there rather than falling back to
 * {@code TEXT}, and a {@link Setting} cannot hold a type that does not exist — so the only way to not lose the
 * value is to carry the lines verbatim and write them back unchanged. An older Studio opening a newer
 * project therefore leaves it alone instead of quietly deleting a setting on the next save.
 *
 * <p>Nothing interprets these. They are not offered in the dialog, not referenced by the expression menu and
 * not counted in a tag: to this build they are four strings that go back where they came from.
 *
 * @param name        the field name, so the writer can skip a live setting that has taken the same one
 * @param annotation  the whole {@code @Setting(…)} text, exactly as it was read
 * @param javaType    the declared field type, as written
 * @param initializer the right-hand side of its assignment in the static block, as written
 */
public record RawSetting(String name, String annotation, String javaType, String initializer) {

    public RawSetting {
        name = name == null ? "" : name.trim();
        annotation = annotation == null ? "" : annotation.trim();
        javaType = javaType == null ? "Object" : javaType.trim();
        initializer = initializer == null || initializer.isBlank() ? "null" : initializer.trim();
    }
}
