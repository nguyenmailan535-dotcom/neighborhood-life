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
| 可复现秒杀性能数据 | `performance/jmeter/seckill-voucher-comparison.jmx` | `performance/seckill/RESULTS.md` 保留 Redis Lua + 同步 MySQL 与 Redis Lua + MQ 三轮新口径原始结果 |
| 热点分布与 Cache Hit Rate | `performance/jmeter/shop-cache-comparison.jmx`、`MultiLevelShopCache.localStats` | `performance/cache/RESULTS.md` 保留 70/20/10 分布的三轮数据和采样前后计数差值 |

## 性能表述状态

既有 `5,990 -> 12,656 QPS、46.0 ms -> 34.3 ms P95` 来自固定 `/shop/1` 的单 Key 历史实验，
只能说明收益上界。当前缓存主实验已改为 20,000 条店铺上的 70/20/10 热点分布。新三轮实验的
平均 QPS 为 `5,521.15 -> 7,409.66`（+34.2%），平均 P95 为 `56.33 -> 43.00 ms`（-23.7%），
正式采样窗口 Caffeine 平均命中率为 93.624%，六轮错误率均为 0。

秒杀新基线为“Redis Lua 原子预扣 + 同步 MySQL”，优化组为“Redis Lua +
RabbitMQ”。三轮实验的平均 QPS 为 `866.01 -> 1,383.76`（+59.8%），平均 P95 为
`447.33 -> 286.00 ms`（-36.1%），三轮错误率均为 0。

新口径的两组都保留 Redis Lua、一人一单、API/用户/IP 三维限流和数据库唯一索引；性能阶段每个
用户仅请求一次，避免把预期业务拒绝混入吞吐统计。Redis 资格预扣后的主要实验变量是同步执行
MySQL 事务，还是写 Outbox 并投递 RabbitMQ 后异步落库。新三轮结果及一致性证据已保留在
`performance/seckill/RESULTS.md`。
