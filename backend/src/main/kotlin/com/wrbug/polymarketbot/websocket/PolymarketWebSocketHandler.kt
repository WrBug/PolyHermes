package com.wrbug.polymarketbot.websocket

import com.wrbug.polymarketbot.constants.PolymarketConstants
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Polymarket WebSocket 处理器
 * 转发前端 WebSocket 连接到 Polymarket RTDS
 */
@Component
class PolymarketWebSocketHandler : WebSocketHandler {
    
    private val logger = LoggerFactory.getLogger(PolymarketWebSocketHandler::class.java)
    
    private val polymarketWsUrl: String = PolymarketConstants.RTDS_WS_URL
    
    // 存储客户端会话和对应的 Polymarket 连接的映射
    private val clientSessions = ConcurrentHashMap<String, WebSocketSession>()
    private val polymarketConnections = ConcurrentHashMap<String, PolymarketWebSocketClient>()
    
    override fun afterConnectionEstablished(session: WebSocketSession) {
        clientSessions[session.id] = session
        
        try {
            // 创建到 Polymarket 的 WebSocket 连接
            val polymarketClient = PolymarketWebSocketClient(
                url = polymarketWsUrl,
                sessionId = session.id,
                onMessage = { message ->
                    // 当收到 Polymarket 消息时，转发给客户端
                    forwardToClient(session.id, message)
                }
            )
            
            polymarketConnections[session.id] = polymarketClient
            
            // 异步连接，不阻塞
            try {
                polymarketClient.connect()
            } catch (e: Exception) {
                logger.error("启动 Polymarket 连接失败: ${e.message}", e)
                // 连接失败时清理资源
                cleanup(session.id)
                try {
                    session.close(CloseStatus.SERVER_ERROR.withReason("无法连接到 Polymarket"))
                } catch (ex: Exception) {
                    logger.error("关闭客户端连接失败: ${ex.message}", ex)
                }
            }
        } catch (e: Exception) {
            logger.error("创建 Polymarket 客户端失败: ${e.message}", e)
            try {
                session.close(CloseStatus.SERVER_ERROR.withReason("无法创建连接"))
            } catch (ex: Exception) {
                logger.error("关闭客户端连接失败: ${ex.message}", ex)
            }
        }
    }
    
    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        
        val polymarketClient = polymarketConnections[session.id]
        if (polymarketClient != null) {
            if (polymarketClient.isConnected()) {
                // 将客户端消息转发给 Polymarket
                try {
                    polymarketClient.sendMessage(message.payload.toString())
                } catch (e: Exception) {
                    logger.error("转发消息到 Polymarket 失败: ${e.message}", e)
                }
            } else {
                logger.warn("Polymarket 连接未就绪，消息将被丢弃: ${session.id}")
            }
        } else {
            logger.warn("Polymarket 连接不存在: ${session.id}")
        }
    }
    
    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        logger.error("WebSocket 传输错误: ${session.id}, ${exception.message}", exception)
        cleanup(session.id)
    }
    
    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        cleanup(session.id)
    }
    
    override fun supportsPartialMessages(): Boolean {
        return false
    }
    
    /**
     * 转发消息给客户端
     */
    private fun forwardToClient(sessionId: String, message: String) {
        val session = clientSessions[sessionId]
        if (session != null && session.isOpen) {
            try {
                session.sendMessage(TextMessage(message))
            } catch (e: Exception) {
                logger.error("转发消息给客户端失败: ${sessionId}, ${e.message}", e)
            }
        } else {
            logger.warn("客户端会话不存在或已关闭: $sessionId")
        }
    }
    
    /**
     * 清理资源
     */
    private fun cleanup(sessionId: String) {
        try {
            // 关闭 Polymarket 连接
            val polymarketClient = polymarketConnections.remove(sessionId)
            if (polymarketClient != null) {
                try {
                    if (polymarketClient.isConnected()) {
                        polymarketClient.closeConnection()
                    }
                } catch (e: Exception) {
                    logger.error("关闭 Polymarket 连接失败: ${sessionId}, ${e.message}", e)
                }
            }
            
            // 移除客户端会话
            clientSessions.remove(sessionId)
        } catch (e: Exception) {
            logger.error("清理资源时发生错误: ${sessionId}, ${e.message}", e)
        }
    }
}

