# Polymarket Data API 游标分页验证

回测拉取 Leader 历史交易改用 **start 游标分页**（不再使用 offset），避免 `offset` 过大（如 3100+）时 API 报错。

## 规则

- `limit` 固定为 500（快速验证可用 50）。
- 首次请求：`start` = 回测开始时间（秒），`end` = 回测结束时间（秒）。
- 若本批返回 **500 条**：取本批中**最大 timestamp**，下一页 `start = max_timestamp`（**不加 1**：同一秒可能有多笔订单，会漏单）。
- 若本批 **不足 500 条**：视为最后一页，不再请求。
- 下一页会与上一页在「最大 timestamp」这一秒重叠，必须按 **tradeId（transactionHash）去重**。

## 快速验证（limit=50）

```bash
# 环境变量（替换为实际值）
USER="0x1979ae6b7e6534de9c4539d0c205e582ca637c9d"
START=1769961432
END=1770566207
LIMIT=50

# 第 1 页（游标分页不传 offset）
curl -s "https://data-api.polymarket.com/activity?user=${USER}&limit=${LIMIT}&type=TRADE&start=${START}&end=${END}&sortBy=TIMESTAMP&sortDirection=ASC" | jq 'length'
# 若输出 50，则取本批最大 timestamp 作为下一页 start（不加 1，同一秒可能多笔）
curl -s "https://data-api.polymarket.com/activity?user=${USER}&limit=${LIMIT}&type=TRADE&start=${START}&end=${END}&sortBy=TIMESTAMP&sortDirection=ASC" | jq 'max_by(.timestamp) | .timestamp'
# 假设得到 1770000000，则下一页 start=1770000000（与上一批重叠，需按 tradeId 去重）

# 第 2 页（游标分页，不使用 offset）
NEXT_START=1770000000
curl -s "https://data-api.polymarket.com/activity?user=${USER}&limit=${LIMIT}&type=TRADE&start=${NEXT_START}&end=${END}&sortBy=TIMESTAMP&sortDirection=ASC" | jq 'length'
# 若输出 < 50，则为最后一页
```

## 单条命令示例（第 1 页，limit=50）

```bash
curl -s "https://data-api.polymarket.com/activity?user=0x1979ae6b7e6534de9c4539d0c205e582ca637c9d&limit=50&type=TRADE&start=1769961432&end=1770566207&sortBy=TIMESTAMP&sortDirection=ASC"
```

注意：**不要传 offset**，下一页 `start = 上一批最大 timestamp`（不加 1），同一秒多笔订单不丢，重叠记录按 tradeId 去重。

## 代码位置

- 拉取批次：`BacktestDataService.getLeaderHistoricalTradesBatch()`，返回 `LeaderTradesBatchResult(trades, nextCursorSeconds)`。
- 执行循环：`BacktestExecutionService.executeBacktest()`，按 `cursorSeconds` 循环，并用 `seenTradeIds` 去重。
