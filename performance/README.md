# 可复现的性能测试

简历主指标应使用秒杀优惠券对照测试：

- 测试计划：`jmeter/seckill-voucher-comparison.jmx`
- 数据准备与正确性协议：[`seckill/README.md`](seckill/README.md)
- 基线：MySQL 同步下单
- 优化：Redis Lua 原子预扣 + RabbitMQ 异步落库

下面的热点商铺读取测试只用于验证 Caffeine 多级缓存，不代表秒杀性能。

## 多级缓存专项实验

该测试使用相同的 `GET /shop/1` 热点读取负载，对比两种运行模式：

- 基线：`LOCAL_CACHE_ENABLED=false`（Redis + MySQL 回源）
- 优化：`LOCAL_CACHE_ENABLED=true`（Caffeine + Redis + MySQL 回源）

两组实验必须使用相同机器、JVM 参数、数据库快照、JMeter 参数和预热方式。启动依赖及应用，访问
一次 `/shop/1` 完成预热，再运行：

```powershell
jmeter -n -t performance/jmeter/shop-cache-comparison.jmx `
  -Jthreads=200 -Jramp=10 -Jduration=60 -JshopId=1 `
  -l performance/results/<mode>.jtl `
  -e -o performance/results/<mode>-report
```

每种模式至少运行 3 次，同时记录吞吐量、P50/P95/P99 和错误率。缓存专项复测结果见
[`results/2026-10-02-local.md`](results/2026-10-02-local.md)。原始 JTL 体积较大且包含运行时数据，
默认被 `.gitignore` 排除；结果文档保留参数、机器信息、逐轮统计与计算口径。
