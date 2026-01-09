package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.ActivityTradeMessage
import com.wrbug.polymarketbot.dto.ActivityTradePayload
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Polymarket Activity WebSocket 监听服务
 * 通过订阅全局 activity 交易流，客户端过滤 Leader 地址，实现实时交易检测
 * 延迟 < 100ms，适合快速跟单场景
 */
@Service
class PolymarketActivityWsService(
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val leaderRepository: LeaderRepository
) {

    private val logger = LoggerFactory.getLogger(PolymarketActivityWsService::class.java)

    @Value("\${polymarket.websocket.activity.url:wss://ws-live-data.polymarket.com}")
    private var websocketUrl: String = "wss://ws-live-data.polymarket.com"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 单例 WebSocket 客户端
    private var wsClient: PolymarketWebSocketClient? = null

    // 要监听的 Leader 地址集合（小写地址 -> leaderId）
    private val monitoredAddresses = ConcurrentHashMap<String, Long>()

    // 是否已订阅
    @Volatile
    private var isSubscribed = false

    /**
     * 启动监听
     */
    fun start(leaders: List<Leader>) {
        monitoredAddresses.clear()
        leaders.forEach { leader ->
            val leaderId = leader.id
            if (leaderId != null) {
                monitoredAddresses[leader.leaderAddress.lowercase()] = leaderId
            }
        }

        if (monitoredAddresses.isEmpty()) {
            logger.info("没有需要监听的 Leader，停止 Activity WebSocket")
            stop()
            return
        }

        logger.info("启动 Activity WebSocket 监听，监控 ${monitoredAddresses.size} 个 Leader 地址")
        connectAndSubscribe()
    }

    /**
     * 添加 Leader
     */
    fun addLeader(leader: Leader) {
        if (leader.id == null) {
            logger.warn("Leader ID 为空，跳过: ${leader.leaderAddress}")
            return
        }

        val address = leader.leaderAddress.lowercase()
        val existingLeaderId = monitoredAddresses[address]

        if (existingLeaderId != null && existingLeaderId == leader.id) {
            logger.debug("Leader 已在监听列表中: ${leader.leaderName} (${address})")
            return
        }

        val leaderId = leader.id
        if (leaderId != null) {
            monitoredAddresses[address] = leaderId
            logger.info("添加 Leader 到 Activity WS 监听: ${leader.leaderName} (${address})")

            // 如果 WebSocket 未连接，连接
            val client = wsClient
            if (client == null || !client.isConnected()) {
                connectAndSubscribe()
            }
        }
    }

    /**
     * 移除 Leader
     */
    fun removeLeader(leaderId: Long) {
        val addressToRemove = monitoredAddresses.entries
            .find { it.value == leaderId }?.key

        if (addressToRemove != null) {
            monitoredAddresses.remove(addressToRemove)
            logger.info("从 Activity WS 监听移除 Leader: leaderId=$leaderId, address=$addressToRemove")
        }

        // 如果没有 Leader 了，停止监听
        if (monitoredAddresses.isEmpty()) {
            logger.info("没有 Leader 需要监听了，停止 Activity WebSocket")
            stop()
        }
    }

    /**
     * 连接并订阅
     */
    private fun connectAndSubscribe() {
        val existingClient = wsClient
        if (existingClient != null && existingClient.isConnected()) {
            // 如果已连接但未订阅，发送订阅
            if (!isSubscribed) {
                subscribeAllActivity()
            }
            return
        }

        logger.info("连接 Activity WebSocket: $websocketUrl")

        val newClient = PolymarketWebSocketClient(
            url = websocketUrl,
            sessionId = "copy-trading-activity",
            onMessage = { message -> handleMessage(message) },
            onOpen = {
                logger.info("Activity WebSocket 连接成功")
                subscribeAllActivity()
            },
            onReconnect = {
                logger.info("Activity WebSocket 重连成功，重新订阅")
                subscribeAllActivity()
            }
        )

        wsClient = newClient

        scope.launch {
            try {
                newClient.connect()
            } catch (e: Exception) {
                logger.error("连接 Activity WebSocket 失败", e)
            }
        }
    }

    /**
     * 订阅全局 activity
     * 根据 @polymarket/real-time-data-client 的协议格式
     * 使用 "action": "subscribe" 而不是 "type": "subscribe"
     */
    private fun subscribeAllActivity() {
        val client = wsClient
        if (client == null || !client.isConnected()) {
            logger.warn("WebSocket 未连接，无法订阅")
            return
        }

        try {
            // 根据 real-time-data-client 的协议格式
            // 订阅消息应包含 "action": "subscribe" 和 "subscriptions" 数组
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
            logger.info("Activity WebSocket 订阅成功（全局交易流）")
        } catch (e: Exception) {
            logger.error("订阅 Activity WebSocket 失败", e)
            isSubscribed = false
        }
    }

    /**
     * 处理消息
     */
    private fun handleMessage(message: String) {
        try {
            // 处理 PONG 响应
            if (message.trim() == "PONG" || message.trim() == "pong") {
                return
            }
            
            // 使用扩展函数解析消息
            val tradeMessage = message.fromJson<ActivityTradeMessage>() ?: run {
                // 不是有效的 JSON 或格式不匹配，跳过
                logger.warn("无法解析为 ActivityTradeMessage，可能不是 activity 消息: ${message.take(200)}")
                return
            }
            
            // 检查是否是 activity trade 消息
            if (tradeMessage.topic != "activity" || tradeMessage.type != "trades") {
                // 不是我们关心的消息，直接返回
                return
            }
            
            val payload = tradeMessage.payload
            
            // 提取交易者地址
            val traderAddress = extractTraderAddress(payload) ?: run {
                // 没有交易者地址，跳过
                logger.warn("Activity Trade 消息中没有交易者地址: trader=${payload.trader}, proxyWallet=${payload.proxyWallet}, asset=${payload.asset}")
                return
            }
            
            // 检查是否是我们监听的 Leader
            val normalizedAddress = traderAddress.lowercase()
            val leaderId = monitoredAddresses[normalizedAddress] ?: run {
                return
            }

            // 解析交易数据
            val trade = parseActivityTrade(payload, leaderId)
            if (trade != null) {
                logger.info("✅ 检测到 Leader 交易: leaderId=$leaderId, address=$traderAddress, side=${trade.side}, market=${trade.market}, size=${trade.size}")

                // 异步处理交易（避免阻塞消息处理）
                scope.launch {
                    try {
                        copyOrderTrackingService.processTrade(
                            leaderId = leaderId,
                            trade = trade,
                            source = "activity-ws"
                        )
                    } catch (e: Exception) {
                        logger.error("处理 Activity WS 交易失败: leaderId=$leaderId, tradeId=${trade.id}", e)
                    }
                }
            } else {
                logger.warn("解析交易数据失败: leaderId=$leaderId, address=$traderAddress, asset=${payload.asset}, side=${payload.side}")
            }
        } catch (e: Exception) {
            logger.error("处理 Activity WebSocket 消息失败: ${e.message}", e)
        }
    }

    /**
     * 提取交易者地址
     * 优先检查 trader.address，fallback 到 proxyWallet
     */
    private fun extractTraderAddress(payload: ActivityTradePayload): String? {
        // 优先检查 trader.address
        val address = payload.trader?.address
            ?: payload.proxyWallet

        return address
    }

    /**
     * 解析 Activity Trade 为 TradeResponse
     */
    private fun parseActivityTrade(payload: ActivityTradePayload, leaderId: Long): TradeResponse? {
        return try {
            // 提取必需字段并验证
            val asset = payload.asset
            val conditionId = payload.conditionId
            val sideRaw = payload.side

            if (asset.isBlank() || conditionId.isBlank() || sideRaw.isBlank()) {
                logger.warn("Activity Trade 消息缺少必需字段: asset=$asset, conditionId=$conditionId, side=$sideRaw")
                return null
            }

            val side = sideRaw.uppercase()

            // 验证 side 必须是 BUY 或 SELL
            if (side != "BUY" && side != "SELL") {
                logger.warn("Activity Trade 消息 side 字段无效: side=$side")
                return null
            }

            // price 和 size 可能是数字或字符串，统一转换为字符串
            val price = convertToString(payload.price) ?: run {
                logger.warn("Activity Trade 消息 price 字段无效: ${payload.price}")
                return null
            }

            val size = convertToString(payload.size) ?: run {
                logger.warn("Activity Trade 消息 size 字段无效: ${payload.size}")
                return null
            }

            // 时间戳处理：可能是秒或毫秒，可能是数字或字符串
            val timestamp = when {
                payload.timestamp == null -> System.currentTimeMillis().toString()
                payload.timestamp is Number -> {
                    val ts = (payload.timestamp as Number).toLong()
                    // 如果时间戳小于 1e12（秒级），转换为毫秒
                    if (ts < 1e12) (ts * 1000).toString() else ts.toString()
                }

                payload.timestamp is String -> {
                    val tsStr = payload.timestamp as String
                    val ts = tsStr.toLongOrNull() ?: System.currentTimeMillis()
                    if (ts < 1e12) (ts * 1000).toString() else tsStr
                }

                else -> System.currentTimeMillis().toString()
            }

            // 解析 outcome 和 outcomeIndex
            // 优先使用消息中的 outcomeIndex，如果没有则从 outcome 字符串解析
            val outcome = payload.outcome
            val outcomeIndex = payload.outcomeIndex
                ?: parseOutcomeIndex(outcome)

            // 使用 transactionHash 作为 trade ID，如果没有则生成 fallback ID
            val tradeId = payload.transactionHash ?: "${leaderId}_${System.currentTimeMillis()}_${asset.take(10)}"

            TradeResponse(
                id = tradeId,
                market = conditionId,
                side = side,
                price = price,
                size = size,
                timestamp = timestamp,
                user = null, // Activity WS 中不需要
                outcomeIndex = outcomeIndex,
                outcome = outcome
            )
        } catch (e: Exception) {
            logger.error("解析 Activity Trade 失败: ${e.message}", e)
            null
        }
    }

    /**
     * 将 Any 类型的值转换为 String
     * 支持 Number、String、BigDecimal 等类型
     */
    private fun convertToString(value: Any?): String? {
        if (value == null) return null

        return when (value) {
            is String -> value
            is Number -> {
                // 如果是数字，转换为 BigDecimal 再转为字符串（保持精度）
                try {
                    BigDecimal(value.toString()).toPlainString()
                } catch (e: Exception) {
                    value.toString()
                }
            }

            is BigDecimal -> value.toPlainString()
            else -> value.toString()
        }
    }

    /**
     * 解析 outcome 为 outcomeIndex
     */
    private fun parseOutcomeIndex(outcome: String?): Int? {
        return when (outcome?.uppercase()) {
            "YES", "UP", "TRUE" -> 0
            "NO", "DOWN", "FALSE" -> 1
            else -> null
        }
    }

    /**
     * 停止监听
     */
    fun stop() {
        logger.info("停止 Activity WebSocket 监听")
        wsClient?.closeConnection()
        wsClient = null
        isSubscribed = false
        monitoredAddresses.clear()
    }

    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean {
        return wsClient?.isConnected() ?: false
    }

    /**
     * 获取监听的 Leader 数量
     */
    fun getMonitoredCount(): Int {
        return monitoredAddresses.size
    }

    @PreDestroy
    fun destroy() {
        stop()
        scope.cancel()
    }
}

