package com.botmaker.studio.validation;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ErrorTranslator {

    private static final Map<Integer, ErrorInfo> ERROR_MAPPINGS = new HashMap<>();
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\\b(\\d{7,})\\b");

    static class ErrorInfo {
        String userMessage;
        String suggestion;
        DiagnosticSeverity severity;

        ErrorInfo(String userMessage, String suggestion, DiagnosticSeverity severity) {
            this.userMessage = userMessage;
            this.suggestion = suggestion;
            this.severity = severity;
        }

        ErrorInfo(String userMessage, String suggestion) {
            this(userMessage, suggestion, DiagnosticSeverity.Error);
        }
    }

    static {
        loadSyntaxErrors();
        loadTypeErrors();
        loadVariableErrors();
        loadMethodErrors();
        loadFlowControlErrors();
        loadWarnings();
    }

    private static void loadSyntaxErrors() {
        // IProblem.MissingSemiColon
        ERROR_MAPPINGS.put(IProblem.MissingSemiColon, new ErrorInfo(
                "Missing semicolon (;) at the end of the line",
                "Add a semicolon (;) at the end of this statement."
        ));

        // IProblem.UnterminatedString
        ERROR_MAPPINGS.put(IProblem.UnterminatedString, new ErrorInfo(
                "Text is missing a closing quote",
                "Add a closing quote (\") at the end of the text."
        ));

        // IProblem.ParsingErrorNoSuggestion
        ERROR_MAPPINGS.put(IProblem.ParsingErrorNoSuggestion, new ErrorInfo(
                "Syntax error: The code structure is incorrect",
                "Check for missing brackets, parentheses, or other syntax issues."
        ));

        // IProblem.UnmatchedBracket
        ERROR_MAPPINGS.put(IProblem.UnmatchedBracket, new ErrorInfo(
                "Unmatched bracket - missing opening or closing bracket",
                "Check that all { } brackets are properly paired."
        ));

        // IProblem.EndOfSource (Missing closing bracket at EOF)
        ERROR_MAPPINGS.put(IProblem.EndOfSource, new ErrorInfo(
                "Unexpected end of file",
                "Did you forget a closing bracket '}' at the end of your code?"
        ));

        // IProblem.InvalidExpressionAsStatement
        ERROR_MAPPINGS.put(IProblem.InvalidExpressionAsStatement, new ErrorInfo(
                "This line of code doesn't do anything",
                "Ensure you are assigning a value, calling a function, or performing an action."
        ));
    }

    private static void loadTypeErrors() {
        // IProblem.TypeMismatch
        ERROR_MAPPINGS.put(IProblem.TypeMismatch, new ErrorInfo(
                "Wrong type used: You're trying to use a {0} where a {1} is expected",
                "Check that you're using the right type of value (number, text, true/false, etc.)"
        ));

        // IProblem.UndefinedType
        ERROR_MAPPINGS.put(IProblem.UndefinedType, new ErrorInfo(
                "Type '{0}' cannot be found",
                "This type doesn't exist. Check for typos or missing imports."
        ));

        // IProblem.IncompatibleTypesInEqualityOperator
        ERROR_MAPPINGS.put(IProblem.IncompatibleTypesInEqualityOperator, new ErrorInfo(
                "Cannot compare these two different types",
                "You are trying to compare incompatible things (like a number and text)."
        ));
    }

    private static void loadVariableErrors() {
        // IProblem.UndefinedName
        ERROR_MAPPINGS.put(IProblem.UndefinedName, new ErrorInfo(
                "Variable or name '{0}' doesn't exist",
                "Did you forget to create this variable? Check for typos in the name."
        ));

        // IProblem.UninitializedLocalVariable
        ERROR_MAPPINGS.put(IProblem.UninitializedLocalVariable, new ErrorInfo(
                "Variable '{0}' is used before being given a value",
                "Set a value to this variable before using it."
        ));

        // IProblem.RedefinedLocal
        ERROR_MAPPINGS.put(IProblem.RedefinedLocal, new ErrorInfo(
                "A variable named '{0}' already exists",
                "Choose a different name or remove the duplicate variable."
        ));

        // IProblem.UndefinedField
        ERROR_MAPPINGS.put(IProblem.UndefinedField, new ErrorInfo(
                "Field '{0}' doesn't exist",
                "This field is not defined. Check the name and spelling."
        ));

        // IProblem.FinalFieldAssignment
        ERROR_MAPPINGS.put(IProblem.FinalFieldAssignment, new ErrorInfo(
                "Cannot change the value of '{0}'",
                "This variable is a constant or final and cannot be modified after it is created."
        ));
    }

    private static void loadMethodErrors() {
        // IProblem.UndefinedMethod
        ERROR_MAPPINGS.put(IProblem.UndefinedMethod, new ErrorInfo(
                "Method '{0}' doesn't exist",
                "Check the spelling of the method name or if it's available."
        ));

        // IProblem.ParameterMismatch
        ERROR_MAPPINGS.put(IProblem.ParameterMismatch, new ErrorInfo(
                "Wrong number of parameters: Expected {0} but got {1}",
                "Check how many inputs this function needs."
        ));

        // IProblem.ShouldReturnValue
        ERROR_MAPPINGS.put(IProblem.ShouldReturnValue, new ErrorInfo(
                "This function must return a value",
                "Add a return statement with a value at the end of the function."
        ));

        // IProblem.VoidMethodReturnsValue
        ERROR_MAPPINGS.put(IProblem.VoidMethodReturnsValue, new ErrorInfo(
                "This function is 'void' and cannot return a value",
                "Remove the value from the return statement, or change the function type."
        ));

        // IProblem.StaticMethodRequested
        ERROR_MAPPINGS.put(IProblem.StaticMethodRequested, new ErrorInfo(
                "Cannot call static method '{0}' from a non-static context",
                "You are trying to use a static function as if it belonged to a specific object instance."
        ));

        // IProblem.DuplicateMethod
        ERROR_MAPPINGS.put(IProblem.DuplicateMethod, new ErrorInfo(
                "Duplicate method '{0}'",
                "You have two functions with the exact same name and parameters. Rename one of them."
        ));
    }

    private static void loadFlowControlErrors() {
        // IProblem.InvalidBreak
        ERROR_MAPPINGS.put(IProblem.InvalidBreak, new ErrorInfo(
                "'break' can only be used inside a loop or switch",
                "Move this break statement inside a loop block."
        ));

        // IProblem.InvalidContinue
        ERROR_MAPPINGS.put(IProblem.InvalidContinue, new ErrorInfo(
                "'continue' can only be used inside a loop",
                "Move this continue statement inside a loop block."
        ));

        // IProblem.CodeCannotBeReached
        ERROR_MAPPINGS.put(IProblem.CodeCannotBeReached, new ErrorInfo(
                "This code will never run (unreachable code)",
                "Remove this code or fix the logic that prevents it from running.",
                DiagnosticSeverity.Warning
        ));
    }

    private static void loadWarnings() {
        // IProblem.LocalVariableIsNeverUsed
        ERROR_MAPPINGS.put(IProblem.LocalVariableIsNeverUsed, new ErrorInfo(
                "Variable '{0}' is created but never used",
                "Remove this variable or use it somewhere in your code.",
                DiagnosticSeverity.Warning
        ));

        // IProblem.ArgumentIsNeverUsed
        ERROR_MAPPINGS.put(IProblem.ArgumentIsNeverUsed, new ErrorInfo(
                "Parameter '{0}' is never used",
                "Remove this parameter or use it in the function.",
                DiagnosticSeverity.Warning
        ));

        // IProblem.AssignmentHasNoEffect
        ERROR_MAPPINGS.put(IProblem.AssignmentHasNoEffect, new ErrorInfo(
                "This assignment does nothing",
                "You're assigning a variable to itself. Remove this line or fix the logic.",
                DiagnosticSeverity.Warning
        ));
    }

    /**
     * Extracts the JDT error code from a diagnostic message
     */
    private static Integer extractErrorCode(String message) {
        Matcher matcher = ERROR_CODE_PATTERN.matcher(message);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Translates diagnostics to user-friendly messages
     */
    public static String translate(List<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "✅ No errors found. Your code looks good!";
        }

        StringBuilder result = new StringBuilder();
        int errorCount = 0;
        int warningCount = 0;

        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.getSeverity() == DiagnosticSeverity.Error) {
                errorCount++;
            } else if (diagnostic.getSeverity() == DiagnosticSeverity.Warning) {
                warningCount++;
            }
        }

        if (errorCount > 0) {
            result.append(String.format("❌ Found %d error%s:\n\n", errorCount, errorCount == 1 ? "" : "s"));
        }
        if (warningCount > 0) {
            result.append(String.format("⚠️  Found %d warning%s:\n\n", warningCount, warningCount == 1 ? "" : "s"));
        }

        for (Diagnostic diagnostic : diagnostics) {
            String icon = diagnostic.getSeverity() == DiagnosticSeverity.Error ? "❌" : "⚠️";
            int lineNumber = diagnostic.getRange().getStart().getLine() + 1;

            String translated = translateSingleDiagnostic(diagnostic);
            result.append(String.format("%s Line %d: %s\n\n", icon, lineNumber, translated));
        }

        return result.toString().trim();
    }

    /**
     * Translates a single diagnostic to a user-friendly message
     */
    public static String translateSingleDiagnostic(Diagnostic diagnostic) {
        String originalMessage = diagnostic.getMessage();

        // Try to extract error code
        Integer errorCode = extractErrorCode(originalMessage);

        if (errorCode != null && ERROR_MAPPINGS.containsKey(errorCode)) {
            ErrorInfo info = ERROR_MAPPINGS.get(errorCode);

            // Try to extract variable/type names from the original message
            String enrichedMessage = enrichMessage(info.userMessage, originalMessage);

            return String.format("%s\n   💡 %s", enrichedMessage, info.suggestion);
        }

        // Fallback: Try pattern matching for common error messages (backward compatibility)
        return translateByPattern(originalMessage);
    }

    /**
     * Enriches the user message with context from the original error message
     */
    private static String enrichMessage(String template, String originalMessage) {
        // Extract quoted strings (variable names, type names, etc.)
        Pattern quotedPattern = Pattern.compile("'([^']+)'|\"([^\"]+)\"");
        Matcher matcher = quotedPattern.matcher(originalMessage);

        int index = 0;
        String result = template;
        while (matcher.find() && result.contains("{" + index + "}")) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            result = result.replace("{" + index + "}", "'" + value + "'");
            index++;
        }

        // Remove unreplaced placeholders
        result = result.replaceAll("\\{\\d+\\}", "the value");

        return result;
    }

    /**
     * Fallback pattern-based translation (for backward compatibility)
     */
    private static String translateByPattern(String message) {
        if (message.contains("cannot be resolved to a type")) {
            return "A type or class could not be found.\n   💡 Check for typos or if the type exists.";
        }
        if (message.contains("cannot be resolved")) {
            return "A variable, method, or name could not be found.\n   💡 Check for typos or if it was declared.";
        }
        if (message.contains("Syntax error, insert")) {
            try {
                String suggestion = message.split("insert \"")[1].split("\" to")[0];
                return String.format("Syntax error: Something is missing.\n   💡 Try adding '%s'", suggestion);
            } catch (Exception e) {
                return "Syntax error: Something is missing in the code structure.\n   💡 Check for missing semicolons, brackets, or parentheses.";
            }
        }
        if (message.contains("incompatible types") || message.contains("Type mismatch")) {
            return "Wrong type used: You're using a value of the wrong type.\n   💡 Make sure you're using the right kind of value (number, text, etc.)";
        }
        if (message.contains("might not have been initialized")) {
            return "Variable used before being set.\n   💡 Give this variable a value before using it.";
        }
        if (message.contains("is not a statement")) {
            return "This line is not a valid statement.\n   💡 It might be an incomplete expression or command.";
        }
        if (message.contains("Duplicate local variable")) {
            return "A variable with this name already exists.\n   💡 Choose a different name for this variable.";
        }

        // Return original message if no translation found
        return message;
    }

    /**
     * Get a short summary for UI display (e.g., tooltip)
     */
    public static String getShortSummary(Diagnostic diagnostic) {
        String originalMessage = diagnostic.getMessage();
        Integer errorCode = extractErrorCode(originalMessage);

        if (errorCode != null && ERROR_MAPPINGS.containsKey(errorCode)) {
            ErrorInfo info = ERROR_MAPPINGS.get(errorCode);
            return enrichMessage(info.userMessage, originalMessage);
        }

        // Fallback: Return first line of original message
        return originalMessage.split("\n")[0];
    }

    /**
     * Get just the suggestion part
     */
    public static String getSuggestion(Diagnostic diagnostic) {
        String originalMessage = diagnostic.getMessage();
        Integer errorCode = extractErrorCode(originalMessage);

        if (errorCode != null && ERROR_MAPPINGS.containsKey(errorCode)) {
            return ERROR_MAPPINGS.get(errorCode).suggestion;
        }

        return "Check your code for issues.";
    }
}