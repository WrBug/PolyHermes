# 跟单监听策略：链上 WebSocket + 轮询并行方案

## 一、方案概述

系统**同时运行**两种监听方式，并行处理，哪个数据先返回就用哪个：

1. **链上 WebSocket 监听**：通过 Polygon RPC 的 `eth_subscribe` 实时监听链上交易
2. **轮询监听**：通过 Polymarket Data API 定期轮询交易记录

**核心特点**：
- 两种方式**并行运行**，不互相排斥
- WS 断开时**不断重试连接**，不停止轮询
- 哪个数据先返回就用哪个，确保最快响应
- 通过去重机制确保同一笔交易只处理一次

## 二、关键流程图

```
系统启动
    ↓
同时启动两种监听方式
    ↓
    ├─→ 链上 WS 监听（并行）
    │       ↓
    │   尝试连接 WS RPC
    │       ↓
    │   连接成功？
    │       ├─→ 是 → 订阅 Leader 钱包地址
    │       │       (eth_subscribe: USDC Transfer + ERC1155 Transfer)
    │       │       ↓
    │       │   实时接收交易日志
    │       │       ↓
    │       │   解析交易 receipt
    │       │       ↓
    │       │   转换为 TradeResponse
    │       │       ↓
    │       │   调用 processTrade()（去重检查）
    │       │       ↓
    │       │   连接断开？
    │       │       ├─→ 是 → 等待重连延迟 → 重试连接（循环）
    │       │       └─→ 否 → 继续监听
    │       │
    │       └─→ 否 → 等待重连延迟 → 重试连接（循环）
    │
    └─→ 轮询监听（并行）
            ↓
        定期轮询 (每 2 秒)
            ↓
        查询 /activity 接口
            ↓
        diff 检测新增交易
            ↓
        调用 processTrade()（去重检查）
            ↓
        继续轮询（循环）
```

## 三、并行处理流程

```
交易发生
    ↓
    ├─→ WS 监听（实时，秒级）
    │       ↓
    │   收到链上日志
    │       ↓
    │   解析并转换为 TradeResponse
    │       ↓
    │   调用 processTrade()
    │       ↓
    │   去重检查（processed_trade 表）
    │       ├─→ 已处理 → 跳过
    │       └─→ 未处理 → 处理交易
    │
    └─→ 轮询监听（延迟，2秒间隔）
            ↓
        轮询到新交易
            ↓
        转换为 TradeResponse
            ↓
        调用 processTrade()
            ↓
        去重检查（processed_trade 表）
            ├─→ 已处理 → 跳过（WS 已处理）
            └─→ 未处理 → 处理交易
```

## 三、实现方案

### 3.1 服务架构

```
CopyTradingMonitorService (主服务)
    ├─→ OnChainWsService (链上 WS 监听，独立运行)
    └─→ CopyTradingPollingService (轮询监听，独立运行)
```

**关键点**：
- 两个服务**独立运行**，互不影响
- 主服务负责启动和协调两个服务
- 两个服务都调用同一个 `processTrade()` 方法
- 去重由 `processTrade()` 内部处理

### 3.2 WS 重连机制

**重连策略**：
1. WS 连接断开时，**不停止轮询服务**
2. 等待重连延迟（如 3 秒）
3. 自动重试连接
4. 连接成功后重新订阅所有 Leader
5. 如果连接失败，继续重试（无限重试）

**重连流程**：
```
WS 连接断开
    ↓
记录断开日志
    ↓
等待重连延迟（3秒）
    ↓
尝试重新连接
    ↓
连接成功？
    ├─→ 是 → 重新订阅所有 Leader → 继续监听
    └─→ 否 → 等待重连延迟 → 继续重试（循环）
```

**关键实现**：
- 使用协程或后台线程持续重试
- 不阻塞主流程
- 轮询服务继续运行，不受 WS 状态影响

### 3.3 去重机制

**去重标识**：
- 使用 `leaderId + transactionHash` 作为唯一标识
- 在 `processed_trade` 表中记录已处理的交易

