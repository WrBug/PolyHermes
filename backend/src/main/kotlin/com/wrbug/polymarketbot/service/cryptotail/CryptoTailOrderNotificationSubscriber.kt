package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.dto.OrderPushMessage
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 尾盘策略订单 TG 通知订阅者
 * 与跟单订单广播方式一致：通过 OrderPushService.subscribeAllEnabled 订阅订单推送，
 * 收到广播后匹配是否为尾盘订单，若是则发送 TG 通知。
 */
@Service
class CryptoTailOrderNotificationSubscriber(
    private val orderPushService: OrderPushService,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    private val accountRepository: AccountRepository,
    private val telegramNotificationService: TelegramNotificationService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailOrderNotificationSubscriber::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var orderPushCallback: ((OrderPushMessage) -> Unit)? = null

    @PostConstruct
    fun subscribe() {
        val callback: (OrderPushMessage) -> Unit = { message -> onOrderPush(message) }
        orderPushCallback = callback
        orderPushService.subscribeAllEnabled(callback)
        logger.info("尾盘订单 TG 通知已订阅 OrderPushService 广播")
    }

    @PreDestroy
    fun unsubscribe() {
        orderPushCallback?.let { orderPushService.unsubscribeAll(it) }
        orderPushCallback = null
        logger.info("尾盘订单 TG 通知已取消订阅")
    }

    private fun onOrderPush(message: OrderPushMessage) {
        val trigger = triggerRepository.findByOrderId(message.order.id) ?: return
        val strategy = strategyRepository.findById(trigger.strategyId).orElse(null) ?: return
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: return
        val orderTimeMs = message.order.timestamp.toLongOrNull()?.let { ts ->
            if (ts < 1_000_000_000_000L) ts * 1000 else ts
        }
        scope.launch {
            try {
                telegramNotificationService.sendCryptoTailOrderSuccessNotification(
                    orderId = message.order.id,
                    marketTitle = trigger.marketTitle ?: message.orderDetail?.marketName ?: "",
                    marketId = message.order.market,
                    marketSlug = message.orderDetail?.marketSlug,
                    side = message.order.side,
                    outcome = message.order.outcome,
                    price = message.order.price,
                    size = message.order.originalSize,
                    strategyName = strategy.name,
                    accountName = account.accountName,
                    walletAddress = account.walletAddress,
                    orderTime = orderTimeMs
                )
            } catch (e: Exception) {
                logger.warn("尾盘订单 TG 通知失败: orderId=${message.order.id}, ${e.message}", e)
            }
        }
    }
}
