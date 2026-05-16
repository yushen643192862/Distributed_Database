package minisql.sql;

public sealed interface SqlCommand permits CreateTableCommand, DeleteCommand, DropTableCommand, FailNodeCommand, InsertCommand, JoinCommand, RecoverNodeCommand, SelectCommand, ShowClusterCommand, ShowNodesCommand, ShowShardsCommand, UpdateCommand {
}
