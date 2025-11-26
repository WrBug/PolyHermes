package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.websocket.PolymarketWebSocketHandler
import com.wrbug.polymarketbot.websocket.UnifiedWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * WebSocket 配置类
 * 用于配置 WebSocket 端点
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val polymarketWebSocketHandler: PolymarketWebSocketHandler,
    private val unifiedWebSocketHandler: UnifiedWebSocketHandler
) : WebSocketConfigurer {
    
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        // Polymarket RTDS 转发端点（转发外部 Polymarket 实时数据流）
        registry.addHandler(polymarketWebSocketHandler, "/ws/polymarket")
            .setAllowedOrigins("*")  // 生产环境应该配置具体的域名
        
        // 统一 WebSocket 端点（所有推送服务统一使用此路径，通过 channel 区分）
        // 支持的频道：position（仓位推送）、order（订单推送，待实现）等
        registry.addHandler(unifiedWebSocketHandler, "/ws")
            .setAllowedOrigins("*")  // 生产环境应该配置具体的域名
    }
}

