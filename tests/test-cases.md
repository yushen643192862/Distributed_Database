# Distributed MiniSQL 验收测试用例

## 运行方式

本地运行�?

```powershell
mvn compile
Get-Content tests\acceptance.sql | java "-Dminisql.data=target\acceptance-state.bin" -cp target\classes minisql.app.App
```

Docker 运行�?

```powershell
docker build -t distributed-minisql:local .
docker volume rm minisql-test-data
docker volume create minisql-test-data
Get-Content tests\acceptance.sql | docker run --rm -i -v minisql-test-data:/app/data distributed-minisql:local
```

## 覆盖�?

1. SQL 基础功能：`CREATE TABLE`、`DROP TABLE`、`INSERT`、`DELETE`、`UPDATE`、`SELECT`�?
2. 数据分片：`SHARD BY HASH(sid) SHARDS 3`，通过 `SHOW SHARDS student` 检查分片布局�?
3. 副本管理：`REPLICAS 3` 生成 1 �?2 从布局�?
4. 分布式查询：分片键查询只命中目标 shard，非分片键查询广播所�?shard�?
5. JOIN：`SELECT student.name, course.cname FROM student JOIN course ON student.sid = course.sid;`�?
6. 集群管理：`SHOW NODES`、`SHOW CLUSTER` 展示节点状态、读写计数和路由版本�?
7. 容错容灾：`FAIL NODE dn2` 后触发受影响 shard 自动切主，查询仍可读�?
8. 恢复补齐：`RECOVER NODE dn2` 后恢复节点从健康副本同步 shard 数据�?
9. 负载均衡：连续读取同一分片后，`SHOW NODES` �?reads 计数会分布到多个健康副本�?
10. 客户端：每条 SQL 执行后显示耗时�?
