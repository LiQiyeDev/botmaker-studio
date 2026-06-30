package com.botmaker.project;

import org.eclipse.jdt.core.dom.CompilationUnit;
import java.nio.file.Path;

public class ProjectFile {
    private final Path path;
    private String content;
    private CompilationUnit ast;
    private final String className; // e.g., "Movement"

    public ProjectFile(Path path, String content) {
        this.path = path;
        this.content = content;
        String filename = path.getFileName().toString();
        this.className = filename.substring(0, filename.lastIndexOf('.'));
    }

    public Path getPath() { return path; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public CompilationUnit getAst() { return ast; }
    public void setAst(CompilationUnit ast) { this.ast = ast; }

    public String getClassName() { return className; }

    public String getUri() {
        return path.toUri().toString();
    }
}