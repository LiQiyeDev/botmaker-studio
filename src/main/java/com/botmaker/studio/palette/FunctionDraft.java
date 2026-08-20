package com.botmaker.studio.palette;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A function the user is about to add: its name, what it gives back, and what it takes. The output of the Add
 * Function dialog and the input to {@code CodeEditor.addFunctionToClass} — pure data with no JDT in it, so the
 * rules below are testable without a parser and without a scene.
 *
 * <h2>Why the rules live here</h2>
 *
 * <p>"+ Add Function" used to write {@code public static void newMethod()} directly, with no name, no return
 * type and no parameters to choose — and no check that {@code newMethod} was free. Adding it twice produced
 * two identical methods, which is a compile error the user gets as a red squiggle some seconds later, in a
 * file they did not think they had broken. A name is refused <em>before</em> the edit here instead.
 *
 * <p>The three refusals are what Java itself would refuse, said earlier and in the second person: a name has
 * to be an identifier, it cannot be a keyword, and the <em>signature</em> cannot be one this class already
 * declares. That last one takes the class's <em>own</em> methods — read from the AST, not from what is
 * rendered — so the generated members an activity no longer draws ({@code run}, {@code isEnabled}) still
 * count as taken.
 *
 * <h2>Why a signature and not a name</h2>
 *
 * <p>The check used to be on the name alone, which refused {@code click(Point)} because {@code click(int, int)}
 * existed — an overload, which Java is perfectly happy with and which the user had every reason to want. What
 * Java actually refuses is two methods with the same name <em>and</em> the same erased parameter types, so that
 * is what {@link #signatureKey()} spells and what is compared. Erased, because {@code List<Point>} and
 * {@code List<Rect>} are the same signature to the compiler however different they read; and by simple name,
 * because {@code java.time.Duration} here and {@code Duration} in a hand-written file are one type.
 */
public record FunctionDraft(String name, SignatureType returnType, List<Parameter> parameters) {

    public FunctionDraft {
        parameters = List.copyOf(parameters);
    }

    /** The common case, written the way every caller that only deals in curated types already writes it. */
    public FunctionDraft(String name, BotType.Choice returnType, List<Parameter> parameters) {
        this(name, SignatureType.of(returnType), parameters);
    }

    /**
     * One parameter: {@code <type> <name>}, plus <b>where it came from</b>.
     *
     * <p>{@code origin} is the index this parameter held in the method being edited, or {@link #NEW} for one
     * the user has just added. It is what makes a <em>reorder</em> a reorder: matched by position, moving the
     * second row above the first is read as "parameter 1 was renamed and retyped, and so was parameter 2" —
     * two silent retypes of the wrong things, and every call site left arguing with the new order. Matched by
     * origin, the same gesture moves the parameter, name, type and all.
     *
     * <p>It is deliberately not a generated id: the dialog reads a draft out of the AST, hands rows to the
     * user and reads a draft back, so an index into the list it was read from is exactly as much identity as
     * exists. Nothing persists it, and a draft built from scratch is all {@link #NEW}.
     */
    public record Parameter(String name, SignatureType type, int origin) {

        /** The origin of a parameter that did not exist before this edit. */
        public static final int NEW = -1;

        public Parameter(String name, SignatureType type) {
            this(name, type, NEW);
        }

        public Parameter(String name, BotType.Choice type) {
            this(name, SignatureType.of(type));
        }

        /** True when nothing in the method being edited corresponds to this parameter. */
        public boolean isNew() {
            return origin < 0;
        }

        /** The same parameter, said to have come from index {@code origin} of the method being edited. */
        public Parameter withOrigin(int origin) {
            return new Parameter(name, type, origin);
        }
    }

    /** A void function with no parameters — what the dialog opens on. */
    public static FunctionDraft empty() {
        return new FunctionDraft("", BotType.Choice.of(BotType.NOTHING), List.of());
    }

    /**
     * Java's reserved words, plus the three literals that are not keywords but are just as unusable as names.
     * {@code var}, {@code yield}, {@code record} and friends are <em>contextual</em> keywords and are legal
     * method names, so they are deliberately not here — refusing them would be inventing a rule.
     */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null");

    /** Why {@code name} cannot be a function name at all, or empty when it can. Says nothing about clashes. */
    public static Optional<String> nameProblem(String name) {
        return identifierProblem(name, "function");
    }

    /** Why {@code name} cannot be a parameter name, or empty when it can. {@code others} are its siblings. */
    public static Optional<String> parameterNameProblem(String name, List<String> others) {
        Optional<String> identifier = identifierProblem(name, "parameter");
        if (identifier.isPresent()) return identifier;
        if (others != null && others.contains(name.trim())) {
            return Optional.of("Two parameters cannot both be called \"" + name.trim() + "\".");
        }
        return Optional.empty();
    }

    /**
     * {@code base}, or the first {@code base2}, {@code base3}… that is free.
     *
     * <p>For the one path that cannot ask: dropping the "Declare Function" palette block onto a class header
     * has no dialog to refuse into, and it wrote {@code newMethod} unconditionally — so the second drop
     * produced two methods of the same name. Uniquifying is what the dialog deliberately does <em>not</em> do,
     * because there the user typed the name and is owed an answer about it; here nobody chose it.
     */
    public static String freeName(String base, Set<String> taken) {
        if (taken == null || !taken.contains(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + i;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    private static Optional<String> identifierProblem(String raw, String what) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) return Optional.of("Give the " + what + " a name.");
        if (RESERVED.contains(name)) {
            return Optional.of("\"" + name + "\" is a Java keyword, so it cannot be a " + what + " name.");
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return Optional.of("A " + what + " name has to start with a letter.");
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return Optional.of("A " + what + " name can only contain letters, digits and _ — \""
                        + name.charAt(i) + "\" is not allowed.");
            }
        }
        return Optional.empty();
    }

    /**
     * Everything wrong with this draft, in the order the dialog's rows appear. Empty when it can be written.
     *
     * @param takenSignatures the {@link #signatureKey() signature keys} the target class already declares —
     *                        every method, generated ones included. When re-editing an existing method, its
     *                        own key must not be in here, or it would collide with itself.
     */
    public Optional<String> problem(Set<String> takenSignatures) {
        Optional<String> nameProblem = nameProblem(name);
        if (nameProblem.isPresent()) return nameProblem;

        List<String> seen = new ArrayList<>();
        for (Parameter p : parameters) {
            Optional<String> problem = parameterNameProblem(p.name(), seen);
            if (problem.isPresent()) return problem;
            seen.add(p.name().trim());
        }

        if (takenSignatures != null && takenSignatures.contains(signatureKey())) {
            return Optional.of(parameters.isEmpty()
                    ? "This class already has a function called \"" + name.trim() + "\" that takes nothing."
                    : "This class already has a function called \"" + name.trim()
                            + "\" that takes exactly these types.");
        }
        return Optional.empty();
    }

    /**
     * What Java compares two methods by: the name and the erased parameter types, as
     * {@code click(int,int)}. Two functions of this class may not share one; anything else about them —
     * the return type, the parameter <em>names</em>, the type arguments — may differ freely.
     */
    public String signatureKey() {
        return signatureKey(name, parameters.stream().map(p -> p.type().sourceName()).toList());
    }

    /**
     * The same key built from source-level type names, for the side that reads a method out of the AST rather
     * than out of a dialog. Both sides go through {@link #erase} so {@code java.time.Duration} and
     * {@code Duration}, {@code List<Point>} and {@code List}, are one type on both.
     */
    public static String signatureKey(String name, List<String> parameterTypeNames) {
        return (name == null ? "" : name.trim()) + "("
                + String.join(",", parameterTypeNames.stream().map(FunctionDraft::erase).toList()) + ")";
    }

    /** A type name reduced to what the compiler compares: no type arguments, no package. */
    public static String erase(String typeName) {
        String erased = typeName == null ? "" : typeName.trim();
        int angle = erased.indexOf('<');
        if (angle >= 0) {
            int close = erased.lastIndexOf('>');
            // Keep a trailing [] — an array of a generic is still an array. "List<Point>[]" -> "List[]".
            erased = erased.substring(0, angle) + (close >= 0 ? erased.substring(close + 1) : "");
        }
        int dot = erased.lastIndexOf('.');
        return dot >= 0 ? erased.substring(dot + 1) : erased;
    }

    /** The signature as it will read in source — for the dialog's preview line. */
    public String signature() {
        StringBuilder sb = new StringBuilder(returnType.sourceName()).append(' ')
                .append(name.isBlank() ? "…" : name.trim()).append('(');
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            Parameter p = parameters.get(i);
            sb.append(p.type().sourceName()).append(' ').append(p.name().isBlank() ? "…" : p.name().trim());
        }
        return sb.append(')').toString();
    }
}
