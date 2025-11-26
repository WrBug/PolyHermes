# WebSocket 推送服务重构设计文档

## 1. 概述

### 1.1 设计目标

- **统一连接**：所有推送服务共用一个 WebSocket 连接（`/ws`）
- **多频道支持**：支持多个推送频道（position、order 等）
- **订阅管理**：前端统一管理订阅，避免重复订阅
- **数据分发**：后端根据频道推送，前端分发到订阅者
- **可扩展性**：易于添加新的推送频道

### 1.2 架构设计

```
前端应用
  ↓
全局 WebSocket 连接 (/ws)
  ↓
订阅管理器 (统一管理订阅)
  ↓
频道分发器 (根据 channel 分发数据)
  ↓
订阅者 (组件/页面)
```

## 2. 消息协议

### 2.1 客户端 → 服务端

#### 订阅频道
```json
{
  "type": 1,
  "channel": "position",
  "payload": {}  // 可选，根据频道自定义参数
}
```

#### 取消订阅
```json
{
  "type": 2,
  "channel": "position"
}
```

#### 心跳保活
```json
"PING"
```

### 2.2 服务端 → 客户端

#### 数据推送
```json
{
  "type": 3,
  "channel": "position",
  "payload": {
    // 频道数据，根据频道类型定义
  },
  "timestamp": 1234567890
}
```

#### 订阅确认
```json
{
  "type": 4,
  "channel": "position",
  "status": 0,  // 0: success, 非0: error
  "message": ""  // 错误时提供错误信息
}
```

#### 心跳响应
```json
"PONG"
```

### 2.3 消息类型定义

**WebSocketMessageType 枚举值**：
- `1`: SUB - 订阅
- `2`: UNSUB - 取消订阅
- `3`: DATA - 数据推送
- `4`: SUB_ACK - 订阅确认
- `5`: PING - 心跳
- `6`: PONG - 心跳响应

**status 字段**：
- `0`: 成功
- `非0`: 错误（具体错误码可自定义）

## 3. 频道定义

### 3.1 仓位推送频道 (position)

#### 订阅参数
```json
{
  "type": "sub",
  "channel": "position",
  "payload": {}  // 当前无需参数，后续可扩展（如账户筛选等）
}
```

#### 推送数据格式
```json
{
  "type": "data",
  "channel": "position",
  "payload": {
    "messageType": "FULL",  // FULL | INCREMENTAL
    "currentPositions": [],
    "historyPositions": [],
    "removedPositionKeys": []
  },
  "timestamp": 1234567890
}
```

### 3.2 订单推送频道 (order)

#### 订阅参数
```json
{
  "type": "sub",
  "channel": "order",
  "payload": {
    "accountId": 123,  // 可选，筛选特定账户
    "status": "active"  // 可选，筛选订单状态
  }
}
```

#### 推送数据格式
```json
{
  "type": "data",
  "channel": "order",
  "payload": {
    "messageType": "FULL",  // FULL | INCREMENTAL
    "orders": [],
    "removedOrderIds": []
  },
  "timestamp": 1234567890
}
```

## 4. 前端实现方案

### 4.1 全局 WebSocket 管理器

**文件**: `frontend/src/services/websocket.ts`

