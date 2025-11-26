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
    // 订阅频道（连接已在 App.tsx 中全局建立，这里只需要订阅）
    const unsubscribe = wsManager.subscribe(channel, (data) => {
      callbackRef.current(data)
    }, payload)
    
    // 监听连接状态（连接在 App.tsx 中全局管理，这里只监听状态变化）
    const removeConnectionListener = wsManager.onConnectionChange(setConnected)
    
    // 初始化连接状态
    setConnected(wsManager.isConnected())
    
    return () => {
      unsubscribe()
      removeConnectionListener()
    }
  }, [channel, payload])
  
  return { connected }
}

