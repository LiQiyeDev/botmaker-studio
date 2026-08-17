package com.botmaker.studio.services;

import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.activity.ParamVisibility;
import com.botmaker.studio.project.settings.RawSetting;
import com.botmaker.studio.project.settings.Setting;
import com.botmaker.studio.project.settings.SettingType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a project's settings back out of the generated {@code Settings.java} — the other half of
 * {@link SettingsClassWriter}, and for a {@link com.botmaker.studio.project.settings.SettingsModel#JAVA}
 * project the only way values get loaded at all.
 *
 * <p><b>It reads the annotations, never the static block.</b> Every value comes from
 * {@code @Setting(value = "90s")}; the initializer {@code java.time.Duration.ofMillis(90000L)} is walked only
 * to keep the text of an {@link RawSetting unrecognised} field alive. Recovering a value from an initializer
 * would mean parsing arbitrary Java expressions, and the writer exists precisely so that is never necessary.
 *
 * <p><b>Two kinds of field are deliberately not returned.</b> One with no {@code @Setting} is a constant
 * somebody wrote by hand, not a setting. One of type {@code ENABLE} is an activity's enable flag, whose home is
 * {@code activities.json} — {@link com.botmaker.studio.project.activity.ActivitiesConfig#allSettings()}
 * regenerates it on every save, and reading it back here would give the flag a second store to disagree with.
 *
 * <p><b>Nothing is dropped for not being understood.</b> A {@code type} this build has never heard of comes
 * back as a {@link RawSetting} holding the exact source that produced it, and goes back out unchanged. Pure:
 * {@link #parse} is the whole of the logic and takes a string.
 */
public final class SettingsReader {

    private SettingsReader() {}

    /**
     * What one {@code Settings.java} says.
     *
     * @param settings the settings this build understands, in file order
     * @param unknown  the ones it does not, as the source text to write back
     * @param warnings what went wrong, for the caller to surface; empty on a clean read
     */
    public record Result(List<Setting> settings, List<RawSetting> unknown, List<String> warnings) {

        /** Nothing readable — a missing file, or one too broken to parse. */
        public static final Result EMPTY = new Result(List.of(), List.of(), List.of());

        public Result {
            settings = settings == null ? List.of() : List.copyOf(settings);
            unknown = unknown == null ? List.of() : List.copyOf(unknown);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        static Result failed(String warning) {
            return new Result(List.of(), List.of(), List.of(warning));
        }

        /** True when the read produced nothing at all — no settings and nothing unrecognised. */
        public boolean isEmpty() {
            return settings.isEmpty() && unknown.isEmpty();
        }

        /** True when something went wrong; the caller must not overwrite the file on the strength of this. */
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    /**
     * Reads {@code file}, or {@link Result#EMPTY} with a warning when it cannot be read.
     *
     * <p>An absent file is <em>not</em> a warning: a project whose settings have all been deleted legitimately
     * has none, and so does one being created. A file that exists and will not parse is — for a java-model
     * project that file <em>is</em> the store, so "read as empty" is indistinguishable from "every value was
     * deleted" unless somebody says so out loud.
     */
    public static Result read(Path file) {
        if (file == null || !Files.exists(file)) return Result.EMPTY;
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            return Result.failed("Could not read " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /** Everything {@code source} declares. Never throws: unparseable input yields a warning, not an exception. */
    public static Result parse(String source) {
        if (source == null || source.isBlank()) return Result.EMPTY;
        CompilationUnit cu = SourceParser.parse(source);
        IProblem problem = SourceParser.firstSyntaxError(cu);
        if (problem != null) {
            return Result.failed("Settings.java did not parse (line " + problem.getSourceLineNumber() + ": "
                    + problem.getMessage() + "); its values were not loaded.");
        }

        TypeDeclaration type = firstType(cu);
        if (type == null) return Result.failed("Settings.java declares no class; its values were not loaded.");

        Map<String, String> initializers = initializers(type, source);
        List<Setting> settings = new ArrayList<>();
        List<RawSetting> unknown = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (FieldDeclaration field : type.getFields()) {
            NormalAnnotation annotation = settingAnnotation(field);
            if (annotation == null) continue;                       // somebody's own constant; not ours to read
            String name = fieldName(field);
            if (name == null) continue;

            Map<String, Expression> values = elements(annotation);
            SettingType settingType = SettingType.fromId(string(values.get("type")));
            if (settingType == null) {
                unknown.add(new RawSetting(name, text(annotation, source),
                        field.getType().toString(), initializers.get(name)));
                warnings.add("Setting " + name + " has a type this version does not know ("
                        + string(values.get("type")) + "); it was left untouched.");
                continue;
            }
            if (settingType == SettingType.ENABLE) continue;        // restored from activities.json, not here

            settings.add(toSetting(name, settingType, values));
        }
        return new Result(settings, unknown, warnings);
    }

    /** One field's annotation turned into a normalised {@link Setting}. */
    private static Setting toSetting(String name, SettingType type, Map<String, Expression> values) {
        List<String> wire = type.isMultiValued()
                ? array(values.get("values"))
                : List.of(stringOrEmpty(values.get("value")));
        ParamVisibility visibility = "true".equals(string(values.get("shared")))
                ? ParamVisibility.PUBLIC : ParamVisibility.EDITOR_ONLY;
        Setting.Bounds bounds = new Setting.Bounds(string(values.get("min")), string(values.get("max")),
                string(values.get("step")));
        return new Setting(name, type, stringOrEmpty(values.get("tag")), stringOrEmpty(values.get("label")),
                visibility, wire, array(values.get("options")), bounds).normalized();
    }

    /** The first class in the unit — {@code Settings} itself; a generated file has exactly one. */
    private static TypeDeclaration firstType(CompilationUnit cu) {
        for (AbstractTypeDeclaration declared : (List<AbstractTypeDeclaration>) cu.types()) {
            if (declared instanceof TypeDeclaration t && !t.isInterface()) return t;
        }
        return null;
    }

    /**
     * Every {@code NAME = <expression>;} in the static block, by name — the source text only, and only so an
     * unrecognised field can be written back with the value it had.
     */
    private static Map<String, String> initializers(TypeDeclaration type, String source) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (BodyDeclaration declaration : (List<BodyDeclaration>) type.bodyDeclarations()) {
            if (!(declaration instanceof Initializer initializer)
                    || !Modifier.isStatic(initializer.getModifiers())) {
                continue;
            }
            for (Statement statement : (List<Statement>) initializer.getBody().statements()) {
                if (statement instanceof ExpressionStatement expression
                        && expression.getExpression() instanceof Assignment assignment
                        && assignment.getLeftHandSide() instanceof SimpleName target) {
                    byName.put(target.getIdentifier(), text(assignment.getRightHandSide(), source));
                }
            }
        }
        return byName;
    }

    /** The field's {@code @Setting(…)}, or null when it has none or one that could not have compiled. */
    private static NormalAnnotation settingAnnotation(FieldDeclaration field) {
        for (IExtendedModifier modifier : (List<IExtendedModifier>) field.modifiers()) {
            if (modifier instanceof Annotation annotation
                    && annotation.getTypeName().getFullyQualifiedName()
                    .endsWith(SettingsClassWriter.ANNOTATION_CLASS)) {
                return annotation instanceof NormalAnnotation normal ? normal : null;
            }
        }
        return null;
    }

    /** The declared name, from the first (and for a generated file, only) fragment. */
    private static String fieldName(FieldDeclaration field) {
        for (VariableDeclarationFragment fragment : (List<VariableDeclarationFragment>) field.fragments()) {
            return fragment.getName().getIdentifier();
        }
        return null;
    }

    /** The annotation's elements by name. */
    private static Map<String, Expression> elements(NormalAnnotation annotation) {
        Map<String, Expression> byName = new LinkedHashMap<>();
        for (MemberValuePair pair : (List<MemberValuePair>) annotation.values()) {
            byName.put(pair.getName().getIdentifier(), pair.getValue());
        }
        return byName;
    }

    /** A string or boolean element's value, or null when it is absent or something else entirely. */
    private static String string(Expression expression) {
        if (expression instanceof StringLiteral literal) return literal.getLiteralValue();
        if (expression instanceof BooleanLiteral literal) return Boolean.toString(literal.booleanValue());
        return null;
    }

    private static String stringOrEmpty(Expression expression) {
        String value = string(expression);
        return value == null ? "" : value;
    }

    /** A {@code {"a", "b"}} element's entries; empty for an absent one. */
    private static List<String> array(Expression expression) {
        if (!(expression instanceof ArrayInitializer initializer)) return List.of();
        List<String> values = new ArrayList<>();
        for (Expression entry : (List<Expression>) initializer.expressions()) {
            String value = string(entry);
            if (value != null) values.add(value);
        }
        return values;
    }

    /** The exact source {@code node} was built from — not {@code toString()}, which reformats. */
    private static String text(org.eclipse.jdt.core.dom.ASTNode node, String source) {
        int start = node.getStartPosition();
        return source.substring(start, start + node.getLength());
    }
}
