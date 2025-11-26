package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.dto.PositionPushMessage
import com.wrbug.polymarketbot.dto.WebSocketMessage as WsMessage
import com.wrbug.polymarketbot.dto.WebSocketMessageType
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
    private val positionPushService: PositionPushService
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
    
    /**
     * 注册会话
     */
    fun registerSession(sessionId: String, callback: (WsMessage) -> Unit) {
        logger.info("注册 WebSocket 会话: $sessionId")
        sessionCallbacks[sessionId] = callback
        sessionSubscriptions[sessionId] = mutableSetOf()
    }
    
    /**
     * 注销会话
     */
    fun unregisterSession(sessionId: String) {
        logger.info("注销 WebSocket 会话: $sessionId")
        
        // 取消所有订阅
        val channels = sessionSubscriptions.remove(sessionId) ?: emptySet()
        channels.forEach { channel ->
            unsubscribe(sessionId, channel)
        }
        
        sessionCallbacks.remove(sessionId)
    }
    
    /**
     * 订阅频道
     */
    fun subscribe(sessionId: String, channel: String, payload: Map<*, *>?) {
        logger.info("订阅频道: $sessionId -> $channel")
        
        // 检查是否已经订阅
        val sessionChannels = sessionSubscriptions.getOrPut(sessionId) { mutableSetOf() }
        if (sessionChannels.contains(channel)) {
            logger.debug("会话 $sessionId 已经订阅了频道 $channel，跳过重复订阅")
            sendSubscribeAck(sessionId, channel, true)
            return
        }
        
        // 记录订阅关系
        sessionChannels.add(channel)
        channelSubscriptions.getOrPut(channel) { mutableSetOf() }.add(sessionId)
        
        // 发送订阅确认
        sendSubscribeAck(sessionId, channel, true)
        
        // 根据频道类型启动推送服务
        when (channel) {
            "position" -> {
                positionPushService.subscribe(sessionId) { message ->
                    pushData(sessionId, channel, message)
                }
                // 立即发送首推数据（全量数据）
                scope.launch {
                    try {
                        positionPushService.sendFullData(sessionId)
                        logger.info("已发送仓位首推数据给会话: $sessionId")
                    } catch (e: Exception) {
                        logger.error("发送仓位首推数据失败: $sessionId, ${e.message}", e)
                    }
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
        logger.info("取消订阅频道: $sessionId -> $channel")
        
        // 移除订阅关系
        sessionSubscriptions[sessionId]?.remove(channel)
        channelSubscriptions[channel]?.remove(sessionId)
        
        // 取消推送服务的订阅（推送服务内部会处理是否停止轮询）
        when (channel) {
            "position" -> positionPushService.unsubscribe(sessionId)
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

