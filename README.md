# Distributed MiniSQL

Distributed MiniSQL 是一个课程实验性质的分布式数据库原型。系统由 master/coordinator、多个 datanode、CLI 客户端和 Vue3 Web Query 页面组成，支持 SQL 解析、哈希分片、主从副本、节点注册、故障切换、重分片、索引和基础查询执行。

> 注意：底层可以使用 MySQL / PostgreSQL 存储物理分片表，但前端和 CLI 输入的是 MiniSQL 自己实现的 SQL 子集，不是完整 MySQL/PostgreSQL 方言。完整语法说明见 [MiniSQL语法规则.md](./MiniSQL语法规则.md)。

## 当前架构

```text
Browser / sql-cli
        |
        v
master / coordinator
  - SQL parse / semantic check
  - logical plan / physical plan
  - shard routing metadata
  - failover / repair / reshard
        |
        v
datanode dn1 ... dn10
        |
        v
MySQL / PostgreSQL physical shard tables
```

主要目录：

```text
src/                  master/coordinator、SQL parser、planner、cluster metadata
datanode/             datanode RPC 服务，连接 MySQL/PostgreSQL/H2
sql-cli/              命令行客户端
web-query/            Vue3 查询页面，类似简化 MySQL Workbench
tests/                自动化测试脚本和验收 SQL
docker-compose.yml    一键启动 master + web + MySQL + PostgreSQL + 10 datanode
```

## Docker Compose 一键运行

当前 compose 会启动：

```text
1 master
1 Vue Web Query
1 MySQL
1 PostgreSQL
10 datanode
```

节点分布：

```text
dn1, dn3, dn5, dn7, dn9      -> MySQL
dn2, dn4, dn6, dn8, dn10     -> PostgreSQL
```

默认端口：

```text
Web Query:     http://127.0.0.1:5173
Master RPC:    http://127.0.0.1:8080/rpc
MySQL:         127.0.0.1:13306
PostgreSQL:    127.0.0.1:15432
```

启动：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database
docker compose up --build
```

后台启动：

```powershell
docker compose up --build -d
```

停止但保留数据：

```powershell
docker compose down
```

清空 master 元数据和 MySQL/PostgreSQL 数据，重新干净测试：

```powershell
docker compose down -v
docker compose up --build
```

查看状态和日志：

```powershell
docker compose ps
docker compose logs -f master
docker compose logs -f web
docker compose logs -f dn1
```

## Web Query 页面

启动 compose 后打开：

```text
http://127.0.0.1:5173
```

页面支持：

- SQL 输入和执行。
- 多条 SQL 批量执行，按分号拆分。
- `Result Grid` 显示查询结果表格。
- `Action Output` 显示每条 SQL 的状态、消息和耗时。
- 左侧 History 可回看历史 SQL 和当时结果。

`SHOW NODES`、`SHOW SHARDS`、`SHOW SHARDS table`、`SHOW CLUSTER`、`SHOW TABLES` 都会显示在 `Result Grid`。

## CLI 客户端

先编译 CLI：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database\sql-cli
mvn compile
```

连接 Docker 中的 master：

```powershell
java -cp "target\classes" minisql.cli.SqlCli http://127.0.0.1:8080
```

退出：

```text
exit
```

## 数据库连接信息

MySQL Workbench：

```text
host: 127.0.0.1
port: 13306
user: ddb_user
password: db_1234567
```

pgAdmin / PostgreSQL：

```text
host: 127.0.0.1
port: 15432
database: ddb
user: ddb_user
password: db_1234567
```

datanode 会自动创建 `minisql_dn1` 到 `minisql_dn10` 这类数据库。MySQL 初始化脚本会给 `ddb_user` 授权；PostgreSQL 容器中的 `ddb_user` 具备建库权限。

## 常用 SQL 子集

推荐先看 [MiniSQL语法规则.md](./MiniSQL语法规则.md)。常用语句如下：

```sql
CREATE TABLE table_name (
  id INT PRIMARY KEY,
  name CHAR(20)
) SHARD BY HASH(id) SHARDS 2 REPLICAS 2;

INSERT INTO table_name VALUES (1, 'Alice');
INSERT INTO table_name VALUES
  (2, 'Bob'),
  (3, 'Cindy');

SELECT * FROM table_name;
SELECT * FROM table_name WHERE id = 1;
SELECT * FROM table_name ORDER BY id DESC;
SELECT * FROM table_name LIMIT 2;

UPDATE table_name SET name = 'Alex' WHERE id = 1;
DELETE FROM table_name WHERE id = 3;

CREATE INDEX idx_name ON table_name (name);
DROP INDEX idx_name;

SHOW NODES;
SHOW SHARDS;
SHOW SHARDS table_name;
SHOW TABLES;
SHOW INDEXES;
SHOW CLUSTER;

FAIL NODE dn1;
RECOVER NODE dn1;
REBALANCE CLUSTER;
RESHARD CLUSTER;
REMOVE NODE dn5;
```

不支持完整数据库方言，例如：

```sql
CREATE DATABASE ...
USE ...
AUTO_INCREMENT
GENERATED ALWAYS AS IDENTITY
DEFAULT CURRENT_TIMESTAMP
CHECK (x IN (...))
```

## 已支持的查询能力

基础能力：

- `CREATE TABLE ... SHARD BY HASH(...) SHARDS n REPLICAS r`
- `DROP TABLE` / `DROP TABLE IF EXISTS`
- `INSERT` 单行和多行
- `SELECT`、`WHERE`
- `UPDATE`
- `DELETE`
- `CREATE INDEX`
- `DROP INDEX`
- `SHOW` 集群和分片信息

