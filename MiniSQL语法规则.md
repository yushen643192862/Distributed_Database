# MiniSQL 语法规则

本文档描述的是本项目 MiniSQL master/coordinator 支持的 SQL 子集。底层虽然可以连接 MySQL / PostgreSQL，但前端和 CLI 输入的 SQL 不是完整 MySQL/PostgreSQL 方言，必须符合这里的 MiniSQL 语法。

## 基本规则

- 一条 SQL 通常以分号 `;` 结束。
- Web 前端支持一次输入多条 SQL，会按分号拆分后顺序执行。
- 表名、列名建议只使用英文字母、数字、下划线，例如 `student`、`uid`。
- 字符串使用单引号，例如 `'Alice'`。
- 支持整数、小数、字符串、`NULL`。
- 不支持 `CREATE DATABASE`、`USE database`。数据库由 datanode 配置和初始化逻辑管理。

## 数据类型

常用推荐类型：

```sql
INT
INTEGER
FLOAT
DOUBLE
CHAR(20)
VARCHAR(50)
```

注意：

- 类型名会转发到底层 MySQL/PostgreSQL，所以建议使用两边都能识别的简单类型。
- 当前项目不建议使用 `DATE`、`TIMESTAMP`、`BOOLEAN` 等复杂类型做测试。
- 时间可以先用 `CHAR(30)` 保存，例如 `'2026-05-20 18:10:00'`。

## 建表

推荐格式：

```sql
CREATE TABLE table_name (
  column1 INT PRIMARY KEY,
  column2 CHAR(20),
  column3 INT
) SHARD BY HASH(column1) SHARDS 2 REPLICAS 2;
```

例子：

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
```

分片规则：

- `SHARD BY HASH(id)` 表示按 `id` 做 hash 分片。
- `SHARDS 2` 表示逻辑分片数为 2。
- `REPLICAS 2` 表示每个分片保留 2 份，包括主副本和从副本。
- 如果省略 `SHARD BY ...`，系统会默认按第一列分片，分片数按当前在线节点数生成，但测试时建议显式写出来。

## 建表约束支持情况

较稳定、推荐使用：

```sql
id INT PRIMARY KEY
name CHAR(20)
```

解析器能识别但测试时谨慎使用：

```sql
NOT NULL
UNIQUE
CHECK (...)
DEFAULT ...
PRIMARY KEY (col)
UNIQUE (col)
```

不支持或不建议：

```sql
GENERATED ALWAYS AS IDENTITY
AUTO_INCREMENT
SERIAL
DEFAULT CURRENT_TIMESTAMP
CHECK (gender IN ('男', '女'))
FOREIGN KEY ...
CREATE DATABASE ...
```

例如下面这种 PostgreSQL 风格 DDL 不适合本项目：

```sql
CREATE TABLE readers (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reader_no VARCHAR(20) NOT NULL UNIQUE,
  registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

应该改成：

```sql
CREATE TABLE readers (
  id INT PRIMARY KEY,
  reader_no CHAR(20),
  registered_at CHAR(30)
) SHARD BY HASH(id) SHARDS 2 REPLICAS 2;
```

## 删除表

```sql
DROP TABLE table_name;
```

也可以写：

```sql
DROP TABLE IF EXISTS table_name;
```

如果 master 元数据和底层物理表不一致，`DROP TABLE IF EXISTS` 仍可能暴露底层 datanode 错误。测试环境最干净的方式是：

```powershell
docker compose down -v
docker compose up --build
```

## 插入

单行插入：

```sql
INSERT INTO readers VALUES (1, 'R001', '张三', '男', '13800000000', 'zhangsan@test.com', '2026-05-20 18:10:00');
```

指定列插入：

```sql
INSERT INTO readers (id, reader_no, name) VALUES (2, 'R002', '李四');
```

批量插入：

```sql
INSERT INTO readers VALUES
  (1, 'R001', '张三', '男', '13800000000', 'zhangsan@test.com', '2026-05-20 18:10:00'),
  (2, 'R002', '李四', '女', '13900000000', 'lisi@test.com', '2026-05-20 18:11:00');
```

注意：

- 插入时必须包含分片键，例如 `SHARD BY HASH(id)` 的表必须插入 `id`。
- 不支持数据库自动生成 ID，`id` 要自己给。

## 随机数函数

支持：

```sql
RAND()
RANDOM()
```

例子：

```sql
CREATE TABLE rand_test (
  id INT PRIMARY KEY,
  val FLOAT
) SHARD BY HASH(id) SHARDS 2 REPLICAS 2;

INSERT INTO rand_test VALUES (1, RAND());
INSERT INTO rand_test VALUES (2, RANDOM());
SELECT * FROM rand_test;
```

随机数会在 master 生成逻辑计划时被替换成一个字面量，保证主副本和从副本写入同一个随机值。

## 查询

查询全部列：

```sql
SELECT * FROM readers;
```

查询指定列：

```sql
SELECT id, name, phone FROM readers;
```

按分片键查询：

```sql
SELECT * FROM readers WHERE id = 1;
```

按非分片键查询：

```sql
SELECT id, name FROM readers WHERE gender = '男';
```

支持常见比较：

```sql
WHERE id = 1
WHERE age > 20
WHERE age >= 18
WHERE name <> 'Alice'
```

解析器支持但分布式执行层不一定完整优化：

```sql
AND
OR
NOT
ORDER BY
LIMIT
GROUP BY
HAVING
```

测试建议优先使用简单 `WHERE column = value`。

## Join

支持简单等值内连接：

```sql
SELECT student.name, course.cname
FROM student JOIN course ON student.sid = course.sid;
```

建议限制：

- 使用一个 `JOIN`。
- 使用等值连接 `ON a.col = b.col`。
- 不要混用复杂 `WHERE`、聚合、子查询。

## 更新

```sql
UPDATE readers SET phone = '13700000000' WHERE id = 1;
```

也支持多个字段：

```sql
UPDATE readers SET phone = '13700000000', email = 'new@test.com' WHERE id = 1;
```

测试建议：

- `WHERE` 尽量写分片键，例如 `WHERE id = 1`。
- 不建议写无 `WHERE` 的大范围更新。

## 删除数据

```sql
DELETE FROM readers WHERE id = 1;
```

测试建议：

- `WHERE` 尽量写分片键。
- 不建议写无 `WHERE` 的全表删除。

## 索引

创建索引：

```sql
CREATE INDEX idx_readers_name ON readers (name);
```

创建唯一索引：

```sql
CREATE UNIQUE INDEX idx_readers_no ON readers (reader_no);
```

删除索引：

```sql
DROP INDEX idx_readers_name;
```

查看索引：

```sql
SHOW INDEXES;
SHOW INDEXES readers;
```

## 集群查看命令

查看 datanode：

```sql
SHOW NODES;
```

查看所有分片：

```sql
SHOW SHARDS;
```

查看某张表分片：

```sql
SHOW SHARDS readers;
```

查看集群完整状态：

```sql
SHOW CLUSTER;
```

查看底层物理表：

```sql
SHOW TABLES;
```

## 容灾和重分片命令

模拟节点故障：

```sql
FAIL NODE dn1;
```

恢复节点：

```sql
RECOVER NODE dn1;
```

修复副本：

```sql
REBALANCE CLUSTER;
```

手动触发重新分片：

```sql
RESHARD CLUSTER;
```

移除节点：

```sql
REMOVE NODE dn5;
```
