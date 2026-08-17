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
 * to be an identifier, it cannot be a keyword, and it cannot be one this class already uses. That last one
 * takes the class's <em>own</em> method names — read from the AST, not from what is rendered — so the
 * generated members an activity no longer draws ({@code run}, {@code isEnabled}) still count as taken.
 */
public record FunctionDraft(String name, BotType.Choice returnType, List<Parameter> parameters) {

    public FunctionDraft {
        parameters = List.copyOf(parameters);
    }

    /** One parameter: {@code <type> <name>}. */
    public record Parameter(String name, BotType.Choice type) {}

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

    /**
     * Why {@code name} cannot be this function's name, or empty when it can.
     *
     * @param taken the names already used in the target class — every method it declares, generated ones
     *              included
     */
    public static Optional<String> nameProblem(String name, Set<String> taken) {
        Optional<String> identifier = identifierProblem(name, "function");
        if (identifier.isPresent()) return identifier;
        if (taken != null && taken.contains(name.trim())) {
            return Optional.of("This class already has a function called \"" + name.trim() + "\".");
        }
        return Optional.empty();
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

    /** Everything wrong with this draft, in the order the dialog's rows appear. Empty when it can be written. */
    public Optional<String> problem(Set<String> takenNames) {
        Optional<String> nameProblem = nameProblem(name, takenNames);
        if (nameProblem.isPresent()) return nameProblem;

        List<String> seen = new ArrayList<>();
        for (Parameter p : parameters) {
            Optional<String> problem = parameterNameProblem(p.name(), seen);
            if (problem.isPresent()) return problem;
            seen.add(p.name().trim());
        }
        return Optional.empty();
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
