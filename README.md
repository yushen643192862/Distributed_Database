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

1. 创建表
2.1 用户表 users
```sql
CREATE TABLE users (
    user_id INT PRIMARY KEY,
    username CHAR(20),
    age INT,
    city CHAR(30),
    email CHAR(50),
    reg_date CHAR(30)
) SHARD BY HASH(user_id) SHARDS 3 REPLICAS 2;
```
2.2 订单表 orders
```sql
CREATE TABLE orders (
    order_id INT PRIMARY KEY,
    user_id INT,
    product CHAR(50),
    quantity INT,
    price FLOAT,
    order_date CHAR(30)
) SHARD BY HASH(order_id) SHARDS 3 REPLICAS 2;
```

1. 插入数据（总计 70 条）
3.1 users 表插入 20 条
```sql
INSERT INTO users VALUES
  (1, 'Alice', 25, 'Beijing', 'alice@test.com', '2025-01-10 10:00:00'),
  (2, 'Bob', 30, 'Shanghai', 'bob@test.com', '2025-01-11 11:00:00'),
  (3, 'Carol', 28, 'Guangzhou', 'carol@test.com', '2025-01-12 12:00:00'),
  (4, 'David', 35, 'Shenzhen', 'david@test.com', '2025-01-13 13:00:00'),
  (5, 'Eve', 22, 'Beijing', 'eve@test.com', '2025-01-14 14:00:00'),
  (6, 'Frank', 40, 'Shanghai', 'frank@test.com', '2025-01-15 15:00:00'),
  (7, 'Grace', 27, 'Guangzhou', 'grace@test.com', '2025-01-16 16:00:00'),
  (8, 'Henry', 33, 'Shenzhen', 'henry@test.com', '2025-01-17 17:00:00'),
  (9, 'Ivy', 29, 'Beijing', 'ivy@test.com', '2025-01-18 18:00:00'),
  (10, 'Jack', 31, 'Shanghai', 'jack@test.com', '2025-01-19 19:00:00'),
  (11, 'Kevin', 26, 'Guangzhou', 'kevin@test.com', '2025-01-20 20:00:00'),
  (12, 'Lisa', 24, 'Shenzhen', 'lisa@test.com', '2025-01-21 21:00:00'),
  (13, 'Mike', 38, 'Beijing', 'mike@test.com', '2025-01-22 22:00:00'),
  (14, 'Nina', 32, 'Shanghai', 'nina@test.com', '2025-01-23 23:00:00'),
  (15, 'Oscar', 27, 'Guangzhou', 'oscar@test.com', '2025-01-24 10:00:00'),
  (16, 'Paul', 29, 'Shenzhen', 'paul@test.com', '2025-01-25 11:00:00'),
  (17, 'Quinn', 34, 'Beijing', 'quinn@test.com', '2025-01-26 12:00:00'),
  (18, 'Rose', 23, 'Shanghai', 'rose@test.com', '2025-01-27 13:00:00'),
  (19, 'Sam', 36, 'Guangzhou', 'sam@test.com', '2025-01-28 14:00:00'),
  (20, 'Tina', 28, 'Shenzhen', 'tina@test.com', '2025-01-29 15:00:00');
```
3.2 orders 表插入 50 条
```sql
INSERT INTO orders VALUES
  (101, 1, 'Laptop', 1, 5999.00, '2025-02-01 09:00:00'),
  (102, 2, 'Mouse', 2, 89.50, '2025-02-01 10:00:00'),
  (103, 3, 'Keyboard', 1, 199.00, '2025-02-02 11:00:00'),
  (104, 4, 'Monitor', 1, 1299.00, '2025-02-02 12:00:00'),
  (105, 5, 'USB Cable', 3, 25.00, '2025-02-03 13:00:00'),
  (106, 6, 'Laptop', 1, 5999.00, '2025-02-03 14:00:00'),
  (107, 7, 'Mouse', 1, 89.50, '2025-02-04 15:00:00'),
  (108, 8, 'Keyboard', 2, 398.00, '2025-02-04 16:00:00'),
  (109, 9, 'Monitor', 1, 1299.00, '2025-02-05 17:00:00'),
  (110, 10, 'USB Cable', 5, 41.50, '2025-02-05 18:00:00'),
  (111, 11, 'Laptop', 1, 5999.00, '2025-02-06 09:00:00'),
  (112, 12, 'Mouse', 2, 179.00, '2025-02-06 10:00:00'),
  (113, 13, 'Keyboard', 1, 199.00, '2025-02-07 11:00:00'),
  (114, 14, 'Monitor', 2, 2598.00, '2025-02-07 12:00:00'),
  (115, 15, 'USB Cable', 4, 33.20, '2025-02-08 13:00:00'),
  (116, 16, 'Laptop', 1, 5999.00, '2025-02-08 14:00:00'),
  (117, 17, 'Mouse', 1, 89.50, '2025-02-09 15:00:00'),
  (118, 18, 'Keyboard', 1, 199.00, '2025-02-09 16:00:00'),
  (119, 19, 'Monitor', 1, 1299.00, '2025-02-10 17:00:00'),
  (120, 20, 'USB Cable', 6, 49.80, '2025-02-10 18:00:00'),
  (121, 1, 'Headphones', 1, 299.00, '2025-02-11 10:00:00'),
  (122, 2, 'Webcam', 1, 499.00, '2025-02-11 11:00:00'),
  (123, 3, 'Desk Lamp', 1, 89.00, '2025-02-12 12:00:00'),
  (124, 4, 'Laptop Stand', 2, 159.00, '2025-02-12 13:00:00'),
  (125, 5, 'HDMI Cable', 2, 39.80, '2025-02-13 14:00:00'),
  (126, 6, 'Headphones', 2, 598.00, '2025-02-13 15:00:00'),
  (127, 7, 'Webcam', 1, 499.00, '2025-02-14 16:00:00'),
  (128, 8, 'Desk Lamp', 2, 178.00, '2025-02-14 17:00:00'),
  (129, 9, 'Laptop Stand', 1, 79.50, '2025-02-15 09:00:00'),
  (130, 10, 'HDMI Cable', 3, 59.70, '2025-02-15 10:00:00'),
  (131, 11, 'Laptop', 1, 5999.00, '2025-02-16 11:00:00'),
  (132, 12, 'Mouse', 1, 89.50, '2025-02-16 12:00:00'),
  (133, 13, 'Keyboard', 1, 199.00, '2025-02-17 13:00:00'),
  (134, 14, 'Monitor', 1, 1299.00, '2025-02-17 14:00:00'),
  (135, 15, 'USB Cable', 2, 16.60, '2025-02-18 15:00:00'),
  (136, 16, 'Headphones', 1, 299.00, '2025-02-18 16:00:00'),
  (137, 17, 'Webcam', 2, 998.00, '2025-02-19 17:00:00'),
  (138, 18, 'Desk Lamp', 1, 89.00, '2025-02-19 18:00:00'),
  (139, 19, 'Laptop Stand', 2, 159.00, '2025-02-20 09:00:00'),
  (140, 20, 'HDMI Cable', 4, 79.60, '2025-02-20 10:00:00'),
  (141, 1, 'Laptop', 1, 5999.00, '2025-02-21 11:00:00'),
  (142, 2, 'Mouse', 2, 179.00, '2025-02-21 12:00:00'),
  (143, 3, 'Keyboard', 1, 199.00, '2025-02-22 13:00:00'),
  (144, 4, 'Monitor', 1, 1299.00, '2025-02-22 14:00:00'),
  (145, 5, 'USB Cable', 5, 41.50, '2025-02-23 15:00:00'),
  (146, 6, 'Headphones', 2, 598.00, '2025-02-23 16:00:00'),
  (147, 7, 'Webcam', 1, 499.00, '2025-02-24 17:00:00'),
  (148, 8, 'Desk Lamp', 2, 178.00, '2025-02-24 18:00:00'),
  (149, 9, 'Laptop Stand', 1, 79.50, '2025-02-25 09:00:00'),
  (150, 10, 'HDMI Cable', 3, 59.70, '2025-02-25 10:00:00');
```
4. 查询测试
4.1 简单全表查询  注意：不支持--注释
```sql
SELECT * FROM users;
SELECT * FROM orders;
```
4.2 投影与条件
```sql
-- 指定列
SELECT user_id, username, city FROM users;

-- 按分片键查询
SELECT * FROM users WHERE user_id = 5;
SELECT * FROM orders WHERE order_id = 130;

-- 按非分片键查询
SELECT username, age FROM users WHERE city = 'Beijing';
SELECT order_id, product, quantity FROM orders WHERE product = 'Laptop';
```
4.3 比较运算符
```sql
SELECT * FROM users WHERE age > 30;
SELECT * FROM orders WHERE price <= 100;
SELECT * FROM users WHERE city <> 'Shanghai';
```
4.4 逻辑组合
```sql
SELECT * FROM users WHERE age BETWEEN 25 AND 35 AND city = 'Guangzhou';
SELECT * FROM orders WHERE product = 'Mouse' OR product = 'Keyboard';
SELECT * FROM users WHERE NOT city = 'Beijing';
```
4.5 排序与限制
```sql
-- ORDER BY（分布式下可能未完全优化，但语法通过）
SELECT * FROM users ORDER BY age DESC LIMIT 5;
SELECT * FROM orders ORDER BY price ASC LIMIT 10;
```
4.6 聚合与分组
```sql
SELECT city, COUNT(*) AS user_count FROM users GROUP BY city;
SELECT product, SUM(quantity) AS total_sold FROM orders GROUP BY product;
SELECT city, AVG(age) FROM users GROUP BY city HAVING AVG(age) > 30;
```
4.7 JOIN 测试（等值内连接）
```sql
-- 查询每个订单对应的用户名和产品
SELECT u.username, o.product, o.quantity, o.price
FROM users u JOIN orders o ON u.user_id = o.user_id
LIMIT 20;

-- 带条件过滤的 JOIN
SELECT u.username, u.city, o.order_id, o.order_date
FROM users u JOIN orders o ON u.user_id = o.user_id
WHERE u.city = 'Shanghai' AND o.price > 200;
```
4.8 随机数函数
```sql
-- 创建临时测试表
CREATE TABLE rand_test (
    id INT PRIMARY KEY,
    val FLOAT
) SHARD BY HASH(id) SHARDS 1 REPLICAS 1;

INSERT INTO rand_test VALUES (1, RAND());
INSERT INTO rand_test VALUES (2, RANDOM());
SELECT * FROM rand_test;
```
5. 更新测试
5.1 使用分片键更新
```sql
UPDATE users SET email = 'alice_new@test.com' WHERE user_id = 1;
UPDATE orders SET quantity = 2, price = 1199.00 WHERE order_id = 104;
```
5.2 使用非分片键更新
```sql
UPDATE users SET city = 'Beijing' WHERE username = 'Bob';
UPDATE orders SET product = 'Gaming Mouse' WHERE product = 'Mouse' AND price = 89.50;
```
5.3 多列更新
```sql
UPDATE users SET age = 26, city = 'Chengdu' WHERE user_id = 11;
```
6. 删除测试
6.1 按分片键删除
```sql
DELETE FROM users WHERE user_id = 20;
DELETE FROM orders WHERE order_id = 150;
```
6.2 按非分片键删除
```sql
DELETE FROM users WHERE city = 'Shenzhen' AND age < 25;
DELETE FROM orders WHERE product = 'USB Cable' AND quantity > 4;
注意：删除后可使用 SELECT 验证数据变化。
```

