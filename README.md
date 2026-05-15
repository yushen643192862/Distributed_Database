# Distributed MiniSQL

这是一个用于课程设计/实验展示的分布式 MiniSQL 原型。当前实现采用“单进程模拟分布式集群”的方式：在一个 JVM 内模拟 `Coordinator`、`MetaStore` 和 3 个 `DataNode`，用于演示分片、主从副本、故障切换、恢复补齐、读负载均衡和跨分片查询。

说明：设计报告中的 ZooKeeper 在本实现中用内存版集群管理逻辑模拟，不额外引入真实 ZooKeeper 服务；核心行为和演示命令已覆盖。

## 当前能力

- 基础 SQL：`CREATE TABLE`、`DROP TABLE`、`INSERT`、`DELETE`、`UPDATE`、`SELECT`。
- 投影查询：支持 `SELECT sid, name FROM student WHERE dept = 'CS';`。
- 哈希分片：`SHARD BY HASH(column) SHARDS n`。
- 副本管理：支持 `REPLICAS 3`，每个 shard 采用 1 主 2 从。
- 分布式查询：分片键条件走分片裁剪，非分片键条件广播查询。
- JOIN：支持等值内连接。
- 集群管理：支持节点状态、读写计数、路由版本和拓扑展示。
- 容错容灾：主副本节点故障后自动从健康从副本中切主。
- 恢复补齐：故障节点恢复后从健康副本同步缺失 shard 数据。
- 负载均衡：普通读请求在健康副本之间轮询分配。
- 持久化：退出后保存快照，重启可恢复表结构、数据和集群状态。
- 客户端：每条 SQL 输出执行耗时。

## 支持的 SQL

```sql
CREATE TABLE table_name (...) SHARD BY HASH(column) SHARDS n REPLICAS r;
DROP TABLE table_name;
INSERT INTO table_name VALUES (...);
DELETE FROM table_name WHERE column = value;
UPDATE table_name SET column = value WHERE column = value;
SELECT * FROM table_name;
SELECT column1, column2 FROM table_name WHERE column = value;
SELECT left_table.column, right_table.column FROM left_table JOIN right_table ON left_table.column = right_table.column;
SHOW SHARDS;
SHOW SHARDS table_name;
SHOW NODES;
SHOW CLUSTER;
FAIL NODE dn1;
RECOVER NODE dn1;
```

## 本地运行

```powershell
mvn compile
java -cp target\classes edu.minisql.app.App
```

默认数据文件：

```text
data/minisql-state.bin
```

指定数据文件：

```powershell
java "-Dminisql.data=D:\minisql-data\state.bin" -cp target\classes edu.minisql.app.App
```

## Docker 演示

```powershell
docker build -t distributed-minisql:local .
docker volume create minisql-data
docker run --rm -it -v minisql-data:/app/data distributed-minisql:local
```

也可以用 Docker Compose：

```powershell
docker compose run --rm minisql
```

如果旧数据卷来自旧版本序列化格式，先清空一次：

```powershell
docker volume rm minisql-data
docker volume create minisql-data
```

## 完整验收测试

本地：

```powershell
mvn compile
Get-Content tests\acceptance.sql | java "-Dminisql.data=target\acceptance-state.bin" -cp target\classes edu.minisql.app.App
```

Docker：

```powershell
docker build -t distributed-minisql:local .
docker volume rm minisql-test-data
docker volume create minisql-test-data
Get-Content tests\acceptance.sql | docker run --rm -i -v minisql-test-data:/app/data distributed-minisql:local
```

测试说明见 [tests/test-cases.md](tests/test-cases.md)。

## 演示片段

```sql
CREATE TABLE student (sid INT PRIMARY KEY, name CHAR(20), age INT, dept CHAR(20)) SHARD BY HASH(sid) SHARDS 3 REPLICAS 3;
INSERT INTO student VALUES (1001, 'Alice', 20, 'CS');
INSERT INTO student VALUES (1002, 'Bob', 21, 'Math');
INSERT INTO student VALUES (1003, 'Cindy', 22, 'CS');
SHOW SHARDS student;
SHOW CLUSTER;
SELECT sid, name FROM student WHERE dept = 'CS';
```

故障切换：

```sql
FAIL NODE dn2;
SHOW CLUSTER;
SELECT * FROM student WHERE sid = 1001;
RECOVER NODE dn2;
SHOW CLUSTER;
```

JOIN：

```sql
CREATE TABLE course (cid INT PRIMARY KEY, sid INT, cname CHAR(20)) SHARD BY HASH(sid) SHARDS 3 REPLICAS 3;
INSERT INTO course VALUES (1, 1001, 'Database');
INSERT INTO course VALUES (2, 1003, 'Network');
SELECT student.name, course.cname FROM student JOIN course ON student.sid = course.sid;
```

输入 `exit` 或 `quit` 退出。
