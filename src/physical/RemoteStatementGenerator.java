package physical;

import parser.lexer.tokenType;
import parser.parser.ASTNode;
import parser.parser.AlterTableAction;
import parser.parser.Assignment;
import parser.parser.BinaryExpression;
import parser.parser.ColumnDefinition;
import parser.parser.Condition;
import parser.parser.Constraint;
import parser.parser.ConstraintType;
import parser.parser.DataType;
import parser.parser.Expression;
import parser.parser.IdentifierExpression;
import parser.parser.LiteralExpression;
import parser.parser.UnaryExpression;

import java.util.ArrayList;
import java.util.List;

public class RemoteStatementGenerator {
    private final ClusterMetadata metadata;

    public RemoteStatementGenerator(ClusterMetadata metadata) {
        this.metadata = metadata;
    }

    public List<RemoteStatement> generate(PhysicalPlan plan) {
        List<RemoteStatement> statements = new ArrayList<>();
        collect(plan, statements);
        return statements;
    }

    private void collect(PhysicalPlan plan, List<RemoteStatement> statements) {
        if (plan instanceof PhysicalProjectPlan) {
            collect(((PhysicalProjectPlan) plan).getChild(), statements);
        } else if (plan instanceof PhysicalFilterPlan) {
            collect(((PhysicalFilterPlan) plan).getChild(), statements);
        } else if (plan instanceof PhysicalJoinPlan) {
            collect(((PhysicalJoinPlan) plan).getLeft(), statements);
            collect(((PhysicalJoinPlan) plan).getRight(), statements);
        } else if (plan instanceof PhysicalAggregatePlan) {
            collect(((PhysicalAggregatePlan) plan).getChild(), statements);
        } else if (plan instanceof PhysicalSortPlan) {
            collect(((PhysicalSortPlan) plan).getChild(), statements);
        } else if (plan instanceof PhysicalLimitPlan) {
            collect(((PhysicalLimitPlan) plan).getChild(), statements);
        } else if (plan instanceof GatherPlan) {
            for (PhysicalPlan child : ((GatherPlan) plan).getChildren()) {
                collect(child, statements);
            }
        } else if (plan instanceof RemoteScanPlan) {
            RemoteScanPlan scan = (RemoteScanPlan) plan;
            statements.add(statement(scan.getNodeId(), scanStatement(scan)));
        } else if (plan instanceof RemoteInsertPlan) {
            RemoteInsertPlan insert = (RemoteInsertPlan) plan;
            statements.add(statement(insert.getNodeId(), insertStatement(insert)));
        } else if (plan instanceof RemoteMutationPlan) {
            RemoteMutationPlan mutation = (RemoteMutationPlan) plan;
            statements.add(statement(mutation.getNodeId(), mutationStatement(mutation)));
        } else if (plan instanceof RemoteDdlPlan) {
            RemoteDdlPlan ddl = (RemoteDdlPlan) plan;
            statements.add(statement(ddl.getNodeId(), ddlStatement(ddl)));
        }
    }

    private RemoteStatement statement(String nodeId, String statement) {
        DataNodeMetadata node = metadata.getNode(nodeId);
        return new RemoteStatement(nodeId, node.getDatabaseType(), statement);
    }

    private String scanStatement(RemoteScanPlan scan) {
        DatabaseType type = nodeType(scan.getNodeId());
        if (type == DatabaseType.MONGODB) {
            return "db." + scan.getShardName() + ".find("
                    + mongoFilter(scan.getFilterCondition()) + ", "
                    + mongoProjection(scan.getColumns()) + ")";
        }
        String columns = scan.getColumns().isEmpty() ? "*" : joinSqlIdentifiers(scan.getColumns(), type);
        String sql = "SELECT " + columns + " FROM " + quote(scan.getShardName(), type);
        if (scan.getFilterCondition() != null) {
            sql += " WHERE " + sqlCondition((Condition) scan.getFilterCondition(), type);
        }
        return sql + ";";
    }