7. 索引测试
```sql
-- 创建普通索引
CREATE INDEX idx_users_city ON users (city);
CREATE INDEX idx_orders_product ON orders (product);

-- 创建唯一索引
CREATE UNIQUE INDEX idx_users_email ON users (email);

-- 查看索引（所有表）
SHOW INDEXES;

-- 查看特定表的索引
SHOW INDEXES users;
SHOW INDEXES orders;

-- 删除索引
DROP INDEX idx_users_city;
DROP INDEX idx_orders_product;
DROP INDEX idx_users_email;
```
8. 集群状态查看命令
```sql
SHOW NODES;          -- 查看所有 datanode
SHOW SHARDS;         -- 查看全部逻辑分片
SHOW SHARDS users;   -- 查看 users 表的分片分布
SHOW SHARDS orders;  -- 查看 orders 表的分片分布
SHOW CLUSTER;        -- 完整集群状态
SHOW TABLES;         -- 查看底层物理表（包含系统生成的物理表）
```

## 初始化脚本
```bash
启动全部节点：
docker compose build
docker compose up -d

如果不启动全部节点：
docker compose build
docker compose up -d master web mysql1 mysql2 mysql3 mysql4 mysql5 postgres1 postgres2 postgres3 postgres4 postgres5
docker compose up -d dn1 dn2 dn3 dn4 ...
最大到dn10

停止某个节点：
docker compose stop dn5
启动某个停止了的节点
docker compose start dn5

前端网址是：
http://127.0.0.1:5173
```