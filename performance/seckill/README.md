# 秒杀优惠券对照压测

本测试比较同一个 `POST /voucher-order/seckill/9001` 接口的两条实现：

- `SECKILL_MODE=database`：MySQL 同步校验、条件扣库存并写订单（基线）。
- `SECKILL_MODE=redis-mq`：Redis Lua 原子校验/去重/预扣，RabbitMQ 异步落库（优化）。

两组都保留数据库 `stock > 0` 条件更新与 `(user_id, voucher_id)` 唯一索引。JMeter 从 CSV
为每次请求读取不同 token 和 IP，避免“一人一单”与 IP 限流把正常请求误判为压测失败。
200 个 JMeter 线程各自复用 HTTP Keep-Alive 连接，CSV 凭据仍在每次迭代时全局顺序取下一行，
避免通过 Docker 端口映射时因反复建立短连接耗尽宿主机临时端口。
API、用户、IP 三维滑动窗口限流在两组中均保持启用且阈值一致；每个用户在性能阶段只发起一次
请求，一人一单与限流拒绝另行通过重复请求测试和自动化测试验证，不通过关闭保护逻辑换取吞吐。
GitHub Actions 对两组统一设置 `RATE_LIMIT_MULTIPLIER=10`，使限流链路仍真实执行，
但不成为本次对比的主要瓶颈；生产默认值仍为 `1`。

## 1. 准备相同初始数据

每轮前执行 `reset-voucher.sql`，清空测试券订单并将数据库库存重置为 100,000；随后运行：

```powershell
& .\performance\seckill\prepare-seckill-users.ps1 `
  -RedisCli .\path\to\redis-cli.exe `
  -UserCount 100000 -VoucherId 9001 -Stock 100000
```

脚本会生成被 Git 忽略的 `performance/results/seckill-users.csv`，并初始化认证 token、Redis
库存、用户去重集合、Outbox 和限流 Key。两组必须使用相同机器、JVM、依赖版本与 JMeter 参数。

## 2. 基线组

以 `SECKILL_MODE=database` 启动应用，预热后执行：

```powershell
jmeter -n -t performance/jmeter/seckill-voucher-comparison.jmx `
  -Jthreads=200 -Jramp=10 -Jduration=60 -JvoucherId=9001 `
  -Jcredentials=performance/results/seckill-users.csv `
  -l performance/results/seckill-database-1.jtl
```

## 3. 优化组

完全重置 MySQL、Redis 与 RabbitMQ 队列，以 `SECKILL_MODE=redis-mq` 重启应用并使用完全相同
命令输出到 `seckill-redis-mq-1.jtl`。为单独测量入队链路，采样期设置
`RABBIT_LISTENER_ENABLED=false`；采样结束立即重启消费者，等待 RabbitMQ 队列与 Redis Outbox
清空后再读取数据库最终状态。每组至少运行 3 轮。

## 4. 必须同时报告的指标

- 请求受理吞吐（成功请求数/实际测试时间）、平均延迟、P95、P99；
- 分开统计业务拒绝、HTTP 服务端错误与连接级错误，任一 HTTP/连接错误均使该轮无效；
- 优化组队列完全排空所需时间与端到端订单落库完成率；
- `数据库初始库存 - 最终库存`、成功订单数、重复业务键数；
- Redis 预扣库存、数据库库存、订单数是否一致，是否出现负库存；
- RabbitMQ 重试次数、DLQ 数量和 Redis Outbox 遗留数量。
- 同一用户第二次下单必须被拒绝；同一用户/IP 的超阈值突发必须触发限流。

只有“成功订单数等于库存扣减数、重复订单为 0、库存不为负、队列最终排空”的轮次才能纳入
简历数据。商铺读取缓存实验不能替代本秒杀测试。
