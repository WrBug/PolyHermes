package com.wrbug.polymarketbot.service.sportstail

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.SportsTailStrategy
import com.wrbug.polymarketbot.entity.SportsTailStrategyTrigger
import com.wrbug.polymarketbot.event.SportsTailStrategyChangedEvent
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.SportsTailStrategyRepository
import com.wrbug.polymarketbot.repository.SportsTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

private const val SIZE_DECIMAL_SCALE = 2

/**
 * 体育尾盘策略执行服务：根据价格触发执行买入/卖出，并更新策略与触发记录。
 */
@Service
class SportsTailStrategyExecutionService(
    private val strategyRepository: SportsTailStrategyRepository,
    private val triggerRepository: SportsTailStrategyTriggerRepository,
    private val accountRepository: AccountRepository,
    private val accountService: AccountService,
    private val retrofitFactory: RetrofitFactory,
    private val clobService: PolymarketClobService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(SportsTailStrategyExecutionService::class.java)

    private val buyMutexMap = ConcurrentHashMap<Long, Mutex>()

    private fun buyMutex(strategyId: Long): Mutex =
        buyMutexMap.getOrPut(strategyId) { Mutex() }

    /**
     * 执行买入：市价买入指定方向，写入触发记录并更新策略为已成交。
     */
    @Transactional
    suspend fun executeBuy(
        strategy: SportsTailStrategy,
        outcomeIndex: Int,
        triggerPrice: BigDecimal
    ): Result<Unit> {
        if (strategy.filled) return Result.failure(IllegalStateException("策略已成交"))
        val tokenId = if (outcomeIndex == 0) strategy.yesTokenId else strategy.noTokenId
        if (tokenId.isNullOrBlank()) return Result.failure(IllegalStateException("Token ID 为空"))

        return buyMutex(strategy.id!!).withLock {
            val latest = strategyRepository.findById(strategy.id!!).orElse(null)
                ?: return@withLock Result.failure(IllegalStateException("策略不存在"))
            if (latest.filled) return@withLock Result.success(Unit)

            val account = accountRepository.findById(latest.accountId).orElse(null)
                ?: return@withLock Result.failure(IllegalStateException("账户不存在"))
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return@withLock Result.failure(IllegalStateException("账户未配置 API 凭证"))
            }

            val decryptedKey = try {
                cryptoUtils.decrypt(account.privateKey) ?: return@withLock Result.failure(IllegalStateException("解密私钥失败"))
            } catch (e: Exception) {
                logger.error("解密私钥失败: accountId=${account.id}", e)
                return@withLock Result.failure(e)
            }
            val apiSecret = try { cryptoUtils.decrypt(account.apiSecret) ?: "" } catch (e: Exception) { "" }
            val apiPassphrase = try { cryptoUtils.decrypt(account.apiPassphrase) ?: "" } catch (e: Exception) { "" }

            val amountUsdc = when (latest.amountMode.uppercase()) {
                "RATIO" -> {
                    val balanceResult = accountService.getAccountBalance(account.id!!)
                    val available = balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    available.multiply(latest.amountValue).div(BigDecimal("100"), 18, RoundingMode.DOWN)
                }
                else -> latest.amountValue
            }
            if (amountUsdc < BigDecimal("1")) {
                saveTriggerOnBuyFail(latest, outcomeIndex, triggerPrice, amountUsdc, "投入金额不足")
                return@withLock Result.failure(IllegalStateException("投入金额不足"))
            }

            val priceStr = triggerPrice.setScale(2, RoundingMode.HALF_UP).toPlainString()
            val size = amountUsdc.div(triggerPrice, SIZE_DECIMAL_SCALE, RoundingMode.UP).max(BigDecimal.ONE)
            val sizeStr = size.toPlainString()

            val clobApi = retrofitFactory.createClobApi(account.apiKey!!, apiSecret, apiPassphrase, account.walletAddress)
            val feeRateBps = clobService.getFeeRate(tokenId).getOrNull()?.toString() ?: "0"
            val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

            val signedOrder = orderSigningService.createAndSignOrder(
                privateKey = decryptedKey,
                makerAddress = account.proxyAddress,
                tokenId = tokenId,
                side = "BUY",
                price = priceStr,
                size = sizeStr,
                signatureType = signatureType,
                nonce = "0",
                feeRateBps = feeRateBps,
                expiration = "0"
            )
            val orderRequest = NewOrderRequest(
                order = signedOrder,
                owner = account.apiKey!!,
                orderType = "FAK",
                deferExec = false
            )

            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.orderId != null) {
                    val outcomeName = if (outcomeIndex == 0) "Yes" else "No"
                    triggerRepository.save(
                        SportsTailStrategyTrigger(
                            strategyId = latest.id!!,
                            accountId = latest.accountId,
                            conditionId = latest.conditionId,
                            marketTitle = latest.marketTitle,
                            buyPrice = triggerPrice,
                            outcomeIndex = outcomeIndex,
                            outcomeName = outcomeName,
                            buyAmount = amountUsdc,
                            buyShares = size,
                            buyOrderId = body.orderId,
                            buyStatus = "SUCCESS",
                            triggeredAt = System.currentTimeMillis()
                        )
                    )
                    strategyRepository.save(
                        latest.copy(
                            filled = true,
                            filledPrice = triggerPrice,
                            filledOutcomeIndex = outcomeIndex,
                            filledOutcomeName = outcomeName,
                            filledAmount = amountUsdc,
                            filledShares = size,
                            filledAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    eventPublisher.publishEvent(SportsTailStrategyChangedEvent(this))
                    logger.info("体育尾盘策略买入成功: strategyId=${latest.id}, outcomeIndex=$outcomeIndex, orderId=${body.orderId}")
                    return@withLock Result.success(Unit)
                }
            }
            val failReason = response.body()?.getErrorMessage() ?: response.errorBody()?.string() ?: "下单失败"
            saveTriggerOnBuyFail(latest, outcomeIndex, triggerPrice, amountUsdc, failReason)
            logger.error("体育尾盘策略买入失败: strategyId=${latest.id}, reason=$failReason")
            Result.failure(IllegalStateException(failReason))
        }
    }

    private fun saveTriggerOnBuyFail(
        strategy: SportsTailStrategy,
        outcomeIndex: Int,
        buyPrice: BigDecimal,
        buyAmount: BigDecimal,
        failReason: String
    ) {
        val outcomeName = if (outcomeIndex == 0) "Yes" else "No"
        triggerRepository.save(
            SportsTailStrategyTrigger(
                strategyId = strategy.id!!,
                accountId = strategy.accountId,
                conditionId = strategy.conditionId,
                marketTitle = strategy.marketTitle,
                buyPrice = buyPrice,
                outcomeIndex = outcomeIndex,
                outcomeName = outcomeName,
                buyAmount = buyAmount,
                buyStatus = "FAIL",
                buyFailReason = failReason,
                triggeredAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 执行卖出：按当前价市价卖出持仓，更新策略与触发记录。
     */
    @Transactional
    suspend fun executeSell(
        strategy: SportsTailStrategy,
        sellType: String,
        currentPrice: BigDecimal
    ): Result<Unit> {
        if (!strategy.filled || strategy.sold) return Result.failure(IllegalStateException("策略未成交或已卖出"))
        val outcomeIndex = strategy.filledOutcomeIndex ?: return Result.failure(IllegalStateException("无成交方向"))
        val tokenId = if (outcomeIndex == 0) strategy.yesTokenId else strategy.noTokenId
        val filledShares = strategy.filledShares ?: return Result.failure(IllegalStateException("无成交份额"))
        if (tokenId.isNullOrBlank()) return Result.failure(IllegalStateException("Token ID 为空"))

        val account = accountRepository.findById(strategy.accountId).orElse(null)
            ?: return Result.failure(IllegalStateException("账户不存在"))
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            return Result.failure(IllegalStateException("账户未配置 API 凭证"))
        }

        val decryptedKey = try {
            cryptoUtils.decrypt(account.privateKey) ?: return Result.failure(IllegalStateException("解密私钥失败"))
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            return Result.failure(e)
        }
        val apiSecret = try { cryptoUtils.decrypt(account.apiSecret) ?: "" } catch (e: Exception) { "" }
        val apiPassphrase = try { cryptoUtils.decrypt(account.apiPassphrase) ?: "" } catch (e: Exception) { "" }

        val priceStr = currentPrice.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val sizeStr = filledShares.setScale(SIZE_DECIMAL_SCALE, RoundingMode.DOWN).toPlainString()

        val clobApi = retrofitFactory.createClobApi(account.apiKey!!, apiSecret, apiPassphrase, account.walletAddress)
        val feeRateBps = clobService.getFeeRate(tokenId).getOrNull()?.toString() ?: "0"
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = decryptedKey,
            makerAddress = account.proxyAddress,
            tokenId = tokenId,
            side = "SELL",
            price = priceStr,
            size = sizeStr,
            signatureType = signatureType,
            nonce = "0",
            feeRateBps = feeRateBps,
            expiration = "0"
        )
        val orderRequest = NewOrderRequest(
            order = signedOrder,
            owner = account.apiKey!!,
            orderType = "FAK",
            deferExec = false
        )

        val response = clobApi.createOrder(orderRequest)
        val filledAmount = strategy.filledAmount ?: BigDecimal.ZERO
        if (response.isSuccessful && response.body() != null) {
            val body = response.body()!!
            if (body.success && body.orderId != null) {
                val sellAmount = currentPrice.multiply(filledShares).setScale(2, RoundingMode.HALF_UP)
                val pnl = sellAmount.subtract(filledAmount)

                strategyRepository.save(
                    strategy.copy(
                        sold = true,
                        sellPrice = currentPrice,
                        sellType = sellType,
                        sellAmount = sellAmount,
                        realizedPnl = pnl,
                        soldAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                val trigger = triggerRepository.findFirstByStrategyIdAndBuyStatusOrderByTriggeredAtDesc(strategy.id!!, "SUCCESS")
                if (trigger != null) {
                    triggerRepository.save(
                        trigger.copy(
                            sellPrice = currentPrice,
                            sellType = sellType,
                            sellAmount = sellAmount,
                            sellOrderId = body.orderId,
                            sellStatus = "SUCCESS",
                            realizedPnl = pnl,
                            soldAt = System.currentTimeMillis()
                        )
                    )
                }
                eventPublisher.publishEvent(SportsTailStrategyChangedEvent(this))
                logger.info("体育尾盘策略卖出成功: strategyId=${strategy.id}, sellType=$sellType, orderId=${body.orderId}")
                return Result.success(Unit)
            }
        }
        val failReason = response.body()?.getErrorMessage() ?: response.errorBody()?.string() ?: "卖出失败"
        val trigger = triggerRepository.findFirstByStrategyIdAndBuyStatusOrderByTriggeredAtDesc(strategy.id!!, "SUCCESS")
        if (trigger != null) {
            triggerRepository.save(
                trigger.copy(
                    sellStatus = "FAIL",
                    sellFailReason = failReason
                )
            )
        }
        logger.error("体育尾盘策略卖出失败: strategyId=${strategy.id}, reason=$failReason")
        return Result.failure(IllegalStateException(failReason))
    }
}