**去重流程**：
```
收到交易数据（WS 或轮询）
    ↓
调用 processTrade(leaderId, trade, source)
    ↓
检查 processed_trade 表
    ├─→ 已存在 → 跳过处理（返回成功）
    └─→ 不存在 → 继续处理
            ↓
        处理交易（创建订单等）
            ↓
        保存到 processed_trade 表
            ├─→ leaderId
            ├─→ leaderTradeId (transactionHash)
            ├─→ tradeType (BUY/SELL)
            ├─→ source (onchain-ws / polling)
            └─→ status (SUCCESS/FAILED)
```

**并发安全**：
- 使用数据库唯一约束（`leaderId + leaderTradeId`）
- 处理唯一约束冲突（并发情况下可能多个请求同时处理同一笔交易）
- 如果冲突，再次查询确认状态

### 3.4 链上 WS 监听实现要点

**订阅参数**：
- 钱包地址：Leader 的 `leaderAddress`
- 订阅类型：
  - USDC Transfer（`from` 或 `to` 为钱包地址）
  - ERC1155 TransferSingle/Batch（`from` 或 `to` 为钱包地址）

**消息处理流程**：
1. 接收 `eth_subscription` 消息
2. 提取 `transactionHash`
3. 调用 RPC 获取交易 receipt
4. 解析 USDC Transfer 和 ERC1155 Transfer 日志
5. 计算交易方向（BUY/SELL）、数量、价格
6. 调用 Gamma API 补齐市场元数据（conditionId、outcomeIndex 等）
7. 转换为 `TradeResponse`
8. 调用 `processTrade(leaderId, trade, "onchain-ws")` 处理交易（去重由内部处理）

**重连实现**：
```kotlin
// 伪代码示例
while (isActive) {
    try {
        // 尝试连接
        val ws = connectWebSocket()
        
        // 订阅所有 Leader
        subscribeAllLeaders(ws)
        
        // 监听消息
        ws.listen { message ->
            handleMessage(message)
        }
        
        // 连接断开，等待重连
        waitReconnectDelay()
    } catch (e: Exception) {
        logger.warn("WS 连接失败，等待重连: ${e.message}")
        waitReconnectDelay()
    }
}
```

### 3.5 轮询监听实现要点

**轮询流程**：
1. 定期查询 `/activity` 接口（每 2 秒）
2. 通过 diff 检测新增交易
3. 转换为 `TradeResponse`
4. 调用 `processTrade(leaderId, trade, "polling")` 处理交易（去重由内部处理）

**关键点**：
- 轮询服务**独立运行**，不受 WS 状态影响
- 即使 WS 正常工作，轮询也继续运行（作为备份）
- 去重机制确保不会重复处理同一笔交易

## 四、配置参数

### 4.1 RPC 配置获取

**RPC 配置从后台配置中读取**，通过 `RpcNodeService` 获取：

```kotlin
// 获取 HTTP RPC URL
val httpUrl = rpcNodeService.getHttpUrl()

// 获取 WebSocket RPC URL
val wsUrl = rpcNodeService.getWsUrl()

// 获取可用节点配置（包含完整信息）
val nodeResult = rpcNodeService.getAvailableNode()
if (nodeResult.isSuccess) {
    val node = nodeResult.getOrNull()
    val httpUrl = node?.httpUrl
    val wsUrl = node?.wsUrl
}
```

**配置管理**：
- **RPC 节点配置存储在数据库**（`rpc_node_config` 表），不从配置文件读取
- 支持多个节点配置，按优先级选择
- 支持健康检查，自动选择可用节点
- 前端可以通过系统设置页面配置 RPC 节点
- 配置变更后，WS 重连时会自动使用新配置

**配置字段**：
- `httpUrl`: HTTP RPC 地址
- `wsUrl`: WebSocket RPC 地址（可选）
- `enabled`: 是否启用
- `priority`: 优先级（数字越小优先级越高）
- `lastCheckStatus`: 最后检查状态（HEALTHY/UNHEALTHY/UNKNOWN）

### 4.2 WS 连接配置

```properties
# WS 连接超时（毫秒）
polygen.ws.connect.timeout=5000

# WS 重连延迟（毫秒）
polygen.ws.reconnect.delay=3000
```

### 4.3 WS 重连配置

