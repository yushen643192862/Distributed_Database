# Distributed MiniSQL

Distributed MiniSQL 是一个用于课程实验和分布式数据库机制演示的简化系统。当前版本由 1 个 master、若干 datanode 和一个命令行客户端组成，支持 SQL 解析、哈希分片、主从副本、节点上下线、故障切换和简单的集群状态查看。

## 当前架构

```text
sql-cli  --->  master/coordinator  --->  datanode
              metadata + routing        H2 storage
              node registry             physical shard tables
              failover/rebalance
```

主要模块：

```text
src/          master/coordinator、SQL 解析、路由元数据、集群管理

datanode/     datanode 服务，负责接收 master RPC 并执行 H2/JDBC SQL
sql-cli/      命令行客户端，连接 master 提交 SQL
docker-compose.yml  本地容器化启动 1 master + 多个 datanode
```

## 主从和分片规则

当前系统的默认参数是 `MINISQL_PRIMARY_COUNT=3`，也就是冷启动时先形成：

```text
1 个 master
3 个 primary datanode
3 个 replica datanode
```

也可以理解为 1 个 master + 2k 个 datanode，初始 `k=3`。其中 k 个 datanode 是主副本节点，k 个 datanode 是从副本节点。

节点角色由 master 在注册时分配：

- 前 `MINISQL_PRIMARY_COUNT` 个 datanode 优先成为 `PRIMARY`。
- 后续 datanode 会优先补到没有从节点的 primary 上，成为 `REPLICA`。
- 如果所有 primary 都已经有在线 replica，新 datanode 会成为新的 `PRIMARY`，此时 master 会按新的 primary 数重新哈希分片。
- primary 挂掉且它有在线 replica 时，replica 会提升为 primary，路由表更新，但不会重新哈希整张表。
- primary 挂掉且没有在线 replica 时，该 primary 对应的分片没有可用副本，系统会尝试按剩余 primary 重新分片；如果没有在线数据源，对应数据无法恢复。
- 旧 replica 恢复上线时，如果原 primary 已经有新的在线 replica，它不会再抢回原来的从节点位置；如果没有其他 primary 缺 replica，它会成为新的 primary 并触发重新哈希。

重新哈希只在两类拓扑变化中发生：

- 新加入 datanode 导致 primary 数量增加，例如 3 个 primary 变 4 个 primary。
- 删除或故障导致某个没有 replica 的 primary 分片必须从路由中移除，例如 5 个 primary 变 4 个 primary。

## 支持的常用 SQL

```sql
CREATE TABLE table_name (...) SHARD BY HASH(column) SHARDS n REPLICAS r;
CREATE INDEX index_name ON table_name(column);
CREATE UNIQUE INDEX index_name ON table_name(column1, column2);
DROP INDEX index_name ON table_name;
DROP TABLE table_name;
INSERT INTO table_name VALUES (...);
DELETE FROM table_name WHERE column = value;
UPDATE table_name SET column = value WHERE column = value;
SELECT * FROM table_name;
SELECT column1, column2 FROM table_name WHERE column = value;
SELECT left_table.column, right_table.column
FROM left_table JOIN right_table ON left_table.column = right_table.column;

SHOW NODES;
SHOW SHARDS;
SHOW SHARDS table_name;
SHOW INDEXES;
SHOW INDEXES table_name;
SHOW TABLES;
SHOW CLUSTER;
FAIL NODE dn1;
RECOVER NODE dn1;
REMOVE NODE dn1;
REBALANCE CLUSTER;
```

说明：

- `SHOW NODES` 查看节点在线状态、角色、partner、读写计数。
- `SHOW SHARDS` 查看 master 的路由元数据。
- `SHOW INDEXES` 查看逻辑索引元数据；每个逻辑索引会在各个物理 shard 表上创建一个本地 B+Tree/B-tree 索引。
- `SHOW TABLES` 查看 datanode 上真实存在的物理分片表。
- `REBALANCE CLUSTER` 当前主要用于修复物理表和清理过期分片，不强制改变分片数量。

## 本地手动运行

要求：JDK 17、Maven。

### 1. 编译

在项目根目录执行：

```powershell
cd D:\distributed_minisql_
mvn compile

cd D:\distributed_minisql_\datanode
mvn compile dependency:copy-dependencies

cd D:\distributed_minisql_\sql-cli
mvn compile
```

### 2. 启动 master

新开一个 PowerShell：

```powershell
cd D:\distributed_minisql_
$env:MINISQL_SERVER_MODE="true"
$env:MINISQL_MASTER_PORT="8080"
$env:MINISQL_PRIMARY_COUNT="3"
$env:MINISQL_HEARTBEAT_TIMEOUT_MS="5000"
$env:MINISQL_DATA="data\terminal-sim-state.bin"
java -cp "target\classes" minisql.app.App
```

### 3. 启动 datanode

每个 datanode 开一个 PowerShell。以 dn1 为例：

```powershell
cd D:\distributed_minisql_\datanode
$env:MINISQL_NODE_ID="dn1"
$env:MINISQL_NODE_HOST="127.0.0.1"
$env:MINISQL_NODE_PORT="9101"
$env:MINISQL_MASTER_URL="http://127.0.0.1:8080"
$env:MINISQL_DATABASE_TYPE="H2"
$env:MINISQL_JDBC_URL="jdbc:h2:./data/terminal-dn1"
$env:MINISQL_JDBC_USER="sa"
$env:MINISQL_JDBC_PASSWORD=""
java -cp "target\classes;target\dependency\*" minisql.datanode.DataNodeServer
```