```typescript
/**
 * WebSocket 消息类型（int 值）
 */
export enum WebSocketMessageType {
  SUB = 1,        // 订阅
  UNSUB = 2,      // 取消订阅
  DATA = 3,       // 数据推送
  SUB_ACK = 4,    // 订阅确认
  PING = 5,       // 心跳
  PONG = 6        // 心跳响应
}

/**
 * WebSocket 消息
 */
export interface WebSocketMessage {
  type: number  // WebSocketMessageType 的 int 值（1:SUB, 2:UNSUB, 3:DATA, 4:SUB_ACK, 5:PING, 6:PONG）
  channel?: string
  payload?: any
  timestamp?: number
  status?: number  // 0: success, 非0: error
  message?: string
}

/**
 * 订阅回调函数
 */
export type SubscriptionCallback = (data: any) => void

/**
 * 全局 WebSocket 管理器
 */
class WebSocketManager {
  private ws: WebSocket | null = null
  private reconnectTimer: NodeJS.Timeout | null = null
  private pingInterval: NodeJS.Timeout | null = null
  private isConnecting = false
  private isUnmounting = false
  
  // 订阅管理：channel -> Set<callback>
  private subscriptions = new Map<string, Set<SubscriptionCallback>>()
  
  // 订阅状态：channel -> boolean（是否已向后端订阅）
  private subscribedChannels = new Set<string>()
  
  // 连接状态回调
  private connectionCallbacks: Set<(connected: boolean) => void> = new Set()
  
  private reconnectDelay = 3000
  private pingIntervalTime = 30000
  
  /**
   * 连接 WebSocket
   */
  connect(): void {
    if (this.ws?.readyState === WebSocket.OPEN || this.isConnecting) {
      return
    }
    
    if (this.isUnmounting) {
      return
    }
    
    this.isConnecting = true
    const wsUrl = this.getWebSocketUrl()
    console.log('正在连接 WebSocket:', wsUrl)
    
    try {
      const ws = new WebSocket(wsUrl)
      this.ws = ws
      
      ws.onopen = () => {
        console.log('WebSocket 连接成功')
        this.isConnecting = false
        this.notifyConnectionStatus(true)
        this.startPing()
        this.resubscribeAll()  // 重新订阅所有频道
      }
      
      ws.onmessage = (event) => {
        this.handleMessage(event.data)
      }
      
      ws.onerror = (error) => {
        console.error('WebSocket 错误:', error)
        this.isConnecting = false
        this.notifyConnectionStatus(false)
      }
      
      ws.onclose = () => {
        console.log('WebSocket 连接关闭')
        this.isConnecting = false
        this.notifyConnectionStatus(false)
        this.stopPing()
        this.scheduleReconnect()
      }
    } catch (error) {
      console.error('创建 WebSocket 连接失败:', error)
      this.isConnecting = false
      this.notifyConnectionStatus(false)
      this.scheduleReconnect()
    }
  }
  
  /**
   * 断开连接
   */
  disconnect(): void {
    this.isUnmounting = true
    this.stopPing()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
  }
  
  /**
   * 订阅频道
   */
  subscribe(channel: string, callback: SubscriptionCallback, payload?: any): () => void {
    // 添加订阅者
    if (!this.subscriptions.has(channel)) {
      this.subscriptions.set(channel, new Set())
    }
    this.subscriptions.get(channel)!.add(callback)
    
    // 如果还未向后端订阅，发送订阅消息
    if (!this.subscribedChannels.has(channel)) {
      this.sendSubscribe(channel, payload)
    }
    
    // 返回取消订阅函数
    return () => {
      this.unsubscribe(channel, callback)
    }
  }
  
  /**
   * 取消订阅
   */
  unsubscribe(channel: string, callback: SubscriptionCallback): void {
    const callbacks = this.subscriptions.get(channel)
    if (callbacks) {
      callbacks.delete(callback)
      
      // 如果没有订阅者了，向后端取消订阅
      if (callbacks.size === 0) {
        this.subscriptions.delete(channel)
        this.sendUnsubscribe(channel)
        this.subscribedChannels.delete(channel)
      }
    }
  }
  
  /**
   * 发送订阅消息
   */
  private sendSubscribe(channel: string, payload?: any): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      const message: WebSocketMessage = {
        type: 'sub',
        channel,
        payload
      }
      this.ws.send(JSON.stringify(message))
      this.subscribedChannels.add(channel)
      console.log('已订阅频道:', channel)
    }
  }
  
  /**
   * 发送取消订阅消息
   */
  private sendUnsubscribe(channel: string): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      const message: WebSocketMessage = {
        type: 'unsub',
        channel
      }
      this.ws.send(JSON.stringify(message))
      console.log('已取消订阅频道:', channel)
    }
  }
  
  /**
   * 处理收到的消息
   */
  private handleMessage(data: string): void {
    // 处理心跳
    if (data === 'PONG') {
      return
    }
    
    try {
      const message: WebSocketMessage = JSON.parse(data)
      
      if (message.type === WebSocketMessageType.DATA && message.channel) {
        // 数据推送：分发到订阅者
        const callbacks = this.subscriptions.get(message.channel)
        if (callbacks) {
          callbacks.forEach(callback => {
            try {
              callback(message.payload)
            } catch (error) {
              console.error(`频道 ${message.channel} 回调执行失败:`, error)
            }
          })
        }
      } else if (message.type === WebSocketMessageType.SUB_ACK) {
        // 订阅确认
        if (message.status !== undefined && message.status !== 0) {
          console.error(`订阅频道 ${message.channel} 失败:`, message.message)
          this.subscribedChannels.delete(message.channel || '')
        }
      }
    } catch (error) {
      console.error('解析 WebSocket 消息失败:', error)
    }
  }
  
  /**
   * 重新订阅所有频道
   */
  private resubscribeAll(): void {
    this.subscribedChannels.clear()
    this.subscriptions.forEach((callbacks, channel) => {
      if (callbacks.size > 0) {
        this.sendSubscribe(channel)
      }
    })
  }
  
  /**
   * 安排重连
   */
  private scheduleReconnect(): void {
    if (this.isUnmounting) {
      return
    }
    
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
    }
    
    this.reconnectTimer = setTimeout(() => {
      this.connect()
    }, this.reconnectDelay)
  }
  
  /**
   * 开始心跳
   */
  private startPing(): void {
    this.stopPing()
    this.pingInterval = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send('PING')
      }
    }, this.pingIntervalTime)
  }
  
  /**
   * 停止心跳
   */
  private stopPing(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval)
      this.pingInterval = null
    }
  }
  
  /**
   * 获取 WebSocket URL
   */
  private getWebSocketUrl(): string {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    return `${protocol}//${host}/ws`
  }
  
  /**
   * 注册连接状态回调
   */
  onConnectionChange(callback: (connected: boolean) => void): () => void {
    this.connectionCallbacks.add(callback)
    return () => {
      this.connectionCallbacks.delete(callback)
    }
  }
  
  /**
   * 通知连接状态变化
   */
  private notifyConnectionStatus(connected: boolean): void {
    this.connectionCallbacks.forEach(callback => {
      try {
        callback(connected)
      } catch (error) {
        console.error('连接状态回调执行失败:', error)
      }
    })
  }
  
  /**
   * 获取连接状态
   */
  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }
}

