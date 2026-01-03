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
   * 连接 WebSocket（全局共享连接）
   * 使用短期票据认证，避免在 URL 中暴露 JWT
   */
  async connect(): Promise<void> {
    // 检查是否有token，未登录不允许连接
    const token = this.getToken()
    if (!token) {
      console.log('[WebSocket] 未登录，不建立连接')
      return
    }

    // 如果已经连接或正在连接，直接返回
    if (this.ws?.readyState === WebSocket.OPEN || this.isConnecting) {
      return
    }

    // 如果正在卸载，不允许连接
    if (this.isUnmounting) {
      return
    }

    this.isConnecting = true

    try {
      // 获取短期票据
      const wsUrl = await this.getWebSocketUrl()
      console.log('[WebSocket] 正在连接...')

      // 如果已经有连接（但状态不是 OPEN），先关闭
      if (this.ws) {
        try {
          this.ws.close()
        } catch (e) {
          // 忽略关闭错误
        }
        this.ws = null
      }

      const ws = new WebSocket(wsUrl)
      this.ws = ws

      ws.onopen = () => {
        console.log('[WebSocket] 连接成功')
        this.isConnecting = false
        this.notifyConnectionStatus(true)
        this.startPing()
        this.resubscribeAll()  // 重新订阅所有频道
      }

      ws.onmessage = (event) => {
        this.handleMessage(event.data)
      }

      ws.onerror = (error) => {
        console.error('[WebSocket] 连接错误:', error)
        this.isConnecting = false
        this.notifyConnectionStatus(false)
      }

      ws.onclose = () => {
        console.log('[WebSocket] 连接关闭')
        this.isConnecting = false
        this.notifyConnectionStatus(false)
        this.stopPing()
        // 自动重连（除非正在卸载或未登录）
        if (!this.isUnmounting && this.getToken()) {
          this.scheduleReconnect()
        }
      }
    } catch (error) {
      console.error('[WebSocket] 创建连接失败:', error)
      this.isConnecting = false
      this.notifyConnectionStatus(false)
      // 自动重连（除非正在卸载或未登录）
      if (!this.isUnmounting && this.getToken()) {
        this.scheduleReconnect()
      }
    }
  }
  
  /**
   * 断开连接（仅在应用完全卸载时调用）
   */
  disconnect(): void {
    console.log('[WebSocket] 断开连接')
    this.isUnmounting = true
    this.stopPing()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      try {
        this.ws.close()
      } catch (e) {
        // 忽略关闭错误
      }
      this.ws = null
    }
    this.notifyConnectionStatus(false)
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
        type: WebSocketMessageType.SUB,
        channel,
        payload
      }
      this.ws.send(JSON.stringify(message))
      this.subscribedChannels.add(channel)
      console.log('已订阅频道:', channel)
    } else {
      // 如果连接未建立，先连接
      this.connect()
      // 连接建立后会通过 resubscribeAll 自动订阅
    }
  }
  
  /**
   * 发送取消订阅消息
   */
  private sendUnsubscribe(channel: string): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      const message: WebSocketMessage = {
        type: WebSocketMessageType.UNSUB,
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
        } else {
          console.log(`订阅频道 ${message.channel} 成功`)
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
    
    // 检查是否有token，未登录不重连
    if (!this.getToken()) {
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
    
    // 立即发送一次心跳
    const sendPing = () => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send('PING')
        console.log('发送心跳: PING')
      }
    }
    
    sendPing()
    
    // 每30秒发送一次心跳
    this.pingInterval = setInterval(sendPing, this.pingIntervalTime)
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
   * 获取 WebSocket URL（使用短期票据认证）
   * 默认使用相对路径 /ws（通过反向代理转发）
   * 如果设置了 VITE_WS_URL 环境变量，则使用完整 URL（用于跨域场景）
   */
  private async getWebSocketUrl(): Promise<string> {
    const envWsUrl = import.meta.env.VITE_WS_URL
    let wsBaseUrl: string

    if (envWsUrl) {
      // 如果设置了环境变量，使用完整 URL（支持跨域）
      wsBaseUrl = envWsUrl
    } else {
      // 否则使用相对路径（通过反向代理转发）
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const host = window.location.host
      wsBaseUrl = `${protocol}//${host}`
    }

    // 获取短期票据（避免在 URL 中暴露 JWT）
    // 使用动态导入避免循环依赖
    try {
      const { apiService } = await import('./api')
      const response = await apiService.auth.getWebSocketTicket()
      if (response.data.code === 0 && response.data.data?.ticket) {
        return `${wsBaseUrl}/ws?ticket=${encodeURIComponent(response.data.data.ticket)}`
      }
    } catch (error) {
      console.warn('[WebSocket] 获取票据失败，尝试使用 token 认证:', error)
    }

    // 兼容旧方式：如果获取票据失败，回退到使用 token（不推荐）
    const token = this.getToken()
    if (token) {
      return `${wsBaseUrl}/ws?token=${encodeURIComponent(token)}`
    }
    return `${wsBaseUrl}/ws`
  }
  
  /**
   * 获取token（从localStorage）
   */
  private getToken(): string | null {
    return localStorage.getItem('jwt_token')
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

