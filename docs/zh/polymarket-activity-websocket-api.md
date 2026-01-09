# Polymarket Activity WebSocket API 格式

## 1. 连接信息

### 1.1 WebSocket URL
```
wss://ws-live-data.polymarket.com
```

这是 Polymarket 官方 RTDS (Real-Time Data Stream) 的 WebSocket 端点。

### 1.2 协议说明
- 使用 **官方 `@polymarket/real-time-data-client`** 的协议格式
- 消息格式为 JSON
- 支持自动重连
- 需要 PING/PONG 保活（每 10 秒发送 PING）

## 2. 订阅消息格式

### 2.1 订阅全局 Activity（所有交易）

**订阅消息**：
```json
{
  "action": "subscribe",
  "subscriptions": [
    {
      "topic": "activity",
      "type": "trades"
    }
  ]
}
```

**关键点**：
- `action`: `"subscribe"` - 订阅动作（根据 `@polymarket/real-time-data-client` 协议）
- `topic`: `"activity"` - 活动数据频道
- `type`: `"trades"` - 交易类型（在 subscriptions 数组内）
- **不传 `filters` 字段** - 订阅所有市场的交易（空对象 `{}` 会被拒绝）

### 2.2 订阅特定市场的 Activity（可选）

如果需要过滤特定市场：
```json
{
  "action": "subscribe",
  "subscriptions": [
    {
      "topic": "activity",
      "type": "trades",
      "filters": "{\"market_slug\":\"trump-win-2024\"}"
    }
  ]
}
```

或者过滤特定事件：
```json
{
  "action": "subscribe",
  "subscriptions": [
    {
      "topic": "activity",
      "type": "trades",
      "filters": "{\"event_slug\":\"presidential-election-2024\"}"
    }
  ]
}
```

**注意**：
- `filters` 是 JSON 字符串（不是对象）
- 使用 `snake_case`（`market_slug`, `event_slug`）
- 对于 Copy Trading，我们**不传 filters**，订阅全局然后客户端过滤

### 2.3 取消订阅

```json
{
  "action": "unsubscribe",
  "subscriptions": [
    {
      "topic": "activity",
      "type": "trades"
    }
  ]
}
```

## 3. 接收消息格式

### 3.1 Trade 消息结构

当有交易发生时，服务器会推送如下格式的消息：

```json
{
  "topic": "activity",
  "type": "trades",
  "timestamp": 1704067200000,
  "payload": {
    "asset": "47632033502843656213...",      // Token ID (用于下单)
    "conditionId": "0xb82c6573...",          // Market condition ID
    "eventSlug": "aus-mct-per-2025-12-28",   // 事件 slug
    "slug": "aus-mct-per-draw",              // 市场 slug
    "outcome": "No",                         // 结果方向 (Yes/No)
    "side": "BUY",                           // 交易方向 (BUY/SELL)
    "size": 15.72,                           // 交易数量 (shares)
    "price": 0.87,                           // 交易价格
    "timestamp": 1766913243,                 // Unix 时间戳 (秒)
    "transactionHash": "0x921936dfc9...",    // 交易哈希
    "trader": {                              // 交易者信息对象
      "name": "gabagool22",                  // 交易者用户名（可选）
      "address": "0x6031B6eed1C97e..."       // 交易者钱包地址 ⭐ 关键字段！
    }
  }
}
```

**重要字段说明**：
- `payload.trader.address` - **交易者钱包地址**（用于过滤 Leader）
- `payload.trader.name` - 交易者用户名（可选）
- `payload.asset` - Token ID（用于下单）
- `payload.conditionId` - Market condition ID
- `payload.side` - BUY 或 SELL
- `payload.size` - 交易数量
- `payload.price` - 交易价格
- `payload.timestamp` - Unix 时间戳（秒）

### 3.2 注意：字段命名可能不同

根据实测（poly-sdk 文档），有些情况下字段可能在顶层：
```json
{
  "topic": "activity",
  "type": "trades",
  "payload": {
    "asset": "...",
    "conditionId": "...",
    "side": "BUY",
    "price": 0.87,
    "size": 15.72,
    "transactionHash": "...",
    "name": "gabagool22",        // 可能在顶层
    "proxyWallet": "0x6031..."   // 可能在顶层（而不是 trader.address）
  }
}
```

**建议处理方式**：同时检查 `payload.trader?.address` 和 `payload.proxyWallet`

## 4. PING/PONG 保活

### 4.1 发送 PING

每 10 秒发送一次：
```json
"PING"
```

或：
```json
{
  "type": "ping"
}
```

### 4.2 接收 PONG

服务器会回复：
```json
"PONG"
```

或：
```json
{
  "type": "pong"
}
```

## 5. Kotlin 实现示例

### 5.1 订阅消息

```kotlin
fun subscribeAllActivity() {
    val subscribeMessage = """
    {
        "type": "subscribe",
        "subscriptions": [
            {
                "topic": "activity",
                "type": "trades"
            }
        ]
    }
    """.trimIndent()
    
    wsClient.sendMessage(subscribeMessage)
}
```

