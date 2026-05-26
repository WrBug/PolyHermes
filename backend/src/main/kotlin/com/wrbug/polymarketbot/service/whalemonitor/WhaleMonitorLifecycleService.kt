package com.wrbug.polymarketbot.service.whalemonitor

import com.wrbug.polymarketbot.event.WhaleMonitorStrategyChangedEvent
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * 大单监听生命周期管理
 * 启动时加载已启用策略、监听策略变更事件重载 WS 配置
 */
@Service
class WhaleMonitorLifecycleService(
    private val strategyService: WhaleMonitorStrategyService,
    private val wsService: WhaleMonitorWsService
) {

    private val logger = LoggerFactory.getLogger(WhaleMonitorLifecycleService::class.java)

    @PostConstruct
    fun init() {
        try {
            val strategies = strategyService.getEnabledStrategies()
            if (strategies.isNotEmpty()) {
                logger.info("大单监听: 加载 ${strategies.size} 个已启用策略")
                wsService.start(strategies)
            } else {
                logger.info("大单监听: 没有已启用的策略")
            }
        } catch (e: Exception) {
            logger.error("大单监听: 初始化失败: ${e.message}", e)
        }
    }

    @EventListener
    fun onStrategyChanged(event: WhaleMonitorStrategyChangedEvent) {
        logger.info("大单监听: 策略变更，重新加载")
        try {
            val strategies = strategyService.getEnabledStrategies()
            if (strategies.isNotEmpty()) {
                wsService.start(strategies)
            } else {
                wsService.stop()
            }
        } catch (e: Exception) {
            logger.error("大单监听: 重载策略失败: ${e.message}", e)
        }
    }

    @PreDestroy
    fun destroy() {
        wsService.stop()
    }
}
