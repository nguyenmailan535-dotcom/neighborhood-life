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
| 可复现性能数据 | `performance/jmeter/shop-cache-comparison.jmx` | `performance/results/2026-10-02-local.md` 保存参数、逐轮统计和口径 |

## 当前可使用的性能表述

在 i5-11300H、Java 8、本机回环环境下，以 200 并发、10 秒升压、持续 60 秒并各运行 3 轮，
多级缓存相较 Redis 基线的平均 QPS 从 5,990 提升至 12,656（+111.3%），平均 P95 从
46.0 ms 降至 34.3 ms（-25.4%），六轮错误率均为 0%。

不建议继续使用 `545 -> 984 QPS、310 ms -> 167 ms P95`，因为当前仓库没有对应环境的原始
JTL；代码能力已经覆盖该描述，但简历中的数值应替换成可复现结果。
