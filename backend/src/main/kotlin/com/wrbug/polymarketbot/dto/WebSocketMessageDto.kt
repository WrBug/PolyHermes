package com.wrbug.polymarketbot.dto

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
        /**
         * 根据 int 值获取枚举
         */
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
    val payload: Any? = null,  // 可以是 PositionPushMessage 或其他类型
    val timestamp: Long? = null,
    val status: Int? = null,  // 0: success, 非0: error
    val message: String? = null   // 错误信息
)