```properties
# WS 重连延迟（毫秒）
polygen.ws.reconnect.delay=3000

# WS 重连最大延迟（毫秒，指数退避上限）
polygen.ws.reconnect.max.delay=60000

# WS 重连是否启用指数退避
polygen.ws.reconnect.exponential.backoff=true
```

### 4.4 合约地址配置

```properties
# USDC 合约地址
usdc.contract.address=0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174

# ERC1155 合约地址（Polymarket）
erc1155.contract.address=0x4d97dcd97ec945f40cf65f87097ace5ea0476045
```

## 五、并行方案优势

### 5.1 双重保障

- **实时性**：WS 提供秒级实时通知
- **可靠性**：轮询作为备份，确保不遗漏交易
- **容错性**：WS 断开时轮询继续工作，WS 恢复后自动继续

### 5.2 性能优化

- **最快响应**：哪个数据先返回就用哪个
- **负载均衡**：两种方式并行，减少单点压力
- **去重保护**：确保同一笔交易只处理一次

### 5.3 实现简单

- **无需切换逻辑**：两种方式始终运行
- **独立管理**：WS 和轮询各自管理自己的状态
- **易于维护**：逻辑清晰，易于调试

## 六、注意事项

1. **WS 重连策略**：
   - WS 断开时**不断重试**，不停止轮询
   - 使用指数退避策略，避免频繁重连
   - 记录重连日志，便于监控

2. **去重机制**：
   - 使用 `leaderId + transactionHash` 作为唯一标识
   - 在 `processTrade()` 方法内部统一处理去重
   - 使用数据库唯一约束确保并发安全

3. **性能考虑**：
   - WS 模式下需要为每个 Leader 订阅日志
   - 大量 Leader 时可能需要优化订阅策略（批量订阅）
   - 轮询间隔可以适当调整（默认 2 秒）

4. **错误处理**：
   - WS 连接失败不影响轮询服务
   - 记录详细的错误日志，便于排查问题
   - 两种方式的错误独立处理，互不影响

5. **元数据补齐**：
   - 链上数据不包含市场元数据（conditionId、outcomeIndex 等）
   - 需要通过 Gamma API 或内部元数据服务补齐
   - 轮询数据已包含元数据，无需额外补齐

6. **数据源标识**：
   - WS 数据：`source = "onchain-ws"`
   - 轮询数据：`source = "polling"`
   - 便于统计和分析不同数据源的处理情况

## 七、实现要点

### 7.1 主服务启动

```kotlin
@Service
class CopyTradingMonitorService(
    private val rpcNodeService: RpcNodeService,
    private val onChainWsService: OnChainWsService,
    private val pollingService: CopyTradingPollingService
) {
    @PostConstruct
    fun init() {
        scope.launch {
            // 同时启动两种监听方式
            launch { onChainWsService.start() }  // WS 监听（独立协程）
            launch { pollingService.start() }     // 轮询监听（独立协程）
        }
    }
}
```

### 7.2 WS 服务实现（从后台配置获取 RPC）

```kotlin
@Service
class OnChainWsService(
    private val rpcNodeService: RpcNodeService,
    private val copyOrderTrackingService: CopyOrderTrackingService
) {
    suspend fun start() {
        while (isActive) {
            try {
                // 从后台配置获取 WS RPC URL
                val wsUrl = rpcNodeService.getWsUrl()
                val httpUrl = rpcNodeService.getHttpUrl()
                
                // 连接并订阅
                connectAndSubscribe(wsUrl, httpUrl)
                
                // 连接成功后持续监听
                waitForDisconnect()
            } catch (e: Exception) {
                logger.warn("WS 连接失败，等待重连: ${e.message}")
                delay(reconnectDelay)
            }
        }
    }
    
    private suspend fun connectAndSubscribe(wsUrl: String, httpUrl: String) {
        // 使用 wsUrl 和 httpUrl 连接和订阅
        // ...
    }
}
```

**关键点**：
- 每次重连时都从 `RpcNodeService` 获取最新的 RPC 配置
- 如果配置变更，下次重连时会自动使用新配置
- 支持多个节点配置，自动选择可用节点

### 7.3 RPC 配置变更处理

**配置变更时的处理**：
- WS 连接断开时，下次重连会从 `RpcNodeService` 获取最新配置
- 如果当前使用的节点不可用，`getAvailableNode()` 会自动选择下一个可用节点
- 支持动态切换节点，无需重启服务

