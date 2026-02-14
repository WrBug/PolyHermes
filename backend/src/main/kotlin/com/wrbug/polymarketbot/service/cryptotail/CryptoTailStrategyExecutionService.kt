package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** 尾盘策略固定下单价格（最高价 0.99），不再在触发时拉取最优价 */
private const val TRIGGER_FIXED_PRICE = "0.99"

/** 数量小数位数，与 OrderSigningService 的 roundConfig.size 一致 */
private const val SIZE_DECIMAL_SCALE = 2

/**
 * 周期内预置上下文：账户、解密凭证、费率、签名类型、CLOB 客户端；不含预签订单。
 * 触发时 FIXED/RATIO 均按 outcomeIndex 计算 size 并签名提交。
 */
private data class PeriodContext(
    val strategy: CryptoTailStrategy,
    val periodStartUnix: Long,
    val account: Account,
    val decryptedPrivateKey: String,
    val apiSecretDecrypted: String,
    val apiPassphraseDecrypted: String,
    val clobApi: PolymarketClobApi,
    val feeRateByTokenId: Map<String, String>,
    val signatureType: Int,
    val tokenIds: List<String>,
    val marketTitle: String?
)

/**
 * 尾盘策略执行服务：按周期与时间窗口检查价格并下单，每周期最多触发一次。
 * 周期开始预置账户、解密、费率、签名类型、CLOB 客户端；触发时按 outcomeIndex 计算 size 并签名提交。
 */
