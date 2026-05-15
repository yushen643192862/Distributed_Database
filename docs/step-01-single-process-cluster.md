# Step 01/02: 单进程分布式 MiniSQL 骨架

本阶段目标是先跑通一个最小可用系统。它在一个 JVM 进程里模拟 Coordinator、MetaStore 和多个 DataNode，用来展示“元数据管理 + 分片路由 + 节点本地执行 + 结果聚合”的核心思路。

## 已完成

- Maven + Java 17 项目结构。
- `Coordinator`：系统入口，负责解析 SQL、查询元数据、计算路由、调用数据节点。
- `MetaStore`：保存表结构、分片键、分片数量、分片到 DataNode 的映射。
- `DataNode`：模拟数据节点。
- `LocalMiniSqlEngine`：每个 DataNode 内部的本地存储执行层。
- 哈希分片：`hash(shard_key) % shard_count`。
- SQL 子集：
  - `CREATE TABLE ... SHARD BY HASH(...) SHARDS n`
  - `INSERT INTO ... VALUES (...)`
  - `SELECT * FROM ...`
  - `SELECT * FROM ... WHERE column = value`
  - `UPDATE ... SET column = value WHERE column = value`
  - `DELETE FROM ... WHERE column = value`
  - `SHOW SHARDS`
- 表级主键唯一性检查。
- 非分片键查询/更新/删除会广播到所有 shard，分片键等值条件会只访问目标 shard。

## 当前架构

```text
Client CLI
   |
Coordinator
   |-- SqlParser
   |-- MetaStore
   |-- Router
   |
   +--> DataNode dn1 -> LocalMiniSqlEngine
   +--> DataNode dn2 -> LocalMiniSqlEngine
   +--> DataNode dn3 -> LocalMiniSqlEngine
```

## 下一步建议

- Step 03：加入本地文件持久化，让表结构和数据重启后不丢失。
- Step 04：把 DataNode 抽成 HTTP/RPC 服务，Coordinator 通过网络访问。
- Step 05：加入副本、节点故障模拟和简单的一致性策略。
- Step 06：扩展 SQL 解析器，支持投影列、更多比较条件和简单索引。
