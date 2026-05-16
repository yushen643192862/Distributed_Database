package parser.parser;

import parser.lexer.Token;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class ASTPrinter {
    public String print(ASTNode node) {
        StringBuilder builder = new StringBuilder();
        printValue(node, builder, 0, null);
        return builder.toString();
    }

    private void printValue(Object value, StringBuilder builder, int indent, String fieldName) {
        printIndent(builder, indent);
        if (fieldName != null) {
            builder.append(fieldName).append(": ");
        }

        if (value == null) {
            builder.append("null").append(System.lineSeparator());
        } else if (value instanceof ASTNode) {
            printNode((ASTNode) value, builder, indent);
        } else if (value instanceof List<?>) {
            printList((List<?>) value, builder, indent);
        } else if (value instanceof Token) {
            printToken((Token) value, builder);
        } else {
            builder.append(value).append(System.lineSeparator());
        }
    }

    private void printNode(ASTNode node, StringBuilder builder, int indent) {
        builder.append(node.getClass().getSimpleName()).append(System.lineSeparator());
        for (Field field : getFields(node.getClass())) {
            try {
                printValue(field.get(node), builder, indent + 1, field.getName());
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot print field: " + field.getName(), e);
            }
        }
    }

    private List<Field> getFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != ASTNode.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private void printList(List<?> list, StringBuilder builder, int indent) {
        builder.append("[").append(System.lineSeparator());
        for (Object item : list) {
            printValue(item, builder, indent + 1, null);
        }
        printIndent(builder, indent);
        builder.append("]").append(System.lineSeparator());
    }

    private void printToken(Token token, StringBuilder builder) {
        builder.append("Token(")
                .append(token.type)
                .append(", ")
                .append(token.value)
                .append(")")
                .append(System.lineSeparator());
    }

    private void printIndent(StringBuilder builder, int indent) {
        for (int i = 0; i < indent; i++) {
            builder.append("  ");
        }
    }
}
