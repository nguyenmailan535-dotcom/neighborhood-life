#!/usr/bin/env python3
"""Summarize successful seckill requests from a JMeter CSV JTL."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path


def percentile(values: list[int], ratio: float) -> int | None:
    if not values:
        return None
    values.sort()
    return values[max(0, math.ceil(len(values) * ratio) - 1)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("jtl", type=Path)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    successful_elapsed: list[int] = []
    starts: list[int] = []
    ends: list[int] = []
    attempts = 0
    business_rejections = 0
    http_errors = 0
    transport_errors = 0
    with args.jtl.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            attempts += 1
            started = int(row["timeStamp"])
            elapsed = int(row["elapsed"])
            starts.append(started)
            ends.append(started + elapsed)
            if row["success"].lower() == "true":
                successful_elapsed.append(elapsed)
            elif row["responseCode"].startswith("Non HTTP response code"):
                transport_errors += 1
            elif row["responseCode"] == "200":
                business_rejections += 1
            else:
                http_errors += 1

    duration = (max(ends) - min(starts)) / 1000 if starts else 0.0
    accepted = len(successful_elapsed)
    result = {
        "mode": args.mode,
        "attempts": attempts,
        "accepted": accepted,
        "rejected_or_failed": attempts - accepted,
        "business_rejections": business_rejections,
        "http_errors": http_errors,
        "transport_errors": transport_errors,
        "success_rate": accepted / attempts if attempts else 0.0,
        "duration_seconds": duration,
        "attempt_qps": attempts / duration if duration else 0.0,
        "accepted_qps": accepted / duration if duration else 0.0,
        "average_ms": sum(successful_elapsed) / accepted if accepted else None,
        "p50_ms": percentile(successful_elapsed.copy(), 0.50),
        "p95_ms": percentile(successful_elapsed.copy(), 0.95),
        "p99_ms": percentile(successful_elapsed.copy(), 0.99),
    }
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