// 导出单例
export const wsManager = new WebSocketManager()
```

### 4.2 React Hook 封装

**文件**: `frontend/src/hooks/useWebSocket.ts`

```typescript
import { useEffect, useState, useRef } from 'react'
import { wsManager, SubscriptionCallback } from '../services/websocket'

/**
 * 使用 WebSocket 订阅
 */
export function useWebSocketSubscription<T = any>(
  channel: string,
  callback: SubscriptionCallback,
  payload?: any
): { connected: boolean } {
  const [connected, setConnected] = useState(wsManager.isConnected())
  const callbackRef = useRef(callback)
  
  // 更新回调引用
  useEffect(() => {
    callbackRef.current = callback
  }, [callback])
  
  useEffect(() => {
    // 确保连接
    if (!wsManager.isConnected()) {
      wsManager.connect()
    }
    
    // 订阅频道
    const unsubscribe = wsManager.subscribe(channel, (data) => {
      callbackRef.current(data)
    }, payload)
    
    // 监听连接状态
    const removeConnectionListener = wsManager.onConnectionChange(setConnected)
    
    return () => {
      unsubscribe()
      removeConnectionListener()
    }
  }, [channel, payload])
  
  return { connected }
}
```

### 4.3 使用示例

**文件**: `frontend/src/pages/PositionList.tsx`

```typescript
import { useWebSocketSubscription } from '../hooks/useWebSocket'
import type { PositionPushMessage } from '../types'

