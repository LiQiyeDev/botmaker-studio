package com.botmaker.validation;

import com.botmaker.core.CodeBlock;
import javafx.application.Platform;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;

import java.util.*;

public class DiagnosticsManager {
    private Map<ASTNode, CodeBlock> nodeToBlockMap;
    private String sourceCode;
    private final Set<CodeBlock> blocksWithErrors = new HashSet<>();
    private List<Diagnostic> lastDiagnostics = new ArrayList<>();

    // NEW: Track line numbers to blocks for fallback matching
    private Map<Integer, Set<CodeBlock>> lineToBlocksMap = new HashMap<>();

    public List<Diagnostic> getDiagnostics() {
        return lastDiagnostics;
    }

    public boolean hasErrors() {
        if (lastDiagnostics == null || lastDiagnostics.isEmpty()) {
            return false;
        }
        return lastDiagnostics.stream().anyMatch(d -> {
            DiagnosticSeverity severity = d.getSeverity();
            return severity == null || severity == DiagnosticSeverity.Error;
        });
    }

    public void updateSource(Map<ASTNode, CodeBlock> nodeToBlockMap, String sourceCode) {
        this.nodeToBlockMap = nodeToBlockMap;
        this.sourceCode = sourceCode;

        // Build line-to-block mapping for fallback
        buildLineToBlockMap();
    }

    /**
     * Builds a map from line numbers to blocks for fallback matching
     * when AST node matching fails.
     */
    private void buildLineToBlockMap() {
        lineToBlocksMap.clear();

        if (nodeToBlockMap == null || sourceCode == null) {
            return;
        }

        for (Map.Entry<ASTNode, CodeBlock> entry : nodeToBlockMap.entrySet()) {
            ASTNode node = entry.getKey();
            CodeBlock block = entry.getValue();

            // Calculate which lines this node spans
            int startLine = getLineNumber(node.getStartPosition());
            int endLine = getLineNumber(node.getStartPosition() + node.getLength());

            // Add block to all lines it spans
            for (int line = startLine; line <= endLine; line++) {
                lineToBlocksMap.computeIfAbsent(line, k -> new HashSet<>()).add(block);
            }
        }
    }

    public void processDiagnostics(List<Diagnostic> diagnostics) {
        this.lastDiagnostics = diagnostics;

        // Clear previous errors
        for (CodeBlock block : blocksWithErrors) {
            block.clearError();
        }
        blocksWithErrors.clear();

        if (nodeToBlockMap == null) return;

        // Process new diagnostics
        for (Diagnostic diagnostic : diagnostics) {
            // UPDATED: Allow Warnings and Info, not just Errors
            if (diagnostic.getSeverity() == DiagnosticSeverity.Hint) {
                continue; // Still skip generic hints/hints to avoid clutter
            }

            Optional<CodeBlock> blockOpt = findBlockForDiagnostic(diagnostic);

            if (blockOpt.isPresent()) {
                CodeBlock block = blockOpt.get();

                // Use translated error message
                String userFriendlyMessage = ErrorTranslator.getShortSummary(diagnostic);
                String suggestion = ErrorTranslator.getSuggestion(diagnostic);

                String prefix = "";
                if (diagnostic.getSeverity() == DiagnosticSeverity.Warning) {
                    prefix = "[Warning] ";
                } else if (diagnostic.getSeverity() == DiagnosticSeverity.Information) {
                    prefix = "[Info] ";
                }

                block.setError(prefix + userFriendlyMessage + "\n" + suggestion);
                blocksWithErrors.add(block);
            } else {
                // Log unmapped diagnostics for debugging
                // System.err.println("Warning: Could not map diagnostic to block: " + ...);
            }
        }
    }

    /**
     * Finds the code block responsible for a diagnostic.
     * Uses multiple strategies for better matching.
     */
    public Optional<CodeBlock> findBlockForDiagnostic(Diagnostic diagnostic) {
        // Strategy 1: Precise AST node matching (best)
        Optional<CodeBlock> block = findBlockByASTNode(diagnostic);
        if (block.isPresent()) {
            return block;
        }

        // Strategy 2: Line-based fallback (good for edge cases)
        block = findBlockByLine(diagnostic);
        if (block.isPresent()) {
            return block;
        }

        // Strategy 3: Parent node search (for nested expressions)
        return findBlockByParentNode(diagnostic);
    }

