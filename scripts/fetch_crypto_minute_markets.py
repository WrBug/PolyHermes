#!/usr/bin/env python3
"""
获取 Polymarket 5/15 分钟加密市场数据（开始时间、结束时间、conditionId）。
使用 Gamma API: https://gamma-api.polymarket.com
验证方式: python3 scripts/fetch_crypto_minute_markets.py
"""
import json
import time
import urllib.request
from datetime import datetime, timezone

GAMMA_BASE = "https://gamma-api.polymarket.com"


def fetch_event_by_slug(slug: str) -> dict | None:
    url = f"{GAMMA_BASE}/events/slug/{slug}"
    req = urllib.request.Request(url, headers={"User-Agent": "PolymarketBot/1.0 (script)"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    except Exception as e:
        print(f"Request error {url}: {e}")
        return None


def parse_iso_to_ms(iso: str | None) -> int | None:
    if not iso:
        return None
    try:
        # ISO 可能带 Z 或 +00:00
        if iso.endswith("Z"):
            iso = iso.replace("Z", "+00:00")
        dt = datetime.fromisoformat(iso.replace("Z", "+00:00"))
        return int(dt.timestamp() * 1000)
    except Exception:
        return None


def main():
    now = int(time.time())
    # 5 分钟周期边界 (300s)
    period_5m = (now // 300) * 300
    next_5m = period_5m + 300
    # 15 分钟周期边界 (900s)；slug 可能用结束时间，这里试起点
    period_15m = (now // 900) * 900
    next_15m = period_15m + 900

    print("=== 5 minute markets (BTC) ===")
    for ts, label in [(period_5m, "current"), (next_5m, "next")]:
        slug = f"btc-updown-5m-{ts}"
        ev = fetch_event_by_slug(slug)
        if ev and ev.get("slug"):
            start = ev.get("startDate")
            end = ev.get("endDate")
            print(f"  [{label}] slug={slug}")
            print(f"    title: {ev.get('title', '')[:70]}")
            print(f"    startDate: {start}  endDate: {end}")
            markets = ev.get("markets") or []
            for m in markets[:1]:
                cid = m.get("conditionId")
                print(f"    conditionId: {cid}")
                print(f"    question: {(m.get('question') or '')[:60]}")
                # clobTokenIds 用于订单簿
                tokens = m.get("clobTokenIds")
                if tokens:
                    try:
                        ids = json.loads(tokens) if isinstance(tokens, str) else tokens
                        print(f"    clobTokenIds: {ids[:2]}..." if len(ids) > 2 else f"    clobTokenIds: {ids}")
                    except Exception:
                        print(f"    clobTokenIds: {tokens[:80]}...")
        else:
            print(f"  [{label}] slug={slug} -> not found (404 or empty)")

    print("\n=== 15 minute markets (BTC) ===")
    for ts, label in [(period_15m, "current"), (next_15m, "next")]:
        slug = f"btc-updown-15m-{ts}"
        ev = fetch_event_by_slug(slug)
        if ev and ev.get("slug"):
            print(f"  [{label}] slug={slug}")
            print(f"    title: {ev.get('title', '')[:70]}")
            print(f"    startDate: {ev.get('startDate')}  endDate: {ev.get('endDate')}")
            for m in (ev.get("markets") or [])[:1]:
                print(f"    conditionId: {m.get('conditionId')}")
        else:
            print(f"  [{label}] slug={slug} -> not found")

    print("\n=== Summary ===")
    print("5m: slug btc-updown-5m-{periodStartUnix}, periodStartUnix = (now // 300) * 300; period end = endDate.")
    print("15m: slug btc-updown-15m-{periodStartUnix}, periodStartUnix = (now // 900) * 900; period end = endDate.")
    print("Period start = slug timestamp; period end = API endDate (do not use startDate as period start).")


if __name__ == "__main__":
    main()
