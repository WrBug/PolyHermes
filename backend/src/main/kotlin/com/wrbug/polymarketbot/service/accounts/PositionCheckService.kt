package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.multi
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import com.wrbug.polymarketbot.service.system.SystemConfigService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.service.common.MarketPriceService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 仓位检查服务
 * 负责检查待赎回仓位和未卖出订单，并执行相应的处理逻辑
 * 订阅 PositionPollingService 的事件，处理仓位检查逻辑
 */
@Service
class PositionCheckService(
    private val positionPollingService: PositionPollingService,
    private val accountService: AccountService,
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val systemConfigService: SystemConfigService,
    private val relayClientService: RelayClientService,
    private val telegramNotificationService: TelegramNotificationService?,
    private val accountRepository: AccountRepository,
    private val messageSource: MessageSource,
    private val marketPriceService: MarketPriceService
) {
    
    private val logger = LoggerFactory.getLogger(PositionCheckService::class.java)
    
    // 协程作用域，用于订阅事件和缓存清理任务
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var subscriptionJob: Job? = null
    
    // 记录已发送通知的仓位（避免重复推送）
    private val notifiedRedeemablePositions = ConcurrentHashMap<String, Long>()  // "accountId_marketId_outcomeIndex" -> lastNotificationTime
    
    // 记录已处理的赎回仓位（避免重复赎回）
    private val processedRedeemablePositions = ConcurrentHashMap<String, Long>()  // "accountId_marketId_outcomeIndex" -> lastProcessTime
    
    // 记录已发送提示的配置（避免重复推送）
    private val notifiedConfigs = ConcurrentHashMap<Long, Long>()  // accountId/copyTradingId -> lastNotificationTime
    
    // 待检查的仓位记录（延迟检测机制）
    // key: "accountId_marketId_outcomeIndex_copyTradingId"
    // value: PendingPositionCheck（包含订单列表和首次检测时间）
    private data class PendingPositionCheck(
        val accountId: Long,
        val marketId: String,
        val outcomeIndex: Int,
        val copyTradingId: Long,
        val orders: List<CopyOrderTracking>,
        val firstDetectedTime: Long  // 首次检测到仓位不存在的时间
    )
    private val pendingPositionChecks = ConcurrentHashMap<String, PendingPositionCheck>()
    
    // 同步锁，确保订阅任务的启动和停止是线程安全的
    private val lock = Any()
    
    /**
     * 初始化服务（订阅 PositionPollingService 的事件，启动缓存清理任务）
     */
    @PostConstruct
    fun init() {
        logger.info("PositionCheckService 初始化，订阅仓位轮训事件")
        startSubscription()
        startCacheCleanup()
        startPendingPositionCheckTask()
    }
    
    /**
     * 清理资源
     */
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            subscriptionJob?.cancel()
            subscriptionJob = null
        }
        scope.cancel()
    }
    
    /**
     * 启动订阅任务（订阅 PositionPollingService 的事件）
     */
    private fun startSubscription() {
        synchronized(lock) {
            // 如果已经有订阅任务在运行，先取消
            subscriptionJob?.cancel()
            
            // 启动新的订阅任务（使用专门的线程，避免阻塞）
            subscriptionJob = scope.launch(Dispatchers.IO) {
                try {
                    // 订阅仓位轮训事件
                    positionPollingService.subscribe { positions ->
                        // 在协程中处理仓位检查逻辑，避免阻塞
                        scope.launch(Dispatchers.IO) {
                            try {
                                checkPositions(positions.currentPositions)
                            } catch (e: Exception) {
                                logger.error("处理仓位检查事件失败: ${e.message}", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("订阅仓位轮训事件失败: ${e.message}", e)
                }
            }
        }
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
     * 启动待检查仓位的定期检查任务
     * 每30秒检查一次，如果超过3分钟且确实不存在，则标记为已卖出
     */
    private fun startPendingPositionCheckTask() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(30000)  // 每30秒检查一次
                    checkPendingPositions()
                } catch (e: Exception) {
                    logger.error("检查待检查仓位异常: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 检查待检查的仓位
     * 如果超过3分钟且确实不存在，则标记为已卖出
     * 如果存在，则删除记录
     */
    private suspend fun checkPendingPositions() {
        if (pendingPositionChecks.isEmpty()) {
            return
        }
        
        try {
            // 获取最新的仓位数据
            val result = accountService.getAllPositions()
            if (result.isFailure) {
                logger.warn("获取仓位数据失败，跳过待检查仓位验证: ${result.exceptionOrNull()?.message}")
                return
            }
            
            val positionListResponse = result.getOrNull() ?: return
            val currentPositions = positionListResponse.currentPositions
            
            // 按账户和市场分组当前仓位
            val positionsByAccountAndMarket = currentPositions.groupBy { 
                "${it.accountId}_${it.marketId}_${it.outcomeIndex ?: 0}"
            }
            
            val now = System.currentTimeMillis()
            val checkDelay = 180000L  // 3分钟 = 180000毫秒
            val toRemove = mutableListOf<String>()
            val toMarkAsSold = mutableListOf<PendingPositionCheck>()
            
            // 遍历所有待检查的仓位
            for ((key, pendingCheck) in pendingPositionChecks) {
                // 先过滤出仍然有效的订单（remainingQuantity > 0）
                val validOrders = pendingCheck.orders.filter { order ->
                    // 重新从数据库查询订单状态，确保数据是最新的
                    val currentOrder = copyOrderTrackingRepository.findById(order.id!!).orElse(null)
                    currentOrder != null && currentOrder.remainingQuantity > BigDecimal.ZERO
                }
                
                // 如果没有有效订单了，删除记录
                if (validOrders.isEmpty()) {
                    toRemove.add(key)
                    logger.info("待检查仓位的订单已全部处理，删除记录: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}")
                    continue
                }
                
                val positionKey = "${pendingCheck.accountId}_${pendingCheck.marketId}_${pendingCheck.outcomeIndex}"
                val position = positionsByAccountAndMarket[positionKey]?.firstOrNull()
                
                if (position != null) {
                    // 仓位存在，删除记录
                    toRemove.add(key)
                    logger.info("待检查仓位已恢复，删除记录: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${now - pendingCheck.firstDetectedTime}ms")
                } else {
                    // 仓位不存在，检查是否超过3分钟
                    val elapsedTime = now - pendingCheck.firstDetectedTime
                    if (elapsedTime >= checkDelay) {
                        // 超过3分钟且确实不存在，标记为已卖出（使用有效订单）
                        toMarkAsSold.add(pendingCheck.copy(orders = validOrders))
                        toRemove.add(key)
                        logger.info("待检查仓位超过3分钟仍不存在，标记为已卖出: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${elapsedTime}ms, validOrderCount=${validOrders.size}, originalOrderCount=${pendingCheck.orders.size}")
                    } else {
                        // 未超过3分钟，更新订单列表（移除已处理的订单）
                        if (validOrders.size < pendingCheck.orders.size) {
                            pendingPositionChecks[key] = pendingCheck.copy(orders = validOrders)
                            logger.debug("更新待检查仓位记录，移除已处理的订单: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, validOrderCount=${validOrders.size}, originalOrderCount=${pendingCheck.orders.size}")
                        }
                        logger.debug("待检查仓位仍不存在，继续等待: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${elapsedTime}ms, remainingTime=${checkDelay - elapsedTime}ms")
                    }
                }
            }
            
            // 删除已恢复或已处理的记录
            toRemove.forEach { key ->
                pendingPositionChecks.remove(key)
            }
            
            // 标记为已卖出
            for (pendingCheck in toMarkAsSold) {
                try {
                    val currentPrice = getCurrentMarketPrice(pendingCheck.marketId, pendingCheck.outcomeIndex)
                    updateOrdersAsSold(
                        pendingCheck.orders,
                        currentPrice,
                        pendingCheck.copyTradingId,
                        pendingCheck.marketId,
                        pendingCheck.outcomeIndex
                    )
                } catch (e: Exception) {
                    logger.error("标记待检查仓位为已卖出失败: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, error=${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("检查待检查仓位异常: ${e.message}", e)
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
        
        // 清理过期的已处理赎回仓位记录
        val expiredProcessed = processedRedeemablePositions.entries.filter { (_, timestamp) ->
            (now - timestamp) > expireTime
        }
        expiredProcessed.forEach { (key, _) ->
            processedRedeemablePositions.remove(key)
        }
        
        // 清理过期的配置通知记录
        val expiredConfigs = notifiedConfigs.entries.filter { (_, timestamp) ->
            (now - timestamp) > expireTime
        }
        expiredConfigs.forEach { (key, _) ->
            notifiedConfigs.remove(key)
        }
        
        // 清理过期的待检查仓位记录（超过1小时的记录，正常情况下应该在3分钟内处理完）
        val expiredPendingChecks = pendingPositionChecks.entries.filter { (_, check) ->
            (now - check.firstDetectedTime) > 3600000  // 1小时
        }
        expiredPendingChecks.forEach { (key, _) ->
            pendingPositionChecks.remove(key)
        }
        
        if (expiredPositions.isNotEmpty() || expiredProcessed.isNotEmpty() || expiredConfigs.isNotEmpty() || expiredPendingChecks.isNotEmpty()) {
            logger.debug("清理过期缓存: positions=${expiredPositions.size}, processed=${expiredProcessed.size}, configs=${expiredConfigs.size}, pendingChecks=${expiredPendingChecks.size}")
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
    https://clob.polymarket.com     * 按照以下逻辑处理：
     * 1. 无待赎回仓位：跳过
     * 2. (未配置apikey || autoredeem==false) && 有待赎回的仓位：发送通知事件
     * 3. (已配置) && 有待赎回的仓位：处理订单逻辑
     */
    private suspend fun checkRedeemablePositions(redeemablePositions: List<AccountPositionDto>) {
        try {
            // 1. 无待赎回仓位：跳过
            if (redeemablePositions.isEmpty()) {
                return
            }
            
            // 检查系统级别的自动赎回配置
            val autoRedeemEnabled = systemConfigService.isAutoRedeemEnabled()
            val apiKeyConfigured = relayClientService.isBuilderApiKeyConfigured()
            
            // 2. (未配置apikey || autoredeem==false) && 有待赎回的仓位：发送通知事件
            if (!autoRedeemEnabled || !apiKeyConfigured) {
                // 按账户分组发送通知
                val positionsByAccount = redeemablePositions.groupBy { it.accountId }
                
                for ((accountId, positions) in positionsByAccount) {
                    for (position in positions) {
                        val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                        // 检查是否在最近2小时内已发送过提示（避免频繁推送）
                        val lastNotification = notifiedRedeemablePositions[positionKey]
                        val now = System.currentTimeMillis()
                        if (lastNotification == null || (now - lastNotification) >= 7200000) {  // 2小时
                            if (!autoRedeemEnabled) {
                                // 自动赎回未开启：直接发送通知，不需要查找跟单配置
                                checkAndNotifyAutoRedeemDisabled(accountId, listOf(position))
                            } else {
                                // API Key 未配置：需要查找跟单配置来发送通知
                                val copyTradings = copyTradingRepository.findByAccountId(accountId)
                                    .filter { it.enabled }
                                for (copyTrading in copyTradings) {
                                    checkAndNotifyBuilderApiKeyNotConfigured(copyTrading, listOf(position))
                                }
                            }
                            notifiedRedeemablePositions[positionKey] = now
                        }
                    }
                }
                return  // 未配置时直接返回，不进行后续处理
            }
            
            // 3. (已配置) && 有待赎回的仓位：处理订单逻辑
            // 自动赎回已开启且已配置 API Key，按账户分组进行赎回处理
            // 先执行赎回，赎回成功后再查找订单并更新订单状态
            val positionsByAccount = redeemablePositions.groupBy { it.accountId }
            
            for ((accountId, positions) in positionsByAccount) {
                // 查找该账户下所有启用的跟单配置（仅用于赎回成功后更新跟单订单状态；无跟单配置的账户如尾盘策略账户也会执行赎回）
                val copyTradings = copyTradingRepository.findByAccountId(accountId)
                    .filter { it.enabled }
                
                // 过滤掉已经处理过的仓位（去重，避免重复赎回）
                val now = System.currentTimeMillis()
                val positionsToRedeem = positions.filter { position ->
                    val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                    val lastProcessed = processedRedeemablePositions[positionKey]
                    // 如果最近30分钟内已经处理过，跳过（避免重复赎回）
                    if (lastProcessed != null && (now - lastProcessed) < 1800000) {  // 30分钟
                        logger.debug("跳过已处理的赎回仓位: $positionKey (上次处理时间: ${lastProcessed})")
                        false
                    } else {
                        true
                    }
                }
                
                if (positionsToRedeem.isEmpty()) {
                    logger.debug("所有仓位都已处理过，跳过赎回: accountId=$accountId")
                    continue
                }
                
                // 先执行赎回（不查找订单）
                val redeemRequest = com.wrbug.polymarketbot.dto.PositionRedeemRequest(
                    positions = positionsToRedeem.map { position ->
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
                        logger.info("自动赎回成功: accountId=$accountId, redeemedCount=${positionsToRedeem.size}, totalValue=${response.totalRedeemedValue}")
                        
                        // 记录已处理的仓位（避免重复赎回）
                        for (position in positionsToRedeem) {
                            val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                            processedRedeemablePositions[positionKey] = now
                        }
                        
                        // 赎回成功后，按每个跟单配置分别查找未卖出订单并更新状态
                        // 同一账户同一市场可能同时跟多个 Leader，需按 copyTradingId 分别生成自动卖出记录（如 leader1 对应 20 share，leader2 对应 16 share）
                        for (position in positionsToRedeem) {
                            if (position.outcomeIndex == null) {
                                continue
                            }
                            for (copyTrading in copyTradings) {
                                val orders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndex(
                                    copyTrading.id!!,
                                    position.marketId,
                                    position.outcomeIndex
                                )
                                if (orders.isNotEmpty()) {
                                    updateOrdersAsSoldAfterRedeem(orders, position, copyTrading.id!!)
                                }
                            }
                        }
                    },
                    onFailure = { e ->
                        logger.error("自动赎回失败: accountId=$accountId, error=${e.message}", e)
                    }
                )
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
                        // 仓位不存在，使用延迟检测机制
                        // 先查询创建时间超过2分钟的未匹配订单（SQL层过滤，避免刚创建的订单被误判）
                        val now = System.currentTimeMillis()
                        val thresholdTime = now - 120000  // 2分钟 = 120000毫秒

                        val ordersToCheck = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndexOlderThan(
                            copyTradingId = copyTrading.id!!,
                            marketId = marketId,
                            outcomeIndex = outcomeIndex,
                            thresholdTime = thresholdTime
                        )

                        if (ordersToCheck.isNotEmpty()) {
                            // 有订单创建时间超过2分钟，记录到待检查列表
                            val checkKey = "${copyTrading.accountId}_${marketId}_${outcomeIndex}_${copyTrading.id}"

                            // 如果已经存在记录，更新订单列表（可能订单状态有变化）
                            val existingCheck = pendingPositionChecks[checkKey]
                            if (existingCheck == null) {
                                // 首次检测到，记录
                                pendingPositionChecks[checkKey] = PendingPositionCheck(
                                    accountId = copyTrading.accountId,
                                    marketId = marketId,
                                    outcomeIndex = outcomeIndex,
                                    copyTradingId = copyTrading.id!!,
                                    orders = ordersToCheck,
                                    firstDetectedTime = now
                                )
                                logger.info("首次检测到仓位不存在，记录待检查: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}, orderCount=${ordersToCheck.size}, positionKey=$positionKey")
                            } else {
                                // 已存在记录，更新订单列表（可能订单状态有变化）
                                pendingPositionChecks[checkKey] = existingCheck.copy(orders = ordersToCheck)
                                logger.debug("更新待检查仓位记录: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}, orderCount=${ordersToCheck.size}, elapsedTime=${now - existingCheck.firstDetectedTime}ms")
                            }
                        } else {
                            // 订单创建时间不足2分钟，可能是刚创建的订单，暂时不处理
                            logger.debug("仓位不存在但无符合条件的订单（创建时间不足2分钟），暂不标记为已卖出: marketId=$marketId, outcomeIndex=$outcomeIndex, orderCount=${orders.size}, thresholdTime=$thresholdTime, positionKey=$positionKey")
                        }
                    } else {
                        // 有仓位，先检查是否有对应的待检查记录，如果有则删除（仓位已恢复）
                        val checkKey = "${copyTrading.accountId}_${marketId}_${outcomeIndex}_${copyTrading.id}"
                        val pendingCheck = pendingPositionChecks.remove(checkKey)
                        if (pendingCheck != null) {
                            logger.info("待检查仓位已恢复，删除待检查记录: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}, elapsedTime=${System.currentTimeMillis() - pendingCheck.firstDetectedTime}ms")
                        }
                        
                        // 有仓位，按订单下单顺序（FIFO）更新状态
                        // 先查询创建时间超过2分钟的未匹配订单（SQL层过滤，避免刚创建的订单被误判）
                        val now = System.currentTimeMillis()
                        val thresholdTime = now - 120000  // 2分钟 = 120000毫秒
                        
                        val validOrders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndexOlderThan(
                            copyTradingId = copyTrading.id!!,
                            marketId = marketId,
                            outcomeIndex = outcomeIndex,
                            thresholdTime = thresholdTime
                        )
                        
                        // 如果没有符合条件的订单，跳过处理
                        if (validOrders.isEmpty()) {
                            logger.debug("仓位存在但无符合条件的订单（创建时间不足2分钟），暂不进行FIFO匹配: marketId=$marketId, outcomeIndex=$outcomeIndex, thresholdTime=$thresholdTime")
                            continue
                        }
                        
                        // 计算逻辑：
                         // 1. 总订单数量 = 所有符合条件的未卖出订单的剩余数量总和
                        // 2. 已成交数量 = 总订单数量 - 仓位数量（因为还有仓位，说明部分订单已卖出）
                        // 3. 如果已成交数量 = 0，说明订单还没有卖出，不修改订单状态
                        // 4. 如果已成交数量 > 0，按FIFO顺序匹配订单
                        val positionQuantity = position.quantity.toSafeBigDecimal()

                        // 计算总订单数量（只计算符合条件的订单）
                        val totalOrderQuantity = validOrders.fold(BigDecimal.ZERO) { sum, order ->
                            sum.add(order.remainingQuantity.toSafeBigDecimal())
                        }

                        // 计算已成交数量
                        val soldQuantity = totalOrderQuantity.subtract(positionQuantity)

                        // 如果已成交数量 <= 0，说明订单还没有卖出，不修改订单状态
                        if (soldQuantity <= BigDecimal.ZERO) {
                            continue
                        }

                        // 如果已成交数量 > 0，按FIFO顺序匹配订单（只匹配符合条件的订单）
                        try {
                        val currentPrice = getCurrentMarketPrice(marketId, outcomeIndex)
                        updateOrdersAsSoldByFIFO(validOrders, soldQuantity, currentPrice,
                            copyTrading.id, marketId, outcomeIndex)
                        } catch (e: Exception) {
                            logger.warn("无法获取市场价格，跳过FIFO匹配: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
                            // 无法获取价格时，跳过该市场的处理，等待下次检查时再试
                            continue
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
     * 委托给 MarketPriceService 处理
     */
    private suspend fun getCurrentMarketPrice(marketId: String, outcomeIndex: Int): BigDecimal {
        return marketPriceService.getCurrentMarketPrice(marketId, outcomeIndex)
    }
    
    
    /**
     * 在仓位赎回成功后，更新订单状态为已卖出
     * 使用卖出逻辑更新所有订单状态（未卖出订单的）
     */
    private suspend fun updateOrdersAsSoldAfterRedeem(
        orders: List<CopyOrderTracking>,
        position: AccountPositionDto,
        copyTradingId: Long
    ) {
        try {
            val currentPrice = getCurrentMarketPrice(position.marketId, position.outcomeIndex ?: 0)
            updateOrdersAsSold(orders, currentPrice, copyTradingId, position.marketId, position.outcomeIndex ?: 0)
        } catch (e: Exception) {
            logger.error("更新订单状态为已卖出失败: ${e.message}", e)
        }
    }
    
    /**
     * 更新订单状态为已卖出（使用当前最新价）
     * 同时创建卖出记录和匹配明细，用于统计
     */
    private suspend fun updateOrdersAsSold(
        orders: List<CopyOrderTracking>,
        sellPrice: BigDecimal,
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    ) {
        if (orders.isEmpty()) {
            return
        }
        
        try {
            // 计算总匹配数量和总盈亏
            var totalMatchedQuantity = BigDecimal.ZERO
            var totalRealizedPnl = BigDecimal.ZERO
            val matchDetails = mutableListOf<SellMatchDetail>()
            
            for (order in orders) {
                val remainingQty = order.remainingQuantity.toSafeBigDecimal()
                if (remainingQty <= BigDecimal.ZERO) {
                    continue
                }
                
                // 计算盈亏
                val buyPrice = order.price.toSafeBigDecimal()
                val realizedPnl = sellPrice.subtract(buyPrice).multi(remainingQty)
                
                // 创建匹配明细（稍后保存）
                val detail = SellMatchDetail(
                    matchRecordId = 0,  // 稍后设置
                    trackingId = order.id!!,
                    buyOrderId = order.buyOrderId,
                    matchedQuantity = remainingQty,
                    buyPrice = buyPrice,
                    sellPrice = sellPrice,
                    realizedPnl = realizedPnl
                )
                matchDetails.add(detail)
                
                totalMatchedQuantity = totalMatchedQuantity.add(remainingQty)
                totalRealizedPnl = totalRealizedPnl.add(realizedPnl)
                
                // 更新订单状态：将剩余数量标记为已匹配
                order.matchedQuantity = order.matchedQuantity.add(remainingQty)
                order.remainingQuantity = BigDecimal.ZERO
                order.status = "fully_matched"
                order.updatedAt = System.currentTimeMillis()
                copyOrderTrackingRepository.save(order)
            }
            
            // 如果有匹配的订单，创建卖出记录
            if (totalMatchedQuantity > BigDecimal.ZERO && matchDetails.isNotEmpty()) {
                val timestamp = System.currentTimeMillis()
                val sellOrderId = "AUTO_${timestamp}_${copyTradingId}"
                val leaderSellTradeId = "AUTO_${timestamp}"
                
                val matchRecord = SellMatchRecord(
                    copyTradingId = copyTradingId,
                    sellOrderId = sellOrderId,
                    leaderSellTradeId = leaderSellTradeId,
                    marketId = marketId,
                    side = outcomeIndex.toString(),  // 使用outcomeIndex作为side
                    outcomeIndex = outcomeIndex,
                    totalMatchedQuantity = totalMatchedQuantity,
                    sellPrice = sellPrice,
                    totalRealizedPnl = totalRealizedPnl,
                    priceUpdated = true  // 自动生成的订单，直接标记为已处理，不发送通知
                )
                
                val savedRecord = sellMatchRecordRepository.save(matchRecord)
                
                // 保存匹配明细
                for (detail in matchDetails) {
                    val savedDetail = detail.copy(matchRecordId = savedRecord.id!!)
                    sellMatchDetailRepository.save(savedDetail)
                }
                
                logger.info("创建自动卖出记录: copyTradingId=$copyTradingId, marketId=$marketId, totalMatched=$totalMatchedQuantity, totalPnl=$totalRealizedPnl")
            }
        } catch (e: Exception) {
            logger.error("更新订单状态为已卖出异常: ${e.message}", e)
        }
    }
    
    /**
     * 按 FIFO 顺序更新订单状态为已卖出
     * @param orders 订单列表（已按创建时间排序，FIFO）
     * @param soldQuantity 已成交数量（总订单数量 - 仓位数量）
     * @param sellPrice 卖出价格
     * @param copyTradingId 跟单配置ID
     * @param marketId 市场ID
     * @param outcomeIndex 结果索引
     * 
     * 逻辑说明：
     * 1. 按订单创建时间顺序（FIFO）处理
     * 2. 如果订单剩余数量 <= 已成交数量，订单完全成交
     * 3. 如果订单剩余数量 > 已成交数量，订单部分成交
     * 4. 同时创建卖出记录和匹配明细，用于统计
     */
    private suspend fun updateOrdersAsSoldByFIFO(
        orders: List<CopyOrderTracking>,
        soldQuantity: BigDecimal,
        sellPrice: BigDecimal,
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    ) {
        if (orders.isEmpty()) {
            return
        }
        
        try {
            // 订单已经按 createdAt ASC 排序（FIFO）
            var remaining = soldQuantity
            var totalMatchedQuantity = BigDecimal.ZERO
            var totalRealizedPnl = BigDecimal.ZERO
            val matchDetails = mutableListOf<SellMatchDetail>()
            
            for (order in orders) {
                if (remaining <= BigDecimal.ZERO) {
                    break
                }
                
                val orderRemaining = order.remainingQuantity.toSafeBigDecimal()
                val toMatch = minOf(orderRemaining, remaining)
                
                if (toMatch > BigDecimal.ZERO) {
                    // 计算盈亏
                    val buyPrice = order.price.toSafeBigDecimal()
                    val realizedPnl = sellPrice.subtract(buyPrice).multi(toMatch)
                    
                    // 创建匹配明细（稍后保存）
                    val detail = SellMatchDetail(
                        matchRecordId = 0,  // 稍后设置
                        trackingId = order.id!!,
                        buyOrderId = order.buyOrderId,
                        matchedQuantity = toMatch,
                        buyPrice = buyPrice,
                        sellPrice = sellPrice,
                        realizedPnl = realizedPnl
                    )
                    matchDetails.add(detail)
                    
                    totalMatchedQuantity = totalMatchedQuantity.add(toMatch)
                    totalRealizedPnl = totalRealizedPnl.add(realizedPnl)
                    
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
            
            // 如果有匹配的订单，创建卖出记录
            if (totalMatchedQuantity > BigDecimal.ZERO && matchDetails.isNotEmpty()) {
                val timestamp = System.currentTimeMillis()
                val sellOrderId = "AUTO_FIFO_${timestamp}_${copyTradingId}"
                val leaderSellTradeId = "AUTO_FIFO_${timestamp}"
                
                val matchRecord = SellMatchRecord(
                    copyTradingId = copyTradingId,
                    sellOrderId = sellOrderId,
                    leaderSellTradeId = leaderSellTradeId,
                    marketId = marketId,
                    side = outcomeIndex.toString(),  // 使用outcomeIndex作为side
                    outcomeIndex = outcomeIndex,
                    totalMatchedQuantity = totalMatchedQuantity,
                    sellPrice = sellPrice,
                    totalRealizedPnl = totalRealizedPnl,
                    priceUpdated = true  // 自动生成的订单，直接标记为已处理，不发送通知
                )
                
                val savedRecord = sellMatchRecordRepository.save(matchRecord)
                
                // 保存匹配明细
                for (detail in matchDetails) {
                    val savedDetail = detail.copy(matchRecordId = savedRecord.id!!)
                    sellMatchDetailRepository.save(savedDetail)
                }
                
                logger.info("创建FIFO自动卖出记录: copyTradingId=$copyTradingId, marketId=$marketId, totalMatched=$totalMatchedQuantity, totalPnl=$totalRealizedPnl")
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

