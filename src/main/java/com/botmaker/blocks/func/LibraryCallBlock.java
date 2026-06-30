package com.botmaker.blocks.func;

import org.eclipse.jdt.core.dom.ASTNode;

/**
 * A specialized MethodInvocationBlock that is locked to a specific Library Class (static context).
 * Examples: ImageFinder.find(), Mouse.click().
 */
public class LibraryCallBlock extends MethodInvocationBlock {

    public LibraryCallBlock(String id, ASTNode astNode, String libraryClassName) {
        super(id, astNode);
        setFixedScope(libraryClassName);
    }

    // Optional: Override getDetails to show it's a library call
    @Override
    public String getDetails() {
        return "Library Call: " + fixedScopeName + "." + methodName + "()";
    }
}