### 5.2 解析交易消息

```kotlin
private fun parseActivityTrade(json: JsonObject): ActivityTrade? {
    val payload = json.getAsJsonObject("payload") ?: return null
    
    // 提取交易者地址（优先 trader.address，fallback 到 proxyWallet）
    val traderObj = payload.getAsJsonObject("trader")
    val traderAddress = traderObj?.get("address")?.asString
        ?: payload.get("proxyWallet")?.asString
        ?: return null
    
    val traderName = traderObj?.get("name")?.asString
        ?: payload.get("name")?.asString
    
    return ActivityTrade(
        asset = payload.get("asset")?.asString ?: return null,
        conditionId = payload.get("conditionId")?.asString ?: return null,
        eventSlug = payload.get("eventSlug")?.asString,
        marketSlug = payload.get("slug")?.asString,
        outcome = payload.get("outcome")?.asString,
        side = payload.get("side")?.asString?.uppercase() ?: return null,
        size = payload.get("size")?.asDouble ?: return null,
        price = payload.get("price")?.asDouble ?: return null,
        timestamp = payload.get("timestamp")?.asLong
            ?.let { if (it < 1e12) it * 1000 else it }  // 秒转毫秒
            ?: System.currentTimeMillis(),
        transactionHash = payload.get("transactionHash")?.asString,
        traderAddress = traderAddress.lowercase(),
        traderName = traderName
    )
}
```

## 6. 与现有实现的对比

### 6.1 User Channel（当前使用）

**URL**: `wss://ws-subscriptions-clob.polymarket.com/ws/user`  
**订阅格式**:
```json
{
  "type": "subscribe",
  "channel": "user",
  "user": "0x1234..."
}
```

**限制**：
- ❌ 只能订阅**自己的**交易（需要 API 认证）
- ❌ 无法监听别人的交易

### 6.2 Activity Channel（新方案）

**URL**: `wss://ws-live-data.polymarket.com`  
**订阅格式**:
```json
{
  "type": "subscribe",
  "subscriptions": [
    {
      "topic": "activity",
      "type": "trades"
    }
  ]
}
```

**优势**：
- ✅ 可以监听**所有**交易的全局流
- ✅ 包含交易者地址，可以客户端过滤
- ✅ 不需要认证
- ✅ 延迟低（< 100ms）

## 7. 完整的消息处理流程

### 7.1 连接和订阅

```kotlin
// 1. 连接到 WebSocket
val client = PolymarketWebSocketClient(
    url = "wss://ws-live-data.polymarket.com",
    sessionId = "copy-trading-activity",
    onMessage = { message -> handleMessage(message) },
    onOpen = {
        subscribeAllActivity()
    },
    onReconnect = {
        subscribeAllActivity()
    }
)

client.connect()

// 2. 发送订阅消息
fun subscribeAllActivity() {
    val message = """
    {
        "type": "subscribe",
        "subscriptions": [
            {
                "topic": "activity",
                "type": "trades"
            }
        ]
    }
    """.trimIndent()
    client.sendMessage(message)
}
```

### 7.2 处理消息

```kotlin
private fun handleMessage(message: String) {
    if (message.trim() == "PONG") return
    
    val json = JsonParser.parseString(message).asJsonObject
    
    // 检查是否是 activity trade 消息
    val topic = json.get("topic")?.asString
    val type = json.get("type")?.asString
    
    if (topic == "activity" && type == "trades") {
        val payload = json.getAsJsonObject("payload") ?: return
        
        // 提取交易者地址
        val traderAddress = extractTraderAddress(payload) ?: return
        
        // 检查是否是我们监听的 Leader
        val leaderId = monitoredAddresses[traderAddress.lowercase()] ?: return
        
        // 解析交易
        val trade = parseActivityTrade(payload) ?: return
        
        // 处理交易
        processTrade(leaderId, trade)
    }
}

private fun extractTraderAddress(payload: JsonObject): String? {
    // 优先检查 trader.address
    val traderObj = payload.getAsJsonObject("trader")
    val address = traderObj?.get("address")?.asString
        ?: payload.get("proxyWallet")?.asString
    
    return address?.lowercase()
}
```

## 8. 错误处理

### 8.1 连接错误

- 连接失败时自动重连（指数退避：3s → 6s → 12s → 最大 60s）
- 重连后重新发送订阅消息

### 8.2 订阅错误

如果订阅失败，服务器可能返回：
```json
{
  "type": "error",
  "message": "Invalid subscription"
}
```

### 8.3 消息解析错误

- 如果字段缺失，记录警告日志并跳过
- 如果地址格式错误，跳过该消息
- 如果解析失败，记录错误但不中断连接

## 9. 性能考虑

### 9.1 消息频率

- Activity WebSocket 会推送**所有市场**的所有交易
- 高频市场可能每秒收到数百条消息
- 需要高效的客户端过滤（使用 `Set` 或 `Map`，O(1) 查找）

### 9.2 内存管理

- 维护 Leader 地址 Set（通常 < 100 个地址）
- 消息处理使用协程异步处理，避免阻塞
- 定期清理不再监听的地址
