package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.event.CryptoTailStrategyChangedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 尾盘策略：策略创建/更新/启用后立即触发一轮检查（由 WebSocket 订单簿持续监听，此处仅做创建/更新后的一次补充）。
 */
@Component
class CryptoTailStrategyScheduler(
    private val executionService: CryptoTailStrategyExecutionService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyScheduler::class.java)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @EventListener
    fun onStrategyChanged(event: CryptoTailStrategyChangedEvent) {
        scope.launch {
            try {
                runBlocking {
                    executionService.runCycle()
                }
            } catch (e: Exception) {
                logger.error("尾盘策略变更后立即执行异常: ${e.message}", e)
            }
        }
    }
}