**节点选择策略**：
1. 优先使用 `lastCheckStatus = HEALTHY` 的节点
2. 按 `priority` 排序，数字越小优先级越高
3. 如果所有节点都不可用，返回失败（不降级到默认节点，因为默认节点可能也不可用）

### 7.4 线程安全保证（单实例推荐方案）

`processTrade` 方法需要保证线程安全，因为：
- WS 和轮询可能同时处理同一笔交易
- 多个协程可能并发调用 `processTrade`
- 需要确保同一笔交易只处理一次

**推荐方案：使用 Mutex（应用级锁）**

对于单实例部署，**最轻量的方案是使用 Kotlin 协程的 Mutex**：
- ✅ 无需额外依赖（不需要 Redis）
- ✅ 性能开销小（内存锁，无网络开销）
- ✅ 实现简单（Kotlin 标准库）
- ✅ 协程友好（支持 suspend 函数）

**实现代码**：

```kotlin
@Service
open class CopyOrderTrackingService(
    // ... 其他依赖
) {
    // 使用 Mutex 保证线程安全（按交易ID锁定）
    private val tradeMutexMap = ConcurrentHashMap<String, Mutex>()
    
    /**
     * 获取或创建 Mutex（按交易ID）
     */
    private fun getMutex(leaderId: Long, tradeId: String): Mutex {
        val key = "${leaderId}_${tradeId}"
        return tradeMutexMap.getOrPut(key) { Mutex() }
    }
    
    /**
     * 清理不再使用的 Mutex（可选，避免内存泄漏）
     */
    private fun cleanupMutex(leaderId: Long, tradeId: String) {
        val key = "${leaderId}_${tradeId}"
        // 延迟清理，避免频繁创建/删除
        // 可以定期清理或使用 WeakReference
    }
    
    /**
     * 处理交易事件（WebSocket 或轮询）
     * 使用 Mutex 保证线程安全
     */
    @Transactional
    suspend fun processTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        // 获取该交易的 Mutex
        val mutex = getMutex(leaderId, trade.id)
        
        return mutex.withLock {
            try {
                // 1. 检查是否已处理（去重）
                val existingProcessed = processedTradeRepository.findByLeaderIdAndLeaderTradeId(
                    leaderId, 
                    trade.id
                )
                
                if (existingProcessed != null) {
                    if (existingProcessed.status == "FAILED") {
                        return@withLock Result.success(Unit)
                    }
                    return@withLock Result.success(Unit)
                }
                
                // 检查是否已记录为失败交易
                val failedTrade = failedTradeRepository.findByLeaderIdAndLeaderTradeId(
                    leaderId, 
                    trade.id
                )
                if (failedTrade != null) {
                    return@withLock Result.success(Unit)
                }
                
                // 2. 处理交易逻辑
                val result = when (trade.side.uppercase()) {
                    "BUY" -> processBuyTrade(leaderId, trade)
                    "SELL" -> processSellTrade(leaderId, trade)
                    else -> {
                        logger.warn("未知的交易方向: ${trade.side}")
                        Result.failure(IllegalArgumentException("未知的交易方向: ${trade.side}"))
                    }
                }
                
                if (result.isFailure) {
                    logger.error(
                        "处理交易失败: leaderId=$leaderId, tradeId=${trade.id}, side=${trade.side}",
                        result.exceptionOrNull()
                    )
                    return@withLock result
                }
                
                // 3. 标记为已处理（成功状态）
                // 由于使用了 Mutex，这里不会出现并发冲突
                try {
                    val processed = ProcessedTrade(
                        leaderId = leaderId,
                        leaderTradeId = trade.id,
                        tradeType = trade.side.uppercase(),
                        source = source,
                        status = "SUCCESS",
                        processedAt = System.currentTimeMillis()
                    )
                    processedTradeRepository.save(processed)
                } catch (e: Exception) {
                    // 理论上不会发生，但保留异常处理作为兜底
                    if (isUniqueConstraintViolation(e)) {
                        val existing = processedTradeRepository.findByLeaderIdAndLeaderTradeId(
                            leaderId, 
                            trade.id
                        )
                        if (existing != null) {
                            logger.debug("交易已处理（并发检测）: leaderId=$leaderId, tradeId=${trade.id}")
                            return@withLock Result.success(Unit)
                        }
                    } else {
                        throw e
                    }
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("处理交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
                Result.failure(e)
            }
        }
    }
}
```

