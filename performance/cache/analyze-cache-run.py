#!/usr/bin/env python3
"""Combine JMeter latency/throughput with per-JVM Caffeine counter deltas."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
from typing import Any


def percentile(values: list[int], ratio: float) -> int | None:
    if not values:
        return None
    values.sort()
    return values[max(0, math.ceil(len(values) * ratio) - 1)]


def load_stats(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(payload, dict) and isinstance(payload.get("data"), dict):
        return payload["data"]
    if isinstance(payload, dict):
        return payload
    raise ValueError(f"Expected a JSON object in {path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("jtl", type=Path)
    parser.add_argument("--before-stats", type=Path, required=True)
    parser.add_argument("--after-stats", type=Path, required=True)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    elapsed_values: list[int] = []
    starts: list[int] = []
    ends: list[int] = []
    errors = 0
    with args.jtl.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            started = int(row["timeStamp"])
            elapsed = int(row["elapsed"])
            starts.append(started)
            ends.append(started + elapsed)
            if row["success"].lower() == "true":
                elapsed_values.append(elapsed)
            else:
                errors += 1

    before = load_stats(args.before_stats)
    after = load_stats(args.after_stats)
    hit_count = int(after.get("hitCount", 0)) - int(before.get("hitCount", 0))
    miss_count = int(after.get("missCount", 0)) - int(before.get("missCount", 0))
    request_count = hit_count + miss_count
    if hit_count < 0 or miss_count < 0:
        raise SystemExit("Caffeine counters moved backwards; the application restarted mid-sample")

    duration = (max(ends) - min(starts)) / 1000 if starts else 0.0
    samples = len(elapsed_values)
    result = {
        "mode": args.mode,
        "samples": samples,
        "errors": errors,
        "error_rate": errors / (samples + errors) if samples + errors else 0.0,
        "duration_seconds": duration,
        "qps": samples / duration if duration else 0.0,
        "average_ms": sum(elapsed_values) / samples if samples else None,
        "p50_ms": percentile(elapsed_values.copy(), 0.50),
        "p95_ms": percentile(elapsed_values.copy(), 0.95),
        "p99_ms": percentile(elapsed_values.copy(), 0.99),
        "caffeine": {
            "enabled": bool(after.get("enabled", False)),
            "request_count": request_count,
            "hit_count": hit_count,
            "miss_count": miss_count,
            "hit_rate": hit_count / request_count if request_count else None,
            "eviction_count_delta": int(after.get("evictionCount", 0))
            - int(before.get("evictionCount", 0)),
            "estimated_size_after": int(after.get("estimatedSize", 0)),
        },
    }
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
