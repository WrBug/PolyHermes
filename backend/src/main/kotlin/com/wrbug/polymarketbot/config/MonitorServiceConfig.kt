package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.service.common.WebSocketSubscriptionService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailMonitorService
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration

/**
 * 尾盘监控服务配置
 * 处理 WebSocketSubscriptionService 和 CryptoTailMonitorService 之间的循环依赖
 */
@Configuration
class MonitorServiceConfig(
    private val webSocketSubscriptionService: WebSocketSubscriptionService,
    private val cryptoTailMonitorService: CryptoTailMonitorService
) {
    
    @PostConstruct
    fun init() {
        // 在所有 Bean 初始化后设置引用
        webSocketSubscriptionService.setCryptoTailMonitorService(cryptoTailMonitorService)
    }
}