@Service
class CryptoTailStrategyExecutionService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val accountRepository: AccountRepository,
    private val accountService: AccountService,
    private val retrofitFactory: RetrofitFactory,
    private val clobService: PolymarketClobService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils,
    private val binanceKlineService: BinanceKlineService,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyExecutionService::class.java)

    /** 按 (strategyId, periodStartUnix) 加锁，避免同一周期被调度器与 WebSocket 等多路并发重复下单 */
    private val triggerMutexMap = ConcurrentHashMap<String, Mutex>()

    private fun triggerLockKey(strategyId: Long, periodStartUnix: Long): String = "$strategyId-$periodStartUnix"

    private fun getTriggerMutex(strategyId: Long, periodStartUnix: Long): Mutex =
        triggerMutexMap.getOrPut(triggerLockKey(strategyId, periodStartUnix)) { Mutex() }

    /** 周期预置上下文缓存：(strategyId-periodStartUnix) -> PeriodContext，过期周期在读取时剔除 */
    private val periodContextCache = ConcurrentHashMap<String, PeriodContext>()

    /** 已打印「首次满足条件」日志的周期：LRU 容量 100，每周期只打一次 */
    private val conditionLoggedCache: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(100)
        .build()

    /**
     * 在周期内首次需要时构建并缓存预置上下文；失败返回 null，触发流程将走完整路径。
     * 预置：账户、解密、费率、签名类型、CLOB 客户端；不预签订单，触发时再签名。
     */
    private suspend fun ensurePeriodContext(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        tokenIds: List<String>,
        marketTitle: String?
    ): PeriodContext? {
        val key = triggerLockKey(strategy.id!!, periodStartUnix)
        periodContextCache[key]?.let { return it }

        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: return null
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) return null

        val decryptedKey = try {
            cryptoUtils.decrypt(account.privateKey) ?: return null
        } catch (e: Exception) {
            logger.warn("尾盘策略周期上下文解密私钥失败: accountId=${account.id}", e)
            return null
        }
        val apiSecret = try {
            account.apiSecret?.let { cryptoUtils.decrypt(it) } ?: ""
        } catch (e: Exception) { "" }
        val apiPassphrase = try {
            account.apiPassphrase?.let { cryptoUtils.decrypt(it) } ?: ""
        } catch (e: Exception) { "" }

        val clobApi = retrofitFactory.createClobApi(account.apiKey!!, apiSecret, apiPassphrase, account.walletAddress)
        val feeRateByTokenId = tokenIds.associate { tokenId ->
            tokenId to (clobService.getFeeRate(tokenId).getOrNull()?.toString() ?: "0")
        }
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        if (strategy.amountMode.uppercase() != "RATIO" && strategy.amountValue < BigDecimal("1")) return null

        val ctx = PeriodContext(
            strategy = strategy,
            periodStartUnix = periodStartUnix,
            account = account,
            decryptedPrivateKey = decryptedKey,
            apiSecretDecrypted = apiSecret,
            apiPassphraseDecrypted = apiPassphrase,
            clobApi = clobApi,
            feeRateByTokenId = feeRateByTokenId,
            signatureType = signatureType,
            tokenIds = tokenIds,
            marketTitle = marketTitle
        )
        periodContextCache[key] = ctx
        return ctx
    }

    /**
     * 按投入金额和价格计算可买张数：size = ceil(amountUsdc/price)，保留小数，至少 1。
     * 与 OrderSigningService 一致使用小数数量，向上取整保证不超过投入金额。
     */
    private fun computeSize(amountUsdc: BigDecimal, price: BigDecimal): String {
        val size = amountUsdc.divide(price, SIZE_DECIMAL_SCALE, RoundingMode.UP).max(BigDecimal.ONE)
        return size.toPlainString()
    }

    private fun getOrInvalidatePeriodContext(strategy: CryptoTailStrategy, periodStartUnix: Long): PeriodContext? {
        val key = triggerLockKey(strategy.id!!, periodStartUnix)
        val nowSeconds = System.currentTimeMillis() / 1000
        val ctx = periodContextCache[key] ?: return null
        if (periodStartUnix + strategy.intervalSeconds <= nowSeconds) {
            periodContextCache.remove(key)
            return null
        }
        return ctx
    }

    /**
     * 由订单簿 WebSocket 触发：当收到某 token 的 bestBid 且满足区间时调用，若本周期未触发则下单。
     */
    suspend fun tryTriggerWithPriceFromWs(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        bestBid: BigDecimal
    ) {
        if (outcomeIndex < 0 || outcomeIndex >= tokenIds.size) return
        if (bestBid < strategy.minPrice || bestBid > strategy.maxPrice) return

        val mutex = getTriggerMutex(strategy.id!!, periodStartUnix)
        mutex.withLock {
            if (triggerRepository.findByStrategyIdAndPeriodStartUnix(strategy.id!!, periodStartUnix) != null) return@withLock
            val logKey = triggerLockKey(strategy.id!!, periodStartUnix)
            if (conditionLoggedCache.getIfPresent(logKey) == null) {
                conditionLoggedCache.put(logKey, periodStartUnix + strategy.intervalSeconds)
                val oc = binanceKlineService.getCurrentOpenClose(strategy.intervalSeconds, periodStartUnix)
                val openPrice = oc?.first?.toPlainString() ?: "-"
                val closePrice = oc?.second?.toPlainString() ?: "-"
                val strategyName = strategy.name?.takeIf { it.isNotBlank() } ?: "尾盘策略-${strategy.marketSlugPrefix}"
                val direction = if (outcomeIndex == 0) "Up" else "Down"
                logger.info(
                    "尾盘策略首次满足条件: strategyName=$strategyName, strategyId=${strategy.id}, " +
                        "openPrice=$openPrice, closePrice=$closePrice, marketPrice=${bestBid.toPlainString()}, " +
                        "direction=$direction, outcomeIndex=$outcomeIndex"
                )
            }
            if (!passMinSpreadCheck(strategy, periodStartUnix, outcomeIndex)) return@withLock
            ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
            placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid)
        }
    }

    private fun passMinSpreadCheck(strategy: CryptoTailStrategy, periodStartUnix: Long, outcomeIndex: Int): Boolean {
        val mode = strategy.minSpreadMode.uppercase()
        if (mode == "NONE") return true
        val oc = binanceKlineService.getCurrentOpenClose(strategy.intervalSeconds, periodStartUnix)
            ?: return false
        val (openP, closeP) = oc
        val spreadAbs = closeP.subtract(openP).abs()
        when (mode) {
            "FIXED" -> {
                val effectiveMinSpread = strategy.minSpreadValue?.takeIf { it > BigDecimal.ZERO }
                if (effectiveMinSpread == null || effectiveMinSpread <= BigDecimal.ZERO) return true
                return spreadAbs >= effectiveMinSpread
            }
            "AUTO" -> {
                val result = computeAutoEffectiveMinSpread(strategy, periodStartUnix, outcomeIndex) ?: return true
                val effectiveMinSpread = result.effectiveMinSpread
                if (effectiveMinSpread <= BigDecimal.ZERO) return true
                return spreadAbs >= effectiveMinSpread
            }
            else -> return true
        }
    }

    /**
     * AUTO 模式：取 100% 基准价差，按窗口内毫秒进度计算动态系数（100%→50%）得到有效最小价差。
     */
    private data class AutoSpreadResult(
        val baseSpread: BigDecimal,
        val coefficient: BigDecimal,
        val effectiveMinSpread: BigDecimal
    )

    private fun computeAutoEffectiveMinSpread(strategy: CryptoTailStrategy, periodStartUnix: Long, outcomeIndex: Int): AutoSpreadResult? {
        val baseSpread = binanceKlineAutoSpreadService.getAutoMinSpreadBase(strategy.intervalSeconds, periodStartUnix, outcomeIndex)
            ?: binanceKlineAutoSpreadService.computeAndCache(strategy.intervalSeconds, periodStartUnix)?.let { if (outcomeIndex == 0) it.first else it.second }
            ?: return null
        if (baseSpread <= BigDecimal.ZERO) return null
        val windowStartMs = (periodStartUnix + strategy.windowStartSeconds) * 1000L
        val windowEndMs = (periodStartUnix + strategy.windowEndSeconds) * 1000L
        val windowLenMs = windowEndMs - windowStartMs
        val coefficient = if (windowLenMs <= 0) {
            BigDecimal.ONE
        } else {
            val nowMs = System.currentTimeMillis()
            val elapsedMs = (nowMs - windowStartMs).toBigDecimal()
            val progress = elapsedMs.div(windowLenMs.toBigDecimal(), 18, RoundingMode.HALF_UP)
                .let { p -> maxOf(BigDecimal.ZERO, minOf(BigDecimal.ONE, p)) }
            BigDecimal.ONE.subtract(progress.multi("0.5"))
        }
        val effectiveMinSpread = baseSpread.multi(coefficient).setScale(8, RoundingMode.HALF_UP)
        return AutoSpreadResult(baseSpread, coefficient, effectiveMinSpread)
    }

    private suspend fun placeOrderForTrigger(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        triggerPrice: BigDecimal
    ) {
        val ctx = getOrInvalidatePeriodContext(strategy, periodStartUnix)

        if (ctx != null) {
            val amountUsdc = when (strategy.amountMode.uppercase()) {
                "RATIO" -> {
                    val balanceResult = accountService.getAccountBalance(ctx.account.id)
                    val availableBalance = balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    availableBalance.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
                }
                else -> strategy.amountValue
            }
            if (amountUsdc < BigDecimal("1")) {
                saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, null, "fail", "投入金额不足")
                return
            }

            val tokenId = tokenIds.getOrNull(outcomeIndex) ?: run {
                saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, null, "fail", "tokenIds 越界")
                return
            }

            val price = BigDecimal(TRIGGER_FIXED_PRICE)
            val size = computeSize(amountUsdc, price)
            val feeRateBps = ctx.feeRateByTokenId[tokenId] ?: "0"
            val signedOrder = orderSigningService.createAndSignOrder(
                privateKey = ctx.decryptedPrivateKey,
                makerAddress = ctx.account.proxyAddress,
                tokenId = tokenId,
                side = "BUY",
                price = TRIGGER_FIXED_PRICE,
                size = size,
                signatureType = ctx.signatureType,
                nonce = "0",
                feeRateBps = feeRateBps,
                expiration = "0"
            )
            val orderRequest = NewOrderRequest(
                order = signedOrder,
                owner = ctx.account.apiKey!!,
                orderType = "FAK",
                deferExec = false
            )
            submitOrderAndSaveRecord(ctx.clobApi, strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, orderRequest)
            return
        }

        placeOrderForTriggerSlowPath(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, triggerPrice)
    }

    private suspend fun submitOrderAndSaveRecord(
        clobApi: PolymarketClobApi,
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        orderRequest: NewOrderRequest
    ) {
        var failReason: String? = null
        try {
            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.orderId != null) {
                    saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, body.orderId, "success", null)
                    logger.info("尾盘策略下单成功: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}")
                    return
                }
                failReason = body.errorMsg ?: "unknown"
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                failReason = "HTTP ${response.code()} $errorBody"
            }
        } catch (e: Exception) {
            failReason = e.message ?: e.toString()
            logger.error("尾盘策略下单异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
        }
        saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, null, "fail", failReason)
        logger.error("尾盘策略下单失败: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, reason=$failReason")
    }

    /** 无预置上下文时的完整流程：固定价格 0.99，账户/解密/费率/签名在触发时执行 */
    private suspend fun placeOrderForTriggerSlowPath(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        triggerPrice: BigDecimal
    ) {
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: run {
            logger.warn("账户不存在: accountId=${strategy.accountId}")
            saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, BigDecimal.ZERO, null, "fail", "账户不存在")
            return
        }
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("账户未配置 API 凭证: accountId=${account.id}")
            saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, BigDecimal.ZERO, null, "fail", "账户未配置API凭证")
            return
        }

        val balanceResult = accountService.getAccountBalance(account.id)
        val availableBalance = balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
        val amountUsdc = when (strategy.amountMode.uppercase()) {
            "RATIO" -> availableBalance.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
            else -> strategy.amountValue
        }
        if (amountUsdc < BigDecimal("1")) {
            saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, null, "fail", "投入金额不足")
            return
        }

        val tokenId = tokenIds.getOrNull(outcomeIndex) ?: run {
            saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, null, "fail", "tokenIds 越界")
            return
        }
        val price = BigDecimal(TRIGGER_FIXED_PRICE)
        val size = computeSize(amountUsdc, price)

        val decryptedKey = try {
            cryptoUtils.decrypt(account.privateKey) ?: ""
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, null, "fail", "解密私钥失败")
            return
        }
        val apiSecret = try {
            account.apiSecret?.let { cryptoUtils.decrypt(it) } ?: ""
        } catch (e: Exception) { "" }
        val apiPassphrase = try {
            account.apiPassphrase?.let { cryptoUtils.decrypt(it) } ?: ""
        } catch (e: Exception) { "" }
        val clobApi = retrofitFactory.createClobApi(account.apiKey!!, apiSecret, apiPassphrase, account.walletAddress)
        val feeRateBps = clobService.getFeeRate(tokenId).getOrNull()?.toString() ?: "0"
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = decryptedKey,
            makerAddress = account.proxyAddress,
            tokenId = tokenId,
            side = "BUY",
            price = TRIGGER_FIXED_PRICE,
            size = size,
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
        submitOrderAndSaveRecord(clobApi, strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, orderRequest)
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

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        val parsed = clobTokenIds.fromJson<List<String>>()
        return parsed ?: emptyList()
    }

    private fun saveTriggerRecord(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        orderId: String?,
        status: String,
        failReason: String?
    ) {
        val record = CryptoTailStrategyTrigger(
            strategyId = strategy.id!!,
            periodStartUnix = periodStartUnix,
            marketTitle = marketTitle,
            outcomeIndex = outcomeIndex,
            triggerPrice = triggerPrice,
            amountUsdc = amountUsdc,
            orderId = orderId,
            status = status,
            failReason = failReason
        )
        triggerRepository.save(record)
    }
}
