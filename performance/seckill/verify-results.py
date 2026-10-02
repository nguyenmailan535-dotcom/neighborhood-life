#!/usr/bin/env python3
"""Fail when seckill benchmark persistence invariants do not hold."""

from __future__ import annotations

import json
from pathlib import Path


RESULTS = Path("performance/results")
INITIAL_STOCK = 100_000


def read_db(name: str) -> tuple[int, int, int, int | None]:
    lines = (RESULTS / name).read_text(encoding="utf-8").strip().splitlines()
    order_fields = lines[0].split()
    orders = int(order_fields[0])
    unique_users = int(order_fields[1])
    stock = int(lines[1].strip())
    duplicates = int(lines[2].strip()) if len(lines) > 2 else None
    return orders, unique_users, stock, duplicates


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def main() -> None:
    database = json.loads((RESULTS / "seckill-database.json").read_text(encoding="utf-8"))
    optimized = json.loads((RESULTS / "seckill-redis-mq.json").read_text(encoding="utf-8"))
    db_orders, db_users, db_stock, _ = read_db("seckill-database-db.txt")
    mq_orders, mq_users, mq_stock, mq_duplicates = read_db("seckill-redis-mq-db.txt")
    redis_stock = int((RESULTS / "seckill-redis-stock.txt").read_text().strip())
    outbox = int((RESULTS / "seckill-outbox.txt").read_text().strip())
    queues = {}
    for line in (RESULTS / "seckill-queues.txt").read_text().splitlines():
        fields = line.split()
        if len(fields) >= 2:
            try:
                queues[fields[0]] = int(fields[1])
            except ValueError:
                # rabbitmqctl may emit the "name messages" header even with -q.
                continue

    require(database["accepted"] == db_orders, "database accepted count != persisted orders")
    require(database["transport_errors"] == 0, "database path has transport errors")
    require(database["http_errors"] == 0, "database path has HTTP server errors")
    require(database["business_rejections"] == 0, "database path hit a business limiter/rejection")
    require(db_orders == db_users, "database baseline produced duplicate user orders")
    require(db_stock == INITIAL_STOCK - db_orders, "database baseline stock mismatch")

    require(optimized["accepted"] == mq_orders, "Redis/MQ accepted count != persisted orders")
    require(optimized["transport_errors"] == 0, "Redis/MQ path has transport errors")
    require(optimized["http_errors"] == 0, "Redis/MQ path has HTTP server errors")
    require(optimized["business_rejections"] == 0, "Redis/MQ path hit a business limiter/rejection")
    require(mq_orders == mq_users, "Redis/MQ path produced duplicate user orders")
    require(mq_duplicates == 0, "Redis/MQ path has duplicate business keys")
    require(mq_stock == INITIAL_STOCK - mq_orders, "Redis/MQ database stock mismatch")
    require(redis_stock == mq_stock, "Redis and database stocks differ")
    require(outbox == 0, "Redis outbox is not empty")
    require(queues.get("seckill.order.queue", 0) == 0, "seckill queue is not empty")
    require(queues.get("seckill.order.dlq", 0) == 0, "dead-letter queue is not empty")
    print("All seckill persistence, inventory and idempotency invariants passed.")


if __name__ == "__main__":
    main()
