package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.dto.CryptoTailMonitorPushData
import com.wrbug.polymarketbot.dto.OrderPushMessage
import com.wrbug.polymarketbot.dto.PositionPushMessage
import com.wrbug.polymarketbot.dto.WebSocketMessage as WsMessage
import com.wrbug.polymarketbot.dto.WebSocketMessageType
import com.wrbug.polymarketbot.service.accounts.PositionPushService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailMonitorService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 订阅管理服务
 * 管理所有频道的订阅和数据推送
 */
@Service
class WebSocketSubscriptionService(
    private val positionPushService: PositionPushService,
    private val orderPushService: OrderPushService
) {
    
    private val logger = LoggerFactory.getLogger(WebSocketSubscriptionService::class.java)
    
    // 协程作用域，用于异步发送首推数据
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 存储会话和对应的推送回调
    private val sessionCallbacks = ConcurrentHashMap<String, (WsMessage) -> Unit>()
    
    // 存储每个会话的订阅频道：sessionId -> Set<channel>
    private val sessionSubscriptions = ConcurrentHashMap<String, MutableSet<String>>()
    
    // 存储每个频道的订阅会话数：channel -> Set<sessionId>
    private val channelSubscriptions = ConcurrentHashMap<String, MutableSet<String>>()
    
    // 存储 order 频道的订阅回调：sessionId -> callback（用于取消订阅）
    private val orderChannelCallbacks = ConcurrentHashMap<String, (OrderPushMessage) -> Unit>()
    
    // 存储尾盘监控频道的订阅回调：sessionId -> (strategyId -> callback)
    private val monitorChannelCallbacks = ConcurrentHashMap<String, MutableMap<Long, (CryptoTailMonitorPushData) -> Unit>>()
    
    // 尾盘监控服务（延迟注入，避免循环依赖）
    private var cryptoTailMonitorService: CryptoTailMonitorService? = null
    
    /**
     * 设置尾盘监控服务（由 Spring 在初始化后调用）
     */
    fun setCryptoTailMonitorService(service: CryptoTailMonitorService) {
        cryptoTailMonitorService = service
    }
    
    /**
     * 注册会话
     */
    fun registerSession(sessionId: String, callback: (WsMessage) -> Unit) {
        sessionCallbacks[sessionId] = callback
        sessionSubscriptions[sessionId] = mutableSetOf()
        monitorChannelCallbacks[sessionId] = mutableMapOf()
    }
    
    /**
     * 注销会话
     */
    fun unregisterSession(sessionId: String) {
        // 取消所有订阅
        val channels = sessionSubscriptions.remove(sessionId) ?: emptySet()
        channels.forEach { channel ->
            unsubscribe(sessionId, channel)
        }

        // 清理 order 频道的回调
        orderChannelCallbacks.remove(sessionId)

        // 清理尾盘监控频道的回调
        val monitorCallbacks = monitorChannelCallbacks.remove(sessionId)
        monitorCallbacks?.keys?.forEach { strategyId ->
            cryptoTailMonitorService?.unsubscribe(sessionId, strategyId)
        }

        sessionCallbacks.remove(sessionId)
    }
    
    /**
     * 订阅频道
     */
    fun subscribe(sessionId: String, channel: String, payload: Map<*, *>?) {
        
        // 检查是否已经订阅
        val sessionChannels = sessionSubscriptions.getOrPut(sessionId) { mutableSetOf() }
        if (sessionChannels.contains(channel)) {
            sendSubscribeAck(sessionId, channel, true)
            return
        }
        
        // 记录订阅关系
        sessionChannels.add(channel)
        channelSubscriptions.getOrPut(channel) { mutableSetOf() }.add(sessionId)
        
        // 发送订阅确认
        sendSubscribeAck(sessionId, channel, true)
        
        // 根据频道类型启动推送服务
        when {
            channel == "position" -> {
                positionPushService.subscribe(sessionId) { message ->
                    pushData(sessionId, channel, message)
                }
                // 立即发送首推数据（全量数据）
                scope.launch {
                    try {
                        positionPushService.sendFullData(sessionId)
                    } catch (e: Exception) {
                        logger.error("发送仓位首推数据失败: $sessionId, ${e.message}", e)
                    }
                }
            }
            channel == "order" -> {
                // 订单推送：自动订阅所有启用的账户
                val callback: (OrderPushMessage) -> Unit = { message ->
                    pushData(sessionId, channel, message)
                }
                orderChannelCallbacks[sessionId] = callback
                orderPushService.subscribeAllEnabled(callback)
            }
            channel.startsWith("crypto_tail_monitor_") -> {
                // 尾盘策略监控频道
                val strategyId = channel.removePrefix("crypto_tail_monitor_").toLongOrNull()
                if (strategyId != null && cryptoTailMonitorService != null) {
                    val callback: (CryptoTailMonitorPushData) -> Unit = { message ->
                        pushData(sessionId, channel, message)
                    }
                    monitorChannelCallbacks.getOrPut(sessionId) { mutableMapOf() }[strategyId] = callback
                    cryptoTailMonitorService!!.subscribe(sessionId, strategyId, callback)
                } else {
                    logger.warn("无效的尾盘监控频道或服务未初始化: $channel")
                    sendSubscribeAck(sessionId, channel, false, "无效的策略ID")
                }
            }
            else -> {
                logger.warn("未知的频道: $channel")
                sendSubscribeAck(sessionId, channel, false, "未知的频道")
            }
        }
    }
    
    /**
     * 取消订阅
     */
    fun unsubscribe(sessionId: String, channel: String) {
        
        // 移除订阅关系
        sessionSubscriptions[sessionId]?.remove(channel)
        channelSubscriptions[channel]?.remove(sessionId)
        
        // 取消推送服务的订阅（推送服务内部会处理是否停止轮询）
        when {
            channel == "position" -> positionPushService.unsubscribe(sessionId)
            channel == "order" -> {
                // 取消订阅所有账户的订单推送
                val callback = orderChannelCallbacks.remove(sessionId)
                if (callback != null) {
                    orderPushService.unsubscribeAll(callback)
                }
            }
            channel.startsWith("crypto_tail_monitor_") -> {
                // 取消尾盘监控订阅
                val strategyId = channel.removePrefix("crypto_tail_monitor_").toLongOrNull()
                if (strategyId != null) {
                    monitorChannelCallbacks[sessionId]?.remove(strategyId)
                    cryptoTailMonitorService?.unsubscribe(sessionId, strategyId)
                }
            }
        }
    }
    
    /**
     * 注册尾盘监控回调（由 CryptoTailMonitorService 调用）
     */
    fun registerMonitorCallback(sessionId: String, strategyId: Long, callback: (CryptoTailMonitorPushData) -> Unit) {
        monitorChannelCallbacks.getOrPut(sessionId) { mutableMapOf() }[strategyId] = callback
    }
    
    /**
     * 注销尾盘监控回调（由 CryptoTailMonitorService 调用）
     */
    fun unregisterMonitorCallback(sessionId: String, strategyId: Long) {
        monitorChannelCallbacks[sessionId]?.remove(strategyId)
    }
    
    /**
     * 推送尾盘监控数据（由 CryptoTailMonitorService 调用）
     */
    fun pushMonitorData(strategyId: Long, data: CryptoTailMonitorPushData) {
        val channel = "crypto_tail_monitor_$strategyId"
        val sessionIds = channelSubscriptions[channel] ?: return
        
        for (sessionId in sessionIds) {
            val callback = sessionCallbacks[sessionId]
            if (callback != null) {
                val message = WsMessage(
                    type = WebSocketMessageType.DATA.value,
                    channel = channel,
                    payload = data,
                    timestamp = System.currentTimeMillis()
                )
                callback(message)
            }
        }
    }
    
    /**
     * 推送数据到指定会话
     */
    private fun pushData(sessionId: String, channel: String, payload: Any) {
        val callback = sessionCallbacks[sessionId]
        if (callback != null) {
            val message = WsMessage(
                type = WebSocketMessageType.DATA.value,
                channel = channel,
                payload = payload,
                timestamp = System.currentTimeMillis()
            )
            callback(message)
        } else {
            logger.warn("会话 $sessionId 的回调不存在，无法推送数据")
        }
    }
    
    /**
     * 发送订阅确认
     */
    private fun sendSubscribeAck(sessionId: String, channel: String, success: Boolean, errorMessage: String? = null) {
        val callback = sessionCallbacks[sessionId]
        if (callback != null) {
            val message = WsMessage(
                type = WebSocketMessageType.SUB_ACK.value,
                channel = channel,
                status = if (success) 0 else 1,  // 0: success, 非0: error
                message = errorMessage
            )
            callback(message)
        }
    }
}
