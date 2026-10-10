# 多级缓存热点分布对照实验

该实验比较完全相同的 `70% / 20% / 10%` 店铺读取流量：

- 70%：店铺 ID 1-100（核心热点）；
- 20%：店铺 ID 101-2,000（次热点）；
- 10%：店铺 ID 2,001-20,000（长尾）；
- 基线：`LOCAL_CACHE_ENABLED=false`，读取链路为 Redis，未命中时回源 MySQL；
- 优化：`LOCAL_CACHE_ENABLED=true`，读取链路为 Caffeine -> Redis -> MySQL。

Caffeine 的真实配置保持 `maximumSize=10,000`、`expireAfterWrite=30s`，小于 20,000 条店铺
工作集。两组唯一核心变量是是否启用本地缓存。

## 1. 准备 20,000 条店铺数据

在一次性测试数据库执行：

```powershell
Get-Content performance/cache/prepare-hot-shops.sql -Raw |
  mysql -h 127.0.0.1 -P 3307 -uroot -phmdp_dev hmdp
```

不要在生产数据库运行该脚本。确认 `SELECT COUNT(*) FROM tb_shop` 至少返回 20,000。

## 2. 固定负载参数

推荐先预热 60 秒，再正式采样 180 秒。两种模式使用相同的 Seed 和 ID 范围：

```powershell
$common = @(
  '-Jthreads=200', '-Jramp=10', '-Jseed=20261010',
  '-JhotStart=1', '-JhotCount=100',
  '-JwarmStart=101', '-JwarmCount=1900',
  '-JtailStart=2001', '-JtailCount=18000'
)
```

每轮都应清理 `cache:shop:*`，重启应用以清空 JVM Caffeine 及其累计统计，然后使用对应的
`LOCAL_CACHE_ENABLED` 启动。先运行不计入结果的预热：

```powershell
& jmeter -n -t performance/jmeter/shop-cache-comparison.jmx @common `
  -Jduration=60 -l performance/results/cache-warmup.jtl
```

预热结束后、正式采样开始前，保存累计计数快照：

```powershell
Invoke-RestMethod http://127.0.0.1:8081/shop/_cache/stats |
  ConvertTo-Json -Depth 5 |
  Set-Content performance/results/cache-before.json -Encoding utf8
```

正式采样：

```powershell
& jmeter -n -t performance/jmeter/shop-cache-comparison.jmx @common `
  -Jduration=180 -l performance/results/cache-run.jtl

Invoke-RestMethod http://127.0.0.1:8081/shop/_cache/stats |
  ConvertTo-Json -Depth 5 |
  Set-Content performance/results/cache-after.json -Encoding utf8

python performance/cache/analyze-cache-run.py `
  performance/results/cache-run.jtl `
  --before-stats performance/results/cache-before.json `
  --after-stats performance/results/cache-after.json `
  --mode caffeine `
  --output performance/results/cache-run.json
```

基线组使用 `--mode redis`。由于基线禁用了本地缓存，`caffeine.hit_rate` 应为 `null`；优化组
则报告正式采样窗口内 `hitCount / (hitCount + missCount)`，而不是包含预热期的累计命中率。

## 3. 有效性要求

- 每种模式至少运行三轮，且每轮错误率为 0；
- 两组使用相同机器、JVM、数据库快照、JMeter 参数和随机 Seed；
- 应同时报告 QPS、平均延迟、P95、P99、错误率与 Cache Hit Rate；
- 不得继续把旧的单 Key `GET /shop/1` 指标描述成热点分布结果；
- 本实验只验证浏览读链路，不代表优惠券秒杀性能。