    private String insertStatement(RemoteInsertPlan insert) {
        DatabaseType type = nodeType(insert.getNodeId());
        if (type == DatabaseType.MONGODB) {
            List<String> docs = new ArrayList<>();
            for (List<ASTNode> row : insert.getRows()) {
                docs.add(mongoDocument(insert.getColumns(), row));
            }
            return "db." + insert.getShardName() + ".insertMany([" + String.join(", ", docs) + "])";
        }

        List<String> rows = new ArrayList<>();
        for (List<ASTNode> row : insert.getRows()) {
            List<String> values = new ArrayList<>();
            for (ASTNode value : row) {
                values.add(sqlLiteral((LiteralExpression) value));
            }
            rows.add("(" + String.join(", ", values) + ")");
        }
        return "INSERT INTO " + quote(insert.getShardName(), type)
                + " (" + joinSqlIdentifiers(insert.getColumns(), type) + ") VALUES "
                + String.join(", ", rows) + ";";
    }

    private String mutationStatement(RemoteMutationPlan mutation) {
        DatabaseType type = nodeType(mutation.getNodeId());
        if ("Delete".equals(mutation.getKind())) {
            if (type == DatabaseType.MONGODB) {
                return "db." + mutation.getShardName() + ".deleteMany(" + mongoFilter(mutation.getCondition()) + ")";
            }
            String sql = "DELETE FROM " + quote(mutation.getShardName(), type);
            if (mutation.getCondition() != null) {
                sql += " WHERE " + sqlCondition((Condition) mutation.getCondition(), type);
            }
            return sql + ";";
        }
        if ("Update".equals(mutation.getKind())) {
            if (type == DatabaseType.MONGODB) {
                return "db." + mutation.getShardName() + ".updateMany("
                        + mongoFilter(mutation.getCondition()) + ", "
                        + "{$set: " + mongoAssignments(mutation.getAssignments()) + "})";
            }
            String sql = "UPDATE " + quote(mutation.getShardName(), type)
                    + " SET " + sqlAssignments(mutation.getAssignments(), type);
            if (mutation.getCondition() != null) {
                sql += " WHERE " + sqlCondition((Condition) mutation.getCondition(), type);
            }
            return sql + ";";
        }
        return "-- Unsupported remote mutation: " + mutation.getKind();
    }

    private String ddlStatement(RemoteDdlPlan ddl) {
        DatabaseType type = nodeType(ddl.getNodeId());
        if (type == DatabaseType.MONGODB) {
            return mongoDdlStatement(ddl);
        }
        if ("CreateTable".equals(ddl.getKind())) {
            List<String> parts = new ArrayList<>();
            for (ASTNode column : ddl.getColumns()) {
                parts.add(sqlColumnDefinition((ColumnDefinition) column, type));
            }
            for (ASTNode constraint : ddl.getConstraints()) {
                String rendered = sqlConstraint((Constraint) constraint, type);
                if (!rendered.isEmpty()) {
                    parts.add(rendered);
                }
            }
            return "CREATE TABLE " + quote(ddl.getShardName(), type)
                    + " (" + String.join(", ", parts) + ");";
        }
        if ("DropTable".equals(ddl.getKind())) {
            return "DROP TABLE " + (ddl.isIfExists() ? "IF EXISTS " : "")
                    + quote(ddl.getShardName(), type) + ";";
        }
        if ("TruncateTable".equals(ddl.getKind())) {
            return "TRUNCATE TABLE " + quote(ddl.getShardName(), type) + ";";
        }
        if ("AlterTable".equals(ddl.getKind())) {
            List<String> statements = new ArrayList<>();
            for (ASTNode action : ddl.getActions()) {
                statements.add(sqlAlterAction(ddl.getShardName(), (AlterTableAction) action, type));
            }
            return String.join(" ", statements);
        }
        return "-- Unsupported DDL: " + ddl.getKind();
    }

    private DatabaseType nodeType(String nodeId) {
        return metadata.getNode(nodeId).getDatabaseType();
    }

    private String joinSqlIdentifiers(List<String> names, DatabaseType type) {
        List<String> quoted = new ArrayList<>();
        for (String name : names) {
            quoted.add(quote(name, type));
        }
        return String.join(", ", quoted);
    }

    private String quote(String identifier, DatabaseType type) {
        if (type == DatabaseType.MYSQL) {
            return "`" + identifier + "`";
        }
        return "\"" + identifier + "\"";
    }

    private String sqlCondition(Condition condition, DatabaseType type) {
        if (condition.logicalOperator != null && condition.leftCondition != null && condition.rightCondition != null) {
            return "(" + sqlCondition(condition.leftCondition, type) + " "
                    + condition.logicalOperator.type + " "
                    + sqlCondition(condition.rightCondition, type) + ")";
        }
        if (condition.logicalOperator != null && condition.rightCondition != null) {
            return condition.logicalOperator.type + " " + sqlCondition(condition.rightCondition, type);
        }
        String result = sqlExpression(condition.left, type);
        if (condition.operator != null) {
            result += " " + sqlOperator(condition.operator.type) + " " + sqlExpression(condition.right, type);
        }
        return result;
    }