**关键点**：
1. **按交易ID锁定**：每个交易使用独立的 Mutex，不同交易可以并行处理
2. **Mutex 复用**：使用 `ConcurrentHashMap` 缓存 Mutex，避免频繁创建
3. **协程友好**：`Mutex.withLock` 是 suspend 函数，不会阻塞线程
4. **性能优化**：只锁定同一笔交易，不影响其他交易的并发处理

**当前实现（基于数据库唯一约束，作为兜底）**：

```kotlin
@Transactional
suspend fun processTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
    // 1. 检查是否已处理（去重）
    val existingProcessed = processedTradeRepository.findByLeaderIdAndLeaderTradeId(
        leaderId, 
        trade.id
    )
    
    if (existingProcessed != null) {
        return Result.success(Unit)  // 已处理，跳过
    }
    
    // 2. 处理交易逻辑
    val result = when (trade.side.uppercase()) {
        "BUY" -> processBuyTrade(leaderId, trade)
        "SELL" -> processSellTrade(leaderId, trade)
        else -> Result.failure(IllegalArgumentException("未知的交易方向"))
    }
    
    if (result.isFailure) {
        return result
    }
    
    // 3. 标记为已处理（使用数据库唯一约束保证并发安全）
    try {
        val processed = ProcessedTrade(
            leaderId = leaderId,
            leaderTradeId = trade.id,
            tradeType = trade.side.uppercase(),
            source = source,
            status = "SUCCESS",
            processedAt = System.currentTimeMillis()
        )
        processedTradeRepository.save(processed)
    } catch (e: Exception) {
        // 处理唯一约束冲突（并发情况下可能发生）
        if (isUniqueConstraintViolation(e)) {
            // 再次检查确认状态
            val existing = processedTradeRepository.findByLeaderIdAndLeaderTradeId(
                leaderId, 
                trade.id
            )
            if (existing != null) {
                logger.debug("交易已处理（并发检测）: leaderId=$leaderId, tradeId=${trade.id}")
                return Result.success(Unit)
            }
        } else {
            throw e
        }
    }
    
    return Result.success(Unit)
}
```

**线程安全机制**：

1. **数据库唯一约束**：
   - `UNIQUE KEY uk_leader_trade (leader_id, leader_trade_id)`
   - 确保同一笔交易只能插入一次
   - 即使两个线程同时通过检查，也只有一个能成功保存

2. **事务隔离**：
   - 使用 `@Transactional` 注解
   - 默认隔离级别（通常是 READ_COMMITTED）
   - 保证事务内的数据一致性

3. **异常处理**：
   - 捕获唯一约束冲突异常
   - 重新查询确认状态
   - 避免重复处理

**潜在问题**：

虽然数据库唯一约束可以防止重复插入，但在检查 `existingProcessed` 和保存 `processed` 之间存在时间窗口（TOCTOU），可能导致：
- 两个线程同时通过检查
- 两个线程都执行 `processBuyTrade` 或 `processSellTrade`
- 虽然只有一个能成功保存 `processed`，但可能创建了重复的订单

**其他方案（不推荐，仅作参考）**：

**方案 1：使用数据库锁（SELECT FOR UPDATE）**
- ❌ 需要修改 Repository 方法
- ❌ 增加数据库负载
- ❌ 不适合高并发场景

**方案 2：使用分布式锁（Redis，仅适用于多实例）**
- ❌ 需要 Redis 依赖
- ❌ 有网络开销
- ❌ 单实例场景不需要

**总结**：

- ✅ **单实例部署推荐**：使用 **Mutex（应用级锁）**
  - 最轻量：无需额外依赖，性能开销小
  - 最简单：Kotlin 标准库，实现简单
  - 最高效：内存锁，无网络开销，支持协程并发
- ❌ **不推荐**：仅依赖数据库唯一约束（存在 TOCTOU 问题，可能创建重复订单）
- ❌ **不推荐**：数据库锁或分布式锁（单实例场景过于复杂）

