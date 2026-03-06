# 体育尾盘策略 - 市场数据与订阅

> 本文档描述体育市场数据获取方式、 WebSocket 订阅管理逻辑。

## 一、Gamma API 数据获取

> 本文档描述如何从 Gamma API 获取体育市场数据。### 1.1 萜索条件
### 1.1 获取体育类别列表
> **前端选择体育类别时，需要获取可选的体育类别列表供用户选择。
```
GET https://gamma-api.polymarket.com/sports
```
> **返回示例**：
```json
[
  {"sport": "nba", "image": "https://...", "tags": "1,745,100639"},
  {"sport": "nfl", "image": "https://...", "tags": "1,450,100639"},
  {"sport": "epl", "image": "https://...", "tags": "1,82,306,100639"},
  ...
]
```

**主要类别**：
| sport | 名称 | tag_id |
|-------|------|--------|
| nba | 篮球 NBA | 745 |
| nfl | 美式足球 NFL | 450 |
| epl | 英超 | 82 |
| lal | 西甲 | 780 |
| mlb | 棒球 MLB | 100381 |
| nhl | 冰球 NHL | 899 |

| ufc | 格斗 UFC | 100639 |

### 1.2 按类别筛选市场
> 根据用户选择的体育类别，使用 `tag_id` 参数筛选市场。
```
GET https://gamma-api.polymarket.com/markets?tag_id=745&active=true&closed=false&limit=50&order=endDate&ascending=true
```
> **返回字段**：
- `id` - 市场ID
- `question` - 市场问题
- `conditionId` - 市场 conditionId
- `outcomes` - 结果选项 `["Yes", "No"]` 或 `["Over", "Under"]`
- `outcomePrices` - 当前价格 `["0.92", "0.08"]`
- `endDate` - 结束时间
- `bestBid` - 最佳买价
- `bestAsk` - 最佳卖价
- `clobTokenIds` - Token IDs
- `gameStartTime` - 比赛开始时间
- `sportsMarketType` - 市场类型
- `liquidityNum` - 流动性
- `volumeNum` - 成交量

- `events` - 关联事件信息

```

> **请求参数**：
```kotlin
data class SportsMarketSearchRequest(
    val sport: String? = null,        // 体育类别: nba, nfl, epl...
    val marketType: String? = null,    // 市场类型: moneyline, spreads, totals
    val endDateMin: String? = null,    // 最小结束时间
    val endDateMax: String? = null,    // 最大结束时间
    val minLiquidity: BigDecimal? = null, // 最小流动性
    val keyword: String? = null,        // 搜索关键词
    val limit: Int = 50                 // 返回数量
)
```

> **marketType 说明**：
- `moneyline` - 胜负市场（谁会赢）
- `spreads` - 让分市场
- `totals` - 大小分市场

### 1.3 获取单个市场详情
```
GET https://gamma-api.polymarket.com/markets?condition_ids={conditionId}
```
> **用于**：
- 创建策略时获取 `clobTokenIds`
- 监控价格时获取实时价格
- 获取 Token ID 用于下单

---

## 二、WebSocket 订阅管理
### 2.1 订阅策略
> 同一市场可能有多个策略，但只维护一个 WebSocket 订阅。
> 策略创建/删除时需要更新订阅计数。
```kotlin
// 市场订阅计数
private val marketSubscriptions = ConcurrentHashMap<String, Int>()

// 市场对应的 Token IDs 缓存
private val marketTokenIds = ConcurrentHashMap<String, Pair<String, String>>()

/**
 * 订阅策略（创建策略时调用）
 */
fun subscribeStrategy(strategy: SportsTailStrategy) {
    val conditionId = strategy.conditionId
    
    marketSubscriptions.compute(conditionId) { _, count ->
        val newCount = (count ?: 0) + 1
        if (newCount == 1) {
            // 首次订阅，建立 WebSocket 连接
            subscribeMarket(conditionId)
        }
        newCount
    }
}

```
> **订阅管理规则**：
| 场景 | 操作 |
|------|------|
| 策略创建 | 如果市场无订阅，建立订阅；否则计数 +1 |
| 策略删除 | 计数 -1；如果计数为 0，取消订阅 |
| 策略成交（无止盈止损） | 检查同市场是否有其他未完成策略；无则取消订阅 |
| 策略卖出 | 检查同市场是否有其他未完成策略；无则取消订阅 |

### 2.2 讣阅流程
```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  策略创建       │────▶│ 检查订阅计数  │────▶│ 首次? 建立连接 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                                              │
                                              ▼
┌─────────────────────────────────────────────────────────────┐
│  WebSocket 接收订单簿更新                               │
│  1. 解析消息，获取 tokenId 和价格                        │
│  2. 查找该 Token 对应的未完成策略                    │
│  3. 检查触发条件                                 │
│  4. 满足条件则执行买入/卖出                          │
└─────────────────────────────────────────────────────────────┘
```

> **WebSocket 消息格式**：
```json
{
  "event_type": "book",
  "asset_id": "1234567890",
  "market": {
    "bids": [...],
    "asks": [...],
    "timestamp": 1234567890
  }
}
```

---

## 三、价格监控与触发
### 3.1 买入触发逻辑
> 任意方向价格达到触发价即买入
```kotlin
fun checkBuyTrigger(
    strategy: SportsTailStrategy,
    yesPrice: BigDecimal,
    noPrice: BigDecimal
): TriggerResult? {
    // 检查 YES 方向
    if (yesPrice >= strategy.triggerPrice) {
        return TriggerResult(outcomeIndex = 0, price = yesPrice)
    }
    
    // 检查 NO 方向
    if (noPrice >= strategy.triggerPrice) {
        return TriggerResult(outcomeIndex = 1, price = noPrice)
    }
    
    return null
}
```
> **注意**：不区分方向，系统自动选择价格满足的方向买入。
### 3.2 止盈止损逻辑
> 已成交的策略，监控持仓方向的价格变化
```kotlin
fun checkSellTrigger(
    strategy: SportsTailStrategy,
    currentPrice: BigDecimal
): SellTrigger? {
    if (!strategy.filled || strategy.sold) {
        return null
    }
    
    // 止盈：当前价格 >= 止盈价
    if (strategy.takeProfitPrice != null && currentPrice >= strategy.takeProfitPrice) {
        return SellTrigger(type = "TAKE_PROFIT", price = currentPrice)
    }
    
    // 止损：当前价格 <= 止损价
    if (strategy.stopLossPrice != null && currentPrice <= strategy.stopLossPrice) {
        return SellTrigger(type = "STOP_LOSS", price = currentPrice)
    }
    
    return null
}
```
> **注意**：只监控已买入方向的价格，不是两个方向都监控。
### 3.3 订阅生命周期
```
┌─────────────┐     价格>=触发价     ┌─────────────┐     止盈/止损     ┌─────────────┐
│  监控两个方向 │ ────────────────▶ │  已成交      │ ────────────────▶ │  已完成      │
└─────────────┘                     └─────────────┘                     └─────────────┘
       │                                   │
       ▼                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  成交后检查止盈止损                                                   │
│  - 有止盈止损：保持订阅，只监控买入方向                                    │
│  - 无止盈止损：检查同市场是否有其他未完成策略，无则取消订阅              │
└─────────────────────────────────────────────────────────────────────────┘
```
