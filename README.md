# 邻里生活｜本地生活服务平台

面向本地生活消费场景，构建支持热点商铺缓存、高并发秒杀、异步下单与流量治理的平台。
本仓库在黑马点评教学项目代码基础上，重点实现并验证多级缓存、分布式限流、Redis Lua
秒杀资格校验与 RabbitMQ 异步落库链路。

技术栈：Java 8、Spring Boot、MySQL、Redis、Lua、RabbitMQ、Redisson、Caffeine、MyBatis-Plus、JMeter

## 核心链路

### 高并发秒杀

`VoucherOrderController -> Redis Sliding Window -> seckill.lua -> Redis Outbox -> RabbitMQ -> MySQL`

- Redis Lua 在一个原子事务内完成库存校验、重复下单判断、预扣库存、用户标记和 Outbox 事件写入。
- 数据库使用 `stock > 0` 条件更新防止超卖，并通过 `(user_id, voucher_id)` 唯一索引保证一人一单。
- 本地 Guava 令牌桶已替换为 Redis ZSET + Lua 滑动窗口，通过 AOP 注解支持 API、用户和 IP 三种维度。

### 异步下单与可靠消息

- RabbitMQ 使用持久化 Exchange/Queue；Publisher Confirm 成功后才删除 Redis Outbox 事件，不可路由消息继续保留并由定时任务重发。
- 消费端使用手动 ACK，持久化成功或识别为幂等重复后确认；异常由容器执行最多 3 次退避重试，耗尽后进入 DLQ。
- 重复投递由业务唯一索引和事务回滚共同兜底，避免消息重试造成重复扣库存。

### 多级缓存

`Caffeine -> Redis logical-expire value -> MySQL`

- Caffeine 承接进程内热点读取；Redis 保存可返回的逻辑过期数据；未命中时回源 MySQL。
- 缓存空值防穿透，逻辑 TTL 加随机抖动降低雪崩风险。
- 逻辑过期后返回旧值并异步重建，使用 Redisson 分布式锁确保多实例只有一个重建者。
- 数据库更新提交后再失效 Caffeine 与 Redis，避免事务未提交时缓存被旧数据重新填充。

## 验证

```bash
mvn test
```

当前自动化测试覆盖多级缓存命中/回源/锁释放、RabbitMQ 手动 ACK 与异常传播、DLQ 拓扑、三维滑动窗口限流及 Lua 接受后才发送订单消息。

## 本地运行

公开配置只包含环境变量占位符。个人连接信息请放在被 Git 忽略的
`src/main/resources/application-local.yaml` 中。

邮件发送功能从 `MAIL_USER`、`MAIL_PASSWORD` 读取发件账号和 SMTP 授权码；图片上传目录可通过
`IMAGE_UPLOAD_DIR` 配置。不要把个人账号、授权码或本机绝对路径写入源码。

```powershell
docker compose -f compose.local.yml up --build
```

MySQL 初始化脚本会创建订单业务唯一索引；已有数据库可单独执行
`src/main/resources/db/migration/V2__voucher_order_idempotency.sql`。

## 性能与正确性验证

秒杀指标使用 `performance/jmeter/seckill-voucher-comparison.jmx` 压测优惠券秒杀接口，支持
通过 `SECKILL_MODE=redis-sync/redis-mq` 对比“Redis Lua + 同步 MySQL”与
“Redis Lua + RabbitMQ 异步落库”。
测试为每次请求分配不同的用户 token 和 IP，并在采样结束后核验落库完成率、库存一致性、重复
订单、Outbox 与 DLQ。完整协议见
[`performance/seckill/README.md`](performance/seckill/README.md)。新基线三轮实验中，异步链路平均
受理 QPS 较同步链路提升 59.8%，平均 P95 下降 36.1%；完整数据见
[`performance/seckill/RESULTS.md`](performance/seckill/RESULTS.md)。

`shop-cache-comparison.jmx` 使用 20,000 条店铺上的 `70% / 20% / 10%` 热点分布，并通过
`/shop/_cache/stats` 记录正式采样窗口内的 Cache Hit Rate。旧的单 Key 数值只保留为历史上界，
不能作为热点分布或秒杀链路指标。新三轮结果中，多级缓存平均 QPS 提升
34.2%、平均 P95 下降 23.7%，正式采样窗口 Caffeine 平均命中率为 93.624%；完整数据见
[`performance/cache/RESULTS.md`](performance/cache/RESULTS.md)。

| 场景 | 对照链路 | 优化链路 | QPS | P95 |
| --- | --- | --- | ---: | ---: |
| 热点商铺读取 | Redis + MySQL | Caffeine + Redis + MySQL | 5,521 → 7,410 | 56.3 ms → 43.0 ms |
| 优惠券秒杀 | Redis Lua + 同步 MySQL | Redis Lua + RabbitMQ 异步落库 | 866 → 1,384 | 447 ms → 286 ms |

秒杀采样指标表示接口受理能力；采样结束后继续消费队列并核验最终状态，订单落库完成率为
100%，且未出现超卖与重复订单。

