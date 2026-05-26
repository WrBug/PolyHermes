package com.wrbug.polymarketbot.service.whalemonitor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.dto.ActivityTradeMessage
import com.wrbug.polymarketbot.entity.WhaleMonitorStrategy
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private data class TradePoint(val tsMillis: Long, val notional: BigDecimal)
private data class AggKey(val conditionId: String, val tokenId: String, val side: String)

/**
 * 大单监听 WebSocket 服务
 * 订阅全局 Activity trades，按 conditionId 过滤，滑动窗口聚合 notional，达阈值触发下单
 */
@Service
class WhaleMonitorWsService(
    private val orderExecutionService: WhaleMonitorOrderExecutionService
) {

    private val logger = LoggerFactory.getLogger(WhaleMonitorWsService::class.java)

    private val websocketUrl: String = PolymarketConstants.ACTIVITY_WS_URL
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var wsClient: PolymarketWebSocketClient? = null

    /** conditionId -> 关联的策略列表 */
    private val conditionIdStrategies = ConcurrentHashMap<String, MutableList<WhaleMonitorStrategy>>()

    /** 聚合窗口：每个 AggKey 维护一个滑动窗口 */
    private val windowBuffers = ConcurrentHashMap<AggKey, ArrayDeque<TradePoint>>()
    private val runningSums = ConcurrentHashMap<AggKey, BigDecimal>()

    /** 冷却：strategyId-tokenId -> 上次触发毫秒时间戳 */
    private val cooldownTimestamps = ConcurrentHashMap<String, Long>()

    /** txHash 去重 */
    private val processedTxHashes: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build()

    @Volatile
    private var isSubscribed = false

    private var cleanupJob: Job? = null

    fun start(strategies: List<WhaleMonitorStrategy>) {
        rebuildConditionIdMap(strategies)
        if (conditionIdStrategies.isEmpty()) {
            logger.info("没有需要监听的市场，停止大单监听 WebSocket")
            stop()
            return
        }
        logger.info("启动大单监听 WebSocket，监控 ${conditionIdStrategies.size} 个市场")
        connectAndSubscribe()
        startCleanupTask()
    }

    fun stop() {
        logger.info("停止大单监听 WebSocket")
        cleanupJob?.cancel()
        cleanupJob = null
        wsClient?.closeConnection()
        wsClient = null
        isSubscribed = false
        conditionIdStrategies.clear()
        windowBuffers.clear()
        runningSums.clear()
        cooldownTimestamps.clear()
        processedTxHashes.invalidateAll()
    }

    fun getMonitoredMarketCount(): Int = conditionIdStrategies.size

    private fun rebuildConditionIdMap(strategies: List<WhaleMonitorStrategy>) {
        conditionIdStrategies.clear()
        for (strategy in strategies) {
            addStrategyToMap(strategy)
        }
    }

    private fun addStrategyToMap(strategy: WhaleMonitorStrategy) {
        val ids: List<String> = try {
            strategy.conditionIds.fromJson<List<String>>() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (cid in ids) {
            conditionIdStrategies.computeIfAbsent(cid) { mutableListOf() }.add(strategy)
        }
    }

    private fun connectAndSubscribe() {
        val existingClient = wsClient
        if (existingClient != null && existingClient.isConnected()) {
            if (!isSubscribed) subscribe()
            return
        }

        logger.info("连接大单监听 Activity WebSocket: $websocketUrl")
        val newClient = PolymarketWebSocketClient(
            url = websocketUrl,
            sessionId = "whale-monitor-activity",
            onMessage = { message -> handleMessage(message) },
            onOpen = {
                logger.info("大单监听 WebSocket 连接成功")
                subscribe()
            },
            onReconnect = {
                logger.info("大单监听 WebSocket 重连成功，重新订阅")
                subscribe()
            }
        )
        wsClient = newClient
        scope.launch {
            try {
                newClient.connect()
            } catch (e: Exception) {
                logger.error("连接大单监听 WebSocket 失败", e)
            }
        }
    }

    private fun subscribe() {
        val client = wsClient ?: return
        if (!client.isConnected()) return
        try {
            val subscribeMessage = """
            {
                "action": "subscribe",
                "subscriptions": [
                    {
                        "topic": "activity",
                        "type": "trades"
                    }
                ]
            }
            """.trimIndent()
            client.sendMessage(subscribeMessage)
            isSubscribed = true
            logger.info("大单监听 WebSocket 订阅成功（全局交易流: trades）")
        } catch (e: Exception) {
            logger.error("订阅大单监听 WebSocket 失败", e)
            isSubscribed = false
        }
    }

    private fun handleMessage(message: String) {
        try {
            if (message.trim() == "PONG" || message.trim() == "pong") return

            if (!fastFilterByConditionId(message)) return

            val tradeMessage = message.fromJson<ActivityTradeMessage>() ?: return

            if (tradeMessage.topic != "activity" || tradeMessage.type != "trades") return

            val payload = tradeMessage.payload

            val txHash = payload.transactionHash
            if (!txHash.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                val existing = processedTxHashes.asMap().putIfAbsent(txHash, now)
                if (existing != null) return
            }

            val side = payload.side?.uppercase() ?: return
            if (side != "BUY") return

            val conditionId = payload.conditionId ?: return
            val tokenId = payload.asset ?: return
            if (conditionId.isBlank() || tokenId.isBlank()) return

            val strategies = conditionIdStrategies[conditionId] ?: return

            val price = convertToBigDecimal(payload.price) ?: return
            val size = convertToBigDecimal(payload.size) ?: return
            val notional = price.multiply(size)

            val aggKey = AggKey(conditionId, tokenId, side)

            for (strategy in strategies) {
                addToWindow(strategy, aggKey, notional)
            }
        } catch (e: Exception) {
            logger.error("大单监听处理消息失败: ${e.message}", e)
        }
    }

    /**
     * 快速字符串级别过滤：检查消息中是否包含任一监听的 conditionId
     */
    private fun fastFilterByConditionId(message: String): Boolean {
        if (message.length < 50) return false
        for (cid in conditionIdStrategies.keys) {
            if (message.contains("\"conditionId\":\"$cid\"")) return true
        }
        return false
    }

    private fun addToWindow(strategy: WhaleMonitorStrategy, key: AggKey, notional: BigDecimal) {
        val now = System.currentTimeMillis()
        val windowMs = strategy.windowSeconds.toLong() * 1000

        val deque = windowBuffers.computeIfAbsent(key) { ArrayDeque() }
        val sum = runningSums.computeIfAbsent(key) { BigDecimal.ZERO }

        // 过期清理
        while (deque.isNotEmpty() && deque.first().tsMillis < now - windowMs) {
            val expired = deque.removeFirst()
            runningSums[key] = sum.subtract(expired.notional)
        }

        // 入队
        deque.addLast(TradePoint(now, notional))
        runningSums[key] = sum.add(notional)

        val currentSum = runningSums[key] ?: BigDecimal.ZERO

        // 阈值检查
        if (currentSum >= strategy.thresholdAmount) {
            val cooldownKey = "${strategy.id}-${key.tokenId}"
            val lastTrigger = cooldownTimestamps[cooldownKey] ?: 0L
            if (now - lastTrigger >= strategy.cooldownSeconds.toLong() * 1000) {
                cooldownTimestamps[cooldownKey] = now
                logger.info("大单监听触发: strategyId=${strategy.id} tokenId=${key.tokenId} volume=$currentSum threshold=${strategy.thresholdAmount}")
                scope.launch {
                    try {
                        orderExecutionService.executeOrder(
                            strategy = strategy,
                            conditionId = key.conditionId,
                            tokenId = key.tokenId,
                            triggerVolume = currentSum
                        )
                    } catch (e: Exception) {
                        logger.error("大单监听下单执行异常: strategyId=${strategy.id} tokenId=${key.tokenId}: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun startCleanupTask() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(60_000)
                cleanupExpiredEntries()
            }
        }
    }

    private fun cleanupExpiredEntries() {
        val now = System.currentTimeMillis()
        val maxWindowMs = conditionIdStrategies.values.flatten().maxOfOrNull { it.windowSeconds.toLong() * 1000 } ?: 10_000

        val iter = windowBuffers.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val deque = entry.value
            while (deque.isNotEmpty() && deque.first().tsMillis < now - maxWindowMs) {
                val expired = deque.removeFirst()
                val currentSum = runningSums[entry.key] ?: BigDecimal.ZERO
                runningSums[entry.key] = currentSum.subtract(expired.notional)
            }
            if (deque.isEmpty()) {
                runningSums.remove(entry.key)
                iter.remove()
            }
        }
    }

    private fun convertToBigDecimal(value: Any?): BigDecimal? {
        if (value == null) return null
        return when (value) {
            is String -> value.toSafeBigDecimal()
            is Number -> BigDecimal(value.toString())
            is BigDecimal -> value
            else -> value.toString().toSafeBigDecimal()
        }
    }

    @PreDestroy
    fun destroy() {
        stop()
        scope.cancel()
    }
}
