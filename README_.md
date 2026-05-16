# Distributed MiniSQL 使用说明

这个项目是一个简化版分布式数据库系统。主节点负责 SQL 解析、语义检查、逻辑/物理计划、分片路由和结果合并；子节点作为独立服务接收主节点下发的 SQL，在本地 H2 数据库中执行；`sql-cli` 是一个命令行前端。

## 目录结构

```text
big_infor_sys_build_tech/
  Distributed_Database/   主节点，Coordinator + parser + metadata + RPC server
  datanode/               子节点，HTTP RPC + JDBC/H2 执行器 + 心跳注册
  sql-cli/                命令行前端，输入 SQL 后发给主节点执行
  parser/                 原始 parser 工程备份/参考
  docker-compose.yml      Docker Compose 部署文件
```

## 环境要求

推荐使用 JDK 17 和 Maven。

PowerShell 临时切换 JDK 17：

```powershell
$env:JAVA_HOME="C:\Users\Lenovo\.jdks\ms-17.0.17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

确认 Maven：

```powershell
mvn -v
```

## 本地启动方式

本地启动需要 5 个终端：

```text
1 个 master
3 个 datanode
1 个 sql-cli
```

### 1. 构建主节点

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database
mvn -DskipTests package
```

### 2. 构建子节点

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\datanode
mvn -DskipTests package dependency:copy-dependencies
```

### 3. 构建命令行前端

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\sql-cli
mvn -DskipTests package
```

### 4. 启动 master

新开一个 PowerShell：

```powershell
$env:JAVA_HOME="C:\Users\Lenovo\.jdks\ms-17.0.17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database

$env:MINISQL_SERVER_MODE="true"
$env:MINISQL_MASTER_PORT="8080"
$env:MINISQL_DATA="data\minisql-state.bin"

java -cp "target\classes" minisql.app.App
```

主节点 RPC 地址：

```text
http://127.0.0.1:8080/rpc
```

### 5. 启动 dn1

新开一个 PowerShell：

```powershell
$env:JAVA_HOME="C:\Users\Lenovo\.jdks\ms-17.0.17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\datanode

$env:MINISQL_NODE_ID="dn1"
$env:MINISQL_NODE_HOST="127.0.0.1"
$env:MINISQL_NODE_PORT="9101"
$env:MINISQL_MASTER_URL="http://127.0.0.1:8080"
$env:MINISQL_DATABASE_TYPE="H2"
$env:MINISQL_JDBC_URL="jdbc:h2:./data/dn1"
$env:MINISQL_JDBC_USER="sa"
$env:MINISQL_JDBC_PASSWORD=""

java -cp "target\classes;target\dependency\*" minisql.datanode.DataNodeServer
```

### 6. 启动 dn2

```powershell
$env:JAVA_HOME="C:\Users\Lenovo\.jdks\ms-17.0.17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\datanode

$env:MINISQL_NODE_ID="dn2"
$env:MINISQL_NODE_HOST="127.0.0.1"
$env:MINISQL_NODE_PORT="9102"
$env:MINISQL_MASTER_URL="http://127.0.0.1:8080"
$env:MINISQL_DATABASE_TYPE="H2"
$env:MINISQL_JDBC_URL="jdbc:h2:./data/dn2"
$env:MINISQL_JDBC_USER="sa"
$env:MINISQL_JDBC_PASSWORD=""

java -cp "target\classes;target\dependency\*" minisql.datanode.DataNodeServer
```

### 7. 启动 dn3

```powershell
$env:JAVA_HOME="C:\Users\Lenovo\.jdks\ms-17.0.17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\datanode

$env:MINISQL_NODE_ID="dn3"
$env:MINISQL_NODE_HOST="127.0.0.1"
$env:MINISQL_NODE_PORT="9103"
$env:MINISQL_MASTER_URL="http://127.0.0.1:8080"
$env:MINISQL_DATABASE_TYPE="H2"
$env:MINISQL_JDBC_URL="jdbc:h2:./data/dn3"
$env:MINISQL_JDBC_USER="sa"
$env:MINISQL_JDBC_PASSWORD=""

java -cp "target\classes;target\dependency\*" minisql.datanode.DataNodeServer
```

### 8. 启动 sql-cli

新开一个 PowerShell：

```powershell
$env:JAVA_HOME="C:\Users\Lenovo\.jdks\ms-17.0.17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\sql-cli

java -cp "target\classes" minisql.cli.SqlCli
```

如果 master 地址不是默认值，可以显式传入：

```powershell
java -cp "target\classes" minisql.cli.SqlCli http://127.0.0.1:8080
```

退出 CLI：

```text
exit
```

## 常用 SQL

查看节点：

```sql
SHOW NODES;
```

查看分片和主副本关系：

```sql
SHOW SHARDS;
```