    private String sqlExpression(Expression expression, DatabaseType type) {
        if (expression instanceof IdentifierExpression) {
            IdentifierExpression identifier = (IdentifierExpression) expression;
            return quote(identifier.name, type);
        }
        if (expression instanceof LiteralExpression) {
            return sqlLiteral((LiteralExpression) expression);
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binary = (BinaryExpression) expression;
            return "(" + sqlExpression(binary.left, type) + " "
                    + sqlOperator(binary.operator.type) + " "
                    + sqlExpression(binary.right, type) + ")";
        }
        if (expression instanceof UnaryExpression) {
            UnaryExpression unary = (UnaryExpression) expression;
            return sqlOperator(unary.operator.type) + " " + sqlExpression(unary.expression, type);
        }
        return expression == null ? "NULL" : expression.getClass().getSimpleName();
    }

    private String sqlOperator(tokenType type) {
        if (type == tokenType.EQ) {
            return "=";
        }
        if (type == tokenType.NE) {
            return "<>";
        }
        if (type == tokenType.GE) {
            return ">=";
        }
        if (type == tokenType.LE) {
            return "<=";
        }
        if (type == tokenType.GT) {
            return ">";
        }
        if (type == tokenType.LT) {
            return "<";
        }
        if (type == tokenType.PLUS) {
            return "+";
        }
        if (type == tokenType.SUB) {
            return "-";
        }
        if (type == tokenType.STAR) {
            return "*";
        }
        if (type == tokenType.DIVIDE) {
            return "/";
        }
        if (type == tokenType.MOD) {
            return "%";
        }
        return type.name();
    }