const PositionList: React.FC = () => {
  // 订阅仓位推送
  const { connected } = useWebSocketSubscription<PositionPushMessage>(
    'position',
    (message) => {
      if (message.messageType === 'FULL') {
        setCurrentPositions(message.currentPositions || [])
        setHistoryPositions(message.historyPositions || [])
        setLoading(false)
      } else if (message.messageType === 'INCREMENTAL') {
        setCurrentPositions(prev => mergePositions(prev, message.currentPositions || [], message.removedPositionKeys || []))
        setHistoryPositions(prev => mergePositions(prev, message.historyPositions || [], message.removedPositionKeys || []))
      }
    }
  )
  
  // ... 其他代码
}
```

## 5. 后端实现方案

### 5.1 WebSocket 消息 DTO

**文件**: `backend/src/main/kotlin/com/wrbug/polymarketbot/dto/WebSocketMessageDto.kt`

```kotlin
package com.wrbug.polymarketbot.dto

/**
 * WebSocket 消息类型
 */
enum class WebSocketMessageType {
    SUB,        // 订阅
    UNSUB,      // 取消订阅
    DATA,       // 数据推送
    SUB_ACK,    // 订阅确认
    PING,       // 心跳
    PONG        // 心跳响应
}

/**
 * WebSocket 消息类型
 */
enum class WebSocketMessageType(val value: Int) {
    SUB(1),        // 订阅
    UNSUB(2),      // 取消订阅
    DATA(3),       // 数据推送
    SUB_ACK(4),    // 订阅确认
    PING(5),       // 心跳
    PONG(6);       // 心跳响应
    
    companion object {
        fun fromValue(value: Int): WebSocketMessageType? {
            return values().find { it.value == value }
        }
    }
}

/**
 * WebSocket 消息
 */
data class WebSocketMessage(
    val type: Int,  // WebSocketMessageType 的 int 值（1:SUB, 2:UNSUB, 3:DATA, 4:SUB_ACK, 5:PING, 6:PONG）
    val channel: String? = null,
    val payload: Any? = null,
    val timestamp: Long? = null,
    val status: Int? = null,  // 0: success, 非0: error
    val message: String? = null   // 错误信息
)

/**
 * 订阅请求
 */
data class SubscribeRequest(
    val channel: String,
    val payload: Map<String, Any>? = null
)

/**
 * 取消订阅请求
 */
