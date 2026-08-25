package com.botmaker.studio.project.activity;

/**
 * The generated class a referenceable field lives on — the qualifier a bot writes in front of its name.
 *
 * <p>A closed set of two rather than the {@code "Activities"} string constant this replaced, which sat in
 * {@code VariablePicker} and again in {@code ExpressionMenu}: since 2026-08-25 there are two classes and the
 * right one is a property of the field, so every place that used to write the name down has to ask instead —
 * and asking has to be a question with two answers, not a string somebody could spell a third way.
 *
 * <ul>
 *   <li>{@link #ACTIVITIES} — one {@code boolean} per activity the project defines. The editor's record of
 *       which activities this bot runs: written by the Activity Flow, read by a stub's {@code isEnabled()}.
 *   <li>{@link #PARAMETERS} — every configured value. The user's, and what the Runner offers.
 * </ul>
 *
 * <p>Both names are also the file names of the generated classes ({@code ProjectConfig.activitiesSourceFile}
 * / {@code parametersSourceFile}) and the {@code target} of the SDK's two templates, so the spelling is not
 * free to drift: a rename would be a scaffold change, not a Studio one.
 */
public enum VariableHolder {

    /** {@code Activities} — the activity enable flags. */
    ACTIVITIES("Activities"),

    /** {@code Parameters} — the project's configured values. */
    PARAMETERS("Parameters");

    private final String className;

    VariableHolder(String className) {
        this.className = className;
    }

    /** The simple class name the bot's source writes: {@code Parameters.REST}. */
    public String className() {
        return className;
    }

    /** The holder that class name belongs to, or null for anything else — a qualifier of the bot's own. */
    public static VariableHolder ofClassName(String name) {
        for (VariableHolder holder : values()) {
            if (holder.className.equals(name)) return holder;
        }
        return null;
    }
}
