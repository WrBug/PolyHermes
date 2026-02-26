package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderRequest
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderResponse
import com.wrbug.polymarketbot.dto.ManualOrderDetails
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadDirection
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
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** 加密价差策略固定下单价格（最高价 0.99），不再在触发时拉取最优价 */
private const val TRIGGER_FIXED_PRICE = "0.99"

/** 最大价差模式（MAX）时，买入价格调整系数（加在触发价格上） */
private const val SPREAD_MAX_PRICE_ADJUSTMENT = "0.02"

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
 * 加密价差策略执行服务：按周期与时间窗口检查价格并下单，每周期最多触发一次。
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

    /** 过期锁 key 保留时间（秒），超过则清理，防止 map 无界增长 */
    private val triggerMutexExpireSeconds = 3600L

    private fun triggerLockKey(strategyId: Long, periodStartUnix: Long): String = "$strategyId-$periodStartUnix"

    private fun getTriggerMutex(strategyId: Long, periodStartUnix: Long): Mutex {
        cleanExpiredTriggerMutexKeys()
        return triggerMutexMap.getOrPut(triggerLockKey(strategyId, periodStartUnix)) { Mutex() }
    }

    /** 清理已过期的 (strategyId, periodStartUnix) 锁，避免内存泄漏 */
    private fun cleanExpiredTriggerMutexKeys() {
        val nowSeconds = System.currentTimeMillis() / 1000
        val expireThreshold = nowSeconds - triggerMutexExpireSeconds
        val keysToRemove = triggerMutexMap.keys.filter { key ->
            key.substringAfterLast('-').toLongOrNull()?.let { it < expireThreshold } ?: false
        }
        keysToRemove.forEach { triggerMutexMap.remove(it) }
    }

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
            logger.warn("加密价差策略周期上下文解密私钥失败: accountId=${account.id}", e)
            return null
        }
        val apiSecret = try {
            account.apiSecret.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val apiPassphrase = try {
            account.apiPassphrase.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }

        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
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
            cleanExpiredPeriodContextCache(nowSeconds)
            return null
        }
        return ctx
    }

    /** 清理已过期的周期上下文缓存，避免内存泄漏 */
    private fun cleanExpiredPeriodContextCache(nowSeconds: Long) {
        val keysToRemove = periodContextCache.entries
            .filter { (_, ctx) -> ctx.periodStartUnix + ctx.strategy.intervalSeconds <= nowSeconds }
            .map { it.key }
        keysToRemove.forEach { periodContextCache.remove(it) }
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
            if (triggerRepository.findByStrategyIdAndPeriodStartUnix(
                    strategy.id!!,
                    periodStartUnix
                ) != null
            ) return@withLock
            val logKey = triggerLockKey(strategy.id!!, periodStartUnix)
            if (conditionLoggedCache.getIfPresent(logKey) == null) {
                conditionLoggedCache.put(logKey, periodStartUnix + strategy.intervalSeconds)
                val oc = binanceKlineService.getCurrentOpenClose(
                    strategy.marketSlugPrefix,
                    strategy.intervalSeconds,
                    periodStartUnix
                )
                val openPrice = oc?.first?.toPlainString() ?: "-"
                val closePrice = oc?.second?.toPlainString() ?: "-"
                val strategyName = strategy.name?.takeIf { it.isNotBlank() } ?: "加密价差策略-${strategy.marketSlugPrefix}"
                val direction = if (outcomeIndex == 0) "Up" else "Down"
                val modeStr = if (strategy.spreadDirection == SpreadDirection.MAX) "最大价差" else "最小价差"
                logger.info(
                    "加密价差策略首次满足条件: strategyName=$strategyName, strategyId=${strategy.id}, " +
                            "openPrice=$openPrice, closePrice=$closePrice, marketPrice=${bestBid.toPlainString()}, " +
                            "direction=$direction, outcomeIndex=$outcomeIndex, spreadMode=$modeStr"
                )
            }
            if (!passSpreadCheck(strategy, periodStartUnix, outcomeIndex)) return@withLock
            ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
            placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid)
        }
    }

    private fun passSpreadCheck(strategy: CryptoTailStrategy, periodStartUnix: Long, outcomeIndex: Int): Boolean {
        if (strategy.spreadMode == SpreadMode.NONE) return true
        val oc = binanceKlineService.getCurrentOpenClose(
            strategy.marketSlugPrefix,
            strategy.intervalSeconds,
            periodStartUnix
        )
            ?: return false
        val (openP, closeP) = oc
        val spreadAbs = closeP.subtract(openP).abs()

        // 获取有效价差
        val effectiveSpread = when (strategy.spreadMode) {
            SpreadMode.FIXED -> {
                strategy.spreadValue?.takeIf { it > BigDecimal.ZERO } ?: return true
            }

            SpreadMode.AUTO -> {
                val result = computeAutoEffectiveSpread(strategy, periodStartUnix, outcomeIndex) ?: return true
                result.effectiveSpread.takeIf { it > BigDecimal.ZERO } ?: return true
            }

            SpreadMode.NONE -> return true
        }

        // 根据价差方向判断
        return if (strategy.spreadDirection == SpreadDirection.MAX) {
            // 最大价差模式：价差 <= 配置值时触发
            spreadAbs <= effectiveSpread
        } else {
            // 最小价差模式：价差 >= 配置值时触发
            spreadAbs >= effectiveSpread
        }
    }

    /**
     * AUTO 模式：取 100% 基准价差，按窗口内毫秒进度计算动态系数（100%→50%）得到有效价差。
     */
    private data class AutoSpreadResult(
        val baseSpread: BigDecimal,
        val coefficient: BigDecimal,
        val effectiveSpread: BigDecimal
    )

    private fun computeAutoEffectiveSpread(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int
    ): AutoSpreadResult? {
        val baseSpread = binanceKlineAutoSpreadService.getAutoMinSpreadBase(
            strategy.marketSlugPrefix,
            strategy.intervalSeconds,
            periodStartUnix,
            outcomeIndex
        )
            ?: binanceKlineAutoSpreadService.computeAndCache(
                strategy.marketSlugPrefix,
                strategy.intervalSeconds,
                periodStartUnix
            )?.let { if (outcomeIndex == 0) it.first else it.second }
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
        val effectiveSpread = baseSpread.multi(coefficient).setScale(8, RoundingMode.HALF_UP)
        return AutoSpreadResult(baseSpread, coefficient, effectiveSpread)
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
                    val availableBalance =
                        balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    availableBalance.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
                }

                else -> strategy.amountValue
            }
            if (amountUsdc < BigDecimal("1")) {
                saveTriggerRecord(
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    triggerPrice,
                    amountUsdc,
                    null,
                    "fail",
                    "投入金额不足"
                )
                return
            }

            val tokenId = tokenIds.getOrNull(outcomeIndex) ?: run {
                saveTriggerRecord(
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    triggerPrice,
                    amountUsdc,
                    null,
                    "fail",
                    "tokenIds 越界"
                )
                return
            }

            // 根据价差方向确定下单价格
            val price = if (strategy.spreadDirection == SpreadDirection.MAX) {
                // 最大价差模式：触发价格 + 0.02
                triggerPrice.add(BigDecimal(SPREAD_MAX_PRICE_ADJUSTMENT)).setScale(8, RoundingMode.HALF_UP)
            } else {
                // 最小价差模式：固定价格 0.99
                BigDecimal(TRIGGER_FIXED_PRICE)
            }
            val priceStr = price.toPlainString()
            val size = computeSize(amountUsdc, price)
            val feeRateBps = ctx.feeRateByTokenId[tokenId] ?: "0"
            val signedOrder = orderSigningService.createAndSignOrder(
                privateKey = ctx.decryptedPrivateKey,
                makerAddress = ctx.account.proxyAddress,
                tokenId = tokenId,
                side = "BUY",
                price = priceStr,
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
            submitOrderAndSaveRecord(
                ctx.clobApi,
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                orderRequest,
                triggerType = "AUTO"
            )
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
        orderRequest: NewOrderRequest,
        triggerType: String = "AUTO"
    ) {
        var failReason: String? = null
        try {
            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.orderId != null) {
                    saveTriggerRecord(
                        strategy,
                        periodStartUnix,
                        marketTitle,
                        outcomeIndex,
                        triggerPrice,
                        amountUsdc,
                        body.orderId,
                        "success",
                        null,
                        triggerType = triggerType
                    )
                    logger.info("加密价差策略下单成功: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}, triggerType=$triggerType")
                    return
                }
                failReason = body.errorMsg ?: "unknown"
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                failReason = errorBody.ifEmpty { "请求失败" }
            }
        } catch (e: Exception) {
            failReason = e.message ?: e.toString()
            logger.error("加密价差策略下单异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
        }
        saveTriggerRecord(
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            triggerPrice,
            amountUsdc,
            null,
            "fail",
            failReason,
            triggerType = triggerType
        )
        logger.error("加密价差策略下单失败: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, reason=$failReason")
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
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                BigDecimal.ZERO,
                null,
                "fail",
                "账户不存在"
            )
            return
        }
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("账户未配置 API 凭证: accountId=${account.id}")
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                BigDecimal.ZERO,
                null,
                "fail",
                "账户未配置API凭证"
            )
            return
        }

        val balanceResult = accountService.getAccountBalance(account.id)
        val availableBalance = balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
        val amountUsdc = when (strategy.amountMode.uppercase()) {
            "RATIO" -> availableBalance.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
            else -> strategy.amountValue
        }
        if (amountUsdc < BigDecimal("1")) {
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                null,
                "fail",
                "投入金额不足"
            )
            return
        }

        val tokenId = tokenIds.getOrNull(outcomeIndex) ?: run {
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                null,
                "fail",
                "tokenIds 越界"
            )
            return
        }

        // 根据价差方向确定下单价格
        val price = if (strategy.spreadDirection == SpreadDirection.MAX) {
            // 最大价差模式：触发价格 + 0.02
            triggerPrice.add(BigDecimal(SPREAD_MAX_PRICE_ADJUSTMENT)).setScale(8, RoundingMode.HALF_UP)
        } else {
            // 最小价差模式：固定价格 0.99
            BigDecimal(TRIGGER_FIXED_PRICE)
        }
        val priceStr = price.toPlainString()
        val size = computeSize(amountUsdc, price)

        val decryptedKey = try {
            cryptoUtils.decrypt(account.privateKey) ?: ""
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                null,
                "fail",
                "解密私钥失败"
            )
            return
        }
        val apiSecret = try {
            account.apiSecret.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val apiPassphrase = try {
            account.apiPassphrase.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val feeRateBps = clobService.getFeeRate(tokenId).getOrNull()?.toString() ?: "0"
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = decryptedKey,
            makerAddress = account.proxyAddress,
            tokenId = tokenId,
            side = "BUY",
            price = priceStr,
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
        submitOrderAndSaveRecord(
            clobApi,
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            triggerPrice,
            amountUsdc,
            orderRequest
        )
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
        failReason: String?,
        triggerType: String = "AUTO"
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
            failReason = failReason,
            triggerType = triggerType
        )
        triggerRepository.save(record)
    }

    /**
     * 手动下单：用户主动触发下单，不检查任何条件，仅检查当前周期是否已下单
     */
    suspend fun manualOrder(request: CryptoTailManualOrderRequest): Result<CryptoTailManualOrderResponse> {
        return try {
            val strategy = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("策略不存在"))

            val outcomeIndex = if (request.direction.uppercase() == "UP") 0 else 1

            if (outcomeIndex < 0 || outcomeIndex >= request.tokenIds.size) {
                return Result.failure(IllegalArgumentException("outcomeIndex 越界"))
            }

            val price = request.price.toSafeBigDecimal()
            if (price <= BigDecimal.ZERO || price > BigDecimal.ONE) {
                return Result.failure(IllegalArgumentException("价格必须在 0~1 之间"))
            }
            val priceRounded = price.setScale(4, RoundingMode.UP)

            val size = request.size.toSafeBigDecimal()
            if (size < BigDecimal.ONE) {
                return Result.failure(IllegalArgumentException("数量不能少于 1"))
            }

            val amountUsdc = priceRounded.multi(size).setScale(2, RoundingMode.HALF_UP)
            if (amountUsdc < BigDecimal.ONE) {
                return Result.failure(IllegalArgumentException("总金额不能少于 1 USDC"))
            }

            val mutex = getTriggerMutex(strategy.id!!, request.periodStartUnix)
            mutex.withLock {
                if (triggerRepository.findByStrategyIdAndPeriodStartUnix(
                        strategy.id!!,
                        request.periodStartUnix
                    ) != null
                ) {
                    return@withLock Result.failure(IllegalArgumentException("当前周期已下单"))
                }

                var ctx = getOrInvalidatePeriodContext(strategy, request.periodStartUnix)
                if (ctx == null) {
                    ctx = ensurePeriodContext(
                        strategy,
                        request.periodStartUnix,
                        request.tokenIds,
                        request.marketTitle.ifBlank { null }
                    )
                }
                if (ctx != null) {
                    val tokenId = request.tokenIds.getOrNull(outcomeIndex)
                        ?: return@withLock Result.failure(IllegalArgumentException("tokenIds 越界"))

                    val priceStr = priceRounded.toPlainString()
                    val sizeStr = size.toPlainString()
                    val feeRateBps = ctx.feeRateByTokenId[tokenId] ?: "0"

                    val signedOrder = orderSigningService.createAndSignOrder(
                        privateKey = ctx.decryptedPrivateKey,
                        makerAddress = ctx.account.proxyAddress,
                        tokenId = tokenId,
                        side = "BUY",
                        price = priceStr,
                        size = sizeStr,
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

                    val orderResult = submitOrderForManualOrder(
                        ctx.clobApi,
                        strategy,
                        request.periodStartUnix,
                        request.marketTitle,
                        outcomeIndex,
                        priceRounded,
                        amountUsdc,
                        orderRequest
                    )

                    orderResult.fold(
                        onSuccess = { orderId ->
                            Result.success(
                                CryptoTailManualOrderResponse(
                                    success = true,
                                    orderId = orderId,
                                    message = "下单成功",
                                    orderDetails = ManualOrderDetails(
                                        strategyId = strategy.id!!,
                                        direction = request.direction,
                                        price = priceStr,
                                        size = sizeStr,
                                        totalAmount = amountUsdc.toPlainString()
                                    )
                                )
                            )
                        },
                        onFailure = { e ->
                            Result.failure(e)
                        }
                    )
                } else {
                    Result.failure(IllegalArgumentException("账户未配置或凭证不足"))
                }
            }
        } catch (e: Exception) {
            logger.error("手动下单异常: strategyId=${request.strategyId}, ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun submitOrderForManualOrder(
        clobApi: PolymarketClobApi,
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        price: BigDecimal,
        amountUsdc: BigDecimal,
        orderRequest: NewOrderRequest
    ): Result<String> {
        return try {
            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.orderId != null) {
                    saveTriggerRecord(
                        strategy,
                        periodStartUnix,
                        marketTitle,
                        outcomeIndex,
                        price,
                        amountUsdc,
                        body.orderId,
                        "success",
                        null,
                        triggerType = "MANUAL"
                    )
                    logger.info("手动下单成功: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}")
                    Result.success(body.orderId)
                } else {
                    Result.failure(Exception(body.errorMsg ?: "unknown"))
                }
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                Result.failure(Exception(errorBody.ifEmpty { "请求失败" }))
            }
        } catch (e: Exception) {
            logger.error("手动下单异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
            Result.failure(e)
        }
    }

    @PreDestroy
    fun destroy() {
        // 清理所有周期上下文缓存，避免敏感信息（明文私钥、API Secret）在内存中保留
        periodContextCache.clear()
        // 清理所有锁，避免内存泄漏
        triggerMutexMap.clear()
        logger.debug("加密价差策略执行服务已清理缓存和锁")
    }
}
