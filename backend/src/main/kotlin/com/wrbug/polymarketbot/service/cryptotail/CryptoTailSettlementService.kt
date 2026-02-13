package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 尾盘策略结算轮询服务
 * 定时扫描「状态成功但未结算」的触发记录，通过 Gamma 获取 conditionId、链上查询结算结果，计算收益并回写。
 * 收益优先使用 CLOB API 订单详情的实际成交价（price）与成交量（size_matched）计算；API 失败时回退为触发时的 amountUsdc + 固定价 0.99。
 */
@Service
class CryptoTailSettlementService(
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    private val accountRepository: AccountRepository,
    private val retrofitFactory: RetrofitFactory,
    private val blockchainService: BlockchainService,
    private val clobService: PolymarketClobService,
    private val cryptoUtils: CryptoUtils
) {

    private val logger = LoggerFactory.getLogger(CryptoTailSettlementService::class.java)

    private val triggerFixedPrice = BigDecimal("0.99")
    private val pnlScale = 8

    private val settlementScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 跟踪上一轮结算任务的 Job，防止并发执行（与 OrderStatusUpdateService 一致） */
    @Volatile
    private var settlementJob: Job? = null

    /**
     * 定时轮询：每 10 秒执行一次。
     * 若上一轮任务仍在执行则跳过本次，避免并发重叠。
     */
    @Scheduled(fixedDelay = 10_000)
    fun scheduledPollAndSettle() {
        val previousJob = settlementJob
        if (previousJob != null && previousJob.isActive) {
            logger.debug("上一轮尾盘结算任务仍在执行，跳过本次调度")
            return
        }
        settlementJob = settlementScope.launch {
            try {
                doPollAndSettle()
            } catch (e: Exception) {
                logger.error("尾盘策略结算定时任务异常: ${e.message}", e)
            } finally {
                settlementJob = null
            }
        }
    }

    /**
     * 轮询入口：拉取所有 status=success 且 resolved=false 的触发记录，逐条尝试结算并更新。
     * Controller/定时任务调用此方法（内部对 suspend 使用 runBlocking）。
     */
    @Transactional
    fun pollAndSettle(): Int = runBlocking {
        doPollAndSettle()
    }

    private suspend fun doPollAndSettle(): Int {
        val pending = triggerRepository.findByStatusAndResolvedAndOrderIdIsNotNullOrderByCreatedAtAsc("success", false)
        if (pending.isEmpty()) return 0
        var settledCount = 0
        for (trigger in pending) {
            try {
                if (settleOne(trigger)) settledCount++
            } catch (e: Exception) {
                logger.warn("尾盘结算单条失败: triggerId=${trigger.id}, ${e.message}", e)
            }
        }
        if (settledCount > 0) {
            logger.info("尾盘策略结算轮询完成: 处理=${pending.size}, 新结算=$settledCount")
        }
        return settledCount
    }

    /**
     * 处理单条触发记录：解析 conditionId -> 查链上结算 -> 若已结算则计算 pnl 并更新。
     * 通过 copy() 生成新实体再 save，不直接修改原实体；有订单信息时用实际成交价与投入金额更新 triggerPrice、amountUsdc。
     * @return true 表示本条已结算并更新
     */
    private suspend fun settleOne(trigger: CryptoTailStrategyTrigger): Boolean {
        if (trigger.resolved) return false
        val strategy = strategyRepository.findById(trigger.strategyId).orElse(null) ?: return false
        val fill = fetchOrderFill(trigger, strategy)
        val (newTriggerPrice, newAmountUsdc) = if (fill != null && fill.first.gt(BigDecimal.ZERO) && fill.second.gt(BigDecimal.ZERO)) {
            val price = fill.first
            val cost = price.multi(fill.second).setScale(pnlScale, RoundingMode.HALF_UP)
            Pair(price, cost)
        } else {
            Pair(trigger.triggerPrice, trigger.amountUsdc)
        }

        val conditionId = resolveConditionId(strategy, trigger) ?: return false
        val (_, payouts) = blockchainService.getCondition(conditionId).getOrNull() ?: run {
            if (fill != null) {
                val updated = trigger.copy(triggerPrice = newTriggerPrice, amountUsdc = newAmountUsdc)
                triggerRepository.save(updated)
            }
            return false
        }
        if (payouts.isEmpty()) {
            if (fill != null) {
                val updated = trigger.copy(triggerPrice = newTriggerPrice, amountUsdc = newAmountUsdc)
                triggerRepository.save(updated)
            }
            return false
        }
        val winnerIndex = payouts.indexOfFirst { it == java.math.BigInteger.ONE }
        if (winnerIndex < 0) return false

        val won = trigger.outcomeIndex == winnerIndex
        val pnl = computePnlFromApiOrFallback(trigger, strategy, won)
        val now = System.currentTimeMillis()

        val updated = trigger.copy(
            triggerPrice = newTriggerPrice,
            amountUsdc = newAmountUsdc,
            conditionId = conditionId,
            resolved = true,
            winnerOutcomeIndex = winnerIndex,
            realizedPnl = pnl,
            settledAt = now
        )
        triggerRepository.save(updated)
        logger.debug("尾盘结算已更新: triggerId=${trigger.id}, winnerOutcomeIndex=$winnerIndex, won=$won, pnl=$pnl")
        return true
    }

    private suspend fun resolveConditionId(strategy: CryptoTailStrategy, trigger: CryptoTailStrategyTrigger): String? {
        if (!trigger.conditionId.isNullOrBlank()) return trigger.conditionId
        val slug = "${strategy.marketSlugPrefix}-${trigger.periodStartUnix}"
        val event = fetchEventBySlug(slug).getOrNull() ?: return null
        val markets = event.markets ?: return null
        val first = markets.firstOrNull() ?: return null
        return first.conditionId?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchEventBySlug(slug: String): Result<GammaEventBySlugResponse> {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.getEventBySlug(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val msg = if (response.code() == 404) "404" else "code=${response.code()}"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 优先用 CLOB API 订单详情的实际成交价与成交量计算收益；失败则用触发时的 amountUsdc + 固定价 0.99。
     */
    private suspend fun computePnlFromApiOrFallback(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        won: Boolean
    ): BigDecimal {
        val fill = fetchOrderFill(trigger, strategy)
        return if (fill != null) {
            val (price, sizeMatched) = fill
            if (price.gt(BigDecimal.ZERO) && sizeMatched.gt(BigDecimal.ZERO)) {
                computePnlFromFill(price, sizeMatched, won)
            } else {
                computePnlFallback(trigger.amountUsdc, won)
            }
        } else {
            computePnlFallback(trigger.amountUsdc, won)
        }
    }

    /**
     * 通过 CLOB API 获取订单实际成交价与成交量；需 L2 认证（账户 API 凭证）。
     * 只有此接口成功返回有效 price/sizeMatched 时，结算才会更新 triggerPrice、amountUsdc（表现）；
     * 否则只更新结算字段（resolved、realizedPnl 等），表现仍为触发时的值。
     */
    private suspend fun fetchOrderFill(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy
    ): Pair<BigDecimal, BigDecimal>? {
        val orderId = trigger.orderId?.takeIf { it.isNotBlank() } ?: run {
            logger.debug("尾盘结算未拉取订单: orderId 为空, triggerId=${trigger.id}")
            return null
        }
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: run {
            logger.warn("尾盘结算未拉取订单: 账户不存在, triggerId=${trigger.id}, accountId=${strategy.accountId}")
            return null
        }
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("尾盘结算未拉取订单: 账户未配置 API 凭证, triggerId=${trigger.id}, accountId=${account.id}")
            return null
        }
        val apiSecret = try {
            account.apiSecret?.let { cryptoUtils.decrypt(it) } ?: ""
        } catch (e: Exception) {
            logger.debug("解密 apiSecret 失败: accountId=${account.id}", e)
            return null
        }
        val apiPassphrase = try {
            account.apiPassphrase?.let { cryptoUtils.decrypt(it) } ?: ""
        } catch (e: Exception) {
            logger.debug("解密 apiPassphrase 失败: accountId=${account.id}", e)
            return null
        }
        val result = clobService.getOrder(
            orderId = orderId,
            apiKey = account.apiKey!!,
            apiSecret = apiSecret,
            apiPassphrase = apiPassphrase,
            walletAddress = account.walletAddress
        )
        return result.fold(
            onSuccess = { order ->
                val price = order.price.toSafeBigDecimal()
                val sizeMatched = order.sizeMatched.toSafeBigDecimal()
                if (price.gt(BigDecimal.ZERO) && sizeMatched.gt(BigDecimal.ZERO)) {
                    Pair(price, sizeMatched)
                } else {
                    logger.debug("尾盘结算订单无有效成交: triggerId=${trigger.id}, orderId=$orderId, price=$price, sizeMatched=$sizeMatched")
                    null
                }
            },
            onFailure = { e ->
                logger.warn("尾盘结算拉取历史订单失败，触发价/投入金额不会更新: triggerId=${trigger.id}, orderId=$orderId, error=${e.message}")
                null
            }
        )
    }

    /**
     * 按实际成交价与成交量计算收益：成本 = sizeMatched * price；赢则赎回 sizeMatched * 1，输则 0。
     */
    private fun computePnlFromFill(price: BigDecimal, sizeMatched: BigDecimal, won: Boolean): BigDecimal {
        val cost = sizeMatched.multi(price).setScale(pnlScale, RoundingMode.HALF_UP)
        return if (won) {
            sizeMatched.subtract(cost).setScale(pnlScale, RoundingMode.HALF_UP)
        } else {
            cost.negate()
        }
    }

    /**
     * 回退收益计算：无 API 数据时用触发时的 amountUsdc 与固定价 0.99。
     * 赢: pnl = amountUsdc/0.99 - amountUsdc；输: pnl = -amountUsdc
     */
    private fun computePnlFallback(amountUsdc: BigDecimal, won: Boolean): BigDecimal {
        return if (won) {
            amountUsdc.divide(triggerFixedPrice, pnlScale, RoundingMode.HALF_UP).subtract(amountUsdc)
        } else {
            amountUsdc.negate()
        }
    }
}
