package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import jakarta.annotation.PostConstruct
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 仓位检查服务
 * 负责检查待赎回仓位和未卖出订单，并执行相应的处理逻辑
 */
@Service
class PositionCheckService(
    private val accountService: AccountService,
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val systemConfigService: SystemConfigService,
    private val relayClientService: RelayClientService,
    private val telegramNotificationService: TelegramNotificationService?,
    private val accountRepository: AccountRepository,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(PositionCheckService::class.java)
    
    // 协程作用域，用于缓存清理任务
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 记录已发送通知的仓位（避免重复推送）
    private val notifiedRedeemablePositions = ConcurrentHashMap<String, Long>()  // "accountId_marketId_outcomeIndex" -> lastNotificationTime
    
    // 记录已发送提示的配置（避免重复推送）
    private val notifiedConfigs = ConcurrentHashMap<Long, Long>()  // accountId/copyTradingId -> lastNotificationTime
    
    /**
     * 初始化服务（启动缓存清理任务）
     */
    @PostConstruct
    fun init() {
        startCacheCleanup()
    }
    
    /**
     * 启动缓存清理任务（定期清理过期的通知记录）
     */
    private fun startCacheCleanup() {
        scope.launch {
            while (isActive) {
                try {
                    delay(7200000)  // 每2小时清理一次
                    cleanupExpiredCache()
                } catch (e: Exception) {
                    logger.error("清理缓存异常: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 清理过期的缓存条目（超过2小时的记录）
     */
    private fun cleanupExpiredCache() {
        val now = System.currentTimeMillis()
        val expireTime = 7200000  // 2小时
        
        // 清理过期的仓位通知记录
        val expiredPositions = notifiedRedeemablePositions.entries.filter { (_, timestamp) ->
            (now - timestamp) > expireTime
        }
        expiredPositions.forEach { (key, _) ->
            notifiedRedeemablePositions.remove(key)
        }
        
        // 清理过期的配置通知记录
        val expiredConfigs = notifiedConfigs.entries.filter { (_, timestamp) ->
            (now - timestamp) > expireTime
        }
        expiredConfigs.forEach { (key, _) ->
            notifiedConfigs.remove(key)
        }
        
        if (expiredPositions.isNotEmpty() || expiredConfigs.isNotEmpty()) {
            logger.debug("清理过期缓存: positions=${expiredPositions.size}, configs=${expiredConfigs.size}")
        }
    }
    
    /**
     * 检查仓位（主入口）
     * 根据 positionloop.md 文档要求：
     * 1. 处理待赎回仓位
     * 2. 处理未卖出订单
     */
    suspend fun checkPositions(currentPositions: List<AccountPositionDto>) {
        try {
            // 逻辑1：处理待赎回仓位
            val redeemablePositions = currentPositions.filter { it.redeemable }
            if (redeemablePositions.isNotEmpty()) {
                checkRedeemablePositions(redeemablePositions)
            }
            
            // 逻辑2：处理未卖出订单（如果没有待赎回仓位或已处理完）
            checkUnmatchedOrders(currentPositions)
        } catch (e: Exception) {
            logger.error("仓位检查异常: ${e.message}", e)
        }
    }
    
    /**
     * 逻辑1：处理待赎回仓位
     * 如果有待赎回的仓位，检查是否开启了自动赎回，是否有相同仓位的订单(未卖出的订单)
     * 如果有的话，在仓位赎回成功后以该订单卖出逻辑更新所有订单状态(未卖出)
     * 如果未开启自动赎回，则发送tg通知，并且记录（内存缓存）该仓位，避免重复发送
     */
    private suspend fun checkRedeemablePositions(redeemablePositions: List<AccountPositionDto>) {
        try {
            // 检查系统级别的自动赎回配置
            val autoRedeemEnabled = systemConfigService.isAutoRedeemEnabled()
            
            // 按账户分组
            val positionsByAccount = redeemablePositions.groupBy { it.accountId }
            
            for ((accountId, positions) in positionsByAccount) {
                // 查找该账户下所有启用的跟单配置
                val copyTradings = copyTradingRepository.findByAccountId(accountId)
                    .filter { it.enabled }
                
                if (copyTradings.isEmpty()) {
                    continue
                }
                
                // 收集所有有未卖出订单的仓位，按账户分组一次性处理
                val positionsWithOrders = mutableListOf<Pair<AccountPositionDto, List<CopyOrderTracking>>>()
                val positionsWithoutOrders = mutableListOf<AccountPositionDto>()
                
                for (position in positions) {
                    // 查找相同仓位的未卖出订单（remaining_quantity > 0）
                    val unmatchedOrders = mutableListOf<CopyOrderTracking>()
                    for (copyTrading in copyTradings) {
                        if (position.outcomeIndex != null) {
                            val orders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndex(
                                copyTrading.id!!,
                                position.marketId,
                                position.outcomeIndex
                            )
                            unmatchedOrders.addAll(orders)
                        }
                    }
                    
                    if (unmatchedOrders.isNotEmpty()) {
                        positionsWithOrders.add(Pair(position, unmatchedOrders))
                    } else {
                        positionsWithoutOrders.add(position)
                    }
                }
                
                // 处理有未卖出订单的仓位
                if (positionsWithOrders.isNotEmpty()) {
                    if (autoRedeemEnabled && relayClientService.isBuilderApiKeyConfigured()) {
                        // 开启自动赎回且已配置 API Key，执行自动赎回（一次性赎回该账户的所有可赎回仓位）
                        val redeemRequest = com.wrbug.polymarketbot.dto.PositionRedeemRequest(
                            positions = positionsWithOrders.map { (position, _) ->
                                com.wrbug.polymarketbot.dto.AccountRedeemPositionItem(
                                    accountId = accountId,
                                    marketId = position.marketId,
                                    outcomeIndex = position.outcomeIndex ?: 0,
                                    side = position.side
                                )
                            }
                        )
                        
                        val redeemResult = accountService.redeemPositions(redeemRequest)
                        redeemResult.fold(
                            onSuccess = { response ->
                                logger.info("自动赎回成功: accountId=$accountId, redeemedCount=${positionsWithOrders.size}, totalValue=${response.totalRedeemedValue}")
                                // 在仓位赎回成功后，以该订单卖出逻辑更新所有订单状态（未卖出）
                                for ((position, orders) in positionsWithOrders) {
                                    updateOrdersAsSoldAfterRedeem(orders, position)
                                }
                            },
                            onFailure = { e ->
                                logger.error("自动赎回失败: accountId=$accountId, error=${e.message}", e)
                            }
                        )
                    } else {
                        // 未开启自动赎回或未配置 API Key，发送通知
                        for ((position, _) in positionsWithOrders) {
                            val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                            if (!autoRedeemEnabled) {
                                checkAndNotifyAutoRedeemDisabled(accountId, listOf(position))
                            } else {
                                // API Key 未配置
                                for (copyTrading in copyTradings) {
                                    checkAndNotifyBuilderApiKeyNotConfigured(copyTrading, listOf(position))
                                }
                            }
                            // 记录已发送通知的仓位（避免重复发送）
                            notifiedRedeemablePositions[positionKey] = System.currentTimeMillis()
                        }
                    }
                }
                
                // 处理没有未卖出订单的仓位（如果未开启自动赎回，发送通知）
                if (positionsWithoutOrders.isNotEmpty() && !autoRedeemEnabled) {
                    for (position in positionsWithoutOrders) {
                        val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                        // 检查是否在最近2小时内已发送过提示（避免频繁推送）
                        val lastNotification = notifiedRedeemablePositions[positionKey]
                        val now = System.currentTimeMillis()
                        if (lastNotification == null || (now - lastNotification) >= 7200000) {  // 2小时
                            checkAndNotifyAutoRedeemDisabled(accountId, listOf(position))
                            notifiedRedeemablePositions[positionKey] = now
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("处理待赎回仓位异常: ${e.message}", e)
        }
    }
    
    /**
     * 逻辑2：处理未卖出订单
     * 检查所有未卖出的订单，匹配仓位
     * 如果仓位不存在，则更新订单状态为已卖出，卖出价为当前最新价
     * 如果发现有仓位，并且仓位数量小于所有未卖出订单数量总和，则按照订单下单顺序更新状态，卖出价价格为最新价
     */
    private suspend fun checkUnmatchedOrders(currentPositions: List<AccountPositionDto>) {
        try {
            // 获取所有启用的跟单配置
            val allCopyTradings = copyTradingRepository.findAll().filter { it.enabled }
            
            // 按账户和市场分组当前仓位
            val positionsByAccountAndMarket = currentPositions.groupBy { 
                "${it.accountId}_${it.marketId}_${it.outcomeIndex ?: 0}"
            }
            
            // 遍历所有跟单配置
            for (copyTrading in allCopyTradings) {
                // 查找该跟单配置下所有未卖出的订单（remaining_quantity > 0）
                val unmatchedOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTrading.id!!)
                    .filter { it.remainingQuantity > BigDecimal.ZERO }
                    .sortedBy { it.createdAt }  // 按创建时间排序（FIFO）
                
                if (unmatchedOrders.isEmpty()) {
                    continue
                }
                
                // 按市场分组订单
                val ordersByMarket = unmatchedOrders.groupBy { 
                    "${it.marketId}_${it.outcomeIndex ?: 0}"
                }
                
                for ((marketKey, orders) in ordersByMarket) {
                    // 从订单中获取市场信息
                    val firstOrder = orders.firstOrNull() ?: continue
                    val marketId = firstOrder.marketId
                    val outcomeIndex = firstOrder.outcomeIndex ?: 0
                    
                    // 查找对应的仓位
                    val positionKey = "${copyTrading.accountId}_$marketKey"
                    val position = positionsByAccountAndMarket[positionKey]?.firstOrNull()
                    
                    if (position == null) {
                        // 仓位不存在，更新所有订单状态为已卖出
                        val currentPrice = getCurrentMarketPrice(marketId, outcomeIndex)
                        updateOrdersAsSold(orders, currentPrice)
                    } else {
                        // 有仓位，检查仓位数量是否小于所有未卖出订单数量总和
                        val totalUnmatchedQuantity = orders.sumOf { it.remainingQuantity.toSafeBigDecimal() }
                        val positionQuantity = position.quantity.toSafeBigDecimal()
                        
                        if (positionQuantity < totalUnmatchedQuantity) {
                            // 仓位数量小于订单数量总和，按订单下单顺序（FIFO）更新状态
                            val currentPrice = getCurrentMarketPrice(marketId, outcomeIndex)
                            updateOrdersAsSoldByFIFO(orders, positionQuantity, currentPrice)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("处理未卖出订单异常: ${e.message}", e)
        }
    }
    
    /**
     * 获取当前市场最新价（用于更新订单卖出价）
     * 优先使用 bestBid（最优买价），如果没有则使用 midpoint（中间价）
     */
    private suspend fun getCurrentMarketPrice(marketId: String, outcomeIndex: Int): BigDecimal {
        return try {
            val priceResult = accountService.getMarketPrice(marketId, outcomeIndex)
            val marketPrice = priceResult.getOrNull()
            if (marketPrice != null) {
                // 优先使用 bestBid（最优买价，用于卖出参考），如果没有则使用 midpoint
                val priceStr = marketPrice.bestBid ?: marketPrice.midpoint ?: marketPrice.lastPrice
                priceStr?.toSafeBigDecimal() ?: BigDecimal.ZERO
            } else {
                BigDecimal.ZERO
            }
        } catch (e: Exception) {
            logger.error("获取市场最新价失败: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}", e)
            BigDecimal.ZERO
        }
    }
    
    /**
     * 在仓位赎回成功后，更新订单状态为已卖出
     * 使用卖出逻辑更新所有订单状态（未卖出订单的）
     */
    private suspend fun updateOrdersAsSoldAfterRedeem(
        orders: List<CopyOrderTracking>,
        position: AccountPositionDto
    ) {
        try {
            val currentPrice = getCurrentMarketPrice(position.marketId, position.outcomeIndex ?: 0)
            updateOrdersAsSold(orders, currentPrice)
        } catch (e: Exception) {
            logger.error("更新订单状态为已卖出失败: ${e.message}", e)
        }
    }
    
    /**
     * 更新订单状态为已卖出（使用当前最新价）
     */
    private suspend fun updateOrdersAsSold(
        orders: List<CopyOrderTracking>,
        sellPrice: BigDecimal
    ) {
        try {
            for (order in orders) {
                // 更新订单状态：将剩余数量标记为已匹配
                order.matchedQuantity = order.matchedQuantity.add(order.remainingQuantity)
                order.remainingQuantity = BigDecimal.ZERO
                order.status = "fully_matched"
                order.updatedAt = System.currentTimeMillis()
                copyOrderTrackingRepository.save(order)
                
                logger.info("更新订单状态为已卖出: orderId=${order.buyOrderId}, marketId=${order.marketId}, sellPrice=$sellPrice")
            }
        } catch (e: Exception) {
            logger.error("更新订单状态为已卖出异常: ${e.message}", e)
        }
    }
    
    /**
     * 按 FIFO 顺序更新订单状态为已卖出
     * 仓位数量小于订单数量总和时，按订单下单顺序更新
     */
    private suspend fun updateOrdersAsSoldByFIFO(
        orders: List<CopyOrderTracking>,
        availableQuantity: BigDecimal,
        sellPrice: BigDecimal
    ) {
        try {
            // 订单已经按 createdAt ASC 排序（FIFO）
            var remaining = availableQuantity
            
            for (order in orders) {
                if (remaining <= BigDecimal.ZERO) {
                    break
                }
                
                val orderRemaining = order.remainingQuantity.toSafeBigDecimal()
                val toMatch = minOf(orderRemaining, remaining)
                
                if (toMatch > BigDecimal.ZERO) {
                    order.matchedQuantity = order.matchedQuantity.add(toMatch)
                    order.remainingQuantity = order.remainingQuantity.subtract(toMatch)
                    
                    // 更新状态
                    if (order.remainingQuantity <= BigDecimal.ZERO) {
                        order.status = "fully_matched"
                    } else {
                        order.status = "partially_matched"
                    }
                    
                    order.updatedAt = System.currentTimeMillis()
                    copyOrderTrackingRepository.save(order)
                    
                    remaining = remaining.subtract(toMatch)
                    
                    logger.info("按 FIFO 更新订单状态: orderId=${order.buyOrderId}, matched=$toMatch, remaining=${order.remainingQuantity}")
                }
            }
        } catch (e: Exception) {
            logger.error("按 FIFO 更新订单状态异常: ${e.message}", e)
        }
    }
    
    /**
     * 检查并通知自动赎回未开启
     */
    private suspend fun checkAndNotifyAutoRedeemDisabled(accountId: Long, positions: List<AccountPositionDto>) {
        if (telegramNotificationService == null) {
            return
        }
        
        // 检查是否在最近2小时内已发送过提示（避免频繁推送）
        val lastNotification = notifiedConfigs[accountId]
        val now = System.currentTimeMillis()
        if (lastNotification != null && (now - lastNotification) < 7200000) {  // 2小时
            return
        }
        
        try {
            val account = accountRepository.findById(accountId).orElse(null)
            if (account == null) {
                return
            }
            
            // 计算可赎回总价值
            val totalValue = positions.fold(BigDecimal.ZERO) { sum, pos ->
                sum.add(pos.quantity.toSafeBigDecimal())
            }
            
            val message = buildAutoRedeemDisabledMessage(
                accountName = account.accountName,
                walletAddress = account.walletAddress,
                totalValue = totalValue.toPlainString(),
                positionCount = positions.size
            )
            
            telegramNotificationService.sendMessage(message)
            notifiedConfigs[accountId] = now
        } catch (e: Exception) {
            logger.error("发送自动赎回未开启提示失败: accountId=$accountId, ${e.message}", e)
        }
    }
    
    /**
     * 检查并通知 Builder API Key 未配置
     */
    private suspend fun checkAndNotifyBuilderApiKeyNotConfigured(
        copyTrading: CopyTrading,
        positions: List<AccountPositionDto>
    ) {
        if (telegramNotificationService == null) {
            return
        }
        
        // 检查是否在最近2小时内已发送过提示（避免频繁推送）
        val copyTradingId = copyTrading.id ?: return
        val lastNotification = notifiedConfigs[copyTradingId]
        val now = System.currentTimeMillis()
        if (lastNotification != null && (now - lastNotification) < 7200000) {  // 2小时
            return
        }
        
        try {
            val account = accountRepository.findById(copyTrading.accountId).orElse(null)
            if (account == null) {
                return
            }
            
            // 计算可赎回总价值
            val totalValue = positions.fold(BigDecimal.ZERO) { sum, pos ->
                sum.add(pos.quantity.toSafeBigDecimal())
            }
            
            val message = buildBuilderApiKeyNotConfiguredMessage(
                accountName = account.accountName,
                walletAddress = account.walletAddress,
                configName = copyTrading.configName,
                totalValue = totalValue.toPlainString(),
                positionCount = positions.size
            )
            
            telegramNotificationService.sendMessage(message)
            notifiedConfigs[copyTradingId] = now
        } catch (e: Exception) {
            logger.error("发送 Builder API Key 未配置提示失败: copyTradingId=$copyTradingId, ${e.message}", e)
        }
    }
    
    /**
     * 构建自动赎回未开启消息
     */
    private fun buildAutoRedeemDisabledMessage(
        accountName: String?,
        walletAddress: String?,
        totalValue: String,
        positionCount: Int
    ): String {
        // 获取当前语言设置
        val locale = try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            java.util.Locale("zh", "CN")
        }
        
        val accountInfo = accountName ?: (walletAddress?.let { maskAddress(it) } ?: messageSource.getMessage("common.unknown", null, "未知", locale))
        val totalValueDisplay = try {
            val totalValueDecimal = totalValue.toSafeBigDecimal()
            val formatted = if (totalValueDecimal.scale() > 4) {
                totalValueDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
            } else {
                totalValueDecimal.stripTrailingZeros().toPlainString()
            }
            formatted
        } catch (e: Exception) {
            totalValue
        }
        
        // 获取多语言文本
        val title = messageSource.getMessage("notification.auto_redeem.disabled.title", null, "自动赎回未开启", locale)
        val accountLabel = messageSource.getMessage("notification.auto_redeem.disabled.account", null, "账户", locale)
        val positionsLabel = messageSource.getMessage("notification.auto_redeem.disabled.redeemable_positions", null, "可赎回仓位", locale)
        val positionsUnit = messageSource.getMessage("notification.auto_redeem.disabled.positions_unit", null, "个", locale)
        val totalValueLabel = messageSource.getMessage("notification.auto_redeem.disabled.total_value", null, "总价值", locale)
        val message = messageSource.getMessage("notification.auto_redeem.disabled.message", null, "请在系统设置中开启自动赎回功能。", locale)
        
        return "⚠️ $title\n\n" +
                "$accountLabel: $accountInfo\n" +
                "$positionsLabel: $positionCount $positionsUnit\n" +
                "$totalValueLabel: $totalValueDisplay USDC\n\n" +
                message
    }
    
    /**
     * 构建 Builder API Key 未配置消息
     */
    private fun buildBuilderApiKeyNotConfiguredMessage(
        accountName: String?,
        walletAddress: String?,
        configName: String?,
        totalValue: String,
        positionCount: Int
    ): String {
        // 获取当前语言设置
        val locale = try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            java.util.Locale("zh", "CN")
        }
        
        val accountInfo = accountName ?: (walletAddress?.let { maskAddress(it) } ?: messageSource.getMessage("common.unknown", null, "未知", locale))
        val unknownConfig = messageSource.getMessage("notification.builder_api_key.not_configured.unknown_config", null, "未命名配置", locale)
        val configInfo = configName ?: unknownConfig
        val totalValueDisplay = try {
            val totalValueDecimal = totalValue.toSafeBigDecimal()
            val formatted = if (totalValueDecimal.scale() > 4) {
                totalValueDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
            } else {
                totalValueDecimal.stripTrailingZeros().toPlainString()
            }
            formatted
        } catch (e: Exception) {
            totalValue
        }
        
        // 获取多语言文本
        val title = messageSource.getMessage("notification.builder_api_key.not_configured.title", null, "Builder API Key 未配置", locale)
        val accountLabel = messageSource.getMessage("notification.builder_api_key.not_configured.account", null, "账户", locale)
        val configLabel = messageSource.getMessage("notification.builder_api_key.not_configured.copy_trading_config", null, "跟单配置", locale)
        val positionsLabel = messageSource.getMessage("notification.builder_api_key.not_configured.redeemable_positions", null, "可赎回仓位", locale)
        val positionsUnit = messageSource.getMessage("notification.builder_api_key.not_configured.positions_unit", null, "个", locale)
        val totalValueLabel = messageSource.getMessage("notification.builder_api_key.not_configured.total_value", null, "总价值", locale)
        val message = messageSource.getMessage("notification.builder_api_key.not_configured.message", null, "请在系统设置中配置 Builder API Key 以启用自动赎回功能。", locale)
        
        return "⚠️ $title\n\n" +
                "$accountLabel: $accountInfo\n" +
                "$configLabel: $configInfo\n" +
                "$positionsLabel: $positionCount $positionsUnit\n" +
                "$totalValueLabel: $totalValueDisplay USDC\n\n" +
                message
    }
    
    /**
     * 掩码地址（只显示前6位和后4位）
     */
    private fun maskAddress(address: String): String {
        if (address.length <= 10) {
            return address
        }
        return "${address.take(6)}...${address.takeLast(4)}"
    }
}