查看所有子节点真实物理表和表内数据：

```sql
SHOW TABLES;
```

建表：

```sql
CREATE TABLE student (
  sid INT PRIMARY KEY,
  name VARCHAR(20),
  age INT
) SHARD BY HASH(sid) SHARDS 3 REPLICAS 3;
```

插入：

```sql
INSERT INTO student (sid, name, age) VALUES (1001, 'Alice', 21);
INSERT INTO student (sid, name, age) VALUES (1002, 'Alice', 20);
INSERT INTO student (sid, name, age) VALUES (1003, 'Bob', 19);
```

批量插入：

```sql
INSERT INTO student (sid, name, age) VALUES
(1004, 'Cindy', 22),
(1005, 'David', 20),
(1006, 'Eva', 23);
```

查询分片键，主节点会定向路由到一个 shard：

```sql
SELECT * FROM student WHERE sid = 1001;
```

查询非分片键，主节点会广播到所有 shard：

```sql
SELECT * FROM student WHERE age = 21;
```

更新：

```sql
UPDATE student SET age = 22 WHERE sid = 1001;
```

删除：

```sql
DELETE FROM student WHERE sid = 1001;
```

模拟节点失败：

```sql
FAIL NODE dn1;
```

模拟节点恢复：

```sql
RECOVER NODE dn1;
```

## 分片和副本规则

建表语句：

```sql
SHARD BY HASH(sid) SHARDS 3 REPLICAS 3
```

含义：

```text
分片键：sid
逻辑 shard 数量：3
每个 shard 副本数量：3
```

行路由规则：

```text
shardIndex = hash(sid) % shardCount
```

例如：

```text
student_0
student_1
student_2
```

`SHOW SHARDS;` 可以看每个 shard 的 primary 和 replicas。

注意：副本不是单独的表名。副本是在不同 datanode 上的同名 shard 表。例如：

```text
dn1: student_0
dn2: student_0
dn3: student_0
```

这三份代表同一个 shard 的 primary/replica 副本。

## 节点上线和健康管理

子节点启动后会向 master 注册：

```text
registerNode
```

master 会返回分配的 `nodeId`。

子节点随后每 2 秒发送 heartbeat：

```text
heartbeat
```

master 如果超过配置时间没有收到 heartbeat，会把节点标记为 `OFFLINE`。

当前系统支持节点状态展示：

```sql
SHOW NODES;
```

## 表级锁

主节点已经实现表级锁：

```text
SELECT 使用读锁
INSERT/UPDATE/DELETE/DDL 使用写锁
```

这样迁移、更新、删除和查询不会在同一张表上无限制交错执行。

## 元数据和数据持久化

主节点元数据：

```text
Distributed_Database/data/minisql-state.bin
```

保存内容包括：

```text
逻辑表
分片路由
节点信息
主副本关系
```

子节点 H2 数据：

```text
datanode/data/dn1.mv.db
datanode/data/dn2.mv.db
datanode/data/dn3.mv.db
```

如果删除这些文件，对应数据会丢失。

## 直接查看 H2 数据库

进入 datanode 目录：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\datanode
```

打开 dn1：

```powershell
java -cp "target\dependency\h2-2.2.224.jar" org.h2.tools.Shell -url "jdbc:h2:./data/dn1" -user sa -password ""
```

进入 Shell 后：

```sql
SHOW TABLES;
SELECT * FROM "student_0";
SELECT * FROM "student_1";
SELECT * FROM "student_2";
```

退出：

```sql
exit
```

如果 H2 文件被 datanode 占用，先停掉对应 datanode 再打开。

## Docker Compose 部署

在根目录执行：

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech
docker compose -p minisql build
docker compose -p minisql up -d
docker compose -p minisql ps
```

查看日志：

```powershell
docker compose -p minisql logs -f master
docker compose -p minisql logs -f dn1
```

停止：

```powershell
docker compose -p minisql down
```

停止并删除数据卷：

```powershell
docker compose -p minisql down -v
```

Docker 如果拉取基础镜像失败，需要先解决 Docker Hub 网络或代理问题。需要的基础镜像：

```text
maven:3.9-eclipse-temurin-17
eclipse-temurin:17-jre
```

## 当前实现边界

当前已经实现：

```text
SQL 解析和语义检查
主节点 metadata
HTTP/JSON RPC
子节点注册和心跳
H2 本地持久化
hash 分片
primary + replicas
表级锁
SHOW NODES / SHOW SHARDS / SHOW TABLES
```

需要注意：

```text
SELECT sid = 常量 可以定向到单个 shard
SELECT 非分片键条件会广播
范围查询、IN 查询暂时会广播
全量重 hash / 全表重分片没有作为稳定功能启用
```

如果要展示新节点接入，建议新建一张表，让新表使用当前在线节点分配 shard。
