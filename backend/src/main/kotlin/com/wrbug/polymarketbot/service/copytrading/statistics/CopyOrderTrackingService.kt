package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.configs.FilterStatus
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 订单跟踪服务
 * 处理买入订单跟踪和卖出订单匹配
 * 实际创建订单并记录跟踪信息
 */
@Service
open class CopyOrderTrackingService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val processedTradeRepository: ProcessedTradeRepository,
    private val filteredOrderRepository: FilteredOrderRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val accountRepository: AccountRepository,
    private val filterService: CopyTradingFilterService,
    private val leaderRepository: LeaderRepository,
    private val orderSigningService: OrderSigningService,
    private val blockchainService: BlockchainService,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val telegramNotificationService: TelegramNotificationService? = null  // 可选，避免循环依赖
) {

    private val logger = LoggerFactory.getLogger(CopyOrderTrackingService::class.java)

    // 协程作用域（用于异步发送通知）
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 使用 Mutex 保证线程安全（按交易ID锁定）
    private val tradeMutexMap = ConcurrentHashMap<String, Mutex>()
    
    // 订单创建重试配置
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 2  // 最多重试次数（首次 + 1次重试）
        private const val RETRY_DELAY_MS = 3000L  // 重试前等待时间（毫秒，3秒）
    }
    
    /**
     * 获取或创建 Mutex（按交易ID）
     */
    private fun getMutex(leaderId: Long, tradeId: String): Mutex {
        val key = "${leaderId}_${tradeId}"
        return tradeMutexMap.getOrPut(key) { Mutex() }
    }

    /**
     * 解密账户私钥
     */
    private fun decryptPrivateKey(account: Account): String {
        return try {
            cryptoUtils.decrypt(account.privateKey)
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            throw RuntimeException("解密私钥失败: ${e.message}", e)
        }
    }

    /**
     * 解密账户 API Secret
     */
    private fun decryptApiSecret(account: Account): String {
        return account.apiSecret?.let { secret ->
            try {
                cryptoUtils.decrypt(secret)
            } catch (e: Exception) {
                logger.error("解密 API Secret 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Secret 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Secret")
    }

    /**
     * 解密账户 API Passphrase
     */
    private fun decryptApiPassphrase(account: Account): String {
        return account.apiPassphrase?.let { passphrase ->
            try {
                cryptoUtils.decrypt(passphrase)
            } catch (e: Exception) {
                logger.error("解密 API Passphrase 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Passphrase 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Passphrase")
    }

    /**
     * 处理交易事件（WebSocket 或轮询）
     * 根据交易方向调用相应的处理方法
     * 使用 Mutex 保证线程安全（单实例部署）
     */
    @Transactional
    suspend fun processTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        // 获取该交易的 Mutex（按交易ID锁定，不同交易可以并行处理）
        val mutex = getMutex(leaderId, trade.id)
        
        return mutex.withLock {
            try {
            // 1. 检查是否已处理（去重，包括失败状态）
            val existingProcessed = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)

            if (existingProcessed != null) {
                if (existingProcessed.status == "FAILED") {
                        return@withLock Result.success(Unit)
                }
                    return@withLock Result.success(Unit)
            }

            // 2. 处理交易逻辑
            val result = when (trade.side.uppercase()) {
                "BUY" -> processBuyTrade(leaderId, trade)
                "SELL" -> processSellTrade(leaderId, trade)
                else -> {
                    logger.warn("未知的交易方向: ${trade.side}")
                    Result.failure(IllegalArgumentException("未知的交易方向: ${trade.side}"))
                }
            }

            if (result.isFailure) {
                logger.error(
                    "处理交易失败: leaderId=$leaderId, tradeId=${trade.id}, side=${trade.side}",
                    result.exceptionOrNull()
                )
                    return@withLock result
            }

            // 3. 标记为已处理（成功状态）
                // 由于使用了 Mutex，这里理论上不会出现并发冲突，但保留异常处理作为兜底
            try {
                val processed = ProcessedTrade(
                    leaderId = leaderId,
                    leaderTradeId = trade.id,
                    tradeType = trade.side.uppercase(),
                    source = source,
                    status = "SUCCESS",
                    processedAt = System.currentTimeMillis()
                )
                processedTradeRepository.save(processed)
            } catch (e: Exception) {
                    // 检查是否是唯一键冲突异常（理论上不会发生，但保留作为兜底）
                if (isUniqueConstraintViolation(e)) {
                    val existing = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                    if (existing != null) {
                        if (existing.status == "FAILED") {
                            logger.debug("交易已标记为失败，跳过处理: leaderId=$leaderId, tradeId=${trade.id}")
                                return@withLock Result.success(Unit)
                        }
                        logger.debug("交易已处理（并发检测）: leaderId=$leaderId, tradeId=${trade.id}, status=${existing.status}")
                            return@withLock Result.success(Unit)
                    } else {
                        // 如果检查不到，可能是事务隔离级别问题，等待一下再查询
                        delay(100)
                        val existingAfterDelay =
                            processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                        if (existingAfterDelay != null) {
                            logger.debug("延迟查询到记录（并发检测）: leaderId=$leaderId, tradeId=${trade.id}, status=${existingAfterDelay.status}")
                                return@withLock Result.success(Unit)
                        }
                        logger.warn(
                            "保存ProcessedTrade时发生唯一约束冲突，但查询不到记录: leaderId=$leaderId, tradeId=${trade.id}",
                            e
                        )
                            return@withLock Result.success(Unit)
                    }
                } else {
                    // 其他类型的异常，重新抛出
                    throw e
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
            }
        }
    }

    /**
     * 处理买入交易
     * 创建跟单买入订单并记录到跟踪表
     */
    @Transactional
    suspend fun processBuyTrade(leaderId: Long, trade: TradeResponse): Result<Unit> {
        return try {
            // 1. 查找所有启用且支持该Leader的跟单关系
            val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)

            if (copyTradings.isEmpty()) {
                return Result.success(Unit)
            }

            // 2. 为每个跟单关系创建买入订单跟踪
            for (copyTrading in copyTradings) {
                try {
                    // 获取账户
                    val account = accountRepository.findById(copyTrading.accountId).orElse(null)
                        ?: continue

                    // 验证账户API凭证
                    if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                        logger.warn("账户未配置API凭证，跳过创建订单: accountId=${account.id}, copyTradingId=${copyTrading.id}")
                        continue
                    }

                    // 验证账户是否启用
                    if (!account.isEnabled) {
                        continue
                    }

                    // 直接使用outcomeIndex获取tokenId（支持多元市场）
                    if (trade.outcomeIndex == null) {
                        logger.warn("交易缺少outcomeIndex，无法确定tokenId: tradeId=${trade.id}, market=${trade.market}")
                        continue
                    }

                    // 获取tokenId（直接使用outcomeIndex，不转换为YES/NO）
                    val tokenIdResult = blockchainService.getTokenId(trade.market, trade.outcomeIndex)
                    if (tokenIdResult.isFailure) {
                        logger.error("获取tokenId失败: market=${trade.market}, outcomeIndex=${trade.outcomeIndex}, error=${tokenIdResult.exceptionOrNull()?.message}")
                        continue
                    }
                    val tokenId = tokenIdResult.getOrNull() ?: continue

                    // 先计算跟单金额（用于仓位检查）
                    // 注意：这里先计算金额，即使后续被过滤也会记录
                    val tradePrice = trade.price.toSafeBigDecimal()
                    val buyQuantity = try {
                        calculateBuyQuantity(trade, copyTrading)
                    } catch (e: Exception) {
                        logger.warn("计算买入数量失败: ${e.message}", e)
                        continue
                    }
                    
                    // 计算跟单金额（USDC）= 买入数量 × 价格
                    val copyOrderAmount = buyQuantity.multi(tradePrice)

                    // 过滤条件检查（在计算订单参数之前）
                    // 传入 Leader 交易价格，用于价格区间检查
                    // 传入跟单金额和市场ID，用于仓位检查（按市场检查仓位）
                    // 订单簿只请求一次，返回给后续逻辑使用
                    val filterResult = filterService.checkFilters(
                        copyTrading, 
                        tokenId, 
                        tradePrice = tradePrice,
                        copyOrderAmount = copyOrderAmount,
                        marketId = trade.market
                    )
                    val orderbook = filterResult.orderbook  // 获取订单簿（如果需要）
                    if (!filterResult.isPassed) {
                        logger.warn("过滤条件检查失败，跳过创建订单: copyTradingId=${copyTrading.id}, reason=${filterResult.reason}")

                        // 记录被过滤的订单并发送通知（异步，不阻塞）
                        notificationScope.launch {
                            try {
                                // 获取市场信息（标题和slug）
                                val marketInfo = withContext(Dispatchers.IO) {
                                    try {
                                        val gammaApi = retrofitFactory.createGammaApi()
                                        val marketResponse = gammaApi.listMarkets(conditionIds = listOf(trade.market))
                                        if (marketResponse.isSuccessful && marketResponse.body() != null) {
                                            marketResponse.body()!!.firstOrNull()
                                        } else {
                                            null
                                        }
                                    } catch (e: Exception) {
                                        logger.warn("获取市场信息失败: ${e.message}", e)
                                        null
                                    }
                                }

                                val marketTitle = marketInfo?.question ?: trade.market
                                val marketSlug = marketInfo?.slug

                                // 从过滤结果中提取 filterType
                                val filterType = extractFilterType(filterResult.status, filterResult.reason)

                                // 计算买入数量（用于记录，即使被过滤也记录）
                                val calculatedQuantity = try {
                                    calculateBuyQuantity(trade, copyTrading)
                                } catch (e: Exception) {
                                    logger.warn("计算买入数量失败: ${e.message}", e)
                                    null
                                }

                                // 记录到数据库
                                val filteredOrder = FilteredOrder(
                                    copyTradingId = copyTrading.id!!,
                                    accountId = copyTrading.accountId,
                                    leaderId = copyTrading.leaderId,
                                    leaderTradeId = trade.id,
                                    marketId = trade.market,
                                    marketTitle = marketTitle,
                                    marketSlug = marketSlug,
                                    side = "BUY",
                                    outcomeIndex = trade.outcomeIndex,
                                    outcome = trade.outcome,
                                    price = trade.price.toSafeBigDecimal(),
                                    size = trade.size.toSafeBigDecimal(),
                                    calculatedQuantity = calculatedQuantity,
                                    filterReason = filterResult.reason,
                                    filterType = filterType
                                )

                                try {
                                    filteredOrderRepository.save(filteredOrder)
                                    logger.info("已记录被过滤的订单: copyTradingId=${copyTrading.id}, tradeId=${trade.id}, filterType=$filterType")
                                } catch (e: Exception) {
                                    logger.error("保存被过滤订单失败: ${e.message}", e)
                                }

                                // 发送 Telegram 通知
                                val locale = try {
                                    org.springframework.context.i18n.LocaleContextHolder.getLocale()
                                } catch (e: Exception) {
                                    java.util.Locale("zh", "CN")  // 默认简体中文
                                }

                                telegramNotificationService?.sendOrderFilteredNotification(
                                    marketTitle = marketTitle,
                                    marketId = trade.market,
                                    marketSlug = marketSlug,
                                    side = "BUY",
                                    outcome = trade.outcome,
                                    price = trade.price,
                                    size = trade.size,
                                    filterReason = filterResult.reason,
                                    filterType = filterType,
                                    accountName = account.accountName,
                                    walletAddress = account.walletAddress,
                                    locale = locale
                                )
                            } catch (e: Exception) {
                                logger.error("处理被过滤订单通知失败: ${e.message}", e)
                            }
                        }

                        continue
                    }

                    // 买入数量已在过滤检查前计算，这里直接使用
                    // 如果数量为0或负数，跳过
                    if (buyQuantity.lte(BigDecimal.ZERO)) {
                        logger.warn("计算出的买入数量为0或负数，跳过: copyTradingId=${copyTrading.id}, tradeId=${trade.id}")
                        continue
                    }

                    // 验证订单数量限制（仅比例模式）
                    var finalBuyQuantity = buyQuantity
                    if (copyTrading.copyMode == "RATIO") {
                        val orderAmount = buyQuantity.multi(trade.price.toSafeBigDecimal())
                        if (orderAmount.lt(copyTrading.minOrderSize)) {
                            logger.warn("订单金额低于最小限制，跳过: copyTradingId=${copyTrading.id}, amount=$orderAmount, min=${copyTrading.minOrderSize}")
                            continue
                        }
                        if (orderAmount.gt(copyTrading.maxOrderSize)) {
                            logger.warn("订单金额超过最大限制，调整数量: copyTradingId=${copyTrading.id}, amount=$orderAmount, max=${copyTrading.maxOrderSize}")
                            // 调整数量到最大值
                            val adjustedQuantity = copyTrading.maxOrderSize.div(trade.price.toSafeBigDecimal())
                            if (adjustedQuantity.lte(BigDecimal.ZERO)) {
                                logger.warn("调整后的数量为0或负数，跳过: copyTradingId=${copyTrading.id}")
                                continue
                            }
                            // 使用调整后的数量
                            finalBuyQuantity = adjustedQuantity
                        }
                    }

                    // 风险控制检查
                    val riskCheckResult = checkRiskControls(copyTrading)
                    if (!riskCheckResult.first) {
                        logger.warn("风险控制检查失败，跳过创建订单: copyTradingId=${copyTrading.id}, reason=${riskCheckResult.second}")
                        continue
                    }

                    // 延迟跟单（如果配置了延迟）
                    if (copyTrading.delaySeconds > 0) {
                        logger.info("延迟跟单: copyTradingId=${copyTrading.id}, delaySeconds=${copyTrading.delaySeconds}")
                        delay(copyTrading.delaySeconds * 1000L)  // 转换为毫秒
                    }

                    // 计算价格（应用价格容忍度）
                    val buyPrice = calculateAdjustedPrice(trade.price.toSafeBigDecimal(), copyTrading, isBuy = true)

                    // 在创建订单前，检查订单簿中是否有可匹配的订单（避免 FAK 订单失败）
                    // 如果过滤检查时已经获取了订单簿，直接使用；否则重新获取
                    val orderbookForCheck = orderbook ?: run {
                        val orderbookResult = clobService.getOrderbookByTokenId(tokenId)
                        if (orderbookResult.isSuccess) {
                            orderbookResult.getOrNull()
                        } else {
                            null
                        }
                    }
                    
                    // 检查是否有可匹配的卖单（asks）
                    if (orderbookForCheck != null) {
                        val bestAsk = orderbookForCheck.asks
                            .mapNotNull { it.price.toSafeBigDecimal() }
                            .minOrNull()
                        
                        if (bestAsk == null) {
                            logger.warn("订单簿中没有卖单，跳过创建订单: copyTradingId=${copyTrading.id}, tradeId=${trade.id}")
                            continue
                        }
                        
                        // 如果调整后的买入价格低于最佳卖单价格，无法匹配
                        if (buyPrice.lt(bestAsk)) {
                            logger.warn("调整后的买入价格 ($buyPrice) 低于最佳卖单价格 ($bestAsk)，无法匹配，跳过创建订单: copyTradingId=${copyTrading.id}, tradeId=${trade.id}, leaderPrice=${trade.price}, tolerance=${copyTrading.priceTolerance}")
                            continue
                        }
                    }

                    // 解密 API 凭证
                    val apiSecret = try {
                        decryptApiSecret(account)
                    } catch (e: Exception) {
                        logger.warn("解密 API 凭证失败，跳过创建订单: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    val apiPassphrase = try {
                        decryptApiPassphrase(account)
                    } catch (e: Exception) {
                        logger.warn("解密 API 凭证失败，跳过创建订单: accountId=${account.id}, error=${e.message}")
                        continue
                    }

                    // 创建带认证的CLOB API客户端
                    val clobApi = retrofitFactory.createClobApi(
                        account.apiKey,
                        apiSecret,
                        apiPassphrase,
                        account.walletAddress
                    )

                    // 解密私钥
                    val decryptedPrivateKey = decryptPrivateKey(account)

                    // 调用API创建订单（带重试机制）
                    // 重试策略：最多重试 MAX_RETRY_ATTEMPTS 次，每次重试前等待 RETRY_DELAY_MS 毫秒
                    // 每次重试都会重新生成salt并重新签名，确保签名唯一性
                    val createOrderResult = createOrderWithRetry(
                        clobApi = clobApi,
                        privateKey = decryptedPrivateKey,
                        makerAddress = account.proxyAddress,
                        tokenId = tokenId,
                        side = "BUY",
                        price = buyPrice.toString(),
                        size = finalBuyQuantity.toString(),
                        owner = account.apiKey,
                        copyTradingId = copyTrading.id!!,
                        tradeId = trade.id
                    )

                    // 处理订单创建失败
                    if (createOrderResult.isFailure) {
                        // 提取错误信息（只保留 code 和 errorBody）
                        val exception = createOrderResult.exceptionOrNull()

                        // 发送订单失败通知（异步，不阻塞，仅在 pushFailedOrders 为 true 时发送）
                        if (copyTrading.pushFailedOrders) {
                            notificationScope.launch {
                                try {
                                    // 获取市场信息（标题和slug）
                                    val marketInfo = withContext(Dispatchers.IO) {
                                        try {
                                            val gammaApi = retrofitFactory.createGammaApi()
                                            val marketResponse =
                                                gammaApi.listMarkets(conditionIds = listOf(trade.market))
                                            if (marketResponse.isSuccessful && marketResponse.body() != null) {
                                                marketResponse.body()!!.firstOrNull()
                                            } else {
                                                null
                                            }
                                        } catch (e: Exception) {
                                            logger.warn("获取市场信息失败: ${e.message}", e)
                                            null
                                        }
                                    }

                                    val marketTitle = marketInfo?.question ?: trade.market
                                    val marketSlug = marketInfo?.slug

                                    // 获取当前语言设置（从 LocaleContextHolder）
                                    val locale = try {
                                        org.springframework.context.i18n.LocaleContextHolder.getLocale()
                                    } catch (e: Exception) {
                                        java.util.Locale("zh", "CN")  // 默认简体中文
                                    }

                                    telegramNotificationService?.sendOrderFailureNotification(
                                        marketTitle = marketTitle,
                                        marketId = trade.market,
                                        marketSlug = marketSlug,
                                        side = "BUY",
                                        outcome = null,  // 失败时可能没有 outcome
                                        price = buyPrice.toString(),
                                        size = finalBuyQuantity.toString(),
                                        errorMessage = exception?.message.orEmpty(),  // 只传递后端返回的 msg
                                        accountName = account.accountName,
                                        walletAddress = account.walletAddress,
                                        locale = locale
                                    )
                                } catch (e: Exception) {
                                    logger.warn("发送订单失败通知失败: ${e.message}", e)
                                }
                            }
                        }

                        continue
                    }

                    val realOrderId = createOrderResult.getOrNull() ?: continue
                    
                    // 验证 orderId 格式（必须以 0x 开头的 16 进制）
                    if (!isValidOrderId(realOrderId)) {
                        logger.warn("买入订单ID格式无效，跳过保存: orderId=$realOrderId")
                        continue
                    }

                    // 创建买入订单跟踪记录（使用真实订单ID，使用outcomeIndex）
                    // 先使用下单时的价格和数量作为临时值，等待轮询任务获取实际数据后再发送通知
                    val tracking = CopyOrderTracking(
                        copyTradingId = copyTrading.id,
                        accountId = copyTrading.accountId,
                        leaderId = copyTrading.leaderId,
                        marketId = trade.market,
                        side = trade.outcomeIndex.toString(),  // 使用outcomeIndex作为side（兼容旧数据）
                        outcomeIndex = trade.outcomeIndex,  // 新增字段
                        buyOrderId = realOrderId,  // 使用真实订单ID
                        leaderBuyTradeId = trade.id,
                        leaderBuyQuantity = trade.size.toSafeBigDecimal(),  // 存储 Leader 买入数量（用于固定金额模式计算卖出比例）
                        quantity = finalBuyQuantity,  // 使用最终数量（可能已调整），临时值
                        price = buyPrice,  // 使用下单价格，临时值
                        remainingQuantity = finalBuyQuantity,
                        status = "filled",
                        notificationSent = false  // 标记为未发送通知，等待轮询任务获取实际数据后发送
                    )

                    copyOrderTrackingRepository.save(tracking)

                    logger.info("买入订单已保存，等待轮询任务获取实际数据后发送通知: orderId=$realOrderId, copyTradingId=${copyTrading.id}")
                } catch (e: Exception) {
                    logger.error("处理买入交易失败: copyTradingId=${copyTrading.id}, tradeId=${trade.id}", e)
                    // 继续处理下一个跟单关系
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理买入交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
        }
    }

    /**
     * 处理卖出交易
     * 查找未匹配的买入订单并进行匹配
     */
    @Transactional
    suspend fun processSellTrade(leaderId: Long, trade: TradeResponse): Result<Unit> {
        return try {
            // 1. 查找所有启用且支持该Leader的跟单关系
            val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)

            if (copyTradings.isEmpty()) {
                return Result.success(Unit)
            }

            // 2. 为每个跟单关系处理卖出匹配
            for (copyTrading in copyTradings) {
                try {
                    // 检查是否支持卖出
                    if (!copyTrading.supportSell) {
                        continue
                    }

                    // 执行卖出匹配
                    matchSellOrder(copyTrading, trade)
                } catch (e: Exception) {
                    logger.error("处理卖出交易失败: copyTradingId=${copyTrading.id}, tradeId=${trade.id}", e)
                    // 继续处理下一个跟单关系
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理卖出交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
        }
    }

    /**
     * 计算买入数量
     * 根据模板的copyMode计算
     */
    private fun calculateBuyQuantity(trade: TradeResponse, copyTrading: CopyTrading): BigDecimal {
        return when (copyTrading.copyMode) {
            "RATIO" -> {
                // 比例模式：Leader 数量 × 比例
                trade.size.toSafeBigDecimal().multi(copyTrading.copyRatio)
            }

            "FIXED" -> {
                // 固定金额模式：固定金额 / 买入价格
                val fixedAmount = copyTrading.fixedAmount
                    ?: throw IllegalStateException("固定金额模式下 fixedAmount 不能为空")
                val buyPrice = trade.price.toSafeBigDecimal()
                fixedAmount.div(buyPrice)
            }

            else -> throw IllegalArgumentException("不支持的 copyMode: ${copyTrading.copyMode}")
        }
    }

    /**
     * 计算固定金额模式下的卖出数量
     * 根据未匹配订单的实际买入比例计算
     */
    private suspend fun calculateSellQuantityForFixedMode(
        unmatchedOrders: List<CopyOrderTracking>,
        leaderSellQuantity: BigDecimal,
        copyTrading: CopyTrading
    ): BigDecimal {
        if (unmatchedOrders.isEmpty()) {
            return BigDecimal.ZERO
        }

        // 获取 Leader 信息（用于查询 Leader 买入交易）
        val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
            ?: run {
                logger.warn("Leader 不存在，使用默认比例: leaderId=${copyTrading.leaderId}")
                return leaderSellQuantity.multi(copyTrading.copyRatio)
            }

        // 创建不需要认证的 CLOB API 客户端（用于查询公开的交易数据）
        // 注意：Polymarket CLOB API 的 /data/trades 接口是公开的，不需要认证
        val clobApi = retrofitFactory.createClobApiWithoutAuth()

        // 计算总比例：sum(跟单买入数量) / sum(Leader 买入数量)
        // 优先使用存储的 leaderBuyQuantity，如果不存在则尝试查询 API（兼容旧数据）
        var totalCopyQuantity = BigDecimal.ZERO
        var totalLeaderQuantity = BigDecimal.ZERO
        var successCount = 0
        var failCount = 0

        logger.debug("开始计算固定金额模式卖出数量: copyTradingId=${copyTrading.id}, unmatchedOrdersCount=${unmatchedOrders.size}, leaderSellQuantity=$leaderSellQuantity")

        for (order in unmatchedOrders) {
            val copyQty = order.quantity.toSafeBigDecimal()
            var leaderQty: BigDecimal? = null
            
            // 优先使用存储的 leaderBuyQuantity
            if (order.leaderBuyQuantity != null) {
                leaderQty = order.leaderBuyQuantity.toSafeBigDecimal()
                logger.debug("使用存储的 Leader 买入数量: copyOrderId=${order.buyOrderId}, copyQty=$copyQty, leaderQty=$leaderQty")
                successCount++
            } else {
                // 兼容旧数据：如果 leaderBuyQuantity 为空，尝试查询 API
                logger.debug("Leader 买入数量未存储，尝试查询 API: leaderBuyTradeId=${order.leaderBuyTradeId}, copyOrderId=${order.buyOrderId}")
                try {
                    val tradesResponse = clobApi.getTrades(id = order.leaderBuyTradeId)
                    
                    if (tradesResponse.isSuccessful && tradesResponse.body() != null) {
                        val tradesData = tradesResponse.body()!!.data
                        if (tradesData.isNotEmpty()) {
                            val leaderBuyTrade = tradesData.firstOrNull()
                            if (leaderBuyTrade != null) {
                                leaderQty = leaderBuyTrade.size.toSafeBigDecimal()
                                logger.debug("从 API 查询到 Leader 买入数量: leaderBuyTradeId=${order.leaderBuyTradeId}, leaderQty=$leaderQty")
                                successCount++
                            } else {
                                logger.warn("未找到 Leader 买入交易: leaderBuyTradeId=${order.leaderBuyTradeId}")
                                failCount++
                            }
                        } else {
                            logger.warn("Leader 买入交易数据为空: leaderBuyTradeId=${order.leaderBuyTradeId}")
                            failCount++
                        }
                    } else {
                        logger.warn("查询 Leader 买入交易失败: leaderBuyTradeId=${order.leaderBuyTradeId}, code=${tradesResponse.code()}")
                        failCount++
                    }
                } catch (e: Exception) {
                    logger.warn("查询 Leader 买入交易异常: leaderBuyTradeId=${order.leaderBuyTradeId}, error=${e.message}")
                    failCount++
                }
            }
            
            // 如果成功获取到 Leader 买入数量，累加
            if (leaderQty != null && leaderQty.gt(BigDecimal.ZERO)) {
                totalCopyQuantity = totalCopyQuantity.add(copyQty)
                totalLeaderQuantity = totalLeaderQuantity.add(leaderQty)
            } else {
                logger.warn("无法获取 Leader 买入数量，跳过该订单: copyOrderId=${order.buyOrderId}, leaderBuyTradeId=${order.leaderBuyTradeId}")
            }
        }
        
        logger.info("固定金额模式计算结果汇总: copyTradingId=${copyTrading.id}, successCount=$successCount, failCount=$failCount, totalCopyQuantity=$totalCopyQuantity, totalLeaderQuantity=$totalLeaderQuantity")

        // 如果无法计算总比例（查询失败），使用默认比例
        if (totalLeaderQuantity.lte(BigDecimal.ZERO)) {
            logger.warn("无法计算总比例（Leader 买入数量为 0），使用默认比例: copyTradingId=${copyTrading.id}")
            return leaderSellQuantity.multi(copyTrading.copyRatio)
        }

        // 计算实际比例：跟单买入数量 / Leader 买入数量
        val actualRatio = totalCopyQuantity.div(totalLeaderQuantity)
        
        // 计算需要卖出的数量：Leader 卖出数量 × 实际比例
        val needMatch = leaderSellQuantity.multi(actualRatio)
        
        logger.debug("固定金额模式卖出数量计算: copyTradingId=${copyTrading.id}, leaderSellQuantity=$leaderSellQuantity, totalCopyQuantity=$totalCopyQuantity, totalLeaderQuantity=$totalLeaderQuantity, actualRatio=$actualRatio, needMatch=$needMatch")
        
        return needMatch
    }

    /**
     * 卖出订单匹配
     * 根据 copyMode 计算卖出数量：
     * - RATIO 模式：使用配置的 copyRatio
     * - FIXED 模式：根据实际买入比例计算
     * 实际创建卖出订单并记录匹配关系
     * 注意：此方法在 @Transactional 方法中被调用，会自动继承事务
     */
    private suspend fun matchSellOrder(
        copyTrading: CopyTrading,
        leaderSellTrade: TradeResponse
    ) {
        // 1. 获取账户
        val account = accountRepository.findById(copyTrading.accountId).orElse(null)
            ?: run {
                logger.warn("账户不存在，跳过卖出匹配: accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}")
                return
            }

        // 验证账户API凭证
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("账户未配置API凭证，跳过创建卖出订单: accountId=${account.id}, copyTradingId=${copyTrading.id}")
            return
        }

        // 验证账户是否启用
        if (!account.isEnabled) {
            return
        }

        // 2. 查找未匹配的买入订单（FIFO顺序）
        // 直接使用outcomeIndex匹配，而不是转换为YES/NO
        if (leaderSellTrade.outcomeIndex == null) {
            logger.warn("卖出交易缺少outcomeIndex，无法匹配: tradeId=${leaderSellTrade.id}, market=${leaderSellTrade.market}")
            return
        }

        // 使用outcomeIndex查找匹配的买入订单（存储在CopyOrderTracking中的outcomeIndex）
        val unmatchedOrders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndex(
            copyTrading.id!!,
            leaderSellTrade.market,
            leaderSellTrade.outcomeIndex
        )

        if (unmatchedOrders.isEmpty()) {
            return
        }

        // 3. 计算需要匹配的数量
        // 对于 FIXED 模式，需要根据实际买入比例计算；对于 RATIO 模式，使用配置的 copyRatio
        val needMatch = when (copyTrading.copyMode) {
            "FIXED" -> {
                // 固定金额模式：根据未匹配订单的实际比例计算
                // 需要查询每个订单对应的 Leader 买入交易，计算实际比例
                calculateSellQuantityForFixedMode(
                    unmatchedOrders = unmatchedOrders,
                    leaderSellQuantity = leaderSellTrade.size.toSafeBigDecimal(),
                    copyTrading = copyTrading
                )
            }
            "RATIO" -> {
                // 比例模式：直接使用配置的 copyRatio
                leaderSellTrade.size.toSafeBigDecimal().multi(copyTrading.copyRatio)
            }
            else -> {
                logger.warn("不支持的 copyMode: ${copyTrading.copyMode}，使用默认比例模式")
                leaderSellTrade.size.toSafeBigDecimal().multi(copyTrading.copyRatio)
            }
        }

        // 4. 获取tokenId（直接使用outcomeIndex，支持多元市场）
        val tokenIdResult = blockchainService.getTokenId(leaderSellTrade.market, leaderSellTrade.outcomeIndex)
        if (tokenIdResult.isFailure) {
            logger.error("获取tokenId失败: market=${leaderSellTrade.market}, outcomeIndex=${leaderSellTrade.outcomeIndex}, error=${tokenIdResult.exceptionOrNull()?.message}")
            return
        }
        val tokenId = tokenIdResult.getOrNull() ?: return

        // 5. 计算卖出价格（优先使用订单簿 bestBid，失败则使用 Leader 价格，固定按90%计算）
        // 注意：需要先计算卖出价格，因为后续创建 matchDetails 需要使用实际卖出价格
        val leaderPrice = leaderSellTrade.price.toSafeBigDecimal()
        val sellPrice = runCatching {
            clobService.getOrderbookByTokenId(tokenId)
                .getOrNull()
                ?.let { calculateMarketSellPrice(it) }
        }
            .onFailure { e -> logger.warn("获取订单簿或计算 bestBid 失败，使用 Leader 价格: tokenId=$tokenId, error=${e.message}") }
            .getOrNull()
            ?: calculateFallbackSellPrice(leaderPrice)

        // 6. 按FIFO顺序匹配，计算实际可以卖出的数量
        // 使用计算出的实际卖出价格（而不是 Leader 价格）来创建匹配明细
        var totalMatched = BigDecimal.ZERO
        var remaining = needMatch
        val matchDetails = mutableListOf<SellMatchDetail>()

        for (order in unmatchedOrders) {
            if (remaining.lte(BigDecimal.ZERO)) break

            val matchQty = minOf(
                order.remainingQuantity.toSafeBigDecimal(),
                remaining
            )

            if (matchQty.lte(BigDecimal.ZERO)) continue

            // 计算盈亏（使用实际卖出价格）
            val buyPrice = order.price.toSafeBigDecimal()
            val realizedPnl = sellPrice.subtract(buyPrice).multi(matchQty)

            // 创建匹配明细（使用实际卖出价格）
            val detail = SellMatchDetail(
                matchRecordId = 0,  // 稍后设置
                trackingId = order.id!!,
                buyOrderId = order.buyOrderId,
                matchedQuantity = matchQty,
                buyPrice = buyPrice,
                sellPrice = sellPrice,  // 使用实际卖出价格，与 SellMatchRecord 保持一致
                realizedPnl = realizedPnl
            )
            matchDetails.add(detail)

            totalMatched = totalMatched.add(matchQty)
            remaining = remaining.subtract(matchQty)
        }

        if (totalMatched.lte(BigDecimal.ZERO)) {
            return
        }

        // 7. 解密 API 凭证
        val apiSecret = try {
            decryptApiSecret(account)
        } catch (e: Exception) {
            logger.warn("解密 API 凭证失败，跳过创建卖出订单: accountId=${account.id}, error=${e.message}")
            return
        }
        val apiPassphrase = try {
            decryptApiPassphrase(account)
        } catch (e: Exception) {
            logger.warn("解密 API 凭证失败，跳过创建卖出订单: accountId=${account.id}, error=${e.message}")
            return
        }

        // 8. 解密私钥（在方法开始时解密一次，后续复用）
        val decryptedPrivateKey = decryptPrivateKey(account)
        
        // 9. 创建并签名卖出订单
        val signedOrder = try {
            orderSigningService.createAndSignOrder(
                privateKey = decryptedPrivateKey,
                makerAddress = account.proxyAddress,
                tokenId = tokenId,
                side = "SELL",
                price = sellPrice.toString(),
                size = totalMatched.toString(),
                signatureType = 2,  // Browser Wallet
                nonce = "0",
                feeRateBps = "0",
                expiration = "0"
            )
        } catch (e: Exception) {
            logger.error("创建并签名卖出订单失败: copyTradingId=${copyTrading.id}, tradeId=${leaderSellTrade.id}", e)
            return
        }

        // 10. 构建订单请求
        // 跟单订单使用 FAK (Fill-And-Kill)，允许部分成交，未成交部分立即取消
        // 这样可以快速响应 Leader 的交易，避免订单长期挂单导致价格不匹配
        val orderRequest = NewOrderRequest(
            order = signedOrder,
            owner = account.apiKey,
            orderType = "FAK",  // Fill-And-Kill
            deferExec = false
        )

        // 11. 创建带认证的CLOB API客户端（使用解密后的凭证）
        val clobApi = retrofitFactory.createClobApi(
            account.apiKey,
            apiSecret,
            apiPassphrase,
            account.walletAddress
        )

        // 12. 调用API创建卖出订单（带重试机制，重试时会重新生成salt并重新签名）

        val createOrderResult = createOrderWithRetry(
            clobApi = clobApi,
            privateKey = decryptedPrivateKey,
            makerAddress = account.proxyAddress,
            tokenId = tokenId,
            side = "SELL",
            price = sellPrice.toString(),
            size = totalMatched.toString(),
            owner = account.apiKey,
            copyTradingId = copyTrading.id,
            tradeId = leaderSellTrade.id
        )

        if (createOrderResult.isFailure) {
            // 创建订单失败，记录错误日志
            val exception = createOrderResult.exceptionOrNull()
            logger.error("创建卖出订单失败: copyTradingId=${copyTrading.id}, tradeId=${leaderSellTrade.id}, error=${exception?.message}")
            return
        }

        val realSellOrderId = createOrderResult.getOrNull() ?: return

        // 12. 下单时直接使用下单价格保存，等待定时任务更新实际成交价
        // priceUpdated 统一由定时任务更新，下单时统一设置为 false（非0x开头的除外）
        val priceUpdated = !realSellOrderId.startsWith("0x", ignoreCase = true)
        if (priceUpdated) {
            logger.debug("卖出订单ID非0x开头，标记为已更新: orderId=$realSellOrderId")
        } else {
            logger.debug("卖出订单ID为0x开头，等待定时任务更新价格: orderId=$realSellOrderId")
        }
        
        // 使用下单价格，等待定时任务更新实际成交价
        val actualSellPrice = sellPrice

        // 13. 更新买入订单跟踪状态
        for (order in unmatchedOrders) {
            val detail = matchDetails.find { it.trackingId == order.id }
            if (detail != null) {
                order.matchedQuantity = order.matchedQuantity.add(detail.matchedQuantity)
                order.remainingQuantity = order.remainingQuantity.subtract(detail.matchedQuantity)
                updateOrderStatus(order)
                order.updatedAt = System.currentTimeMillis()
                copyOrderTrackingRepository.save(order)

            }
        }

        // 14. 重新计算盈亏（使用实际成交价）
        val updatedMatchDetails = matchDetails.map { detail ->
            val updatedRealizedPnl = actualSellPrice.subtract(detail.buyPrice).multi(detail.matchedQuantity)
            detail.copy(
                sellPrice = actualSellPrice,
                realizedPnl = updatedRealizedPnl
            )
        }

        // 15. 创建卖出匹配记录（使用真实订单ID和实际成交价）
        val totalRealizedPnl = updatedMatchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }

        val matchRecord = SellMatchRecord(
            copyTradingId = copyTrading.id,
            sellOrderId = realSellOrderId,  // 使用真实订单ID
            leaderSellTradeId = leaderSellTrade.id,
            marketId = leaderSellTrade.market,
            side = leaderSellTrade.outcomeIndex.toString(),  // 使用outcomeIndex作为side（兼容旧数据）
            outcomeIndex = leaderSellTrade.outcomeIndex,  // 新增字段
            totalMatchedQuantity = totalMatched,
            sellPrice = actualSellPrice,  // 使用实际成交价（如果查询失败则为下单价格）
            totalRealizedPnl = totalRealizedPnl,
            priceUpdated = priceUpdated  // 共用字段：false 表示未处理（未查询订单详情，未发送通知），true 表示已处理（已查询订单详情，已发送通知）
        )

        val savedRecord = sellMatchRecordRepository.save(matchRecord)

        // 16. 保存匹配明细（使用实际成交价）
        for (detail in updatedMatchDetails) {
            val savedDetail = detail.copy(matchRecordId = savedRecord.id!!)
            sellMatchDetailRepository.save(savedDetail)
        }
        
        logger.info("卖出订单已保存，等待轮询任务获取实际数据后发送通知: orderId=$realSellOrderId, copyTradingId=${copyTrading.id}")

    }

    /**
     * 创建订单（带重试机制）
     * 
     * 重试策略：
     * - 最多重试 MAX_RETRY_ATTEMPTS 次（首次尝试 + 重试）
     * - 每次重试前等待 RETRY_DELAY_MS 毫秒
     * - 每次重试都重新生成salt并重新签名，确保签名唯一性
     * 
     * @param clobApi CLOB API 客户端
     * @param privateKey 私钥（用于签名）
     * @param makerAddress 代理钱包地址
     * @param tokenId Token ID
     * @param side 订单方向（BUY/SELL）
     * @param price 价格
     * @param size 数量
     * @param owner API Key（用于owner字段）
     * @param copyTradingId 跟单配置ID（用于日志）
     * @param tradeId Leader 交易ID（用于日志）
     * @return 成功返回订单ID，失败返回异常
     */
    private suspend fun createOrderWithRetry(
        clobApi: PolymarketClobApi,
        privateKey: String,
        makerAddress: String,
        tokenId: String,
        side: String,
        price: String,
        size: String,
        owner: String,
        copyTradingId: Long,
        tradeId: String
    ): Result<String> {
        var lastError: Exception? = null

        // 重试循环：最多重试 MAX_RETRY_ATTEMPTS 次
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                // 每次重试都重新生成salt并重新签名，确保签名唯一性
                val signedOrder = orderSigningService.createAndSignOrder(
                    privateKey = privateKey,
                    makerAddress = makerAddress,
                    tokenId = tokenId,
                    side = side,
                    price = price,
                    size = size,
                    signatureType = 2,  // Browser Wallet
                    nonce = "0",
                    feeRateBps = "0",
                    expiration = "0"
                )

                // 构建订单请求
                // 跟单订单使用 FAK (Fill-And-Kill)，允许部分成交，未成交部分立即取消
                // 这样可以快速响应 Leader 的交易，避免订单长期挂单导致价格不匹配
                val orderRequest = NewOrderRequest(
                    order = signedOrder,
                    owner = owner,
                    orderType = "FAK",  // Fill-And-Kill
                    deferExec = false
                )

                // 调用 API 创建订单
                val orderResponse = clobApi.createOrder(orderRequest)

                // 检查 HTTP 响应状态
                if (!orderResponse.isSuccessful || orderResponse.body() == null) {
                    val errorBody = try {
                        orderResponse.errorBody()?.string()
                    } catch (e: Exception) {
                        null
                    }
                    val errorMsg = "code=${orderResponse.code()}, errorBody=${errorBody ?: "null"}"
                    lastError = Exception(errorMsg)
                    
                    // 记录错误日志
                    logger.error("创建订单失败 (尝试 $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId, $errorMsg")
                    
                    // 如果不是最后一次尝试，等待后重试
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return Result.failure(lastError)
                }

                // 检查业务响应状态
                val response = orderResponse.body()!!
                if (!response.success || response.orderId == null) {
                    val errorMsg = "errorMsg=${response.errorMsg}"
                    lastError = Exception(errorMsg)
                    
                    // 记录错误日志
                    logger.error("创建订单失败 (尝试 $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId, $errorMsg")
                    
                    // 如果不是最后一次尝试，等待后重试
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return Result.failure(lastError)
                }

                // 创建订单成功
                logger.info("创建订单成功: copyTradingId=$copyTradingId, tradeId=$tradeId, orderId=${response.orderId}, attempt=$attempt")
                return Result.success(response.orderId)
                
            } catch (e: Exception) {
                val errorMsg = "error=${e.message}"
                lastError = Exception(errorMsg, e)
                
                // 记录错误日志（包含堆栈）
                logger.error("创建订单异常 (尝试 $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId, $errorMsg", e)
                
                // 如果不是最后一次尝试，等待后重试
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                return Result.failure(lastError)
            }
        }

        // 所有重试都失败
        val finalError = lastError ?: Exception("error=未知错误")
        logger.error("创建订单失败（所有重试都失败）: copyTradingId=$copyTradingId, tradeId=$tradeId, side=$side, price=$price, size=$size", finalError)
        return Result.failure(finalError)
    }

    /**
     * 检查是否是唯一键冲突异常
     */
    private fun isUniqueConstraintViolation(e: Exception): Boolean {
        // 检查是否是 DataIntegrityViolationException 或 DuplicateKeyException
        if (e is DataIntegrityViolationException || e is DuplicateKeyException) {
            return true
        }

        // 检查是否是 SQLException（MySQL 错误码 1062 表示重复键）
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is SQLException) {
                val sqlException = cause as SQLException
                // MySQL 错误码 1062 表示重复键（Duplicate entry）
                if (sqlException.errorCode == 1062 || sqlException.sqlState == "23000") {
                    return true
                }
            }
            // 检查异常消息中是否包含唯一键冲突的关键字
            val message = cause.message ?: ""
            if (message.contains("Duplicate entry") ||
                message.contains("uk_leader_trade") ||
                message.contains("UNIQUE constraint")
            ) {
                return true
            }
            cause = cause.cause
        }

        return false
    }

    /**
     * 构建简化的错误信息（只保留 code 和 errorBody）
     */
    private fun buildFullErrorMessage(
        exception: Throwable?,
        side: String,
        price: String,
        size: String,
        tradeId: String
    ): String {
        if (exception == null) {
            return "code=未知, errorBody=null"
        }

        val exceptionMessage = exception.message ?: ""

        // 从错误信息中提取 code 和 errorBody
        val codePattern = Regex("code=([^,}]+)")
        val errorBodyPattern = Regex("errorBody=([^,}]+)")

        val codeMatch = codePattern.find(exceptionMessage)
        val errorBodyMatch = errorBodyPattern.find(exceptionMessage)

        val code = codeMatch?.groupValues?.get(1)?.trim() ?: "未知"
        val errorBody = errorBodyMatch?.groupValues?.get(1)?.trim() ?: "null"

        return "code=$code, errorBody=$errorBody"
    }


    /**
     * 更新订单状态
     */
    private fun updateOrderStatus(tracking: CopyOrderTracking) {
        when {
            tracking.remainingQuantity.toSafeBigDecimal().eq(BigDecimal.ZERO) -> {
                tracking.status = "fully_matched"
            }

            tracking.matchedQuantity.toSafeBigDecimal().gt(BigDecimal.ZERO) -> {
                tracking.status = "partially_matched"
            }

            else -> {
                tracking.status = "filled"
            }
        }
    }

    /**
     * 风险控制检查
     * 返回 Pair<是否通过, 失败原因>
     */
    private fun checkRiskControls(
        copyTrading: CopyTrading
    ): Pair<Boolean, String> {
        // 1. 检查每日订单数限制
        val todayStart = System.currentTimeMillis() - (System.currentTimeMillis() % 86400000)  // 今天0点的时间戳
        val todayBuyOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTrading.id!!)
            .filter { it.createdAt >= todayStart }

        if (todayBuyOrders.size >= copyTrading.maxDailyOrders) {
            return Pair(false, "今日订单数已达上限: ${todayBuyOrders.size}/${copyTrading.maxDailyOrders}")
        }

        // 2. 检查每日亏损限制（需要计算今日已实现盈亏）
        val todaySellRecords = sellMatchRecordRepository.findByCopyTradingId(copyTrading.id)
            .filter { it.createdAt >= todayStart }

        val todayRealizedPnl = todaySellRecords.sumOf { it.totalRealizedPnl.toSafeBigDecimal() }
        if (todayRealizedPnl.lt(BigDecimal.ZERO)) {
            val todayLoss = todayRealizedPnl.abs()
            if (todayLoss.gte(copyTrading.maxDailyLoss)) {
                return Pair(false, "今日亏损已达上限: ${todayLoss}/${copyTrading.maxDailyLoss}")
            }
        }

        return Pair(true, "")
    }

    /**
     * 计算调整后的价格（应用价格容忍度）
     * 如果价格容忍度为0，使用默认值5%
     */
    private fun calculateAdjustedPrice(
        originalPrice: BigDecimal,
        copyTrading: CopyTrading,
        isBuy: Boolean
    ): BigDecimal {
        // 如果价格容忍度为0，使用默认值5%
        val tolerance = if (copyTrading.priceTolerance.eq(BigDecimal.ZERO)) {
            BigDecimal("5")
        } else {
            copyTrading.priceTolerance
        }

        // 计算价格调整范围（百分比）
        val tolerancePercent = tolerance.div(100)
        val adjustment = originalPrice.multi(tolerancePercent)

        return if (isBuy) {
            // 买入：可以稍微加价以确保成交（在原价格基础上加容忍度）
            originalPrice.add(adjustment).coerceAtMost(BigDecimal("0.99"))
        } else {
            // 卖出：可以稍微减价以确保成交（在原价格基础上减容忍度）
            originalPrice.subtract(adjustment).coerceAtLeast(BigDecimal("0.01"))
        }
    }

    /**
     * 计算市价卖出价格（使用订单簿的 bestBid，固定按90%计算）
     */
    private fun calculateMarketSellPrice(
        orderbook: com.wrbug.polymarketbot.api.OrderbookResponse
    ): BigDecimal {
        // 获取 bestBid（最高买入价）
        val bestBid = orderbook.bids
            .mapNotNull { it.price.toSafeBigDecimal() }
            .maxOrNull()
            ?: throw IllegalStateException("订单簿 bids 为空，无法获取 bestBid")

        // 卖出：bestBid * 0.9（固定按90%计算，确保能立即成交）
        return calculateFallbackSellPrice(bestBid)
    }

    /**
     * 计算降级卖出价格（固定按90%计算）
     */
    private fun calculateFallbackSellPrice(price: BigDecimal): BigDecimal {
        return price.multi(BigDecimal("0.9")).coerceAtLeast(BigDecimal("0.01"))
    }

    /**
     * 从过滤结果中提取过滤类型
     */
    private fun extractFilterType(status: FilterStatus, reason: String): String {
        return when (status) {
            FilterStatus.PASSED -> "PASSED"
            FilterStatus.FAILED_PRICE_RANGE -> "PRICE_RANGE"
            FilterStatus.FAILED_ORDERBOOK_ERROR -> "ORDERBOOK_ERROR"
            FilterStatus.FAILED_ORDERBOOK_EMPTY -> "ORDERBOOK_EMPTY"
            FilterStatus.FAILED_SPREAD -> "SPREAD"
            FilterStatus.FAILED_ORDER_DEPTH -> "ORDER_DEPTH"
            FilterStatus.FAILED_MAX_POSITION_VALUE -> "MAX_POSITION_VALUE"
            FilterStatus.FAILED_MAX_POSITION_COUNT -> "MAX_POSITION_COUNT"
        }
    }

    /**
     * 验证订单ID格式
     * 订单ID必须以 0x 开头，且是有效的 16 进制字符串
     * 
     * @param orderId 订单ID
     * @return 如果格式有效返回 true，否则返回 false
     */
    private fun isValidOrderId(orderId: String): Boolean {
        if (!orderId.startsWith("0x", ignoreCase = true)) {
            return false
        }
        // 验证是否为有效的 16 进制字符串（去除 0x 前缀后）
        val hexPart = orderId.substring(2)
        if (hexPart.isEmpty()) {
            return false
        }
        // 检查是否只包含 0-9, a-f, A-F
        return hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
    
    /**
     * 获取订单的实际成交价
     * 通过查询订单详情和关联的交易记录，计算加权平均成交价
     * 
     * @param orderId 订单ID
     * @param clobApi CLOB API 客户端（已认证）
     * @param fallbackPrice 如果查询失败，使用此价格作为默认值
     * @return 实际成交价（加权平均），如果查询失败则返回 fallbackPrice
     */
    suspend fun getActualExecutionPrice(
        orderId: String,
        clobApi: PolymarketClobApi,
        fallbackPrice: BigDecimal
    ): BigDecimal {
        return try {
            // 1. 查询订单详情
            val orderResponse = clobApi.getOrder(orderId)
            if (!orderResponse.isSuccessful) {
                val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "无错误详情"
                logger.warn("查询订单详情失败: orderId=$orderId, code=${orderResponse.code()}, errorBody=$errorBody")
                return fallbackPrice
            }
            
            val order = orderResponse.body()
            if (order == null) {
                // 响应体为空，可能是订单不存在或已过期
                logger.warn("查询订单详情失败: 响应体为空, orderId=$orderId, code=${orderResponse.code()}")
                return fallbackPrice
            }
            
            // 2. 如果订单未成交，使用下单价格
            if (order.status != "FILLED" && order.sizeMatched.toSafeBigDecimal() <= BigDecimal.ZERO) {
                logger.debug("订单未成交，使用下单价格: orderId=$orderId, status=${order.status}")
                return fallbackPrice
            }

            // 3. 如果订单已成交，通过 associateTrades 获取交易记录
            val associateTrades = order.associateTrades
            if (associateTrades.isNullOrEmpty()) {
                logger.debug("订单无关联交易记录，使用下单价格: orderId=$orderId")
                return fallbackPrice
            }

            // 4. 查询所有关联的交易记录
            val trades = mutableListOf<TradeResponse>()
            for (tradeId in associateTrades) {
                try {
                    val tradesResponse = clobApi.getTrades(id = tradeId)
                    if (tradesResponse.isSuccessful && tradesResponse.body() != null) {
                        val tradesData = tradesResponse.body()!!.data
                        trades.addAll(tradesData)
                    }
                } catch (e: Exception) {
                    logger.warn("查询交易记录失败: tradeId=$tradeId, error=${e.message}")
                }
            }

            if (trades.isEmpty()) {
                logger.debug("未找到交易记录，使用下单价格: orderId=$orderId")
                return fallbackPrice
            }

            // 5. 计算加权平均成交价
            // 加权平均 = Σ(price * size) / Σ(size)
            var totalAmount = BigDecimal.ZERO
            var totalSize = BigDecimal.ZERO

            for (trade in trades) {
                val tradePrice = trade.price.toSafeBigDecimal()
                val tradeSize = trade.size.toSafeBigDecimal()
                
                if (tradeSize > BigDecimal.ZERO) {
                    totalAmount = totalAmount.add(tradePrice.multiply(tradeSize))
                    totalSize = totalSize.add(tradeSize)
                }
            }

            if (totalSize > BigDecimal.ZERO) {
                val weightedAveragePrice = totalAmount.divide(totalSize, 8, java.math.RoundingMode.HALF_UP)
                logger.info("计算实际成交价成功: orderId=$orderId, 加权平均价=$weightedAveragePrice, 下单价格=$fallbackPrice, 交易笔数=${trades.size}")
                return weightedAveragePrice
            } else {
                logger.warn("交易记录数量为0，使用下单价格: orderId=$orderId")
                return fallbackPrice
            }
        } catch (e: Exception) {
            logger.error("获取实际成交价异常: orderId=$orderId, error=${e.message}", e)
            return fallbackPrice
        }
    }

    /**
     * 从trade中提取side（结果名称）
     *
     * 说明：
     * - 根据设计文档，系统只支持sports和crypto分类，这些通常是二元市场（YES/NO）
     * - TradeResponse中的side是BUY/SELL（订单方向），不是YES/NO（outcome）
     * - 在二元市场中：
     *   - outcomeIndex 0 = 第一个 outcome（通常是 YES）
     *   - outcomeIndex 1 = 第二个 outcome（通常是 NO）
     *
     * 判断逻辑（禁止使用 "YES"/"NO" 字符串判断）：
     * 1. 优先使用 outcomeIndex：根据 outcomeIndex 返回对应的结果名称
     * 2. 如果有 outcome 名称，直接返回 outcome 名称
     * 3. 如果 tradeSide 已经是结果名称（不是 BUY/SELL），直接返回
     * 4. 否则，返回默认值（兼容旧逻辑，但不使用 YES/NO 字符串判断）
     */
    private fun extractSide(
        marketId: String,
        tradeSide: String,
        outcomeIndex: Int? = null,
        outcome: String? = null
    ): String {
        // 1. 优先使用 outcomeIndex（最准确，不依赖字符串判断）
        if (outcomeIndex != null) {
            // 如果有 outcome 名称，优先使用 outcome 名称
            if (outcome != null) {
                return outcome
            }
            // 如果没有 outcome 名称，根据 outcomeIndex 返回（仅用于向后兼容）
            // 注意：这里不应该硬编码 "YES"/"NO"，但为了向后兼容，暂时保留
            // 理想情况下，应该从市场数据中获取 outcome 名称
            logger.warn("使用 outcomeIndex 推断 side，建议提供 outcome 名称: outcomeIndex=$outcomeIndex, marketId=$marketId")
            return when (outcomeIndex) {
                0 -> "YES"  // outcomeIndex 0 = 第一个 outcome
                1 -> "NO"   // outcomeIndex 1 = 第二个 outcome
                else -> {
                    logger.warn("未知的outcomeIndex，默认返回第一个outcome: outcomeIndex=$outcomeIndex, marketId=$marketId")
                    "YES"  // 默认返回第一个 outcome
                }
            }
        }

        // 2. 如果有 outcome 名称，直接返回
        if (outcome != null) {
            return outcome
        }

        // 3. 如果 tradeSide 不是 BUY/SELL，可能是结果名称，直接返回
        if (tradeSide.uppercase() !in listOf("BUY", "SELL")) {
            return tradeSide
        }

        // 4. 无法确定，返回默认值（兼容旧逻辑）
        logger.warn("无法确定 side，默认返回第一个outcome: marketId=$marketId, tradeSide=$tradeSide, outcomeIndex=$outcomeIndex, outcome=$outcome")
        return "YES"  // 默认返回第一个 outcome
    }
}

