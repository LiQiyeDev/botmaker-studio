package com.botmaker.studio.project.settings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Where a project keeps the values its bot reads while it runs — the one discriminator that separates the two
 * generation paths Studio carries.
 *
 * <p>{@link #JSON} is the original model: {@code activities.json} holds the values, and the generated
 * {@code Activities} class reads them off the classpath at startup through Jackson. {@link #JAVA} is the
 * model every project created since 2026-08 uses: the values <em>are</em> the generated {@code Settings}
 * class, inlined as Java literals, and nothing is parsed at run time.
 *
 * <p><b>Read it once, whole.</b> The two paths are selected at the three edges that care — writing, loading,
 * and generating a stub — and never branched inside. That is the containment: a legacy project keeps the
 * legacy generator exactly as it was, rather than a generator with a flag threaded through it.
 *
 * <p>The parse is total and defaults to {@link #JSON}, because absent means legacy: every project written
 * before the discriminator existed is a JSON-model project and says nothing about it.
 */
public enum SettingsModel {

    /** Values in {@code activities.json}, read at bot startup. Every project created before 2026-08. */
    JSON("json"),

    /** Values inlined in the generated {@code Settings} class. Nothing is read at startup. */
    JAVA("java");

    private final String id;

    SettingsModel(String id) {
        this.id = id;
    }

    /** The stable wire form, as written into {@code activities.json}. */
    @JsonValue
    public String id() {
        return id;
    }

    /** Never throws and never returns null: anything unrecognised — including absent — is {@link #JSON}. */
    @JsonCreator
    public static SettingsModel fromId(String id) {
        if (id == null) return JSON;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (SettingsModel m : values()) {
            if (m.id.equals(key)) return m;
        }
        return JSON;
    }

    public boolean isJava() {
        return this == JAVA;
    }
}
