package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.multi
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 订单状态更新服务
 * 定时轮询更新卖出订单的实际成交价，并更新买入订单的实际数据并发送通知
 */
@Service
class OrderStatusUpdateService(
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val accountRepository: AccountRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val leaderRepository: LeaderRepository,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val trackingService: CopyOrderTrackingService,
    private val telegramNotificationService: TelegramNotificationService?
) {
    
    private val logger = LoggerFactory.getLogger(OrderStatusUpdateService::class.java)
    
    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        logger.info("订单状态更新服务已启动，将每5秒轮询一次")
    }
    
    /**
     * 定时更新订单状态
     * 每5秒执行一次
     */
    @Scheduled(fixedDelay = 5000)
    fun updateOrderStatus() {
        updateScope.launch {
            try {
                // 1. 清理已删除账户的订单
                cleanupDeletedAccountOrders()
                
                // 2. 检查30秒前创建的订单，如果未成交则删除
                checkAndDeleteUnfilledOrders()
                
                // 3. 更新卖出订单的实际成交价并发送通知（priceUpdated 共用字段）
                updatePendingSellOrderPrices()
                
                // 4. 更新买入订单的实际数据并发送通知
                updatePendingBuyOrders()
            } catch (e: Exception) {
                logger.error("订单状态更新异常: ${e.message}", e)
            }
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
     * 清理已删除账户的订单
     */
    @Transactional
    private suspend fun cleanupDeletedAccountOrders() {
        try {
            // 查询所有卖出记录
            val allRecords = sellMatchRecordRepository.findAll()
            
            // 查询所有有效的账户ID
            val validAccountIds = accountRepository.findAll().mapNotNull { it.id }.toSet()
            
            // 查询所有有效的跟单关系
            val validCopyTradingIds = copyTradingRepository.findAll()
                .filter { it.accountId in validAccountIds }
                .mapNotNull { it.id }
                .toSet()
            
            // 找出需要删除的记录（关联的跟单关系已不存在或账户已删除）
            val recordsToDelete = allRecords.filter { record ->
                val copyTrading = copyTradingRepository.findById(record.copyTradingId).orElse(null)
                copyTrading == null || copyTrading.accountId !in validAccountIds
            }
            
            if (recordsToDelete.isNotEmpty()) {
                logger.info("清理已删除账户的订单: ${recordsToDelete.size} 条记录")
                
                // 删除匹配明细
                for (record in recordsToDelete) {
                    val details = sellMatchDetailRepository.findByMatchRecordId(record.id!!)
                    sellMatchDetailRepository.deleteAll(details)
                }
                
                // 删除卖出记录
                sellMatchRecordRepository.deleteAll(recordsToDelete)
                
                logger.info("已清理 ${recordsToDelete.size} 条已删除账户的订单记录")
            }
        } catch (e: Exception) {
            logger.error("清理已删除账户订单异常: ${e.message}", e)
        }
    }
    
    /**
     * 检查30秒前创建的订单，如果未成交则删除
     * 首次检测但加入缓存中30s后还没有成交，则删除
     */
    @Transactional
    private suspend fun checkAndDeleteUnfilledOrders() {
        try {
            // 计算30秒前的时间戳
            val thirtySecondsAgo = System.currentTimeMillis() - 30000
            
            // 查询30秒前创建的订单
            val ordersToCheck = copyOrderTrackingRepository.findByCreatedAtBefore(thirtySecondsAgo)
            
            if (ordersToCheck.isEmpty()) {
                return
            }
            
            logger.debug("检查 ${ordersToCheck.size} 个30秒前创建的订单是否成交")
            
            // 按账户分组，避免重复创建 API 客户端
            val ordersByAccount = ordersToCheck.groupBy { it.accountId }
            
            for ((accountId, orders) in ordersByAccount) {
                try {
                    // 获取账户
                    val account = accountRepository.findById(accountId).orElse(null)
                    if (account == null) {
                        logger.warn("账户不存在，跳过检查: accountId=$accountId")
                        continue
                    }
                    
                    // 检查账户是否配置了 API 凭证
                    if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                        logger.debug("账户未配置 API 凭证，跳过检查: accountId=${account.id}")
                        continue
                    }
                    
                    // 解密 API 凭证
                    val apiSecret = try {
                        cryptoUtils.decrypt(account.apiSecret!!)
                    } catch (e: Exception) {
                        logger.warn("解密 API Secret 失败: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    
                    val apiPassphrase = try {
                        cryptoUtils.decrypt(account.apiPassphrase!!)
                    } catch (e: Exception) {
                        logger.warn("解密 API Passphrase 失败: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    
                    // 创建带认证的 CLOB API 客户端
                    val clobApi = retrofitFactory.createClobApi(
                        account.apiKey!!,
                        apiSecret,
                        apiPassphrase,
                        account.walletAddress
                    )
                    
                    // 检查每个订单
                    for (order in orders) {
                        try {
                            // 查询订单详情
                            val orderResponse = clobApi.getOrder(order.buyOrderId)
                            
                            // 先检查 HTTP 状态码，非 200 的都跳过
                            if (orderResponse.code() != 200) {
                                // HTTP 非 200，记录日志并跳过，等待下次轮询
                                // 不删除订单，因为可能是临时网络问题或 API 错误
                                val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "无错误详情"
                                logger.debug("订单查询失败（HTTP非200），等待下次轮询: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, code=${orderResponse.code()}, errorBody=$errorBody")
                                continue
                            }
                            
                            // HTTP 200，检查响应体
                            // 响应体也可能返回字符串 "null"，Gson 解析时会返回 null
                            val orderDetail = orderResponse.body()
                            if (orderDetail == null) {
                                // HTTP 200 且响应体为 null（或字符串 "null"），表示订单不存在
                                // 检查订单是否已部分卖出，如果已部分卖出则保留订单用于统计
                                val hasMatchedDetails = sellMatchDetailRepository.findByTrackingId(order.id!!).isNotEmpty()
                                if (hasMatchedDetails || order.matchedQuantity > BigDecimal.ZERO) {
                                    logger.debug("订单不存在但已部分卖出，保留订单用于统计: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, matchedQuantity=${order.matchedQuantity}")
                                    continue
                                }
                                
                                // 订单不存在且未部分卖出，删除本地订单
                                logger.info("订单不存在（HTTP 200 但响应体为空），删除本地订单: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                                try {
                                    copyOrderTrackingRepository.deleteById(order.id!!)
                                    logger.info("已删除本地订单: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                                } catch (e: Exception) {
                                    logger.error("删除本地订单失败: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, error=${e.message}", e)
                                }
                                continue
                            }
                            
                            // 检查订单是否成交
                            // 如果订单状态不是 FILLED 且已成交数量为0，说明未成交，删除
                            val sizeMatched = orderDetail.sizeMatched?.toSafeBigDecimal() ?: BigDecimal.ZERO
                            if (orderDetail.status != "FILLED" && sizeMatched <= BigDecimal.ZERO) {
                                logger.info("订单30秒后仍未成交，删除本地订单: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, status=${orderDetail.status}, sizeMatched=$sizeMatched")
                                try {
                                    copyOrderTrackingRepository.deleteById(order.id!!)
                                    logger.info("已删除未成交订单: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                                } catch (e: Exception) {
                                    logger.error("删除未成交订单失败: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, error=${e.message}", e)
                                }
                            } else {
                                logger.debug("订单已成交或部分成交，保留: orderId=${order.buyOrderId}, status=${orderDetail.status}, sizeMatched=$sizeMatched")
                            }
                        } catch (e: Exception) {
                            logger.error("检查订单失败: orderId=${order.buyOrderId}, error=${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("检查账户订单失败: accountId=$accountId, error=${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("检查未成交订单异常: ${e.message}", e)
        }
    }
    
    /**
     * 更新待更新的卖出订单价格
     * 注意：priceUpdated 现在同时表示价格已更新和通知已发送（共用字段）
     */
    @Transactional
    private suspend fun updatePendingSellOrderPrices() {
        try {
            // 查询所有价格未更新的卖出记录（priceUpdated = false 表示未处理）
            val pendingRecords = sellMatchRecordRepository.findByPriceUpdatedFalse()
            
            if (pendingRecords.isEmpty()) {
                return
            }
            
            logger.debug("找到 ${pendingRecords.size} 条待更新价格的卖出订单")
            
            for (record in pendingRecords) {
                try {
                    // 获取跟单关系
                    val copyTrading = copyTradingRepository.findById(record.copyTradingId).orElse(null)
                    if (copyTrading == null) {
                        logger.warn("跟单关系不存在，跳过更新: copyTradingId=${record.copyTradingId}")
                        continue
                    }
                    
                    // 获取账户
                    val account = accountRepository.findById(copyTrading.accountId).orElse(null)
                    if (account == null) {
                        logger.warn("账户不存在，跳过更新: accountId=${copyTrading.accountId}")
                        continue
                    }
                    
                    // 检查账户是否配置了 API 凭证
                    if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                        logger.debug("账户未配置 API 凭证，跳过更新: accountId=${account.id}")
                        continue
                    }
                    
                    // 解密 API 凭证
                    val apiSecret = try {
                        cryptoUtils.decrypt(account.apiSecret!!)
                    } catch (e: Exception) {
                        logger.warn("解密 API Secret 失败: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    
                    val apiPassphrase = try {
                        cryptoUtils.decrypt(account.apiPassphrase!!)
                    } catch (e: Exception) {
                        logger.warn("解密 API Passphrase 失败: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    
                    // 创建带认证的 CLOB API 客户端
                    val clobApi = retrofitFactory.createClobApi(
                        account.apiKey!!,
                        apiSecret,
                        apiPassphrase,
                        account.walletAddress
                    )
                    
                    // 如果 orderId 不是 0x 开头，直接标记为已处理（priceUpdated = true 表示已处理，包括价格更新和通知发送）
                    if (!record.sellOrderId.startsWith("0x", ignoreCase = true)) {
                        logger.debug("卖出订单ID非0x开头，直接标记为已处理: orderId=${record.sellOrderId}")
                        
                        // 检查是否为自动生成的订单（AUTO_ 或 AUTO_FIFO_ 开头），如果是则不发送通知
                        val isAutoOrder = record.sellOrderId.startsWith("AUTO_", ignoreCase = true) || 
                                         record.sellOrderId.startsWith("AUTO_FIFO_", ignoreCase = true) ||
                                         record.sellOrderId.startsWith("AUTO_WS_", ignoreCase = true)
                        
                        if (!isAutoOrder) {
                            // 非自动订单，发送通知（使用临时数据）
                            sendSellOrderNotification(
                                record = record,
                                useTemporaryData = true,
                                account = account,
                                copyTrading = copyTrading,
                                clobApi = clobApi,
                                apiSecret = apiSecret,
                                apiPassphrase = apiPassphrase
                            )
                        } else {
                            logger.debug("自动生成的订单，跳过发送通知: orderId=${record.sellOrderId}")
                        }
                        
                        // 标记为已处理（priceUpdated = true 同时表示价格已更新和通知已发送）
                        val updatedRecord = SellMatchRecord(
                            id = record.id,
                            copyTradingId = record.copyTradingId,
                            sellOrderId = record.sellOrderId,
                            leaderSellTradeId = record.leaderSellTradeId,
                            marketId = record.marketId,
                            side = record.side,
                            outcomeIndex = record.outcomeIndex,
                            totalMatchedQuantity = record.totalMatchedQuantity,
                            sellPrice = record.sellPrice,
                            totalRealizedPnl = record.totalRealizedPnl,
                            priceUpdated = true,  // 标记为已处理（价格已更新和通知已发送）
                            createdAt = record.createdAt
                        )
                        sellMatchRecordRepository.save(updatedRecord)
                        continue
                    }
                    
                    // 检查是否为自动生成的订单（AUTO_ 或 AUTO_FIFO_ 开头），如果是则跳过发送通知
                    val isAutoOrder = record.sellOrderId.startsWith("AUTO_", ignoreCase = true) || 
                                     record.sellOrderId.startsWith("AUTO_FIFO_", ignoreCase = true) ||
                                     record.sellOrderId.startsWith("AUTO_WS_", ignoreCase = true)
                    
                    if (isAutoOrder) {
                        logger.debug("自动生成的订单，跳过发送通知并直接标记为已处理: orderId=${record.sellOrderId}")
                        // 直接标记为已处理，不发送通知
                        val updatedRecord = SellMatchRecord(
                            id = record.id,
                            copyTradingId = record.copyTradingId,
                            sellOrderId = record.sellOrderId,
                            leaderSellTradeId = record.leaderSellTradeId,
                            marketId = record.marketId,
                            side = record.side,
                            outcomeIndex = record.outcomeIndex,
                            totalMatchedQuantity = record.totalMatchedQuantity,
                            sellPrice = record.sellPrice,
                            totalRealizedPnl = record.totalRealizedPnl,
                            priceUpdated = true,  // 标记为已处理
                            createdAt = record.createdAt
                        )
                        sellMatchRecordRepository.save(updatedRecord)
                        continue
                    }
                    
                    // 查询订单详情，获取实际成交价
                    val actualSellPrice = trackingService.getActualExecutionPrice(
                        orderId = record.sellOrderId,
                        clobApi = clobApi,
                        fallbackPrice = record.sellPrice
                    )
                    
                    // 如果价格已更新（与当前价格不同），更新数据库
                    if (actualSellPrice != record.sellPrice) {
                        // 重新计算盈亏
                        val details = sellMatchDetailRepository.findByMatchRecordId(record.id!!)
                        var totalRealizedPnl = BigDecimal.ZERO
                        
                        for (detail in details) {
                            val updatedRealizedPnl = actualSellPrice.subtract(detail.buyPrice).multi(detail.matchedQuantity)
                            
                            // 更新明细的卖出价格和盈亏
                            // 注意：SellMatchDetail 的字段都是 val，需要创建新对象
                            val updatedDetail = SellMatchDetail(
                                id = detail.id,
                                matchRecordId = detail.matchRecordId,
                                trackingId = detail.trackingId,
                                buyOrderId = detail.buyOrderId,
                                matchedQuantity = detail.matchedQuantity,
                                buyPrice = detail.buyPrice,
                                sellPrice = actualSellPrice,  // 更新卖出价格
                                realizedPnl = updatedRealizedPnl,  // 更新盈亏
                                createdAt = detail.createdAt
                            )
                            sellMatchDetailRepository.save(updatedDetail)
                            
                            totalRealizedPnl = totalRealizedPnl.add(updatedRealizedPnl)
                        }
                        
                        // 发送通知（使用实际价格）
                        sendSellOrderNotification(
                            record = record,
                            actualPrice = actualSellPrice.toString(),
                            actualSize = record.totalMatchedQuantity.toString(),
                            account = account,
                            copyTrading = copyTrading,
                            clobApi = clobApi,
                            apiSecret = apiSecret,
                            apiPassphrase = apiPassphrase
                        )
                        
                        // 更新卖出记录
                        // 注意：SellMatchRecord 的字段都是 val，需要创建新对象
                        val updatedRecord = SellMatchRecord(
                            id = record.id,
                            copyTradingId = record.copyTradingId,
                            sellOrderId = record.sellOrderId,
                            leaderSellTradeId = record.leaderSellTradeId,
                            marketId = record.marketId,
                            side = record.side,
                            outcomeIndex = record.outcomeIndex,
                            totalMatchedQuantity = record.totalMatchedQuantity,
                            sellPrice = actualSellPrice,  // 更新卖出价格
                            totalRealizedPnl = totalRealizedPnl,  // 更新总盈亏
                            priceUpdated = true,  // 标记为已处理（价格已更新和通知已发送）
                            createdAt = record.createdAt
                        )
                        sellMatchRecordRepository.save(updatedRecord)
                        
                        logger.info("更新卖出订单价格成功并已发送通知: orderId=${record.sellOrderId}, 原价格=${record.sellPrice}, 新价格=$actualSellPrice")
                    } else {
                        // 价格相同，但已经查询过，发送通知并标记为已处理
                        sendSellOrderNotification(
                            record = record,
                            actualPrice = actualSellPrice.toString(),
                            actualSize = record.totalMatchedQuantity.toString(),
                            account = account,
                            copyTrading = copyTrading,
                            clobApi = clobApi,
                            apiSecret = apiSecret,
                            apiPassphrase = apiPassphrase
                        )
                        
                        val updatedRecord = SellMatchRecord(
                            id = record.id,
                            copyTradingId = record.copyTradingId,
                            sellOrderId = record.sellOrderId,
                            leaderSellTradeId = record.leaderSellTradeId,
                            marketId = record.marketId,
                            side = record.side,
                            outcomeIndex = record.outcomeIndex,
                            totalMatchedQuantity = record.totalMatchedQuantity,
                            sellPrice = record.sellPrice,
                            totalRealizedPnl = record.totalRealizedPnl,
                            priceUpdated = true,  // 标记为已处理（价格已更新和通知已发送）
                            createdAt = record.createdAt
                        )
                        sellMatchRecordRepository.save(updatedRecord)
                        logger.debug("卖出订单价格无需更新但已发送通知: orderId=${record.sellOrderId}, price=$actualSellPrice")
                    }
                } catch (e: Exception) {
                    logger.warn("更新卖出订单价格失败: orderId=${record.sellOrderId}, error=${e.message}", e)
                    // 继续处理下一条记录
                }
            }
        } catch (e: Exception) {
            logger.error("更新待更新卖出订单价格异常: ${e.message}", e)
        }
    }
    
    /**
     * 更新待发送通知的买入订单
     * 查询订单详情获取实际价格和数量，然后发送通知并更新数据库
     */
    @Transactional
    private suspend fun updatePendingBuyOrders() {
        try {
            // 查询所有未发送通知的买入订单
            val pendingOrders = copyOrderTrackingRepository.findByNotificationSentFalse()
            
            if (pendingOrders.isEmpty()) {
                return
            }
            
            logger.debug("找到 ${pendingOrders.size} 条待发送通知的买入订单")
            
            for (order in pendingOrders) {
                try {
                    // 验证 orderId 格式（必须以 0x 开头的 16 进制）
                    if (!isValidOrderId(order.buyOrderId)) {
                        logger.warn("买入订单ID格式无效，直接标记为已发送通知: orderId=${order.buyOrderId}")
                        // 对于非 0x 开头的订单ID，直接标记为已发送，使用临时数据发送通知
                        val updatedOrder = CopyOrderTracking(
                            id = order.id,
                            copyTradingId = order.copyTradingId,
                            accountId = order.accountId,
                            leaderId = order.leaderId,
                            marketId = order.marketId,
                            side = order.side,
                            outcomeIndex = order.outcomeIndex,
                            buyOrderId = order.buyOrderId,
                            leaderBuyTradeId = order.leaderBuyTradeId,
                            quantity = order.quantity,
                            price = order.price,
                            matchedQuantity = order.matchedQuantity,
                            remainingQuantity = order.remainingQuantity,
                            status = order.status,
                            notificationSent = true,  // 标记为已发送通知
                            createdAt = order.createdAt,
                            updatedAt = System.currentTimeMillis()
                        )
                        copyOrderTrackingRepository.save(updatedOrder)
                        sendBuyOrderNotification(updatedOrder, useTemporaryData = true)
                        continue
                    }
                    
                    // 获取跟单关系
                    val copyTrading = copyTradingRepository.findById(order.copyTradingId).orElse(null)
                    if (copyTrading == null) {
                        logger.warn("跟单关系不存在，跳过更新: copyTradingId=${order.copyTradingId}")
                        continue
                    }
                    
                    // 获取账户
                    val account = accountRepository.findById(order.accountId).orElse(null)
                    if (account == null) {
                        logger.warn("账户不存在，跳过更新: accountId=${order.accountId}")
                        continue
                    }
                    
                    // 检查账户是否配置了 API 凭证
                    if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                        logger.debug("账户未配置 API 凭证，跳过更新: accountId=${account.id}")
                        continue
                    }
                    
                    // 解密 API 凭证
                    val apiSecret = try {
                        cryptoUtils.decrypt(account.apiSecret!!)
                    } catch (e: Exception) {
                        logger.warn("解密 API Secret 失败: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    
                    val apiPassphrase = try {
                        cryptoUtils.decrypt(account.apiPassphrase!!)
                    } catch (e: Exception) {
                        logger.warn("解密 API Passphrase 失败: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    
                    // 创建带认证的 CLOB API 客户端
                    val clobApi = retrofitFactory.createClobApi(
                        account.apiKey!!,
                        apiSecret,
                        apiPassphrase,
                        account.walletAddress
                    )
                    
                    // 查询订单详情
                    val orderResponse = clobApi.getOrder(order.buyOrderId)
                    
                    // 先检查 HTTP 状态码，非 200 的都跳过
                    if (orderResponse.code() != 200) {
                        val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "无错误详情"
                        logger.debug("查询订单详情失败（HTTP非200），等待下次轮询: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, code=${orderResponse.code()}, errorBody=$errorBody")
                        continue
                    }
                    
                    // HTTP 200，检查响应体
                    // 响应体也可能返回字符串 "null"，Gson 解析时会返回 null
                    val orderDetail = orderResponse.body()
                    if (orderDetail == null) {
                        // HTTP 200 且响应体为 null（或字符串 "null"），表示订单不存在
                        // 检查订单是否已部分卖出，如果已部分卖出则保留订单用于统计
                        val hasMatchedDetails = sellMatchDetailRepository.findByTrackingId(order.id!!).isNotEmpty()
                        if (hasMatchedDetails || order.matchedQuantity > BigDecimal.ZERO) {
                            logger.debug("订单不存在但已部分卖出，保留订单用于统计: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, matchedQuantity=${order.matchedQuantity}")
                            continue
                        }
                        
                        // 订单不存在且未部分卖出，删除本地订单
                        logger.info("订单不存在（HTTP 200 但响应体为空），删除本地订单: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                        try {
                            copyOrderTrackingRepository.deleteById(order.id!!)
                            logger.info("已删除本地订单: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                        } catch (e: Exception) {
                            logger.error("删除本地订单失败: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, error=${e.message}", e)
                        }
                        continue
                    }
                    
                    // 获取实际价格和数量
                    val actualPrice = orderDetail.price?.toSafeBigDecimal() ?: order.price
                    val actualSize = orderDetail.originalSize?.toSafeBigDecimal() ?: order.quantity
                    val actualOutcome = orderDetail.outcome
                    
                    // 更新订单数据（如果实际数据与临时数据不同）
                    val needUpdate = actualPrice != order.price || actualSize != order.quantity
                    
                    // 创建更新后的订单对象
                    val updatedOrder = CopyOrderTracking(
                        id = order.id,
                        copyTradingId = order.copyTradingId,
                        accountId = order.accountId,
                        leaderId = order.leaderId,
                        marketId = order.marketId,
                        side = order.side,
                        outcomeIndex = order.outcomeIndex,
                        buyOrderId = order.buyOrderId,
                        leaderBuyTradeId = order.leaderBuyTradeId,
                        quantity = actualSize,  // 使用实际数量
                        price = actualPrice,  // 使用实际价格
                        matchedQuantity = order.matchedQuantity,
                        remainingQuantity = order.remainingQuantity,
                        status = order.status,
                        notificationSent = true,  // 标记为已发送通知
                        createdAt = order.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                    
                    // 保存更新后的订单
                    copyOrderTrackingRepository.save(updatedOrder)
                    
                    if (needUpdate) {
                        logger.info("更新买入订单数据成功: orderId=${order.buyOrderId}, 原价格=${order.price}, 新价格=$actualPrice, 原数量=${order.quantity}, 新数量=$actualSize")
                    } else {
                        logger.debug("买入订单数据无需更新: orderId=${order.buyOrderId}")
                    }
                    
                    // 发送通知（使用实际数据）
                    sendBuyOrderNotification(
                        order = updatedOrder,
                        actualPrice = actualPrice.toString(),
                        actualSize = actualSize.toString(),
                        actualOutcome = actualOutcome,
                        account = account,
                        copyTrading = copyTrading,
                        clobApi = clobApi,
                        apiSecret = apiSecret,
                        apiPassphrase = apiPassphrase
                    )
                } catch (e: Exception) {
                    logger.warn("更新买入订单失败: orderId=${order.buyOrderId}, error=${e.message}", e)
                    // 继续处理下一条记录
                }
            }
        } catch (e: Exception) {
            logger.error("更新待发送通知买入订单异常: ${e.message}", e)
        }
    }
    
    /**
     * 发送买入订单通知
     */
    private suspend fun sendBuyOrderNotification(
        order: CopyOrderTracking,
        useTemporaryData: Boolean = false,
        actualPrice: String? = null,
        actualSize: String? = null,
        actualOutcome: String? = null,
        account: Account? = null,
        copyTrading: CopyTrading? = null,
        clobApi: PolymarketClobApi? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null
    ) {
        if (telegramNotificationService == null) {
            return
        }
        
        try {
            // 获取跟单关系和账户信息（如果未提供）
            val finalCopyTrading = copyTrading ?: copyTradingRepository.findById(order.copyTradingId).orElse(null)
            if (finalCopyTrading == null) {
                logger.warn("跟单关系不存在，跳过发送通知: copyTradingId=${order.copyTradingId}")
                return
            }
            
            val finalAccount = account ?: accountRepository.findById(order.accountId).orElse(null)
            if (finalAccount == null) {
                logger.warn("账户不存在，跳过发送通知: accountId=${order.accountId}")
                return
            }
            
            // 获取市场信息
            val marketInfo = withContext(Dispatchers.IO) {
                try {
                    val gammaApi = retrofitFactory.createGammaApi()
                    val marketResponse = gammaApi.listMarkets(conditionIds = listOf(order.marketId))
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
            
            val marketTitle = marketInfo?.question ?: order.marketId
            val marketSlug = marketInfo?.slug
            
            // 获取 Leader 和跟单配置信息
            val leader = leaderRepository.findById(order.leaderId).orElse(null)
            val leaderName = leader?.leaderName
            val configName = finalCopyTrading.configName
            
            // 获取当前语言设置
            val locale = try {
                LocaleContextHolder.getLocale()
            } catch (e: Exception) {
                java.util.Locale("zh", "CN")  // 默认简体中文
            }
            
            // 创建 CLOB API 客户端（如果未提供）
            val finalClobApi = clobApi ?: if (finalAccount.apiKey != null && apiSecret != null && apiPassphrase != null) {
                retrofitFactory.createClobApi(
                    finalAccount.apiKey!!,
                    apiSecret,
                    apiPassphrase,
                    finalAccount.walletAddress
                )
            } else {
                null
            }
            
            // 发送通知
            telegramNotificationService.sendOrderSuccessNotification(
                orderId = order.buyOrderId,
                marketTitle = marketTitle,
                marketId = order.marketId,
                marketSlug = marketSlug,
                side = "BUY",
                price = actualPrice ?: order.price.toString(),  // 使用实际价格或临时价格
                size = actualSize ?: order.quantity.toString(),  // 使用实际数量或临时数量
                outcome = actualOutcome,  // 使用实际 outcome
                accountName = finalAccount.accountName,
                walletAddress = finalAccount.walletAddress,
                clobApi = finalClobApi,
                apiKey = finalAccount.apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddressForApi = finalAccount.walletAddress,
                locale = locale,
                leaderName = leaderName,
                configName = configName
            )
            
            logger.info("买入订单通知已发送: orderId=${order.buyOrderId}, copyTradingId=${order.copyTradingId}")
        } catch (e: Exception) {
            logger.warn("发送买入订单通知失败: orderId=${order.buyOrderId}, error=${e.message}", e)
        }
    }
    
    
    /**
     * 发送卖出订单通知
     */
    private suspend fun sendSellOrderNotification(
        record: SellMatchRecord,
        useTemporaryData: Boolean = false,
        actualPrice: String? = null,
        actualSize: String? = null,
        actualOutcome: String? = null,
        account: Account? = null,
        copyTrading: CopyTrading? = null,
        clobApi: PolymarketClobApi? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null
    ) {
        if (telegramNotificationService == null) {
            return
        }
        
        try {
            // 获取跟单关系和账户信息（如果未提供）
            val finalCopyTrading = copyTrading ?: copyTradingRepository.findById(record.copyTradingId).orElse(null)
            if (finalCopyTrading == null) {
                logger.warn("跟单关系不存在，跳过发送通知: copyTradingId=${record.copyTradingId}")
                return
            }
            
            val finalAccount = account ?: accountRepository.findById(finalCopyTrading.accountId).orElse(null)
            if (finalAccount == null) {
                logger.warn("账户不存在，跳过发送通知: accountId=${finalCopyTrading.accountId}")
                return
            }
            
            // 获取市场信息
            val marketInfo = withContext(Dispatchers.IO) {
                try {
                    val gammaApi = retrofitFactory.createGammaApi()
                    val marketResponse = gammaApi.listMarkets(conditionIds = listOf(record.marketId))
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
            
            val marketTitle = marketInfo?.question ?: record.marketId
            val marketSlug = marketInfo?.slug
            
            // 获取 Leader 和跟单配置信息
            val leader = leaderRepository.findById(finalCopyTrading.leaderId).orElse(null)
            val leaderName = leader?.leaderName
            val configName = finalCopyTrading.configName
            
            // 获取当前语言设置
            val locale = try {
                LocaleContextHolder.getLocale()
            } catch (e: Exception) {
                java.util.Locale("zh", "CN")  // 默认简体中文
            }
            
            // 创建 CLOB API 客户端（如果未提供）
            val finalClobApi = clobApi ?: if (finalAccount.apiKey != null && apiSecret != null && apiPassphrase != null) {
                retrofitFactory.createClobApi(
                    finalAccount.apiKey!!,
                    apiSecret,
                    apiPassphrase,
                    finalAccount.walletAddress
                )
            } else {
                null
            }
            
            // 发送通知
            telegramNotificationService.sendOrderSuccessNotification(
                orderId = record.sellOrderId,
                marketTitle = marketTitle,
                marketId = record.marketId,
                marketSlug = marketSlug,
                side = "SELL",
                price = actualPrice ?: record.sellPrice.toString(),  // 使用实际价格或临时价格
                size = actualSize ?: record.totalMatchedQuantity.toString(),  // 使用实际数量或临时数量
                outcome = actualOutcome,  // 使用实际 outcome
                accountName = finalAccount.accountName,
                walletAddress = finalAccount.walletAddress,
                clobApi = finalClobApi,
                apiKey = finalAccount.apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddressForApi = finalAccount.walletAddress,
                locale = locale,
                leaderName = leaderName,
                configName = configName
            )
            
            logger.info("卖出订单通知已发送: orderId=${record.sellOrderId}, copyTradingId=${record.copyTradingId}")
        } catch (e: Exception) {
            logger.warn("发送卖出订单通知失败: orderId=${record.sellOrderId}, error=${e.message}", e)
        }
    }
}

