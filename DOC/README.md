# 真实 MySQL + ShardingSphere + Redis 集成测试

目录 `project/` 内两份文件分别复制到你的短链接项目中相同的相对路径。

## 只运行之前的 45 项测试（不触碰 MySQL）
```powershell
cd E:\STUDENT\shortlink-main
mvn -pl project -am test
```

## 显式开启真实数据库测试
先确保当前 MySQL、Redis 运行，确认 ShardingSphere 对应配置指向的确实是允许测试的数据库。

```powershell
cd E:\STUDENT\shortlink-main
mvn -pl project -am test -DrunMysqlIT=true
```

**有意加的安全开关**：没有 `-DrunMysqlIT=true`，新增的 `ShortLinkMysqlIntegrationTest` 会被 JUnit 跳过。其他 45 项继续执行。

## 本轮 9 项
1. 两张真实分片表正常提交
2. MySQL 主表唯一键冲突
3. 映射表唯一键冲突后主表回滚
4. 两表 SQL 执行后抛异常时完整回滚
5. 模拟 Redis 抛异常后的数据库回滚契约（Redis 异常为模拟，SQL 为真实）
6. 不同 gid 真实分片路由
7. 真实 Redis 写、读及 TTL
8. 真实 Service 创建、查表、缓存和 HTTP 302
9. 真实 Service 加锁创建、查表和缓存

## 范围说明
- 测试不会 `TRUNCATE`、`DROP` 或 `FLUSHDB`，清理只针对当前测试生成的完整短链 URL 和 Redis key。
- 测试可能会触发短链接统计 Redis Stream；Service 的 Bloom Filter add 不能自动撤销。
- `test05` 在真实 SQL 事务中抛 RedisConnectionFailureException，**没有断开真实 Redis，也不是实际的 Service 故障注入**。
- `test03` 验证真实 SQL 分片事务，但未故意劫持 Service 内部 Mapper。
- 若数据库的真实配置有额外权限约束或域名策略，请以当前源码为准。归档代码可能旧于本地 Git 提交。
- 如发生清理失败，先检查 `project/target/surefire-reports`，不要直接运行广泛的 DELETE。

## 补充 4 项真实 Service 故障注入
`ShortLinkMysqlFaultInjectionTest` 额外包含四项：普通创建的跳转表/Redis 故障、加锁创建的跳转表/Redis 故障。使用 `@SpyBean` 仅在指定错误点注入异常，其余 SQL 仍走真正的 Mapper、ShardingSphere 和 MySQL。这四项是测试 Service 事务代理的核心验证，不等同于完整停掉数据库或 Redis 服务。

合计 13 项真实环境集成测试；如果你原有 45 项不变，显式开启时目标为 58 项（新测试环境可能暴露真实故障）。