data class UnsubscribeRequest(
    val channel: String
)
```

### 5.2 统一 WebSocket 处理器

**文件**: `backend/src/main/kotlin/com/wrbug/polymarketbot/websocket/UnifiedWebSocketHandler.kt`

```kotlin
package com.wrbug.polymarketbot.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.service.WebSocketSubscriptionService
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
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null
    
    @PostConstruct
    fun init() {
        logger.info("统一 WebSocket 处理器已初始化，心跳超时: ${heartbeatTimeout}ms")
        startCleanupTask()
    }
    
    @PreDestroy
    fun destroy() {
        logger.info("停止统一 WebSocket 处理器")
        cleanupJob?.cancel()
        scope.cancel()
    }
    
    override fun afterConnectionEstablished(session: WebSocketSession) {
        logger.info("WebSocket 客户端连接建立: ${session.id}")
        clientSessions[session.id] = session
        lastActivityTime[session.id] = System.currentTimeMillis()
        
        // 注册会话到订阅服务
        subscriptionService.registerSession(session.id) { message ->
            sendMessageToClient(session.id, message)
        }
    }
    
    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        val payload = message.payload.toString()
        
        // 处理心跳
        if (payload == "PING" || payload == "ping") {
            lastActivityTime[session.id] = System.currentTimeMillis()
            try {
                session.sendMessage(TextMessage("PONG"))
            } catch (e: Exception) {
                logger.error("发送心跳响应失败: ${session.id}, ${e.message}", e)
            }
            return
        }
        
        // 更新活动时间
        lastActivityTime[session.id] = System.currentTimeMillis()
        
        // 解析消息
        try {
            val wsMessage = objectMapper.readValue(payload, WebSocketMessage::class.java)
            handleWebSocketMessage(session.id, wsMessage)
        } catch (e: Exception) {
            logger.error("解析 WebSocket 消息失败: ${session.id}, ${e.message}", e)
        }
    }
    
    /**
     * 处理 WebSocket 消息
     */
    private fun handleWebSocketMessage(sessionId: String, message: WebSocketMessage) {
        val messageType = WebSocketMessageType.fromValue(message.type)
        when (messageType) {
            WebSocketMessageType.SUB -> {
                val channel = message.channel ?: return
                val payload = message.payload as? Map<*, *>
                subscriptionService.subscribe(sessionId, channel, payload)
            }
            WebSocketMessageType.UNSUB -> {
                val channel = message.channel ?: return
                subscriptionService.unsubscribe(sessionId, channel)
            }
            null -> {
                logger.warn("未知的消息类型: ${message.type}")
            }
            else -> {
                logger.warn("不支持的消息类型: $messageType")
            }
        }
    }
    
    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        logger.error("WebSocket 传输错误: ${session.id}, ${exception.message}", exception)
        cleanup(session.id)
    }
    
    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        logger.info("WebSocket 客户端连接关闭: ${session.id}, 状态: $closeStatus")
        cleanup(session.id)
    }
    
    override fun supportsPartialMessages(): Boolean = false
    
    /**
     * 发送消息给客户端
     */
    private fun sendMessageToClient(sessionId: String, message: WebSocketMessage) {
        val session = clientSessions[sessionId]
        if (session != null && session.isOpen) {
            try {
                val json = objectMapper.writeValueAsString(message)
                session.sendMessage(TextMessage(json))
                lastActivityTime[sessionId] = System.currentTimeMillis()
            } catch (e: Exception) {
                logger.error("发送消息失败: $sessionId, ${e.message}", e)
                cleanup(sessionId)
            }
        } else {
            logger.warn("客户端会话不存在或已关闭: $sessionId")
            cleanup(sessionId)
        }
    }
    
    /**
     * 清理资源
     */
    private fun cleanup(sessionId: String) {
        try {
            clientSessions.remove(sessionId)
            lastActivityTime.remove(sessionId)
            subscriptionService.unregisterSession(sessionId)
            
            val session = clientSessions[sessionId]
            if (session != null && session.isOpen) {
                try {
                    session.close(CloseStatus.NORMAL)
                } catch (e: Exception) {
                    logger.debug("关闭会话失败: $sessionId, ${e.message}")
                }
            }
            
            logger.info("已清理 WebSocket 资源: $sessionId")
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
            logger.info("已清理 ${inactiveSessions.size} 个不活跃连接")
        }
    }
}
```

### 5.3 订阅管理服务

**文件**: `backend/src/main/kotlin/com/wrbug/polymarketbot/service/WebSocketSubscriptionService.kt`

```kotlin
package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.dto.WebSocketMessage
import com.wrbug.polymarketbot.dto.WebSocketMessageType
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
    private val orderPushService: OrderPushService  // 后续实现
) {
    
    private val logger = LoggerFactory.getLogger(WebSocketSubscriptionService::class.java)
    
    // 存储会话和对应的推送回调
    private val sessionCallbacks = ConcurrentHashMap<String, (WebSocketMessage) -> Unit>()
    
    // 存储每个会话的订阅频道：sessionId -> Set<channel>
    private val sessionSubscriptions = ConcurrentHashMap<String, MutableSet<String>>()
    
    // 存储每个频道的订阅会话数：channel -> Set<sessionId>
    private val channelSubscriptions = ConcurrentHashMap<String, MutableSet<String>>()
    
    /**
     * 注册会话
     */
    fun registerSession(sessionId: String, callback: (WebSocketMessage) -> Unit) {
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
        
        // 记录订阅关系
        sessionSubscriptions.getOrPut(sessionId) { mutableSetOf() }.add(channel)
        channelSubscriptions.getOrPut(channel) { mutableSetOf() }.add(sessionId)
        
        // 发送订阅确认
        sendSubscribeAck(sessionId, channel, true)
        
        // 根据频道类型启动推送服务
        when (channel) {
            "position" -> {
                positionPushService.subscribe(sessionId) { message ->
                    pushData(sessionId, channel, message)
                }
            }
            "order" -> {
                orderPushService.subscribe(sessionId, payload) { message ->
                    pushData(sessionId, channel, message)
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
        
        // 如果频道没有订阅者了，停止推送服务
        if (channelSubscriptions[channel]?.isEmpty() == true) {
            when (channel) {
                "position" -> positionPushService.unsubscribe(sessionId)
                "order" -> orderPushService.unsubscribe(sessionId)
            }
        }
    }
    
    /**
     * 推送数据到指定会话
     */
    private fun pushData(sessionId: String, channel: String, payload: Any) {
        val callback = sessionCallbacks[sessionId]
        if (callback != null) {
            val message = WebSocketMessage(
                type = WebSocketMessageType.DATA,
                channel = channel,
                payload = payload,
                timestamp = System.currentTimeMillis()
            )
            callback(message)
        }
    }
    
    /**
     * 发送订阅确认
     */
    private fun sendSubscribeAck(sessionId: String, channel: String, success: Boolean, errorMessage: String? = null) {
        val callback = sessionCallbacks[sessionId]
        if (callback != null) {
            val message = WebSocketMessage(
                type = WebSocketMessageType.SUB_ACK.value,
                channel = channel,
                status = if (success) 0 else 1,  // 0: success, 非0: error
                message = errorMessage
            )
            callback(message)
        }
    }
}
```

### 5.4 频道推送服务接口

**文件**: `backend/src/main/kotlin/com/wrbug/polymarketbot/service/PositionPushService.kt`

需要修改 `PositionPushService`，添加订阅接口：

```kotlin
/**
 * 订阅仓位推送
 */
fun subscribe(sessionId: String, callback: (PositionPushMessage) -> Unit) {
    // 注册回调
    clientCallbacks[sessionId] = callback
    
    // 如果是第一个订阅者，启动轮询
    if (clientCallbacks.size == 1) {
        startPolling()
    }
    
    // 立即发送全量数据
    scope.launch {
        sendFullData(sessionId)
    }
}

/**
 * 取消订阅仓位推送
 */
fun unsubscribe(sessionId: String) {
    clientCallbacks.remove(sessionId)
    
    // 如果没有订阅者了，停止轮询
    if (clientCallbacks.isEmpty()) {
        stopPolling()
    }
}
```

## 6. 配置更新

### 6.1 WebSocket 配置

**文件**: `backend/src/main/kotlin/com/wrbug/polymarketbot/config/WebSocketConfig.kt`

```kotlin
override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
    // 统一 WebSocket 端点
    registry.addHandler(unifiedWebSocketHandler, "/ws")
        .setAllowedOrigins("*")
}
```

### 6.2 配置文件

**文件**: `backend/src/main/resources/application.properties`

```properties
# WebSocket 配置
websocket.heartbeat-timeout=${WEBSOCKET_HEARTBEAT_TIMEOUT:60000}
```

## 7. 迁移计划

### 7.1 阶段一：后端重构
1. 创建统一 WebSocket 处理器
2. 创建订阅管理服务
3. 重构 PositionPushService 支持订阅接口
4. 更新 WebSocket 配置

### 7.2 阶段二：前端重构
1. 创建全局 WebSocket 管理器
2. 创建 React Hook
3. 重构 PositionList 页面使用新接口
4. 移除旧的 WebSocket 连接代码

### 7.3 阶段三：测试验证
1. 测试订阅/取消订阅
2. 测试多订阅者场景
3. 测试重连机制
4. 测试数据分发

## 8. 优势

1. **统一管理**：所有推送服务共用一个连接，减少资源消耗
2. **易于扩展**：添加新频道只需实现推送服务接口
3. **避免重复**：前端统一管理，避免重复订阅
4. **数据分发**：后端推送一次，前端分发到多个订阅者
5. **可维护性**：清晰的架构，易于维护和调试

## 9. 注意事项

1. **消息格式**：确保前后端消息格式一致
2. **错误处理**：完善的错误处理和日志记录
3. **性能优化**：大量订阅者时注意性能
4. **向后兼容**：迁移时保持向后兼容
5. **测试覆盖**：充分测试各种场景

