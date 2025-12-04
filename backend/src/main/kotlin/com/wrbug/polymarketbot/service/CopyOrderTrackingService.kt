package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 订单跟踪服务
 * 处理买入订单跟踪和卖出订单匹配
 * 实际创建订单并记录跟踪信息
 */
@Service
class CopyOrderTrackingService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val processedTradeRepository: ProcessedTradeRepository,
    private val failedTradeRepository: FailedTradeRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val templateRepository: CopyTradingTemplateRepository,
    private val accountRepository: AccountRepository,
    private val leaderRepository: LeaderRepository,
    private val orderSigningService: OrderSigningService,
    private val blockchainService: BlockchainService,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: com.wrbug.polymarketbot.util.CryptoUtils
) {
    
    private val logger = LoggerFactory.getLogger(CopyOrderTrackingService::class.java)
    
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
     */
    @Transactional
    suspend fun processTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        return try {
            // 1. 检查是否已处理（去重，包括失败状态）
            val existingProcessed = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
            
            if (existingProcessed != null) {
                if (existingProcessed.status == "FAILED") {
                    return Result.success(Unit)
                }
                return Result.success(Unit)
            }
            
            // 检查是否已记录为失败交易
            val failedTrade = failedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
            if (failedTrade != null) {
                return Result.success(Unit)
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
                logger.error("处理交易失败: leaderId=$leaderId, tradeId=${trade.id}, side=${trade.side}", result.exceptionOrNull())
                return result
            }
            
            // 3. 标记为已处理（成功状态）
            // 注意：并发情况下可能多个请求同时处理同一笔交易，需要处理唯一约束冲突
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
            } catch (e: DataIntegrityViolationException) {
                // 唯一约束冲突，说明已经处理过了（可能是并发请求）
                // 再次检查确认状态
                val existing = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                if (existing != null) {
                    if (existing.status == "FAILED") {
                        return Result.success(Unit)
                    }
                    return Result.success(Unit)
                } else {
                    // 如果检查不到，说明可能是其他约束冲突，重新抛出异常
                    logger.warn("保存ProcessedTrade时发生唯一约束冲突，但查询不到记录: leaderId=$leaderId, tradeId=${trade.id}", e)
                    throw e
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
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
                    // 获取模板
                    val template = templateRepository.findById(copyTrading.templateId).orElse(null)
                        ?: continue
                    
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
                    
                    // 计算买入数量
                    val buyQuantity = calculateBuyQuantity(trade, template)
                    
                    if (buyQuantity.lte(BigDecimal.ZERO)) {
                        logger.warn("计算出的买入数量为0或负数，跳过: copyTradingId=${copyTrading.id}, tradeId=${trade.id}")
                        continue
                    }
                    
                    // 验证订单数量限制（仅比例模式）
                    var finalBuyQuantity = buyQuantity
                    if (template.copyMode == "RATIO") {
                        val orderAmount = buyQuantity.multi(trade.price.toSafeBigDecimal())
                        if (orderAmount.lt(template.minOrderSize)) {
                            logger.warn("订单金额低于最小限制，跳过: copyTradingId=${copyTrading.id}, amount=$orderAmount, min=${template.minOrderSize}")
                            continue
                        }
                        if (orderAmount.gt(template.maxOrderSize)) {
                            logger.warn("订单金额超过最大限制，调整数量: copyTradingId=${copyTrading.id}, amount=$orderAmount, max=${template.maxOrderSize}")
                            // 调整数量到最大值
                            val adjustedQuantity = template.maxOrderSize.div(trade.price.toSafeBigDecimal())
                            if (adjustedQuantity.lte(BigDecimal.ZERO)) {
                                logger.warn("调整后的数量为0或负数，跳过: copyTradingId=${copyTrading.id}")
                                continue
                            }
                            // 使用调整后的数量
                            finalBuyQuantity = adjustedQuantity
                        }
                    }
                    
                    // 风险控制检查
                    val riskCheckResult = checkRiskControls(copyTrading, template, finalBuyQuantity, trade.price.toSafeBigDecimal())
                    if (!riskCheckResult.first) {
                        logger.warn("风险控制检查失败，跳过创建订单: copyTradingId=${copyTrading.id}, reason=${riskCheckResult.second}")
                        continue
                    }
                    
                    // 延迟跟单（如果配置了延迟）
                    if (template.delaySeconds > 0) {
                        logger.info("延迟跟单: copyTradingId=${copyTrading.id}, delaySeconds=${template.delaySeconds}")
                        delay(template.delaySeconds * 1000L)  // 转换为毫秒
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
                    
                    // 计算价格（应用价格容忍度）
                    val buyPrice = calculateAdjustedPrice(trade.price.toSafeBigDecimal(), template, isBuy = true)
                    
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
                        account.apiKey!!,
                        apiSecret,
                        apiPassphrase,
                        account.walletAddress
                    )
                    
                    // 解密私钥
                    val decryptedPrivateKey = decryptPrivateKey(account)
                    
                    // 调用API创建订单（带重试机制，重试时会重新生成salt并重新签名）
                    val createOrderResult = createOrderWithRetry(
                        clobApi = clobApi,
                        privateKey = decryptedPrivateKey,
                        makerAddress = account.proxyAddress,
                        tokenId = tokenId,
                        side = "BUY",
                        price = buyPrice.toString(),
                        size = finalBuyQuantity.toString(),
                        owner = account.apiKey!!,
                        copyTradingId = copyTrading.id!!,
                        tradeId = trade.id
                    )
                    
                    if (createOrderResult.isFailure) {
                        // 创建订单失败，记录到失败表
                        val exception = createOrderResult.exceptionOrNull()
                        val errorMsg = buildFullErrorMessage(exception, "BUY", buyPrice.toString(), finalBuyQuantity.toString(), trade.id)
                        recordFailedTrade(
                            leaderId = leaderId,
                            trade = trade,
                            copyTradingId = copyTrading.id!!,
                            accountId = copyTrading.accountId,
                            side = "BUY",  // 订单方向是BUY
                            price = buyPrice.toString(),
                            size = finalBuyQuantity.toString(),
                            errorMessage = errorMsg,
                            retryCount = 1  // 已重试一次
                        )
                        continue
                    }
                    
                    val realOrderId = createOrderResult.getOrNull() ?: continue
                    
                    // 创建买入订单跟踪记录（使用真实订单ID，使用outcomeIndex）
                    val tracking = CopyOrderTracking(
                        copyTradingId = copyTrading.id!!,
                        accountId = copyTrading.accountId,
                        leaderId = copyTrading.leaderId,
                        templateId = copyTrading.templateId,
                        marketId = trade.market,
                        side = trade.outcomeIndex?.toString() ?: "0",  // 使用outcomeIndex作为side（兼容旧数据）
                        outcomeIndex = trade.outcomeIndex,  // 新增字段
                        buyOrderId = realOrderId,  // 使用真实订单ID
                        leaderBuyTradeId = trade.id,
                        quantity = finalBuyQuantity,  // 使用最终数量（可能已调整）
                        price = buyPrice,
                        remainingQuantity = finalBuyQuantity,
                        status = "filled"
                    )
                    
                    copyOrderTrackingRepository.save(tracking)
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
                    // 获取模板
                    val template = templateRepository.findById(copyTrading.templateId).orElse(null)
                        ?: continue
                    
                    // 检查是否支持卖出
                    if (!template.supportSell) {
                        continue
                    }
                    
                    // 执行卖出匹配
                    matchSellOrder(copyTrading, trade, template)
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
    private fun calculateBuyQuantity(trade: TradeResponse, template: CopyTradingTemplate): BigDecimal {
        return when (template.copyMode) {
            "RATIO" -> {
                // 比例模式：Leader 数量 × 比例
                trade.size.toSafeBigDecimal().multi(template.copyRatio)
            }
            "FIXED" -> {
                // 固定金额模式：固定金额 / 买入价格
                val fixedAmount = template.fixedAmount
                    ?: throw IllegalStateException("固定金额模式下 fixedAmount 不能为空")
                val buyPrice = trade.price.toSafeBigDecimal()
                fixedAmount.div(buyPrice)
            }
            else -> throw IllegalArgumentException("不支持的 copyMode: ${template.copyMode}")
        }
    }
    
    /**
     * 卖出订单匹配
     * 统一按比例计算，不区分RATIO或FIXED模式
     * 实际创建卖出订单并记录匹配关系
     */
    @Transactional
    private suspend fun matchSellOrder(
        copyTrading: CopyTrading,
        leaderSellTrade: TradeResponse,
        template: CopyTradingTemplate
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
        
        // 2. 计算需要匹配的数量（统一按比例计算）
        val needMatch = leaderSellTrade.size.toSafeBigDecimal().multi(template.copyRatio)
        
        // 3. 查找未匹配的买入订单（FIFO顺序）
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
        
        // 4. 按FIFO顺序匹配，计算实际可以卖出的数量
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
            
            // 计算盈亏
            val buyPrice = order.price.toSafeBigDecimal()
            val sellPrice = leaderSellTrade.price.toSafeBigDecimal()
            val realizedPnl = sellPrice.subtract(buyPrice).multi(matchQty)
            
            // 创建匹配明细（稍后保存）
            val detail = SellMatchDetail(
                matchRecordId = 0,  // 稍后设置
                trackingId = order.id!!,
                buyOrderId = order.buyOrderId,
                matchedQuantity = matchQty,
                buyPrice = buyPrice,
                sellPrice = sellPrice,
                realizedPnl = realizedPnl
            )
            matchDetails.add(detail)
            
            totalMatched = totalMatched.add(matchQty)
            remaining = remaining.subtract(matchQty)
        }
        
        if (totalMatched.lte(BigDecimal.ZERO)) {
            return
        }
        
        // 5. 获取tokenId（直接使用outcomeIndex，支持多元市场）
        val tokenIdResult = blockchainService.getTokenId(leaderSellTrade.market, leaderSellTrade.outcomeIndex)
        if (tokenIdResult.isFailure) {
            logger.error("获取tokenId失败: market=${leaderSellTrade.market}, outcomeIndex=${leaderSellTrade.outcomeIndex}, error=${tokenIdResult.exceptionOrNull()?.message}")
            return
        }
        val tokenId = tokenIdResult.getOrNull() ?: return
        
        // 6. 计算卖出价格（应用价格容忍度）
        val sellPrice = calculateAdjustedPrice(leaderSellTrade.price.toSafeBigDecimal(), template, isBuy = false)
        
        // 7. 解密私钥（在方法开始时解密一次，后续复用）
        val decryptedPrivateKey = decryptPrivateKey(account)
        // 8. 创建并签名卖出订单
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
        
        // 9. 构建订单请求
        // 跟单订单使用 FAK (Fill-And-Kill)，允许部分成交，未成交部分立即取消
        // 这样可以快速响应 Leader 的交易，避免订单长期挂单导致价格不匹配
        val orderRequest = NewOrderRequest(
            order = signedOrder,
            owner = account.apiKey!!,
            orderType = "FAK",  // Fill-And-Kill
            deferExec = false
        )
        
        // 10. 创建带认证的CLOB API客户端
        val clobApi = retrofitFactory.createClobApi(
            account.apiKey!!,
            account.apiSecret!!,
            account.apiPassphrase!!,
            account.walletAddress
        )
        
        // 11. 调用API创建卖出订单（带重试机制，重试时会重新生成salt并重新签名）
        
        val createOrderResult = createOrderWithRetry(
            clobApi = clobApi,
            privateKey = decryptedPrivateKey,
            makerAddress = account.proxyAddress,
            tokenId = tokenId,
            side = "SELL",
            price = sellPrice.toString(),
            size = totalMatched.toString(),
            owner = account.apiKey!!,
            copyTradingId = copyTrading.id!!,
            tradeId = leaderSellTrade.id
        )
        
        if (createOrderResult.isFailure) {
            // 创建订单失败，记录到失败表
            val exception = createOrderResult.exceptionOrNull()
            val errorMsg = buildFullErrorMessage(exception, "SELL", sellPrice.toString(), totalMatched.toString(), leaderSellTrade.id)
            recordFailedTrade(
                leaderId = copyTrading.leaderId,
                trade = leaderSellTrade,
                copyTradingId = copyTrading.id!!,
                accountId = copyTrading.accountId,
                side = "SELL",  // 订单方向是SELL
                price = sellPrice.toString(),
                size = totalMatched.toString(),
                errorMessage = errorMsg,
                retryCount = 1  // 已重试一次
            )
            return
        }
        
        val realSellOrderId = createOrderResult.getOrNull() ?: return
        
        // 12. 更新买入订单跟踪状态
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
        
        // 13. 创建卖出匹配记录（使用真实订单ID，使用outcomeIndex）
        val totalRealizedPnl = matchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }
        
        val matchRecord = SellMatchRecord(
            copyTradingId = copyTrading.id!!,
            sellOrderId = realSellOrderId,  // 使用真实订单ID
            leaderSellTradeId = leaderSellTrade.id,
            marketId = leaderSellTrade.market,
            side = leaderSellTrade.outcomeIndex?.toString() ?: "0",  // 使用outcomeIndex作为side（兼容旧数据）
            outcomeIndex = leaderSellTrade.outcomeIndex,  // 新增字段
            totalMatchedQuantity = totalMatched,
            sellPrice = sellPrice,
            totalRealizedPnl = totalRealizedPnl
        )
        
        val savedRecord = sellMatchRecordRepository.save(matchRecord)
        
        // 14. 保存匹配明细
        for (detail in matchDetails) {
            val savedDetail = detail.copy(matchRecordId = savedRecord.id!!)
            sellMatchDetailRepository.save(savedDetail)
        }
        
    }
    
    /**
     * 创建订单（带重试机制）
     * 失败后重试一次，如果仍然失败则返回失败结果
     * 注意：重试时会重新生成salt并重新签名，确保每次重试都是新的订单
     */
    private suspend fun createOrderWithRetry(
        clobApi: com.wrbug.polymarketbot.api.PolymarketClobApi,
        privateKey: String,
        makerAddress: String,
        tokenId: String,
        side: String,
        price: String,
        size: String,
        owner: String,  // API Key，用于owner字段
        copyTradingId: Long,
        tradeId: String
    ): Result<String> {
        var lastError: Exception? = null
        
        // 最多重试2次（首次 + 1次重试）
        for (attempt in 1..2) {
            try {
                // 每次重试都重新生成salt并重新签名
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
                
                // 构建订单请求（每次重试都使用新签名的订单）
                // 跟单订单使用 FAK (Fill-And-Kill)，允许部分成交，未成交部分立即取消
                // 这样可以快速响应 Leader 的交易，避免订单长期挂单导致价格不匹配
                val orderRequest = NewOrderRequest(
                    order = signedOrder,
                    owner = owner,  // API Key
                    orderType = "FAK",  // Fill-And-Kill
                    deferExec = false
                )
                
                val orderResponse = clobApi.createOrder(orderRequest)
                
                if (!orderResponse.isSuccessful || orderResponse.body() == null) {
                    val errorBody = try {
                        orderResponse.errorBody()?.string()
                    } catch (e: Exception) {
                        null
                    }
                    val errorMsg = "创建订单失败: copyTradingId=$copyTradingId, tradeId=$tradeId, attempt=$attempt, side=$side, price=$price, size=$size, tokenId=$tokenId, code=${orderResponse.code()}, message=${orderResponse.message()}${if (errorBody != null) ", errorBody=$errorBody" else ""}"
                    lastError = Exception(errorMsg)
                    // 所有失败都记录详细日志
                    logger.error(errorMsg)
                    if (attempt < 2) {
                        delay(1000)  // 重试前等待1秒
                        continue
                    }
                    return Result.failure(lastError!!)
                }
                
                val response = orderResponse.body()!!
                if (!response.success || response.orderId == null) {
                    val errorMsg = "创建订单失败: copyTradingId=$copyTradingId, tradeId=$tradeId, attempt=$attempt, side=$side, price=$price, size=$size, tokenId=$tokenId, errorMsg=${response.errorMsg}"
                    lastError = Exception(errorMsg)
                    // 所有失败都记录详细日志
                    logger.error(errorMsg)
                    if (attempt < 2) {
                        delay(1000)  // 重试前等待1秒
                        continue
                    }
                    return Result.failure(lastError!!)
                }
                
                // 成功
                return Result.success(response.orderId)
            } catch (e: Exception) {
                val errorMsg = "调用创建订单API异常: copyTradingId=$copyTradingId, tradeId=$tradeId, attempt=$attempt, side=$side, price=$price, size=$size, tokenId=$tokenId, error=${e.message}"
                lastError = Exception(errorMsg, e)
                // 所有失败都记录详细日志（包括堆栈）
                logger.error(errorMsg, e)
                if (attempt < 2) {
                    delay(1000)  // 重试前等待1秒
                    continue
                }
                return Result.failure(lastError!!)
            }
        }
        
        val finalError = lastError ?: Exception("创建订单失败：未知错误")
        logger.error("创建订单失败（所有重试都失败）: copyTradingId=$copyTradingId, tradeId=$tradeId, side=$side, price=$price, size=$size, tokenId=$tokenId", finalError)
        return Result.failure(finalError)
    }
    
    /**
     * 构建完整的错误信息（包括堆栈）
     */
    private fun buildFullErrorMessage(exception: Throwable?, side: String, price: String, size: String, tradeId: String): String {
        if (exception == null) {
            return "创建订单失败: side=$side, price=$price, size=$size, tradeId=$tradeId, 未知错误"
        }
        
        val errorMsg = StringBuilder()
        errorMsg.append("创建订单失败: side=$side, price=$price, size=$size, tradeId=$tradeId")
        errorMsg.append(", error=${exception.message}")
        
        // 添加堆栈信息（限制长度，避免过长）
        val stackTrace = exception.stackTraceToString()
        val maxLength = 2000  // 限制错误信息最大长度为2000字符
        if (stackTrace.length > maxLength) {
            errorMsg.append(", stackTrace=${stackTrace.substring(0, maxLength)}...")
        } else {
            errorMsg.append(", stackTrace=$stackTrace")
        }
        
        // 如果有 cause，也添加
        exception.cause?.let { cause ->
            errorMsg.append(", cause=${cause.message}")
        }
        
        return errorMsg.toString()
    }
    
    /**
     * 记录失败交易到数据库
     */
    @Transactional
    private fun recordFailedTrade(
        leaderId: Long,
        trade: TradeResponse,
        copyTradingId: Long,
        accountId: Long,
        side: String,
        price: String,
        size: String,
        errorMessage: String,
        retryCount: Int
    ) {
        try {
            // 确保错误信息不超过数据库字段限制（TEXT类型通常支持65535字符）
            val maxErrorMessageLength = 50000  // 保留一些余量
            val finalErrorMessage = if (errorMessage.length > maxErrorMessageLength) {
                errorMessage.substring(0, maxErrorMessageLength) + "... (截断)"
            } else {
                errorMessage
            }
            
            val failedTrade = FailedTrade(
                leaderId = leaderId,
                leaderTradeId = trade.id,
                tradeType = trade.side.uppercase(),
                copyTradingId = copyTradingId,
                accountId = accountId,
                marketId = trade.market,
                side = side,
                price = price,
                size = size,
                errorMessage = finalErrorMessage,
                retryCount = retryCount,
                failedAt = System.currentTimeMillis()
            )
            failedTradeRepository.save(failedTrade)
            
            // 记录日志，确认已保存到数据库
            logger.info("失败交易已保存到数据库: leaderId=$leaderId, tradeId=${trade.id}, errorMessageLength=${finalErrorMessage.length}")
            
            // 标记为已处理（失败状态），避免重复处理
            // 注意：并发情况下可能多个请求同时处理同一笔交易，需要处理唯一约束冲突
            try {
                val processed = ProcessedTrade(
                    leaderId = leaderId,
                    leaderTradeId = trade.id,
                    tradeType = trade.side.uppercase(),
                    source = "polling",
                    status = "FAILED",
                    processedAt = System.currentTimeMillis()
                )
                processedTradeRepository.save(processed)
            } catch (e: DataIntegrityViolationException) {
                // 唯一约束冲突，说明已经处理过了（可能是并发请求）
                // 检查现有记录的状态
                val existing = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                if (existing != null) {
                    if (existing.status == "SUCCESS") {
                        logger.warn("交易已成功处理，但尝试记录为失败（并发冲突）: leaderId=$leaderId, tradeId=${trade.id}")
                    } else {
                        logger.debug("交易已标记为失败（并发检测）: leaderId=$leaderId, tradeId=${trade.id}")
                    }
                } else {
                    logger.warn("保存ProcessedTrade失败记录时发生唯一约束冲突，但查询不到记录: leaderId=$leaderId, tradeId=${trade.id}", e)
                }
            }
            
            logger.warn("已记录失败交易: leaderId=$leaderId, tradeId=${trade.id}, error=$errorMessage")
        } catch (e: Exception) {
            logger.error("记录失败交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
        }
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
        copyTrading: CopyTrading,
        template: CopyTradingTemplate,
        quantity: BigDecimal,
        price: BigDecimal
    ): Pair<Boolean, String> {
        // 1. 检查每日订单数限制
        val todayStart = System.currentTimeMillis() - (System.currentTimeMillis() % 86400000)  // 今天0点的时间戳
        val todayBuyOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTrading.id!!)
            .filter { it.createdAt >= todayStart }
        
        if (todayBuyOrders.size >= template.maxDailyOrders) {
            return Pair(false, "今日订单数已达上限: ${todayBuyOrders.size}/${template.maxDailyOrders}")
        }
        
        // 2. 检查每日亏损限制（需要计算今日已实现盈亏）
        val todaySellRecords = sellMatchRecordRepository.findByCopyTradingId(copyTrading.id!!)
            .filter { it.createdAt >= todayStart }
        
        val todayRealizedPnl = todaySellRecords.sumOf { it.totalRealizedPnl.toSafeBigDecimal() }
        if (todayRealizedPnl.lt(BigDecimal.ZERO)) {
            val todayLoss = todayRealizedPnl.abs()
            if (todayLoss.gte(template.maxDailyLoss)) {
                return Pair(false, "今日亏损已达上限: ${todayLoss}/${template.maxDailyLoss}")
            }
        }
        
        return Pair(true, "")
    }
    
    /**
     * 计算调整后的价格（应用价格容忍度）
     */
    private fun calculateAdjustedPrice(
        originalPrice: BigDecimal,
        template: CopyTradingTemplate,
        isBuy: Boolean
    ): BigDecimal {
        // 如果价格容忍度为0，直接返回原价格
        if (template.priceTolerance.eq(BigDecimal.ZERO)) {
            return originalPrice
        }
        
        // 计算价格调整范围（百分比）
        val tolerancePercent = template.priceTolerance.div(100)
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

