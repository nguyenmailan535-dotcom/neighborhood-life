# 简历描述与实现对照

本文档用于保证项目表述可由仓库代码、测试和压测结果直接验证。

| 简历能力点 | 实现位置 | 验证方式 |
|---|---|---|
| Redis + Lua 原子校验库存、去重与预扣 | `src/main/resources/seckill.lua`、`VoucherOrderServiceImpl` | `VoucherOrderServiceImplTest` 验证 Lua 成功后才投递订单事件 |
| 数据库条件扣减与一人一单 | `VoucherOrderServiceImpl.persistVoucherOrder`、`db/migration/V2__voucher_order_idempotency.sql` | `stock > 0` 条件更新 + `(user_id, voucher_id)` 唯一索引 |
| RabbitMQ Confirm、手动 ACK、重试与 DLQ | `MQSender`、`MQReceiver`、`RabbitMQTopicConfig` | `MQReceiverTest`、`RabbitMQTopicConfigTest` |
| 消息重复消费幂等 | 订单业务唯一索引 + 消费端将 `DuplicateKeyException` 视为已完成 | 单元测试与数据库约束共同验证 |
| Caffeine + Redis + MySQL 多级缓存 | `cache/MultiLevelShopCache`、`LocalCacheConfig` | `MultiLevelShopCacheTest` |
| 空值、逻辑过期、随机 TTL、Redisson 重建锁 | `MultiLevelShopCache` | 命中、回源、锁释放测试；热点接口 JMeter 对照 |
| Redis + Lua + AOP 滑动窗口限流 | `ratelimit/*`、`sliding-window-rate-limit.lua` | `SlidingWindowRateLimitAspectTest` 覆盖 API、用户、IP 三维策略 |
| 可复现秒杀性能数据 | `performance/jmeter/seckill-voucher-comparison.jmx` | `performance/seckill/README.md` 规定唯一用户、库存一致性、落库完成率与三轮对照口径 |

## 性能表述状态

既有 `5,990 -> 12,656 QPS、46.0 ms -> 34.3 ms P95` 测量的是热点商铺读取，只能用于多级
缓存专项说明，不能写成优惠券秒杀性能。秒杀项目描述必须等待数据库同步基线与 Redis Lua +
RabbitMQ 优化组各完成至少三轮有效测试，并同时通过库存、幂等、落库与队列排空校验。

两组均保留相同的一人一单、API/用户/IP 三维限流和数据库唯一索引；性能阶段每个用户仅请求一次，
避免把预期业务拒绝混入延迟与吞吐统计。唯一实验变量是同步数据库落单或 Redis/MQ 异步落单。

在正式秒杀结果产生前，不应使用 `545 -> 984` 或任何其他秒杀 QPS/P95 数字。
