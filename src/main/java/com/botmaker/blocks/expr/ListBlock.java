package com.botmaker.blocks.expr;

import com.botmaker.core.AbstractExpressionBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.palette.ExpressionCatalog;
import com.botmaker.palette.ExpressionType;
import com.botmaker.types.ResolvedType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import com.botmaker.ui.render.menu.MenuComponents;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

public class ListBlock extends AbstractExpressionBlock {

    private final List<ExpressionBlock> elements = new ArrayList<>();
    private final boolean isFixedArray;

    public ListBlock(String id, ASTNode astNode) {
        super(id, astNode);
        this.isFixedArray = (astNode instanceof ArrayInitializer);
    }

    public void addElement(ExpressionBlock element) { this.elements.add(element); }
    public List<ExpressionBlock> getElements() { return elements; }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);
        container.setAlignment(Pos.TOP_LEFT);
        container.getStyleClass().add("list-block");

        boolean isNested = (this.astNode.getParent() instanceof ArrayInitializer) ||
                (this.astNode.getParent() instanceof MethodInvocation);

        if (isNested) {
            container.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 6; -fx-border-color: rgba(255,255,255,0.15); -fx-border-width: 1;");
            container.setPadding(new Insets(4, 6, 4, 6));
        } else {
            container.setPadding(new Insets(6, 10, 6, 10));
        }

        HBox headerRow = new HBox(8);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // Use the FIXED determineItemType
        ResolvedType itemType = determineItemType();

        String typeLabel = isFixedArray ? "Array" : "List";
        Label listLabel = new Label(typeLabel + " (" + elements.size() + ")");
        listLabel.getStyleClass().add("list-label");
        if (!isFixedArray) listLabel.setStyle("-fx-text-fill: #aaddff;");

        Button addButton = new Button("+");
        addButton.getStyleClass().add("expression-add-button");
        addButton.setStyle("-fx-font-size: 10px; -fx-padding: 2px 8px;");
        addButton.setOnAction(e -> showAddElementMenu(addButton, context, elements.size(), itemType));

        headerRow.getChildren().addAll(listLabel, addButton);
        container.getChildren().add(headerRow);

        if (elements.isEmpty()) {
            Label emptyLabel = new Label(" (empty) ");
            emptyLabel.setStyle("-fx-font-style: italic; -fx-text-fill: rgba(255,255,255,0.4); -fx-font-size: 10px;");
            container.getChildren().add(emptyLabel);
        } else {
            VBox elementsContainer = new VBox(3);
            elementsContainer.setPadding(new Insets(2, 0, 0, 12));
            for (int i = 0; i < elements.size(); i++) {
                HBox elementRow = createElementRow(i, elements.get(i), context, itemType);
                elementsContainer.getChildren().add(elementRow);
            }
            container.getChildren().add(elementsContainer);
        }
        return container;
    }

    private HBox createElementRow(int index, ExpressionBlock element, CodeEditorService context, ResolvedType itemType) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        Label indexLabel = new Label(String.valueOf(index));
        indexLabel.setStyle("-fx-font-family: monospace; -fx-text-fill: #666; -fx-font-size: 9px; -fx-min-width: 10px;");

        Node elementNode = element.getUINode(context);
        if (element instanceof ListBlock) HBox.setHgrow(elementNode, javafx.scene.layout.Priority.ALWAYS);

        Button changeButton = new Button("+");
        changeButton.getStyleClass().add("icon-button");
        changeButton.setStyle("-fx-font-size: 8px; -fx-padding: 1px 4px; -fx-opacity: 0.3;");
        changeButton.setOnAction(e -> showChangeElementMenu(changeButton, context, index, itemType));

        Button deleteButton = new Button("✕");
        deleteButton.getStyleClass().add("icon-button");
        deleteButton.setStyle("-fx-font-size: 8px; -fx-padding: 1px 4px; -fx-text-fill: #ff5555; -fx-opacity: 0.3;");
        deleteButton.setOnAction(e -> deleteElement(index, context));

        row.setOnMouseEntered(e -> {
            changeButton.setStyle("-fx-font-size: 8px; -fx-padding: 1px 4px; -fx-opacity: 1.0;");
            deleteButton.setStyle("-fx-font-size: 8px; -fx-padding: 1px 4px; -fx-text-fill: #ff5555; -fx-opacity: 1.0;");
        });
        row.setOnMouseExited(e -> {
            changeButton.setStyle("-fx-font-size: 8px; -fx-padding: 1px 4px; -fx-opacity: 0.3;");
            deleteButton.setStyle("-fx-font-size: 8px; -fx-padding: 1px 4px; -fx-text-fill: #ff5555; -fx-opacity: 0.3;");
        });

        row.getChildren().addAll(indexLabel, elementNode, changeButton, deleteButton);
        return row;
    }

    // ========================================================================
    // FIXED: Determine item type by walking up AST and calculating depth
    // ========================================================================

    private ResolvedType determineItemType() {
        ASTNode node = this.astNode;

        // Walk up to find the declared array type
        ResolvedType declaredType = findDeclaredArrayType(node);

        if (declaredType.isUnknown()) {
            return ResolvedType.UNKNOWN;
        }

        // Calculate our nesting depth (how many ArrayInitializers deep are we?)
        int depth = calculateInitializerDepth(node);

        // Element type = declared dimensions - depth
        int declaredDims = declaredType.arrayDimensions();
        int elementDims = declaredDims - depth;

        ResolvedType elementType;
        if (elementDims > 0) {
            elementType = declaredType.leafType().asArray(elementDims);
        } else {
            elementType = declaredType.leafType();
        }

        return elementType;
    }

    /**
     * Finds the declared type of the array by walking up the AST.
     */
    private ResolvedType findDeclaredArrayType(ASTNode node) {
        ASTNode current = node;

        while (current != null) {
            ASTNode parent = current.getParent();

            if (parent == null) {
                break;
            }

            // Case 1: ArrayCreation - new int[][] { ... }
            if (parent instanceof ArrayCreation) {
                ArrayCreation ac = (ArrayCreation) parent;
                ArrayType arrayType = ac.getType();

                // Try to get binding first
                ITypeBinding binding = arrayType.resolveBinding();
                if (binding != null) {
                    return ResolvedType.of(binding);
                }

                // Fallback to string representation
                String typeStr = arrayType.toString();
                return ResolvedType.named(typeStr);
            }

            // Case 2: VariableDeclarationFragment - int[][] x = { ... } (without 'new')
            if (parent instanceof VariableDeclarationFragment) {
                VariableDeclarationFragment frag = (VariableDeclarationFragment) parent;
                ASTNode grandParent = frag.getParent();

                Type type = null;
                if (grandParent instanceof VariableDeclarationStatement) {
                    type = ((VariableDeclarationStatement) grandParent).getType();
                } else if (grandParent instanceof FieldDeclaration) {
                    type = ((FieldDeclaration) grandParent).getType();
                }

                if (type != null) {
                    ITypeBinding binding = type.resolveBinding();
                    if (binding != null) {
                        return ResolvedType.of(binding);
                    }
                    String typeStr = type.toString();
                    return ResolvedType.named(typeStr);
                }
            }

            // Case 3: MethodInvocation argument - Arrays.asList({...})
            if (parent instanceof MethodInvocation) {
                MethodInvocation mi = (MethodInvocation) parent;
                int argIndex = mi.arguments().indexOf(current);
                if (argIndex >= 0) {
                    IMethodBinding methodBinding = mi.resolveMethodBinding();
                    if (methodBinding != null && argIndex < methodBinding.getParameterTypes().length) {
                        ITypeBinding paramType = methodBinding.getParameterTypes()[argIndex];
                        return ResolvedType.of(paramType);
                    }
                }
            }

            current = parent;
        }

        return ResolvedType.UNKNOWN;
    }

    /**
     * Calculates how many ArrayInitializer levels deep this node is.
     * The outermost ArrayInitializer is depth 1.
     */
    private int calculateInitializerDepth(ASTNode node) {
        int depth = 0;
        ASTNode current = node;

        while (current != null) {
            if (current instanceof ArrayInitializer) {
                depth++;
            }

            ASTNode parent = current.getParent();

            // Stop when we hit the declaration/creation
            if (parent instanceof ArrayCreation ||
                    parent instanceof VariableDeclarationFragment ||
                    (parent instanceof MethodInvocation && !(current instanceof ArrayInitializer))) {
                break;
            }

            current = parent;
        }

        return depth;
    }

    private void showAddElementMenu(Button button, CodeEditorService context, int insertIndex, ResolvedType targetType) {
        List<ExpressionType> options = ExpressionCatalog.getForType(targetType, context.getState());
        MenuComponents.showListMenu(button, options, ExpressionType::displayName,
                type -> context.getCodeEditor().addElementToList(this.astNode, type, insertIndex),
                "(No valid expressions for " + targetType.simpleName() + ")");
    }

    private void showChangeElementMenu(Button button, CodeEditorService context, int elementIndex, ResolvedType targetType) {
        if (elementIndex < elements.size()) {
            ExpressionBlock oldElement = elements.get(elementIndex);
            Expression oldExpr = (Expression) oldElement.getAstNode();

            // Delegate to the shared menu logic in AbstractExpressionBlock
            // Use oldExpr as the 'toReplace' target
            // Use this.astNode (the array initializer/list) as context for scope resolution
            showExpressionMenuAndReplace(button, context, targetType, oldExpr);
        }
    }

    private void deleteElement(int index, CodeEditorService context) {
        if (index >= 0 && index < elements.size()) {
            context.getCodeEditor().deleteElementFromList(this.astNode, index);
        }
    }

    @Override
    public String getDetails() { return (isFixedArray ? "Array" : "List") + " (" + elements.size() + " items)"; }
}