    /**
     * Strategy 1: Find block by matching AST node ranges
     */
    private Optional<CodeBlock> findBlockByASTNode(Diagnostic diagnostic) {
        int startOffset = getOffsetFromPosition(diagnostic.getRange().getStart());
        int endOffset = getOffsetFromPosition(diagnostic.getRange().getEnd());

        // Find the most specific (smallest) block that contains the diagnostic range
        ASTNode bestNode = null;
        int bestLength = Integer.MAX_VALUE;

        for (ASTNode node : nodeToBlockMap.keySet()) {
            int nodeStart = node.getStartPosition();
            int nodeEnd = nodeStart + node.getLength();

            // Check if node contains the diagnostic range
            if (nodeStart <= startOffset && nodeEnd >= endOffset) {
                int nodeLength = node.getLength();

                // Prefer the smallest containing node
                if (nodeLength < bestLength) {
                    bestNode = node;
                    bestLength = nodeLength;
                }
            }
        }

        return Optional.ofNullable(bestNode).map(nodeToBlockMap::get);
    }

    /**
     * Strategy 2: Find block by line number (fallback)
     */
    private Optional<CodeBlock> findBlockByLine(Diagnostic diagnostic) {
        int line = diagnostic.getRange().getStart().getLine();

        Set<CodeBlock> blocksOnLine = lineToBlocksMap.get(line);
        if (blocksOnLine == null || blocksOnLine.isEmpty()) {
            return Optional.empty();
        }

        // If multiple blocks on same line, prefer the first one
        // (Could be improved with more sophisticated heuristics)
        return blocksOnLine.stream().findFirst();
    }

    /**
     * Strategy 3: Find block by searching parent nodes
     */
    private Optional<CodeBlock> findBlockByParentNode(Diagnostic diagnostic) {
        int startOffset = getOffsetFromPosition(diagnostic.getRange().getStart());

        // Find any node that contains the start position
        for (ASTNode node : nodeToBlockMap.keySet()) {
            int nodeStart = node.getStartPosition();
            int nodeEnd = nodeStart + node.getLength();

            if (nodeStart <= startOffset && nodeEnd >= startOffset) {
                return Optional.of(nodeToBlockMap.get(node));
            }
        }

        return Optional.empty();
    }

    /**
     * Converts LSP position to source code offset
     */
    private int getOffsetFromPosition(org.eclipse.lsp4j.Position pos) {
        int line = pos.getLine();
        int character = pos.getCharacter();
        int offset = 0;
        int currentLine = 0;

        if (sourceCode == null) return 0;

        while (currentLine < line && offset < sourceCode.length()) {
            if (sourceCode.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }

        return offset + character;
    }

    /**
     * Gets line number from character offset
     */
    private int getLineNumber(int offset) {
        if (sourceCode == null || offset < 0) return 0;

        int line = 0;
        for (int i = 0; i < Math.min(offset, sourceCode.length()); i++) {
            if (sourceCode.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Get all blocks that have errors (useful for UI highlighting)
     */
    public Set<CodeBlock> getBlocksWithErrors() {
        return Collections.unmodifiableSet(blocksWithErrors);
    }

    /**
     * Check if a specific block has errors
     */
    public boolean hasError(CodeBlock block) {
        return blocksWithErrors.contains(block);
    }

    /**
     * Get diagnostics for a specific block
     */
    public List<Diagnostic> getDiagnosticsForBlock(CodeBlock block) {
        if (lastDiagnostics == null || nodeToBlockMap == null) {
            return Collections.emptyList();
        }

        List<Diagnostic> blockDiagnostics = new ArrayList<>();

        for (Diagnostic diagnostic : lastDiagnostics) {
            Optional<CodeBlock> diagBlock = findBlockForDiagnostic(diagnostic);
            if (diagBlock.isPresent() && diagBlock.get() == block) {
                blockDiagnostics.add(diagnostic);
            }
        }

        return blockDiagnostics;
    }

    /**
     * Get a summary of all errors (useful for status bar)
     */
    public String getErrorSummary() {
        if (!hasErrors() && lastDiagnostics.isEmpty()) { // Adjusted logic
            return "✅ No errors";
        }

        long errorCount = lastDiagnostics.stream()
                .filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
                .count();

        long warningCount = lastDiagnostics.stream()
                .filter(d -> d.getSeverity() == DiagnosticSeverity.Warning)
                .count();

        if (errorCount == 0 && warningCount == 0) {
            return "✅ No errors";
        }

        StringBuilder summary = new StringBuilder();
        if (errorCount > 0) {
            summary.append(String.format("❌ %d error%s", errorCount, errorCount == 1 ? "" : "s"));
        }
        if (warningCount > 0) {
            if (summary.length() > 0) summary.append(", ");
            summary.append(String.format("⚠️ %d warning%s", warningCount, warningCount == 1 ? "" : "s"));
        }

        return summary.toString();
    }
}