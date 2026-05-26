package com.wrbug.polymarketbot.service.whalemonitor

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.WhaleMonitorStrategy
import com.wrbug.polymarketbot.entity.WhaleMonitorTrigger
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.WhaleMonitorTriggerRepository
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class WhaleMonitorOrderExecutionService(
    private val accountRepository: AccountRepository,
    private val triggerRepository: WhaleMonitorTriggerRepository,
    private val clobService: PolymarketClobService,
    private val orderSigningService: OrderSigningService,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils
) {

    private val logger = LoggerFactory.getLogger(WhaleMonitorOrderExecutionService::class.java)

    private val mutexMap = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun executeOrder(
        strategy: WhaleMonitorStrategy,
        conditionId: String,
        tokenId: String,
        triggerVolume: BigDecimal
    ) {
        val mutexKey = "${strategy.id}-$tokenId"
        val mutex = mutexMap.computeIfAbsent(mutexKey) { Mutex() }
        mutex.withLock {
            try {
                doExecuteOrder(strategy, conditionId, tokenId, triggerVolume)
            } catch (e: Exception) {
                logger.error("大单监听下单执行失败 strategyId=${strategy.id} tokenId=$tokenId: ${e.message}", e)
                saveTriggerRecord(
                    strategy = strategy,
                    conditionId = conditionId,
                    tokenId = tokenId,
                    triggerVolume = triggerVolume,
                    orderPrice = BigDecimal.ZERO,
                    orderSize = BigDecimal.ZERO,
                    orderAmount = BigDecimal.ZERO,
                    orderId = null,
                    status = "fail",
                    failReason = e.message?.take(500)
                )
            }
        }
    }

    private suspend fun doExecuteOrder(
        strategy: WhaleMonitorStrategy,
        conditionId: String,
        tokenId: String,
        triggerVolume: BigDecimal
    ) {
        val account = accountRepository.findById(strategy.accountId).orElse(null)
        if (account == null) {
            logger.error("大单监听: 账户不存在 accountId=${strategy.accountId}")
            return
        }
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank() || account.apiPassphrase.isNullOrBlank()) {
            logger.error("大单监听: 账户 API 凭证未配置 accountId=${strategy.accountId}")
            return
        }

        val orderbookResult = clobService.getOrderbookByTokenId(tokenId)
        if (orderbookResult.isFailure) {
            logger.error("大单监听: 获取订单簿失败 tokenId=$tokenId")
            return
        }
        val orderbook = orderbookResult.getOrThrow()
        val asks = orderbook.asks
        if (asks.isNullOrEmpty()) {
            logger.info("大单监听: 订单簿无卖单，跳过 tokenId=$tokenId")
            return
        }
        val bestAsk = asks.minOfOrNull { it.price.toSafeBigDecimal() } ?: return

        if (bestAsk < strategy.minPrice || bestAsk > strategy.maxPrice) {
            logger.info("大单监听: 价格 $bestAsk 不在区间 [${strategy.minPrice}, ${strategy.maxPrice}]，跳过 tokenId=$tokenId")
            saveTriggerRecord(
                strategy = strategy,
                conditionId = conditionId,
                tokenId = tokenId,
                triggerVolume = triggerVolume,
                orderPrice = bestAsk,
                orderSize = BigDecimal.ZERO,
                orderAmount = BigDecimal.ZERO,
                orderId = null,
                status = "fail",
                failReason = "价格 $bestAsk 不在区间 [${strategy.minPrice}, ${strategy.maxPrice}]"
            )
            return
        }

        val orderAmount = strategy.orderAmount
        val orderSize = orderAmount.divide(bestAsk, 0, RoundingMode.UP).coerceAtLeast(BigDecimal.ONE)

        val decryptedPrivateKey = cryptoUtils.decrypt(account.privateKey)
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = decryptedPrivateKey,
            makerAddress = account.proxyAddress,
            tokenId = tokenId,
            side = "BUY",
            price = bestAsk.toPlainString(),
            size = orderSize.toPlainString(),
            signatureType = signatureType
        )

        val newOrderRequest = NewOrderRequest(
            order = signedOrder,
            owner = account.apiKey,
            orderType = "FAK"
        )

        val apiSecret = cryptoUtils.decrypt(account.apiSecret)
        val apiPassphrase = cryptoUtils.decrypt(account.apiPassphrase)
        val clobApi = retrofitFactory.createClobApi(
            account.apiKey, apiSecret, apiPassphrase, account.walletAddress
        )

        val response = clobApi.createOrder(newOrderRequest)
        if (response.isSuccessful) {
            val body = response.body()
            val orderId = body?.orderId
            logger.info("大单监听: 下单成功 strategyId=${strategy.id} tokenId=$tokenId orderId=$orderId price=$bestAsk size=$orderSize")
            saveTriggerRecord(
                strategy = strategy,
                conditionId = conditionId,
                tokenId = tokenId,
                triggerVolume = triggerVolume,
                orderPrice = bestAsk,
                orderSize = orderSize,
                orderAmount = orderAmount,
                orderId = orderId,
                status = "success",
                failReason = null
            )
        } else {
            val errorBody = response.errorBody()?.string()?.take(200)
            logger.error("大单监听: 下单失败 strategyId=${strategy.id} tokenId=$tokenId code=${response.code()} body=$errorBody")
            saveTriggerRecord(
                strategy = strategy,
                conditionId = conditionId,
                tokenId = tokenId,
                triggerVolume = triggerVolume,
                orderPrice = bestAsk,
                orderSize = orderSize,
                orderAmount = orderAmount,
                orderId = null,
                status = "fail",
                failReason = "下单失败: ${response.code()} $errorBody"
            )
        }
    }

    private fun saveTriggerRecord(
        strategy: WhaleMonitorStrategy,
        conditionId: String,
        tokenId: String,
        triggerVolume: BigDecimal,
        orderPrice: BigDecimal,
        orderSize: BigDecimal,
        orderAmount: BigDecimal,
        orderId: String?,
        status: String,
        failReason: String?
    ) {
        try {
            val trigger = WhaleMonitorTrigger(
                strategyId = strategy.id ?: return,
                conditionId = conditionId,
                tokenId = tokenId,
                side = "BUY",
                triggerVolume = triggerVolume,
                orderPrice = orderPrice,
                orderSize = orderSize,
                orderAmount = orderAmount,
                orderId = orderId,
                status = status,
                failReason = failReason
            )
            triggerRepository.save(trigger)
        } catch (e: Exception) {
            logger.error("大单监听: 保存触发记录失败: ${e.message}", e)
        }
    }
}
