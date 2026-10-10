# 可复现的性能测试

简历主指标应使用秒杀优惠券对照测试：

- 测试计划：`jmeter/seckill-voucher-comparison.jmx`
- 数据准备与正确性协议：[`seckill/README.md`](seckill/README.md)
- 三轮实测结果：[`seckill/RESULTS.md`](seckill/RESULTS.md)
- 基线：Redis Lua 原子预扣 + 同步 MySQL 事务
- 优化：Redis Lua 原子预扣 + RabbitMQ 异步落库

下面的热点分布读取测试只用于验证 Caffeine 多级缓存，不代表秒杀性能。

## 多级缓存专项实验

该测试使用相同的 `70% / 20% / 10%` 热点分布读取负载，对比两种运行模式：

- 70% 请求访问 ID 1-100，20% 访问 ID 101-2,000，10% 访问 ID 2,001-20,000；

- 基线：`LOCAL_CACHE_ENABLED=false`（Redis + MySQL 回源）
- 优化：`LOCAL_CACHE_ENABLED=true`（Caffeine + Redis + MySQL 回源）

两组实验必须使用相同机器、JVM 参数、数据库快照、JMeter 参数、随机 Seed 和预热方式。先执行
`cache/prepare-hot-shops.sql`，用相同分布预热 60 秒，并在正式采样前后分别读取
`GET /shop/_cache/stats`，再运行：

```powershell
jmeter -n -t performance/jmeter/shop-cache-comparison.jmx `
  -Jthreads=200 -Jramp=10 -Jduration=180 -Jseed=20261010 `
  -l performance/results/<mode>.jtl `
  -e -o performance/results/<mode>-report
```

每种模式至少运行 3 次，同时记录吞吐量、P50/P95/P99、错误率和正式采样窗口内的
**Cache Hit Rate**。完整协议见 [`cache/README.md`](cache/README.md)。旧的
[`results/2026-10-02-local.md`](results/2026-10-02-local.md) 是单 Key 历史实验，不能作为新的
热点分布结果。新三轮数据见 [`cache/RESULTS.md`](cache/RESULTS.md)。原始 JTL 默认被
`.gitignore` 排除；结果文档保留参数、机器信息、逐轮统计与口径。
