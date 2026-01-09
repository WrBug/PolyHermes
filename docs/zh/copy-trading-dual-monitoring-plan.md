# 跟单双重监听方案（OnChain + Poly Activity WS）

## 1. 方案概述

### 1.1 目标
实现 **OnChain WebSocket + Polymarket Activity WebSocket** 双重监听机制，提高跟单系统的实时性和可靠性。

### 1.2 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                    CopyTradingMonitorService                     │
│                     (主监听协调服务)                              │
└────────────┬──────────────────────────────────┬──────────────────┘
             │                                   │
             ▼                                   ▼
┌────────────────────────┐         ┌──────────────────────────────┐
│  PolymarketActivity    │         │    OnChainWsService          │
│  WsService (新增)      │         │    (已存在)                  │
│                        │         │                              │
│ - 订阅全局 activity    │         │ - 订阅 Leader 地址的链上事件  │
│ - 客户端地址过滤       │         │ - 监听 Transfer 事件         │
│ - 延迟 < 100ms        │         │ - 延迟 ~2-3s (出块时间)       │
│ - 数据来源：Poly WS   │         │ - 数据来源：链上 RPC          │
└────────────┬───────────┘         └──────────────┬───────────────┘
             │                                     │
             └──────────────┬──────────────────────┘
                            ▼
                ┌───────────────────────┐
                │ CopyOrderTrackingService│
                │  (统一处理服务)         │
                │                        │
                │ - 去重（leaderId+      │
                │   tradeId）            │
                │ - 处理买入/卖出        │
                │ - 记录处理状态         │
                └────────────────────────┘
