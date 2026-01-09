package com.wrbug.polymarketbot.websocket

import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.getProxyConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.slf4j.LoggerFactory

/**
 * Polymarket WebSocket 客户端（使用 OkHttp 实现）
 * 用于连接到 Polymarket RTDS
 * 支持代理配置（从数据库读取）
 */
class PolymarketWebSocketClient(
    private val url: String,
    private val sessionId: String,
    private val onMessage: (String) -> Unit,
    private val onOpen: (() -> Unit)? = null,  // 连接建立后的回调，用于发送订阅消息
    private val onReconnect: (() -> Unit)? = null  // 重连回调，用于重新发送订阅消息
) {
    
    private val logger = LoggerFactory.getLogger(PolymarketWebSocketClient::class.java)
    
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var shouldReconnect = true  // 是否应该自动重连
    private var reconnectDelay = 3000L  // 重连延迟（毫秒），初始 3 秒
    
    private val okHttpClient: OkHttpClient by lazy {
        val proxy = getProxyConfig()
        val builder = createClient()
        
        // 如果启用了代理，配置代理
        if (proxy != null) {
            builder.proxy(proxy)
        }
        
        builder.build()
    }
    
    /**
     * 连接 WebSocket
     */
    fun connect() {
        if (webSocket != null && isConnected) {
            return
        }
        
        try {
            val request = Request.Builder()
                .url(url)
                .build()
            
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    isConnected = true
                    
                    // 重置重连延迟（连接成功后重置为初始值）
                    reconnectDelay = 3000L
                    
                    // 停止重连任务（如果存在）
                    stopReconnect()
                    
                    // 连接建立后立即调用回调（用于发送订阅消息）
                    // 如果是重连，调用 onReconnect；否则调用 onOpen
                    if (reconnectJob != null) {
                        // 这是重连，调用 onReconnect
                        onReconnect?.invoke()
                    } else {
                        // 这是首次连接，调用 onOpen
                        onOpen?.invoke()
                    }
                    
                    // 启动 PING 保活机制（每 10 秒发送一次 PING）
                    startPing()
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    onMessage(text)
                }
                
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(bytes.utf8())
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    stopPing()
                    // 如果不是正常关闭（code != 1000），尝试重连
                    if (code != 1000 && shouldReconnect) {
                        scheduleReconnect()
                    }
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    stopPing()
                    // 如果不是正常关闭（code != 1000），尝试重连
                    if (code != 1000 && shouldReconnect) {
                        scheduleReconnect()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    logger.error("Polymarket WebSocket 错误: $sessionId, ${t.message}", t)
                    if (response != null) {
                        logger.error("响应码: ${response.code}, 响应消息: ${response.message}")
                        try {
                            response.body?.let { body ->
                                val bodyString = body.string()
                                if (bodyString.isNotEmpty()) {
                                    logger.error("响应体: $bodyString")
                                }
                            }
                        } catch (e: Exception) {
                            logger.debug("无法读取响应体: ${e.message}")
                        }
                    }
                    isConnected = false
                    stopPing()
                    // 连接失败，尝试重连
                    if (shouldReconnect) {
                        scheduleReconnect()
                    }
                }
            })
            
        } catch (e: Exception) {
            logger.error("创建 WebSocket 连接失败: $sessionId, ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 启动 PING 保活机制
     * 根据 @polymarket/real-time-data-client，发送小写 "ping"
     * 每 10 秒发送一次
     */
    private fun startPing() {
        stopPing()  // 先停止之前的 PING 任务
        
        pingJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && isConnected) {
                delay(10000)  // 10 秒
                if (isConnected) {
                    try {
                        sendMessage("ping")  // 使用小写，与官方客户端保持一致
                    } catch (e: Exception) {
                        logger.warn("发送 PING 失败: $sessionId, ${e.message}")
                        break
                    }
                }
            }
        }
    }
    
    /**
     * 停止 PING 保活机制
     */
    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }
    
    /**
     * 安排重连
     * 使用指数退避策略：3秒 -> 6秒 -> 12秒 -> 24秒 -> 最大 60 秒
     */
    private fun scheduleReconnect() {
        // 如果已经有重连任务在运行，不重复安排
        if (reconnectJob != null && reconnectJob!!.isActive) {
            return
        }
        
        reconnectJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                delay(reconnectDelay)
                
                // 检查是否应该重连
                if (!shouldReconnect) {
                    return@launch
                }
                
                // 如果已经连接，不需要重连
                if (isConnected) {
                    return@launch
                }
                
                
                // 清理旧的连接
                webSocket = null
                
                // 重新连接
                connect()
                
                // 增加重连延迟（指数退避，最大 60 秒）
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(60000L)
            } catch (e: Exception) {
                logger.error("重连失败: $sessionId, ${e.message}", e)
                // 重连失败，继续安排下一次重连
                scheduleReconnect()
            }
        }
    }
    
    /**
     * 停止重连
     */
    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }
    
    /**
     * 关闭连接
     */
    fun closeConnection() {
        try {
            shouldReconnect = false  // 禁用自动重连
            stopReconnect()  // 停止重连任务
            stopPing()
            webSocket?.close(1000, "正常关闭")
            webSocket = null
            isConnected = false
        } catch (e: Exception) {
            logger.error("关闭连接失败: $sessionId, ${e.message}", e)
        }
    }
    
    /**
     * 发送消息到 Polymarket
     */
    fun sendMessage(message: String) {
        val ws = webSocket
        if (ws != null && isConnected) {
            try {
                val sent = ws.send(message)
                if (!sent) {
                    logger.warn("发送消息失败（连接可能已关闭）: $sessionId")
                    throw IllegalStateException("WebSocket 连接已关闭，无法发送消息")
                }
            } catch (e: Exception) {
                logger.error("发送消息失败: $sessionId, ${e.message}", e)
                throw e
            }
        } else {
            logger.warn("WebSocket 未连接，无法发送消息: $sessionId")
            throw IllegalStateException("WebSocket 未连接")
        }
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean {
        return isConnected && webSocket != null
    }
}

