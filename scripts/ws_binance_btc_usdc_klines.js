#!/usr/bin/env node

/**
 * 通过币安 WebSocket 订阅 BTC/USDC 15 分钟 K 线推送。
 *
 * 文档: https://developers.binance.com/docs/binance-spot-api-docs/web-socket-streams
 * - 现货 K 线流: wss://stream.binance.com:9443/ws/btcusdc@kline_15m
 * - 服务端约每 20 秒发 ping，ws 库会自动回 pong
 * - K 线推送频率: 15m 约每 2 秒更新一次；x=true 表示该根 K 线已收盘
 *
 * 依赖: npm install ws（在 scripts 目录下执行）
 *
 * 使用方法:
 *   node scripts/ws_binance_btc_usdc_klines.js
 *   node scripts/ws_binance_btc_usdc_klines.js --interval 1m
 *   node scripts/ws_binance_btc_usdc_klines.js --url "wss://stream.binance.com:9443/ws/btcusdc@kline_15m"
 *   Ctrl+C 退出
 */

import WebSocket from 'ws';

const BINANCE_WS_BASE = 'wss://stream.binance.com:9443';

function parseArgs() {
  const args = process.argv.slice(2);
  const out = { symbol: 'btcusdc', interval: '15m', url: null };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--symbol' && args[i + 1]) {
      out.symbol = String(args[i + 1]).toLowerCase();
      i++;
    } else if (args[i] === '--interval' && args[i + 1]) {
      out.interval = args[i + 1];
      i++;
    } else if (args[i] === '--url' && args[i + 1]) {
      out.url = args[i + 1];
      i++;
    }
  }
  return out;
}

function formatKline(msg) {
  if (msg.e !== 'kline') {
    return JSON.stringify(msg);
  }
  const k = msg.k || {};
  const tMs = Number(k.t) || 0;
  const tsStr = new Date(tMs).toISOString().replace('T', ' ').slice(0, 19);
  const closed = k.x ? ' [CLOSED]' : '';
  return `  ${tsStr}  O:${k.o}  H:${k.h}  L:${k.l}  C:${k.c}  V:${k.v}${closed}`;
}

function run(wsUrl) {
  console.log(`Connecting: ${wsUrl}`);
  console.log('(Ctrl+C to exit)\n');

  const ws = new WebSocket(wsUrl);

  ws.on('open', () => {
    // ws 库收到 ping 会自动回 pong，无需手动处理
  });

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());
      if (msg.result !== undefined && msg.id !== undefined) return;
      if (msg.code !== undefined && msg.code !== 0) {
        console.error('Error:', msg);
        return;
      }
      console.log(formatKline(msg));
    } catch {
      console.log(data.toString());
    }
  });

  ws.on('error', (err) => {
    console.error('WebSocket error:', err.message);
    process.exit(1);
  });

  ws.on('close', (code, reason) => {
    if (code !== 1000) {
      console.error(`Connection closed: ${code} ${reason?.toString() || ''}`);
      process.exit(1);
    }
  });
}

const args = parseArgs();
const wsUrl = args.url || `${BINANCE_WS_BASE}/ws/${args.symbol}@kline_${args.interval}`;
run(wsUrl);