    private String sqlLiteral(LiteralExpression literal) {
        Object value = literal.value.value;
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String) {
            return "'" + value.toString().replace("'", "''") + "'";
        }
        return value.toString();
    }

    private String sqlAssignments(List<ASTNode> assignments, DatabaseType type) {
        List<String> parts = new ArrayList<>();
        for (ASTNode node : assignments) {
            Assignment assignment = (Assignment) node;
            parts.add(quote(assignment.columnName, type) + " = " + sqlExpression(assignment.value, type));
        }
        return String.join(", ", parts);
    }

    private String sqlColumnDefinition(ColumnDefinition column, DatabaseType type) {
        List<String> parts = new ArrayList<>();
        parts.add(quote(column.columnName, type));
        parts.add(sqlDataType(column.dataType, type));
        if (column.constraints != null) {
            for (Constraint constraint : column.constraints) {
                String rendered = sqlInlineConstraint(constraint, type);
                if (!rendered.isEmpty()) {
                    parts.add(rendered);
                }
            }
        }
        return String.join(" ", parts);
    }

    private String sqlDataType(DataType dataType, DatabaseType type) {
        String name = dataType.name;
        if (type == DatabaseType.MONGODB) {
            return name;
        }
        if (dataType.length != null) {
            return name + "(" + dataType.length + ")";
        }
        if (dataType.precision != null && dataType.scale != null) {
            return name + "(" + dataType.precision + ", " + dataType.scale + ")";
        }
        return name;
    }

    private String sqlInlineConstraint(Constraint constraint, DatabaseType type) {
        if (constraint.type == ConstraintType.NOT_NULL) {
            return "NOT NULL";
        }
        if (constraint.type == ConstraintType.UNIQUE) {
            return "UNIQUE";
        }
        if (constraint.type == ConstraintType.PRIMARY_KEY) {
            return "PRIMARY KEY";
        }
        if (constraint.type == ConstraintType.DEFAULT) {
            return "DEFAULT " + sqlExpression(constraint.expression, type);
        }
        if (constraint.type == ConstraintType.CHECK) {
            return "CHECK (" + sqlExpression(constraint.expression, type) + ")";
        }
        return "";
    }

    private String sqlConstraint(Constraint constraint, DatabaseType type) {
        if (constraint.type == ConstraintType.PRIMARY_KEY) {
            return "PRIMARY KEY (" + joinSqlIdentifiers(constraint.columns, type) + ")";
        }
        if (constraint.type == ConstraintType.UNIQUE) {
            return "UNIQUE (" + joinSqlIdentifiers(constraint.columns, type) + ")";
        }
        if (constraint.type == ConstraintType.CHECK) {
            return "CHECK (" + sqlExpression(constraint.expression, type) + ")";
        }
        return "";
    }

    private String sqlAlterAction(String shardName, AlterTableAction action, DatabaseType type) {
        String prefix = "ALTER TABLE " + quote(shardName, type) + " ";
        if ("ADD".equals(action.actionType)) {
            if (action.columnDefinition != null) {
                return prefix + "ADD COLUMN " + sqlColumnDefinition(action.columnDefinition, type) + ";";
            }
            if (action.constraint != null) {
                return prefix + "ADD " + sqlConstraint(action.constraint, type) + ";";
            }
        }
        if ("DROP".equals(action.actionType)) {
            return prefix + "DROP COLUMN " + quote(action.columnName, type) + ";";
        }
        return "-- Unsupported ALTER action: " + action.actionType;
    }

    private String mongoFilter(ASTNode condition) {
        if (condition == null) {
            return "{}";
        }
        return mongoCondition((Condition) condition);
    }

    private String mongoCondition(Condition condition) {
        if (condition.logicalOperator != null && condition.leftCondition != null && condition.rightCondition != null) {
            String operator = condition.logicalOperator.type == tokenType.OR ? "$or" : "$and";
            return "{" + operator + ": [" + mongoCondition(condition.leftCondition) + ", "
                    + mongoCondition(condition.rightCondition) + "]}";
        }
        if (!(condition.left instanceof IdentifierExpression)) {
            return "{}";
        }
        IdentifierExpression identifier = (IdentifierExpression) condition.left;
        String field = identifier.name;
        Object value = literalValue(condition.right);
        if (condition.operator == null || condition.operator.type == tokenType.EQ) {
            return "{" + field + ": " + mongoLiteral(value) + "}";
        }
        return "{" + field + ": {" + mongoOperator(condition.operator.type) + ": " + mongoLiteral(value) + "}}";
    }

    private String mongoProjection(List<String> columns) {
        if (columns.isEmpty()) {
            return "{}";
        }
        List<String> fields = new ArrayList<>();
        fields.add("_id: 0");
        for (String column : columns) {
            fields.add(column + ": 1");
        }
        return "{" + String.join(", ", fields) + "}";
    }

    private String mongoDocument(List<String> columns, List<ASTNode> row) {
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < columns.size() && i < row.size(); i++) {
            fields.add(columns.get(i) + ": " + mongoLiteral(literalValue(row.get(i))));
        }
        return "{" + String.join(", ", fields) + "}";
    }

    private String mongoAssignments(List<ASTNode> assignments) {
        List<String> fields = new ArrayList<>();
        for (ASTNode node : assignments) {
            Assignment assignment = (Assignment) node;
            fields.add(assignment.columnName + ": " + mongoLiteral(literalValue(assignment.value)));
        }
        return "{" + String.join(", ", fields) + "}";
    }

    private String mongoDdlStatement(RemoteDdlPlan ddl) {
        if ("CreateTable".equals(ddl.getKind())) {
            return "db.createCollection(\"" + ddl.getShardName() + "\")";
        }
        if ("DropTable".equals(ddl.getKind())) {
            return "db." + ddl.getShardName() + ".drop()";
        }
        if ("TruncateTable".equals(ddl.getKind())) {
            return "db." + ddl.getShardName() + ".deleteMany({})";
        }
        if ("AlterTable".equals(ddl.getKind())) {
            return "// MongoDB collection " + ddl.getShardName()
                    + " is schemaless; ALTER TABLE action is recorded in metadata";
        }
        return "// Unsupported MongoDB DDL: " + ddl.getKind();
    }

    private String mongoOperator(tokenType type) {
        if (type == tokenType.NE) {
            return "$ne";
        }
        if (type == tokenType.GT) {
            return "$gt";
        }
        if (type == tokenType.GE) {
            return "$gte";
        }
        if (type == tokenType.LT) {
            return "$lt";
        }
        if (type == tokenType.LE) {
            return "$lte";
        }
        return "$eq";
    }

    private Object literalValue(ASTNode node) {
        if (node instanceof LiteralExpression) {
            return ((LiteralExpression) node).value.value;
        }
        return null;
    }

    private String mongoLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + value.toString().replace("\"", "\\\"") + "\"";
        }
        return value.toString();
    }
}