查询后处理：

- 跨分片 `ORDER BY`
- 跨分片 `LIMIT / OFFSET`
- 简单聚合：`COUNT`、`SUM`、`AVG`、`MIN`、`MAX`
- 简单 `GROUP BY`
- 简单等值 `JOIN`
- 部分 `LEFT JOIN + GROUP BY + COUNT(column)` 场景

随机函数：

```sql
RAND()
RANDOM()
```

`RAND()` / `RANDOM()` 会在 master 生成逻辑计划时折叠成 Java 随机数字面量，保证主副本和从副本写入相同随机值。

## 分片和副本

建表时：

```sql
SHARD BY HASH(id) SHARDS 2 REPLICAS 2
```

含义：

- `id` 是分片键。
- `SHARDS 2` 生成 2 个逻辑分片，例如 `readers_0`、`readers_1`。
- `REPLICAS 2` 表示每个分片有 2 份数据，包括 primary 和 replica。
- master 保存逻辑表到物理分片表的路由元数据。
- datanode 存的是物理分片表。

查看分片：

```sql
SHOW SHARDS readers;
```

手动触发重新分片：

```sql
RESHARD CLUSTER;
```

修复副本：

```sql
REBALANCE CLUSTER;
```

## 容灾容错

模拟节点故障：

```sql
FAIL NODE dn1;
```

恢复节点：

```sql
RECOVER NODE dn1;
```

查看集群：

```sql
SHOW CLUSTER;
```

行为概述：

- primary 下线且有可用 replica 时，replica 可提升为 primary。
- 节点重新上线会重新注册自己的 host/port/databaseType。
- 注册成功后 master 后台异步执行 repair/rebalance，避免 datanode 注册被慢数据库阻塞。
- 表/分片路由元数据持久化；节点 endpoint 可由同名 datanode 重新注册覆盖。

## 自动化测试

设计报告功能测试：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database
powershell -ExecutionPolicy Bypass -File .\tests\run-design-report-tests.ps1
```

跳过故障切换：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\run-design-report-tests.ps1 -SkipFailover
```

重分片测试：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\run-reshard-test.ps1
```

观察并触发重分片：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\watch-reshard.ps1 -TableName reshard_user -ExpectedRows 50
```

清理测试状态：

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\reset-test-state.ps1
```

## 手动本地运行

Docker 是推荐方式。若要手动运行，需要分别编译 master、datanode、sql-cli。

编译 master：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database
mvn compile
```

编译 datanode：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database\datanode
mvn compile dependency:copy-dependencies
```

编译 sql-cli：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database\sql-cli
mvn compile
```

启动 master：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database
$env:MINISQL_SERVER_MODE="true"
$env:MINISQL_MASTER_PORT="8080"
$env:MINISQL_HEARTBEAT_TIMEOUT_MS="5000"
$env:MINISQL_DATA="data\minisql-state.bin"
java -cp "target\classes" minisql.app.App
```

启动 datanode 示例：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database\datanode
$env:MINISQL_NODE_ID="dn1"
$env:MINISQL_NODE_HOST="127.0.0.1"
$env:MINISQL_NODE_PORT="9101"
$env:MINISQL_MASTER_URL="http://127.0.0.1:8080"
$env:MINISQL_DATABASE_TYPE="MYSQL"
$env:MINISQL_JDBC_URL="jdbc:mysql://127.0.0.1:13306/minisql_dn1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:MINISQL_JDBC_USER="ddb_user"
$env:MINISQL_JDBC_PASSWORD="db_1234567"
$env:MINISQL_JDBC_DRIVER="com.mysql.cj.jdbc.Driver"
java -cp "target\classes;target\dependency\*" minisql.datanode.DataNodeServer
```

## 可复制示例

```sql
CREATE TABLE readers (
  id INT PRIMARY KEY,
  reader_no CHAR(20),
  name CHAR(50),
  gender CHAR(10),
  phone CHAR(20),
  email CHAR(100),
  registered_at CHAR(30)
) SHARD BY HASH(id) SHARDS 2 REPLICAS 2;

INSERT INTO readers VALUES
  (1, 'R001', '张三', '男', '13800000001', 'zhangsan@test.com', '2025-01-10 10:00:00'),
  (2, 'R002', '李四', '女', '13800000002', 'lisi@test.com', '2025-02-15 14:30:00'),
  (3, 'R003', '王五', '男', '13800000003', 'wangwu@test.com', '2025-03-20 09:15:00');

SELECT * FROM readers;
SELECT * FROM readers ORDER BY registered_at DESC;
SELECT * FROM readers LIMIT 2;
SHOW SHARDS readers;
SHOW NODES;
```

## 常见问题

Docker 拉镜像失败：

```powershell
docker pull nginx:1.27-alpine
docker pull node:22-alpine
docker pull mysql:8.4
docker pull postgres:16
docker pull maven:3.9-eclipse-temurin-17
docker pull eclipse-temurin:17-jre
```

如果 Docker Hub 网络不通，在 Docker Desktop 里设置代理：

```text
Settings -> Resources -> Proxies
HTTP proxy:  http://127.0.0.1:7897
HTTPS proxy: http://127.0.0.1:7897
```

页面结果仍是旧版本：

```text
Ctrl + F5
```

代码改了但 Docker 里没生效：

```powershell
docker compose up --build master
docker compose up --build web
```

彻底清空测试数据：

```powershell
docker compose down -v
docker compose up --build
```