常用节点参数：

```text
dn1  9101  jdbc:h2:./data/terminal-dn1
dn2  9102  jdbc:h2:./data/terminal-dn2
dn3  9103  jdbc:h2:./data/terminal-dn3
dn4  9104  jdbc:h2:./data/terminal-dn4
dn5  9105  jdbc:h2:./data/terminal-dn5
dn6  9106  jdbc:h2:./data/terminal-dn6
dn7  9107  jdbc:h2:./data/terminal-dn7
dn8  9108  jdbc:h2:./data/terminal-dn8
dn9  9109  jdbc:h2:./data/terminal-dn9
```

如果需要强制请求角色，可以额外设置：

```powershell
$env:MINISQL_NODE_ROLE="PRIMARY"
```

或：

```powershell
$env:MINISQL_NODE_ROLE="REPLICA"
```

通常测试时不需要手动指定，交给 master 分配即可。

### 4. 启动客户端

`sql-cli` 是客户端，`minisql.app.App` 是 master/coordinator 服务。

```powershell
cd D:\distributed_minisql_\sql-cli
java -cp "target\classes" minisql.cli.SqlCli http://127.0.0.1:8080
```

退出客户端：

```text
exit
```

## Docker Compose 运行

默认 compose 会启动：

```text
master + dn1 + dn2 + dn3 + dn4 + dn5 + dn6
```

也就是初始 `k=3` 的 3 主 3 从集群。

```powershell
cd D:\distributed_minisql_
docker compose -p minisql build
docker compose -p minisql up -d
docker compose -p minisql ps
```

查看日志：

```powershell
docker compose -p minisql logs -f master
docker compose -p minisql logs -f dn1
```

停止集群但保留数据：

```powershell
docker compose -p minisql down
```

停止并删除 volume，重新开始干净测试：

```powershell
docker compose -p minisql down -v
```

启动扩容测试节点 dn7、dn8、dn9：

```powershell
docker compose -p minisql --profile scale-test up -d dn7 dn8 dn9
```

单独停掉某个容器模拟故障：

```powershell
docker compose -p minisql stop dn1
```

恢复节点：

```powershell
docker compose -p minisql start dn1
```

## 测试数据

### student 表

```sql
CREATE TABLE student (sid INT PRIMARY KEY, name CHAR(20), age INT, dept CHAR(20)) SHARD BY HASH(sid) SHARDS 4 REPLICAS 2;
INSERT INTO student VALUES (1001, 'Alice', 21, 'CS');
INSERT INTO student VALUES (1002, 'Bob', 20, 'Math');
INSERT INTO student VALUES (1003, 'Cindy', 22, 'CS');
INSERT INTO student VALUES (1004, 'David', 19, 'SE');
INSERT INTO student VALUES (1005, 'Eva', 23, 'AI');
INSERT INTO student VALUES (1006, 'Frank', 20, 'Security');
CREATE INDEX idx_student_name ON student(name);
SHOW INDEXES student;
```

### course 表

```sql
CREATE TABLE course (cid INT PRIMARY KEY, sid INT, cname CHAR(20), credit INT) SHARD BY HASH(sid) SHARDS 4 REPLICAS 2;
INSERT INTO course VALUES (1, 1001, 'Database', 4);
INSERT INTO course VALUES (2, 1002, 'Network', 3);
INSERT INTO course VALUES (3, 1003, 'OS', 4);
INSERT INTO course VALUES (4, 1004, 'Compiler', 3);
INSERT INTO course VALUES (5, 1005, 'AI', 3);
INSERT INTO course VALUES (6, 1006, 'Security', 2);
```

Join 测试：

```sql
SELECT student.name, course.cname FROM student JOIN course ON student.sid = course.sid;
```

状态检查：

```sql
SHOW NODES;
SHOW SHARDS student;
SHOW SHARDS course;
SHOW TABLES;
```

删改测试：

```sql
UPDATE student SET age = 24 WHERE sid = 1005;
UPDATE student SET dept = 'Software' WHERE sid = 1004;
UPDATE course SET credit = 5 WHERE cid = 1;

SELECT * FROM student WHERE sid = 1005;
SELECT * FROM student WHERE sid = 1004;
SELECT * FROM course WHERE cid = 1;

DELETE FROM course WHERE cid = 6;
DELETE FROM student WHERE sid = 1006;

SELECT * FROM course WHERE cid = 6;
SELECT * FROM student WHERE sid = 1006;
SELECT student.name, course.cname FROM student JOIN course ON student.sid = course.sid;
```

## 数据文件

master 元数据默认保存在：

```text
data/terminal-sim-state.bin
```

手动运行 datanode 时，H2 数据默认在：

```text
datanode/data/terminal-dn1.mv.db
datanode/data/terminal-dn2.mv.db
...
```

清空本地手动测试状态会删除测试数据：

```powershell
Remove-Item D:\distributed_minisql_\data\terminal-sim-state.bin -ErrorAction SilentlyContinue
Remove-Item D:\distributed_minisql_\datanode\data\terminal-dn*.* -ErrorAction SilentlyContinue
```
