# 多级缓存热点分布三轮对照结果

测试日期：2026-10-10  
GitHub Actions：[38033195965](https://github.com/nguyenmailan535-dotcom/neighborhood-life/actions/runs/38033195965)

## 测试口径

- GitHub-hosted Ubuntu Runner，JMeter 5.6.3；
- 20,000 条店铺数据，每个虚拟用户按确定性 `70% / 20% / 10%` 比例请求热点、次热点与长尾店铺；
- 200 并发线程、10 秒升压，每轮先预热 60 秒，再正式采样 180 秒；
- 基线：`LOCAL_CACHE_ENABLED=false`，读链路为 Redis，未命中时回源 MySQL；
- 优化：`LOCAL_CACHE_ENABLED=true`，读链路为 Caffeine -> Redis -> MySQL；
- 两组使用相同数据、Seed、请求分布、JVM 参数与采样时长，唯一核心变量是是否启用 Caffeine；
- Cache Hit Rate 使用正式采样前后 Caffeine 计数器差值计算，不包含预热流量。

## 逐轮结果

| 模式 | 轮次 | 样本数 | 实际采样时长 | QPS | 平均延迟 | P50 | P95 | P99 | 错误数 | Cache Hit Rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Redis + MySQL | 1 | 982,568 | 179.24 s | 5,481.98 | 30.81 ms | 30 ms | 57 ms | 92 ms | 0 | - |
| Redis + MySQL | 2 | 996,122 | 179.15 s | 5,560.30 | 30.26 ms | 30 ms | 56 ms | 89 ms | 0 | - |
| Redis + MySQL | 3 | 989,396 | 179.20 s | 5,521.18 | 30.65 ms | 30 ms | 56 ms | 90 ms | 0 | - |
| Caffeine + Redis + MySQL | 1 | 1,337,414 | 179.17 s | 7,464.37 | 20.33 ms | 20 ms | 43 ms | 78 ms | 0 | 93.630% |
| Caffeine + Redis + MySQL | 2 | 1,306,746 | 179.12 s | 7,295.49 | 19.99 ms | 20 ms | 42 ms | 75 ms | 0 | 93.624% |
| Caffeine + Redis + MySQL | 3 | 1,338,479 | 179.20 s | 7,469.11 | 20.63 ms | 20 ms | 44 ms | 79 ms | 0 | 93.618% |

## 三轮汇总

| 指标 | Redis + MySQL | Caffeine + Redis + MySQL | 变化 |
|---|---:|---:|---:|
| QPS | 5,521.15 | 7,409.66 | +34.2% |
| 平均延迟 | 30.57 ms | 20.32 ms | -33.5% |
| P95 | 56.33 ms | 43.00 ms | -23.7% |
| P99 | 90.33 ms | 77.33 ms | -14.4% |
| 错误率 | 0% | 0% | 持平 |
| Cache Hit Rate | - | 93.624% | - |

Caffeine 配置为 `maximumSize=10,000`、`expireAfterWrite=30s`。三轮正式采样期间的命中率
分别为 93.630%、93.624% 和 93.618%，且三轮错误率均为 0。这些数值只代表本次受控
GitHub Runner 实验，不是生产 SLA。

## 简历可用表述

> 使用 JMeter 在 200 并发、10 秒升压的 `70% / 20% / 10%` 热点分布读取场景下完成
> 三轮对照；每轮预热 60 秒、正式采样 180 秒。相比 Redis + MySQL 基线，引入 Caffeine
> 后平均 QPS 由 5,521.15 提升至 7,409.66（+34.2%），平均 P95 由 56.33 ms 降至
> 43.00 ms（-23.7%），正式采样窗口内 Caffeine 平均命中率为 93.624%，六轮错误率均为 0。
