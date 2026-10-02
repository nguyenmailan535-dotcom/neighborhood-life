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
| 可复现秒杀性能数据 | `performance/jmeter/seckill-voucher-comparison.jmx` | `performance/seckill/README.md` 规定口径，`performance/seckill/RESULTS.md` 保留三轮结果与 Actions 证据 |

## 性能表述状态

既有 `5,990 -> 12,656 QPS、46.0 ms -> 34.3 ms P95` 测量的是热点商铺读取，只能用于多级
缓存专项说明，不能写成优惠券秒杀性能。秒杀链路已完成三轮有效对照：平均受理 QPS
`643.63 -> 1,238.20`（`+92.4%`），平均 P95 `757.67 ms -> 325.00 ms`（`-57.1%`）。

两组均保留相同的一人一单、API/用户/IP 三维限流和数据库唯一索引；性能阶段每个用户仅请求一次，
避免把预期业务拒绝混入延迟与吞吐统计。唯一实验变量是同步数据库落单或 Redis/MQ 异步落单。

三轮业务、HTTP 与连接错误均为 0，端到端落库完成率 100%，无超卖、重复订单、
Outbox 遗留、主队列积压与死信；详细证据见 `performance/seckill/RESULTS.md`。
