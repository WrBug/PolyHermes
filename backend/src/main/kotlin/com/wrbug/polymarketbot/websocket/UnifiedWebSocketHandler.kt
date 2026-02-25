package com.wrbug.polymarketbot.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.WebSocketMessage as WsMessage
import com.wrbug.polymarketbot.dto.WebSocketMessageType
import com.wrbug.polymarketbot.service.common.WebSocketSubscriptionService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.socket.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一 WebSocket 处理器
 * 处理所有推送频道的订阅和数据推送
 */
@Component
class UnifiedWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val subscriptionService: WebSocketSubscriptionService
) : WebSocketHandler {
    
    private val logger = LoggerFactory.getLogger(UnifiedWebSocketHandler::class.java)
    
    @Value("\${websocket.heartbeat-timeout:60000}")
    private var heartbeatTimeout: Long = 60000
    
    // 存储客户端会话
    private val clientSessions = ConcurrentHashMap<String, WebSocketSession>()
    
    // 存储每个连接的最后活动时间
    private val lastActivityTime = ConcurrentHashMap<String, Long>()
    
    // 存储每个会话的同步锁，确保同一会话的消息按顺序发送
    private val sessionLocks = ConcurrentHashMap<String, Any>()
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null
    
    @PostConstruct
    fun init() {
        startCleanupTask()
    }
    
    @PreDestroy
    fun destroy() {
        cleanupJob?.cancel()
        scope.cancel()
    }
    
    override fun afterConnectionEstablished(session: WebSocketSession) {
        clientSessions[session.id] = session
        lastActivityTime[session.id] = System.currentTimeMillis()
        // 为每个会话创建同步锁
        sessionLocks[session.id] = Any()
        
        // 注册会话到订阅服务
        subscriptionService.registerSession(session.id) { wsMessage ->
            sendMessageToClient(session.id, wsMessage)
        }
    }
    
    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        val payload = message.payload.toString()
        
        // 处理心跳
        if (payload == "PING" || payload == "ping") {
            lastActivityTime[session.id] = System.currentTimeMillis()
            // 心跳响应也使用同步锁，避免与数据消息冲突
            val lock = sessionLocks[session.id]
            if (lock != null && session.isOpen) {
                synchronized(lock) {
                    try {
                        if (session.isOpen) {
                            session.sendMessage(TextMessage("PONG"))
                        }
                    } catch (e: IllegalStateException) {
                        logger.warn("发送心跳响应时 WebSocket 状态异常: ${session.id}, ${e.message}")
                    } catch (e: Exception) {
                        logger.error("发送心跳响应失败: ${session.id}, ${e.message}", e)
                    }
                }
            }
            return
        }
        
        // 更新活动时间
        lastActivityTime[session.id] = System.currentTimeMillis()
        
        // 解析消息
        try {
            val wsMessage: WsMessage = objectMapper.readValue(payload, WsMessage::class.java)
            handleWebSocketMessage(session.id, wsMessage)
        } catch (e: Exception) {
            logger.error("解析 WebSocket 消息失败: ${session.id}, ${e.message}", e)
        }
    }
    
    /**
     * 处理 WebSocket 消息
     */
    private fun handleWebSocketMessage(sessionId: String, message: WsMessage) {
        val messageType = WebSocketMessageType.fromValue(message.type)
        when (messageType) {
            WebSocketMessageType.SUB -> {
                val channel = message.channel
                if (channel != null) {
                    val payload = message.payload as? Map<*, *>
                    subscriptionService.subscribe(sessionId, channel, payload)
                } else {
                    logger.warn("订阅消息缺少 channel 字段: $sessionId")
                }
            }
            WebSocketMessageType.UNSUB -> {
                val channel = message.channel
                if (channel != null) {
                    subscriptionService.unsubscribe(sessionId, channel)
                } else {
                    logger.warn("取消订阅消息缺少 channel 字段: $sessionId")
                }
            }
            null -> {
                logger.warn("未知的消息类型: ${message.type}")
            }
            else -> {
                logger.warn("不支持的消息类型: ${messageType}")
            }
        }
    }
    
    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        logger.error("WebSocket 传输错误: ${session.id}, ${exception.message}", exception)
        cleanup(session.id)
    }
    
    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        cleanup(session.id)
    }
    
    override fun supportsPartialMessages(): Boolean = false
    
    /**
     * 发送消息给客户端
     * 使用同步锁确保同一会话的消息按顺序发送，避免并发冲突
     */
    private fun sendMessageToClient(sessionId: String, message: WsMessage) {
        val session = clientSessions[sessionId]
        if (session == null || !session.isOpen) {
            logger.warn("客户端会话不存在或已关闭: $sessionId")
            cleanup(sessionId)
            return
        }
        
        // 获取该会话的同步锁
        val lock = sessionLocks[sessionId] ?: return
        
        // 使用同步块确保同一会话的消息按顺序发送
        synchronized(lock) {
            // 再次检查会话状态（可能在等待锁的过程中会话已关闭）
            val currentSession = clientSessions[sessionId]
            if (currentSession == null || !currentSession.isOpen) {
                logger.warn("客户端会话在发送消息前已关闭: $sessionId")
                cleanup(sessionId)
                return
            }
            
            try {
                val json = objectMapper.writeValueAsString(message)
                currentSession.sendMessage(TextMessage(json))
                lastActivityTime[sessionId] = System.currentTimeMillis()
            } catch (e: IllegalStateException) {
                // WebSocket 状态异常（如 TEXT_PARTIAL_WRITING），记录但不清理会话
                // 这可能是暂时的状态问题，让后续消息有机会重试
                logger.warn("发送消息时 WebSocket 状态异常: $sessionId, ${e.message}")
                // 不立即清理，等待连接自然关闭或下次发送时再处理
            } catch (e: Exception) {
                logger.error("发送消息失败: $sessionId, ${e.message}", e)
                cleanup(sessionId)
            }
        }
    }
    
    /**
     * 清理资源
     */
    private fun cleanup(sessionId: String) {
        try {
            val session = clientSessions.remove(sessionId)
            lastActivityTime.remove(sessionId)
            sessionLocks.remove(sessionId)  // 清理同步锁
            subscriptionService.unregisterSession(sessionId)

            if (session != null && session.isOpen) {
                try {
                    session.close(CloseStatus.NORMAL)
                } catch (e: Exception) {
                    // 忽略关闭时的异常
                }
            }
        } catch (e: Exception) {
            logger.error("清理 WebSocket 资源时发生错误: $sessionId, ${e.message}", e)
        }
    }
    
    /**
     * 启动清理任务
     */
    private fun startCleanupTask() {
        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    cleanupInactiveConnections()
                } catch (e: Exception) {
                    logger.error("清理不活跃连接失败: ${e.message}", e)
                }
                delay(30000)
            }
        }
    }
    
    /**
     * 清理不活跃的连接
     */
    private fun cleanupInactiveConnections() {
        val now = System.currentTimeMillis()
        val inactiveSessions = mutableListOf<String>()
        
        lastActivityTime.forEach { (sessionId, lastActivity) ->
            val inactiveTime = now - lastActivity
            if (inactiveTime > heartbeatTimeout) {
                inactiveSessions.add(sessionId)
            }
        }
        
        inactiveSessions.forEach { sessionId ->
            logger.warn("检测到不活跃连接，准备清理: $sessionId, 不活跃时间: ${now - (lastActivityTime[sessionId] ?: 0)}ms")
            cleanup(sessionId)
        }
        
        if (inactiveSessions.isNotEmpty()) {
        }
    }
}

