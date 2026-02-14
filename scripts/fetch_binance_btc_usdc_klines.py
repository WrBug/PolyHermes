#!/usr/bin/env python3
"""
从币安现货 API 获取 BTC/USDC 15 分钟 K 线数据。

API: https://api.binance.com/api/v3/klines
无需 API Key，公开行情接口。

使用方法:
  python3 scripts/fetch_binance_btc_usdc_klines.py
  python3 scripts/fetch_binance_btc_usdc_klines.py --limit 96
  python3 scripts/fetch_binance_btc_usdc_klines.py --limit 10 --interval 15m
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BINANCE_BASE = "https://api.binance.com"


def fetch_klines(
    symbol: str = "BTCUSDC",
    interval: str = "15m",
    limit: int = 500,
    start_time: int | None = None,
    end_time: int | None = None,
) -> list[list] | None:
    """
    获取 K 线数据。
    返回每根 K 线: [ openTime, open, high, low, close, volume, closeTime, ... ]
    """
    params = {"symbol": symbol, "interval": interval, "limit": limit}
    if start_time is not None:
        params["startTime"] = start_time
    if end_time is not None:
        params["endTime"] = end_time
    qs = urllib.parse.urlencode(params)
    url = f"{BINANCE_BASE}/api/v3/klines?{qs}"
    req = urllib.request.Request(url, headers={"User-Agent": "PolymarketBot/1.0 (script)"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        body = e.read().decode() if e.fp else ""
        try:
            err = json.loads(body)
        except json.JSONDecodeError:
            err = {"msg": body}
        print(f"Request failed: {e.code} - {err}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"Request error: {e}", file=sys.stderr)
        return None


def main():
    parser = argparse.ArgumentParser(description="Fetch Binance BTC/USDC 15m klines")
    parser.add_argument("--symbol", default="BTCUSDC", help="Trading pair (default: BTCUSDC)")
    parser.add_argument("--interval", default="15m", help="Kline interval (default: 15m)")
    parser.add_argument("--limit", type=int, default=20, help="Number of klines (default: 20, max 1000)")
    parser.add_argument("--start", type=int, default=None, help="Start time (ms)")
    parser.add_argument("--end", type=int, default=None, help="End time (ms)")
    args = parser.parse_args()

    limit = max(1, min(1000, args.limit))

    print("=== Binance BTC/USDC K-line (15m) ===\n")
    print(f"Symbol: {args.symbol}  Interval: {args.interval}  Limit: {limit}")
    if args.start:
        print(f"Start: {args.start} ({time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(args.start // 1000))})")
    if args.end:
        print(f"End:   {args.end} ({time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(args.end // 1000))})")
    print()

    klines = fetch_klines(
        symbol=args.symbol,
        interval=args.interval,
        limit=limit,
        start_time=args.start,
        end_time=args.end,
    )

    if not klines:
        print("No kline data returned")
        sys.exit(1)

    print(f"Got {len(klines)} kline(s)\n")
    print("Columns: openTime, open, high, low, close, volume, closeTime, ...")
    print("-" * 72)
    for k in klines:
        open_ts_ms = k[0]
        open_ts = open_ts_ms // 1000
        ts_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(open_ts))
        o, h, l, c, v = k[1], k[2], k[3], k[4], k[5]
        print(f"  {ts_str}  O:{o}  H:{h}  L:{l}  C:{c}  V:{v}")
    print("-" * 72)
    last = klines[-1]
    print(f"Latest: open={last[1]}, high={last[2]}, low={last[3]}, close={last[4]}, volume={last[5]}")


if __name__ == "__main__":
    main()
