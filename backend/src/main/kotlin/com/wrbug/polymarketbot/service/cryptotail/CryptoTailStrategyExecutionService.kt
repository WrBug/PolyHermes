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
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/** 尾盘策略固定下单价格（最高价 0.99），不再在触发时拉取最优价 */
private const val TRIGGER_FIXED_PRICE = "0.99"

/** 数量小数位数，与 OrderSigningService 的 roundConfig.size 一致 */
private const val SIZE_DECIMAL_SCALE = 2

/** 下单成功后拉取订单成交数据的短暂延迟（毫秒），便于交易所更新订单状态 */
private const val FETCH_ORDER_AFTER_PLACE_DELAY_MS = 800L

/** 存库时投入金额/触发价小数精度，与结算服务一致 */
private const val FILL_AMOUNT_SCALE = 8

/**
 * 周期内预置上下文：账户、解密凭证、费率、签名类型、CLOB 客户端；FIXED 模式含预签订单。
 * 触发时 RATIO 仅算 size 并签名提交，FIXED 直接提交预签订单。
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
    val marketTitle: String?,
    val preSignedOrderByOutcome: Map<Int, NewOrderRequest>?
)

/**
 * 尾盘策略执行服务：按周期与时间窗口检查价格并下单，每周期最多触发一次。
 * 周期开始预置账户、解密、费率、签名类型、CLOB 客户端；FIXED 模式预签两张订单，触发时仅提交；RATIO 模式触发时再算 size 并签名提交。
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
    private val cryptoUtils: CryptoUtils
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyExecutionService::class.java)

    private val maxRetryAttempts = 3
    private val retryDelayMs = 2000L

    /** 按 (strategyId, periodStartUnix) 加锁，避免同一周期被调度器与 WebSocket 等多路并发重复下单 */
    private val triggerMutexMap = ConcurrentHashMap<String, Mutex>()

    private fun triggerLockKey(strategyId: Long, periodStartUnix: Long): String = "$strategyId-$periodStartUnix"

    private fun getTriggerMutex(strategyId: Long, periodStartUnix: Long): Mutex =
        triggerMutexMap.getOrPut(triggerLockKey(strategyId, periodStartUnix)) { Mutex() }

    /** 周期预置上下文缓存：(strategyId-periodStartUnix) -> PeriodContext，过期周期在读取时剔除 */
    private val periodContextCache = ConcurrentHashMap<String, PeriodContext>()

    /**
     * 在周期内首次需要时构建并缓存预置上下文；失败返回 null，触发流程将走完整路径。
     * 预置：账户、解密、费率、签名类型、CLOB 客户端；FIXED 时预签两个 outcome 的订单。
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

        val preSignedOrderByOutcome: Map<Int, NewOrderRequest>? = when (strategy.amountMode.uppercase()) {
            "RATIO" -> null
            else -> {
                val amountUsdc = strategy.amountValue
                if (amountUsdc < BigDecimal("1")) return null
                val price = BigDecimal(TRIGGER_FIXED_PRICE)
                val size = computeSize(amountUsdc, price)
                val orders = mutableMapOf<Int, NewOrderRequest>()
                for (i in 0..1) {
                    if (i >= tokenIds.size) break
                    val tokenId = tokenIds[i]
                    val feeRateBps = feeRateByTokenId[tokenId] ?: "0"
                    try {
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
                        orders[i] = NewOrderRequest(
                            order = signedOrder,
                            owner = account.apiKey!!,
                            orderType = "FAK",
                            deferExec = false
                        )
                    } catch (e: Exception) {
                        logger.warn("尾盘策略预签订单失败: strategyId=${strategy.id}, outcomeIndex=$i", e)
                        return null
                    }
                }
                orders.ifEmpty { null }
            }
        }

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
            marketTitle = marketTitle,
            preSignedOrderByOutcome = preSignedOrderByOutcome
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
            ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
            placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid)
        }
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

            when {
                ctx.preSignedOrderByOutcome != null -> {
                    val orderRequest = ctx.preSignedOrderByOutcome[outcomeIndex]
                    if (orderRequest != null) {
                        submitOrderAndSaveRecord(
                            ctx.clobApi, strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, orderRequest,
                            ctx.account.apiKey, ctx.apiSecretDecrypted, ctx.apiPassphraseDecrypted, ctx.account.walletAddress
                        )
                        return
                    }
                }
                strategy.amountMode.uppercase() == "RATIO" -> {
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
                    submitOrderAndSaveRecord(
                        ctx.clobApi, strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, orderRequest,
                        ctx.account.apiKey, ctx.apiSecretDecrypted, ctx.apiPassphraseDecrypted, ctx.account.walletAddress
                    )
                    return
                }
            }
        }

        placeOrderForTriggerSlowPath(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, triggerPrice)
    }

    /**
     * 下单并写触发记录。若传入账户 L2 凭证，下单成功后会拉取订单实际成交价与成交量，用真实触发价与投入金额写库，表现从首条记录起即正确。
     */
    private suspend fun submitOrderAndSaveRecord(
        clobApi: PolymarketClobApi,
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        orderRequest: NewOrderRequest,
        apiKey: String? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null,
        walletAddress: String? = null
    ) {
        var lastError: String? = null
        for (attempt in 1..maxRetryAttempts) {
            try {
                val response = clobApi.createOrder(orderRequest)
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    if (body.success && body.orderId != null) {
                        val (savePrice, saveAmount) = resolveFillPriceAndAmount(
                            orderId = body.orderId,
                            triggerPrice = triggerPrice,
                            amountUsdc = amountUsdc,
                            apiKey = apiKey,
                            apiSecret = apiSecret,
                            apiPassphrase = apiPassphrase,
                            walletAddress = walletAddress
                        )
                        saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, savePrice, saveAmount, body.orderId, "success", null)
                        logger.info("尾盘策略下单成功: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}, triggerPrice=$savePrice")
                        return
                    }
                    lastError = body.errorMsg ?: "unknown"
                } else {
                    lastError = "HTTP ${response.code()} ${response.errorBody()?.string()?.take(200)}"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "exception"
                logger.warn("尾盘策略下单异常 (attempt $attempt/$maxRetryAttempts): strategyId=${strategy.id}, error=$lastError")
            }
            if (attempt < maxRetryAttempts) delay(retryDelayMs)
        }
        saveTriggerRecord(strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, null, "fail", lastError)
        logger.warn("尾盘策略下单失败(已重试${maxRetryAttempts}次): strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, reason=$lastError")
    }

    /**
     * 下单成功后拉取订单实际成交价与成交量；需 L2 凭证。失败或无效则返回传入的 triggerPrice、amountUsdc。
     */
    private suspend fun resolveFillPriceAndAmount(
        orderId: String,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        apiKey: String?,
        apiSecret: String?,
        apiPassphrase: String?,
        walletAddress: String?
    ): Pair<BigDecimal, BigDecimal> {
        if (apiKey.isNullOrBlank() || apiSecret.isNullOrBlank() || apiPassphrase.isNullOrBlank() || walletAddress.isNullOrBlank()) {
            return Pair(triggerPrice, amountUsdc)
        }
        delay(FETCH_ORDER_AFTER_PLACE_DELAY_MS)
        val result = clobService.getOrder(
            orderId = orderId,
            apiKey = apiKey!!,
            apiSecret = apiSecret!!,
            apiPassphrase = apiPassphrase!!,
            walletAddress = walletAddress!!
        )
        return result.getOrNull()?.let { order ->
            val price = order.price.toSafeBigDecimal()
            val sizeMatched = order.sizeMatched.toSafeBigDecimal()
            if (price.gt(BigDecimal.ZERO) && sizeMatched.gt(BigDecimal.ZERO)) {
                val cost = price.multi(sizeMatched).setScale(FILL_AMOUNT_SCALE, RoundingMode.HALF_UP)
                Pair(price, cost)
            } else {
                Pair(triggerPrice, amountUsdc)
            }
        } ?: Pair(triggerPrice, amountUsdc)
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
        submitOrderAndSaveRecord(
            clobApi, strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc, orderRequest,
            account.apiKey, apiSecret, apiPassphrase, account.walletAddress
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