```

### 1.3 优势对比

| 特性 | Activity WS | OnChain WS | 双重监听 |
|------|-------------|------------|----------|
| **延迟** | < 100ms | ~2-3s | < 100ms (优先) |
| **可靠性** | 中等（依赖 Poly 服务） | 高（链上数据） | 高（双重保障） |
| **数据完整性** | 完整（包含 market、price、size） | 需要解析 Transfer | 完整 |
| **兜底机制** | ❌ | ✅ | ✅ |
| **去重** | 需要 | 需要 | 统一去重 |

## 2. 技术方案

### 2.1 新增服务：PolymarketActivityWsService

**职责**：
- 订阅 Polymarket Activity WebSocket（全局交易流）
- 根据 Leader 地址列表进行客户端过滤
- 将交易事件转换为 `TradeResponse` 格式
- 调用 `CopyOrderTrackingService.processTrade()` 处理

**实现要点**：
1. 使用单个 WebSocket 连接订阅全局 activity（不传 filters）
2. 客户端维护 Leader 地址 Set，实时过滤交易
3. 支持动态添加/移除 Leader（无需重连）
4. 自动重连机制

### 2.2 修改 CopyTradingMonitorService

**职责**：
- 协调两种监听服务
- 统一管理 Leader 的添加/移除
- 启动时同时启动两种监听

### 2.3 去重机制

**现有机制**（CopyOrderTrackingService）：
- 使用 `leaderId + trade.id` 作为唯一键
- 基于数据库表 `processed_trade` 去重
- 使用 Mutex 保证线程安全

**双重监听场景**：
- Activity WS 和 OnChain WS 可能同时检测到同一笔交易
- 去重机制确保只处理一次（第一个到达的）
- 通过 `source` 字段区分数据源（"activity-ws" / "onchain-ws"）

## 3. 实现细节

### 3.1 PolymarketActivityWsService 设计

```kotlin
@Service
class PolymarketActivityWsService(
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val leaderRepository: LeaderRepository
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 单例 WebSocket 客户端
    private var wsClient: PolymarketWebSocketClient? = null
    
    // 要监听的 Leader 地址集合（小写）
    private val monitoredAddresses = ConcurrentHashMap<String, Long>() // address -> leaderId
    
    /**
     * 启动监听
     */
    fun start(leaders: List<Leader>) {
        monitoredAddresses.clear()
        leaders.forEach { leader ->
            if (leader.id != null) {
                monitoredAddresses[leader.leaderAddress.lowercase()] = leader.id!!
            }
        }
        
        if (monitoredAddresses.isEmpty()) {
            stop()
            return
        }
        
        connectAndSubscribe()
    }
    
    /**
     * 添加 Leader
     */
    fun addLeader(leader: Leader) {
        if (leader.id == null) return
        monitoredAddresses[leader.leaderAddress.lowercase()] = leader.id!!
        
        // 如果 WebSocket 未连接，连接
        if (wsClient == null) {
            connectAndSubscribe()
        }
    }
    
    /**
     * 移除 Leader
     */
    fun removeLeader(leaderId: Long) {
        val addressToRemove = monitoredAddresses.entries
            .find { it.value == leaderId }?.key
        addressToRemove?.let { monitoredAddresses.remove(it) }
        
        // 如果没有 Leader 了，停止监听
        if (monitoredAddresses.isEmpty()) {
            stop()
        }
    }
    
    /**
     * 连接并订阅
     */
    private fun connectAndSubscribe() {
        if (wsClient != null && wsClient!!.isConnected()) {
            return
        }
        
        wsClient = PolymarketWebSocketClient(
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
        
        scope.launch {
            try {
                wsClient!!.connect()
            } catch (e: Exception) {
                logger.error("连接 Activity WebSocket 失败", e)
            }
        }
    }
    
    /**
     * 订阅全局 activity
     */
    private fun subscribeAllActivity() {
        val message = """
        {
            "type": "subscribe",
            "channel": "activity",
            "topic": "trades"
        }
        """.trimIndent()
        
        wsClient?.sendMessage(message)
    }
    
    /**
     * 处理消息
     */
    private fun handleMessage(message: String) {
        try {
            if (message.trim() == "PONG") return
            
            val json = JsonParser.parseString(message).asJsonObject
            
            // 检查是否是 trade 事件
            val topic = json.get("topic")?.asString
            val type = json.get("type")?.asString
            if (topic != "activity" || type != "trades") {
                return
            }
            
            val payload = json.getAsJsonObject("payload") ?: return
            
            // 提取交易者地址
            val traderObj = payload.getAsJsonObject("trader")
            val traderAddress = traderObj?.get("address")?.asString?.lowercase()
                ?: payload.get("proxyWallet")?.asString?.lowercase()
                ?: return
            
            // 检查是否是我们要监听的 Leader
            val leaderId = monitoredAddresses[traderAddress] ?: return
            
            // 解析交易数据
            val trade = parseActivityTrade(payload, leaderId)
            if (trade != null) {
                scope.launch {
                    copyOrderTrackingService.processTrade(
                        leaderId = leaderId,
                        trade = trade,
                        source = "activity-ws"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("处理 Activity WebSocket 消息失败", e)
        }
    }
    
    /**
     * 解析 Activity Trade 为 TradeResponse
     */
    private fun parseActivityTrade(json: JsonObject, leaderId: Long): TradeResponse? {
        return try {
            TradeResponse(
                id = json.get("transactionHash")?.asString
                    ?: "${leaderId}_${System.currentTimeMillis()}", // fallback
                market = json.get("conditionId")?.asString ?: return null,
                side = json.get("side")?.asString?.uppercase() ?: return null,
                price = json.get("price")?.asString ?: return null,
                size = json.get("size")?.asString ?: return null,
                timestamp = (json.get("timestamp")?.asLong ?: System.currentTimeMillis()).toString(),
                user = null, // Activity WS 中不需要
                outcomeIndex = parseOutcomeIndex(json.get("outcome")?.asString),
                outcome = json.get("outcome")?.asString
            )
        } catch (e: Exception) {
            logger.error("解析 Activity Trade 失败", e)
            null
        }
    }
    
    private fun parseOutcomeIndex(outcome: String?): Int? {
        return when (outcome?.uppercase()) {
            "YES", "UP", "TRUE" -> 0
            "NO", "DOWN", "FALSE" -> 1
            else -> null
        }
    }
    
    fun stop() {
        wsClient?.closeConnection()
        wsClient = null
        monitoredAddresses.clear()
    }
}
```

### 3.2 修改 CopyTradingMonitorService

```kotlin
@Service
class CopyTradingMonitorService(
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderRepository: LeaderRepository,
    private val accountRepository: AccountRepository,
    private val onChainWsService: OnChainWsService,
    private val accountOnChainMonitorService: AccountOnChainMonitorService,
    private val activityWsService: PolymarketActivityWsService  // 新增
) {
    
    suspend fun startMonitoring() {
        val enabledCopyTradings = copyTradingRepository.findByEnabledTrue()
        if (enabledCopyTradings.isEmpty()) {
            return
        }
        
        val leaderIds = enabledCopyTradings.map { it.leaderId }.distinct()
        val leaders = leaderIds.mapNotNull { leaderId ->
            leaderRepository.findById(leaderId).orElse(null)
        }
        
        val accountIds = enabledCopyTradings.map { it.accountId }.distinct()
        val accounts = accountIds.mapNotNull { accountId ->
            accountRepository.findById(accountId).orElse(null)
        }
        
        // 1. 启动 Activity WebSocket 监听（优先，低延迟）
        activityWsService.start(leaders)
        
        // 2. 启动链上 WebSocket 监听（兜底，高可靠性）
        onChainWsService.start(leaders)
        
        // 3. 启动账户监听
        accountOnChainMonitorService.start(accounts)
    }
    
    suspend fun addLeaderMonitoring(leaderId: Long) {
        val leader = leaderRepository.findById(leaderId).orElse(null) ?: return
        
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        if (copyTradings.isEmpty()) {
            return
        }
        
        // 同时添加到两种监听
        activityWsService.addLeader(leader)
        onChainWsService.addLeader(leader)
    }
    
    suspend fun removeLeaderMonitoring(leaderId: Long) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        if (copyTradings.isNotEmpty()) {
            return
        }
        
        // 同时从两种监听移除
        activityWsService.removeLeader(leaderId)
        onChainWsService.removeLeader(leaderId)
    }
    
    @PreDestroy
    fun destroy() {
        scope.cancel()
        activityWsService.stop()
        onChainWsService.stop()
        accountOnChainMonitorService.stop()
    }
}
```

### 3.3 WebSocket 客户端复用

**检查现有 PolymarketWebSocketClient**：
- 如果已存在，检查是否支持 Activity 订阅
- 如果不存在，需要创建或引入依赖

**依赖选项**：
1. **使用现有实现**：检查 `PolymarketWebSocketClient` 是否支持 Activity
2. **引入 poly-sdk 的方式**：参考 poly-sdk 的 `RealTimeDataClient`
3. **自行实现**：基于 OkHttp WebSocket 或 Java-WebSocket

## 4. 数据流

### 4.1 Activity WS 数据流

```
Polymarket 服务器
  ↓ (WebSocket推送 activity trade)
PolymarketActivityWsService.handleMessage()
  ↓ (提取 trader.address)
客户端过滤（monitoredAddresses.contains(traderAddress)）
  ↓ (匹配到 Leader)
parseActivityTrade() → TradeResponse
  ↓
CopyOrderTrackingService.processTrade(
    leaderId, trade, source="activity-ws"
)
  ↓ (去重检查)
如果未处理 → 执行跟单逻辑
如果已处理 → 跳过（可能是 OnChain WS 先到达）
```

### 4.2 OnChain WS 数据流（保持不变）

```
链上 RPC WebSocket
  ↓ (推送 Transfer 事件)
UnifiedOnChainWsService
  ↓
OnChainWsService.handleLeaderTransaction()
  ↓ (解析 Transfer → Trade)
CopyOrderTrackingService.processTrade(
    leaderId, trade, source="onchain-ws"
)
  ↓ (去重检查)
如果未处理 → 执行跟单逻辑
如果已处理 → 跳过（可能是 Activity WS 先到达）
```

## 5. 配置项

### 5.1 application.properties

```properties
# Polymarket WebSocket
polymarket.websocket.url=wss://ws-live-data.polymarket.com

# 监听策略
copy.trading.monitor.strategy=dual
# dual: 双重监听（Activity WS + OnChain WS）
# activity-only: 仅 Activity WS
# onchain-only: 仅 OnChain WS

# Activity WS 配置
copy.trading.activity.ws.enabled=true
copy.trading.activity.ws.reconnect.delay=3000

# OnChain WS 配置（已存在）
copy.trading.onchain.ws.reconnect.delay=3000
```

## 6. 监控和日志

### 6.1 关键指标

- **Activity WS 连接状态**
- **OnChain WS 连接状态**
- **每种源的交易检测数量**
- **去重次数（重复检测）**
- **处理延迟（Activity WS vs OnChain WS）**

### 6.2 日志示例

```
[INFO] Activity WS 连接成功
[INFO] Activity WS 订阅全局 activity 成功
[INFO] 检测到交易: leaderId=1, address=0x1234..., source=activity-ws, latency=85ms
[INFO] 检测到交易: leaderId=1, address=0x1234..., source=onchain-ws, latency=2150ms
[WARN] 交易已处理（去重）: leaderId=1, tradeId=xxx, firstSource=activity-ws, duplicateSource=onchain-ws
```

## 7. 实施步骤

### Phase 1: 基础实现
1. ✅ 创建 `PolymarketActivityWsService`
2. ✅ 实现 Activity WebSocket 连接和订阅
3. ✅ 实现客户端地址过滤
4. ✅ 实现交易解析和转换

### Phase 2: 集成
5. ✅ 修改 `CopyTradingMonitorService` 集成 Activity WS
6. ✅ 测试双重监听和去重机制
7. ✅ 添加配置项支持

### Phase 3: 优化
8. ✅ 添加监控指标
9. ✅ 性能优化（连接池、消息批处理等）
10. ✅ 错误处理和降级策略

### Phase 4: 验证
11. ✅ 端到端测试
12. ✅ 压力测试
13. ✅ 生产环境灰度验证

## 8. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| Activity WS 服务不稳定 | 高 | OnChain WS 作为兜底 |
| 消息重复处理 | 中 | 数据库去重 + Mutex 锁 |
| 连接断开频繁 | 中 | 自动重连 + 指数退避 |
| 地址过滤性能 | 低 | 使用 ConcurrentHashMap，O(1) 查询 |

## 9. 未来优化

1. **消息批处理**：将多个交易合并处理，减少数据库写入
2. **智能切换**：根据 Activity WS 稳定性自动降级到 OnChain
3. **延迟监控**：实时监控两种源的延迟，选择最优源
4. **缓存优化**：缓存 Leader 地址列表，减少查询

