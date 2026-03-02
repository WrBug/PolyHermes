package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.DateUtils
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * Telegram 通知服务
 * 负责发送 Telegram 消息
 */
@Service
class TelegramNotificationService(
    private val notificationConfigService: NotificationConfigService,
    private val notificationTemplateService: NotificationTemplateService,
    private val objectMapper: ObjectMapper,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(TelegramNotificationService::class.java)

    private val okHttpClient = createClient()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val apiBaseUrl = "https://api.telegram.org/bot"

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 发送订单成功通知
     * @param orderId 订单ID（用于查询订单详情获取实际价格和数量）
     * @param marketTitle 市场标题
     * @param marketId 市场ID（conditionId），用于生成链接
     * @param marketSlug 市场slug，用于生成链接
     * @param side 订单方向（BUY/SELL），用于多语言显示
     * @param accountName 账户名称
     * @param walletAddress 钱包地址
     * @param clobApi CLOB API 客户端（可选，如果提供则查询订单详情获取实际价格和数量）
     * @param apiKey API Key（可选，用于查询订单详情）
     * @param apiSecret API Secret（可选，用于查询订单详情）
     * @param apiPassphrase API Passphrase（可选，用于查询订单详情）
     * @param walletAddressForApi 钱包地址（可选，用于查询订单详情）
     * @param locale 语言设置（可选，如果提供则使用，否则使用 LocaleContextHolder 获取）
     */
    // 已发送通知的订单ID缓存（key: orderId, value: timestamp）
    private val sentOrderIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 发送订单成功通知
     * @param orderId 订单ID（用于查询订单详情获取实际价格和数量）
     * @param marketTitle 市场标题
     * @param marketId 市场ID（conditionId），用于生成链接
     * @param marketSlug 市场slug，用于生成链接
     * @param side 订单方向（BUY/SELL），用于多语言显示
     * @param accountName 账户名称
     * @param walletAddress 钱包地址
     * @param clobApi CLOB API 客户端（可选，如果提供则查询订单详情获取实际价格和数量）
     * @param apiKey API Key（可选，用于查询订单详情）
     * @param apiSecret API Secret（可选，用于查询订单详情）
     * @param apiPassphrase API Passphrase（可选，用于查询订单详情）
     * @param walletAddressForApi 钱包地址（可选，用于查询订单详情）
     * @param locale 语言设置（可选，如果提供则使用，否则使用 LocaleContextHolder 获取）
     * @param orderTime 订单时间（可选，如果提供则使用订单创建时间，否则使用当前通知时间）
     */
    suspend fun sendOrderSuccessNotification(
        orderId: String?,
        marketTitle: String,
        marketId: String? = null,
        marketSlug: String? = null,
        side: String,
        price: String? = null,  // 订单价格（可选，如果提供则直接使用）
        size: String? = null,  // 订单数量（可选，如果提供则直接使用）
        outcome: String? = null,  // 市场方向（可选，如果提供则直接使用）
        accountName: String? = null,
        walletAddress: String? = null,
        clobApi: PolymarketClobApi? = null,
        apiKey: String? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null,
        walletAddressForApi: String? = null,
        locale: java.util.Locale? = null,
        leaderName: String? = null,  // Leader 名称（备注）
        configName: String? = null,  // 跟单配置名
        orderTime: Long? = null,  // 订单创建时间（毫秒时间戳），用于通知中的时间显示
        availableBalance: String? = null  // 可用余额（可选）
    ) {
        // 1. 如果提供了 orderId，检查是否已发送过通知（去重）
        if (orderId != null) {
            val lastSentTime = sentOrderIds[orderId]
            if (lastSentTime != null) {
                // 如果5分钟内已发送过，跳过
                if (System.currentTimeMillis() - lastSentTime < 5 * 60 * 1000) {
                    logger.info("订单通知已发送过（5分钟内），跳过: orderId=$orderId")
                    return
                }
            }
            // 记录发送时间
            sentOrderIds[orderId] = System.currentTimeMillis()
            
            // 简单的清理逻辑：如果缓存过大，清理过期的
            if (sentOrderIds.size > 1000) {
                val expiryTime = System.currentTimeMillis() - 5 * 60 * 1000
                sentOrderIds.entries.removeIf { it.value < expiryTime }
            }
        }

        // 获取语言设置（优先使用传入的 locale，否则从 LocaleContextHolder 获取）
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")  // 默认简体中文
        }
        
        // 优先使用传入的价格和数量，如果没有提供则尝试从订单详情获取
        var actualPrice: String? = price
        var actualSize: String? = size
        var actualSide: String = side
        var actualOutcome: String? = outcome
        
        // 如果价格或数量未提供，尝试从订单详情获取
        if ((actualPrice == null || actualSize == null) && orderId != null && clobApi != null && apiKey != null && apiSecret != null && apiPassphrase != null && walletAddressForApi != null) {
            try {
                val orderResponse = clobApi.getOrder(orderId)
                if (orderResponse.isSuccessful) {
                    val order = orderResponse.body()
                    if (order != null) {
                        if (actualPrice == null) {
                            actualPrice = order.price
                        }
                        if (actualSize == null) {
                            actualSize = order.originalSize  // 使用 originalSize 作为订单数量
                        }
                        actualSide = order.side  // 使用订单详情中的 side
                        if (actualOutcome == null) {
                            actualOutcome = order.outcome  // 使用订单详情中的 outcome（市场方向）
                        }
                    } else {
                        logger.debug("查询订单详情失败: 响应体为空, orderId=$orderId")
                    }
                } else {
                    val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "无错误详情"
                    logger.debug("查询订单详情失败: orderId=$orderId, code=${orderResponse.code()}, errorBody=$errorBody")
                }
            } catch (e: Exception) {
                logger.warn("查询订单详情失败: orderId=$orderId, ${e.message}", e)
            }
        }
        
        // 如果仍然没有获取到实际值，使用默认值（这种情况不应该发生，但为了兼容性保留）
        val finalPrice = actualPrice ?: "0"
        val finalSize = actualSize ?: "0"
        
        // 计算订单金额 = price × size（USDC）
        val amount = try {
            val priceDecimal = finalPrice.toSafeBigDecimal()
            val sizeDecimal = finalSize.toSafeBigDecimal()
            priceDecimal.multiply(sizeDecimal).toString()
        } catch (e: Exception) {
            logger.warn("计算订单金额失败: ${e.message}", e)
            null
        }

        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale).orEmpty().ifEmpty { "未知账户" }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale).orEmpty().ifEmpty { "计算失败" }
        val vars = buildOrderSuccessVariables(
            orderId = orderId,
            marketTitle = marketTitle,
            marketId = marketId,
            marketSlug = marketSlug,
            side = actualSide,
            outcome = actualOutcome,
            price = finalPrice,
            size = finalSize,
            amount = amount,
            accountName = accountName,
            walletAddress = walletAddress,
            locale = currentLocale,
            leaderName = leaderName,
            configName = configName,
            orderTime = orderTime,
            availableBalance = availableBalance,
            unknownAccount = unknownAccount,
            calculateFailed = calculateFailed
        )
        val message = notificationTemplateService.renderTemplate("ORDER_SUCCESS", vars)
        sendMessage(message)
    }

    /**
     * 发送订单失败通知
     * @param locale 语言设置（可选，如果提供则使用，否则使用 LocaleContextHolder 获取）
     */
    suspend fun sendOrderFailureNotification(
        marketTitle: String,
        marketId: String? = null,  // 市场ID（conditionId），用于生成链接
        marketSlug: String? = null,  // 市场slug，用于生成链接
        side: String,
        outcome: String? = null,  // 市场方向（outcome，如 "YES", "NO" 等）
        price: String,
        size: String,
        errorMessage: String,  // 只传递后端返回的 msg，不传递完整堆栈
        accountName: String? = null,
        walletAddress: String? = null,
        locale: java.util.Locale? = null
    ) {
        // 获取语言设置（优先使用传入的 locale，否则从 LocaleContextHolder 获取）
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")  // 默认简体中文
        }
        
        // 计算订单金额 = price × size（USDC）
        val amount = try {
            val priceDecimal = price.toSafeBigDecimal()
            val sizeDecimal = size.toSafeBigDecimal()
            priceDecimal.multiply(sizeDecimal).toString()
        } catch (e: Exception) {
            logger.warn("计算订单金额失败: ${e.message}", e)
            null
        }

        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale).orEmpty().ifEmpty { "未知账户" }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale).orEmpty().ifEmpty { "计算失败" }
        val vars = buildOrderFailureVariables(
            marketTitle = marketTitle,
            marketId = marketId,
            marketSlug = marketSlug,
            side = side,
            outcome = outcome,
            price = price,
            size = size,
            amount = amount,
            errorMessage = errorMessage,
            accountName = accountName,
            walletAddress = walletAddress,
            locale = currentLocale,
            unknownAccount = unknownAccount,
            calculateFailed = calculateFailed
        )
        val message = notificationTemplateService.renderTemplate("ORDER_FAILED", vars)
        sendMessage(message)
    }

    /**
     * 构建订单失败通知的变量 Map
     */
    private fun buildOrderFailureVariables(
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        errorMessage: String,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        unknownAccount: String,
        calculateFailed: String
    ): Map<String, String> {
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> ""
        }
        val amountDisplay = amount?.let { am ->
            try {
                val amountDecimal = am.toSafeBigDecimal()
                (if (amountDecimal.scale() > 4) amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else amountDecimal.stripTrailingZeros()).toPlainString()
            } catch (e: Exception) { am }
        } ?: calculateFailed
        val shortError = if (errorMessage.length > 500) errorMessage.substring(0, 500) + "..." else errorMessage
        return mapOf(
            "market_title" to marketTitle.replace("<", "&lt;").replace(">", "&gt;"),
            "market_link" to marketLink,
            "side" to sideDisplay,
            "outcome" to (outcome?.replace("<", "&lt;")?.replace(">", "&gt;") ?: ""),
            "price" to formatPrice(price),
            "quantity" to formatQuantity(size),
            "amount" to amountDisplay,
            "account_name" to accountInfo,
            "error_message" to shortError.replace("<", "&lt;").replace(">", "&gt;"),
            "time" to DateUtils.formatDateTime()
        )
    }

    /**
     * 发送订单被过滤通知
     * @param locale 语言设置（可选，如果提供则使用，否则使用 LocaleContextHolder 获取）
     */
    suspend fun sendOrderFilteredNotification(
        marketTitle: String,
        marketId: String? = null,  // 市场ID（conditionId），用于生成链接
        marketSlug: String? = null,  // 市场slug，用于生成链接
        side: String,
        outcome: String? = null,  // 市场方向（outcome，如 "YES", "NO" 等）
        price: String,
        size: String,
        filterReason: String,  // 过滤原因
        filterType: String,  // 过滤类型
        accountName: String? = null,
        walletAddress: String? = null,
        locale: java.util.Locale? = null
    ) {
        // 获取语言设置（优先使用传入的 locale，否则从 LocaleContextHolder 获取）
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")  // 默认简体中文
        }
        
        // 计算订单金额 = price × size（USDC）
        val amount = try {
            val priceDecimal = price.toSafeBigDecimal()
            val sizeDecimal = size.toSafeBigDecimal()
            priceDecimal.multiply(sizeDecimal).toString()
        } catch (e: Exception) {
            logger.warn("计算订单金额失败: ${e.message}", e)
            null
        }

        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale).orEmpty().ifEmpty { "未知账户" }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale).orEmpty().ifEmpty { "计算失败" }
        val vars = buildOrderFilteredVariables(
            marketTitle = marketTitle,
            marketId = marketId,
            marketSlug = marketSlug,
            side = side,
            outcome = outcome,
            price = price,
            size = size,
            amount = amount,
            filterReason = filterReason,
            filterType = filterType,
            accountName = accountName,
            walletAddress = walletAddress,
            locale = currentLocale,
            unknownAccount = unknownAccount,
            calculateFailed = calculateFailed
        )
        val message = notificationTemplateService.renderTemplate("ORDER_FILTERED", vars)
        sendMessage(message)
    }

    private fun buildOrderFilteredVariables(
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        filterReason: String,
        filterType: String,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        unknownAccount: String,
        calculateFailed: String
    ): Map<String, String> {
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val filterTypeDisplay = when (filterType.uppercase()) {
            "ORDER_DEPTH" -> messageSource.getMessage("notification.filter.type.order_depth", null, "订单深度不足", locale).orEmpty().ifEmpty { "订单深度不足" }
            "SPREAD" -> messageSource.getMessage("notification.filter.type.spread", null, "价差过大", locale).orEmpty().ifEmpty { "价差过大" }
            "ORDERBOOK_DEPTH" -> messageSource.getMessage("notification.filter.type.orderbook_depth", null, "订单簿深度不足", locale).orEmpty().ifEmpty { "订单簿深度不足" }
            "PRICE_VALIDITY" -> messageSource.getMessage("notification.filter.type.price_validity", null, "价格不合理", locale).orEmpty().ifEmpty { "价格不合理" }
            "MARKET_STATUS" -> messageSource.getMessage("notification.filter.type.market_status", null, "市场状态不可交易", locale).orEmpty().ifEmpty { "市场状态不可交易" }
            else -> filterType
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> ""
        }
        val amountDisplay = amount?.let { am ->
            try {
                (am.toSafeBigDecimal().let { if (it.scale() > 4) it.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else it.stripTrailingZeros() }.toPlainString())
            } catch (e: Exception) { am }
        } ?: calculateFailed
        return mapOf(
            "market_title" to marketTitle.replace("<", "&lt;").replace(">", "&gt;"),
            "market_link" to marketLink,
            "side" to sideDisplay,
            "outcome" to (outcome?.replace("<", "&lt;")?.replace(">", "&gt;") ?: ""),
            "price" to formatPrice(price),
            "quantity" to formatQuantity(size),
            "amount" to amountDisplay,
            "account_name" to accountInfo,
            "filter_type" to filterTypeDisplay,
            "filter_reason" to filterReason.replace("<", "&lt;").replace(">", "&gt;"),
            "time" to DateUtils.formatDateTime()
        )
    }

    /**
     * 发送加密价差策略下单成功通知（与跟单一致：在收到 WS 订单推送时匹配价差策略订单后调用）
     */
    suspend fun sendCryptoTailOrderSuccessNotification(
        orderId: String?,
        marketTitle: String,
        marketId: String? = null,
        marketSlug: String? = null,
        side: String,
        outcome: String? = null,
        price: String,
        size: String,
        strategyName: String? = null,
        accountName: String? = null,
        walletAddress: String? = null,
        locale: java.util.Locale? = null,
        orderTime: Long? = null
    ) {
        if (orderId != null) {
            val lastSentTime = sentOrderIds[orderId]
            if (lastSentTime != null && System.currentTimeMillis() - lastSentTime < 5 * 60 * 1000) {
                logger.info("加密价差策略订单通知已发送过（5分钟内），跳过: orderId=$orderId")
                return
            }
            sentOrderIds[orderId] = System.currentTimeMillis()
            if (sentOrderIds.size > 1000) {
                val expiryTime = System.currentTimeMillis() - 5 * 60 * 1000
                sentOrderIds.entries.removeIf { it.value < expiryTime }
            }
        }
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }
        val amount = try {
            val priceDecimal = price.toSafeBigDecimal()
            val sizeDecimal = size.toSafeBigDecimal()
            priceDecimal.multiply(sizeDecimal).toString()
        } catch (e: Exception) {
            logger.warn("计算订单金额失败: ${e.message}", e)
            null
        }
        val unknown = messageSource.getMessage("common.unknown", null, "未知", currentLocale).orEmpty().ifEmpty { "未知" }
        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale).orEmpty().ifEmpty { "未知账户" }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale).orEmpty().ifEmpty { "计算失败" }
        val vars = buildCryptoTailOrderSuccessVariables(
            orderId = orderId,
            marketTitle = marketTitle,
            marketId = marketId,
            marketSlug = marketSlug,
            side = side,
            outcome = outcome,
            price = price,
            size = size,
            amount = amount,
            strategyName = strategyName,
            accountName = accountName,
            walletAddress = walletAddress,
            orderTime = orderTime,
            unknown = unknown,
            unknownAccount = unknownAccount,
            calculateFailed = calculateFailed,
            locale = currentLocale
        )
        val message = notificationTemplateService.renderTemplate("CRYPTO_TAIL_SUCCESS", vars)
        sendMessage(message)
    }

    private fun buildCryptoTailOrderSuccessVariables(
        orderId: String?,
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        strategyName: String?,
        accountName: String?,
        walletAddress: String?,
        orderTime: Long?,
        unknown: String,
        unknownAccount: String,
        calculateFailed: String,
        locale: java.util.Locale
    ): Map<String, String> {
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val time = if (orderTime != null) DateUtils.formatDateTime(orderTime) else DateUtils.formatDateTime()
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> ""
        }
        val amountDisplay = amount?.let { am ->
            try {
                (am.toSafeBigDecimal().let { if (it.scale() > 4) it.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else it.stripTrailingZeros() }.toPlainString())
            } catch (e: Exception) { am }
        } ?: calculateFailed
        return mapOf(
            "order_id" to (orderId ?: unknown),
            "market_title" to marketTitle.replace("<", "&lt;").replace(">", "&gt;"),
            "market_link" to marketLink,
            "side" to sideDisplay,
            "outcome" to (outcome?.replace("<", "&lt;")?.replace(">", "&gt;") ?: ""),
            "price" to formatPrice(price),
            "quantity" to formatQuantity(size),
            "amount" to amountDisplay,
            "account_name" to accountInfo,
            "strategy_name" to (strategyName?.takeIf { it.isNotBlank() } ?: unknown),
            "time" to time
        )
    }

    /**
     * 构建订单被过滤消息
     */
    private fun buildOrderFilteredMessage(
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        filterReason: String,
        filterType: String,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale
    ): String {
        
        // 获取多语言文本
        val orderFiltered = messageSource.getMessage("notification.order.filtered", null, "订单被过滤", locale)
        val orderInfo = messageSource.getMessage("notification.order.info", null, "订单信息", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val sideLabel = messageSource.getMessage("notification.order.side", null, "方向", locale)
        val outcomeLabel = messageSource.getMessage("notification.order.outcome", null, "市场方向", locale)
        val priceLabel = messageSource.getMessage("notification.order.price", null, "价格", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val amountLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val filterReasonLabel = messageSource.getMessage("notification.order.filter_reason", null, "过滤原因", locale)
        val filterTypeLabel = messageSource.getMessage("notification.order.filter_type", null, "过滤类型", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", locale)
        
        // 获取方向的多语言文本
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale)
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale)
            else -> side
        }
        
        // 获取过滤类型的多语言文本
        val filterTypeDisplay = when (filterType.uppercase()) {
            "ORDER_DEPTH" -> messageSource.getMessage("notification.filter.type.order_depth", null, "订单深度不足", locale)
            "SPREAD" -> messageSource.getMessage("notification.filter.type.spread", null, "价差过大", locale)
            "ORDERBOOK_DEPTH" -> messageSource.getMessage("notification.filter.type.orderbook_depth", null, "订单簿深度不足", locale)
            "PRICE_VALIDITY" -> messageSource.getMessage("notification.filter.type.price_validity", null, "价格不合理", locale)
            "MARKET_STATUS" -> messageSource.getMessage("notification.filter.type.market_status", null, "市场状态不可交易", locale)
            else -> filterType
        }
        
        // 构建账户信息（格式：账户名(钱包地址)）
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)

        val time = DateUtils.formatDateTime()

        // 转义 HTML 特殊字符
        val escapedMarketTitle = marketTitle.replace("<", "&lt;").replace(">", "&gt;")
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedFilterReason = filterReason.replace("<", "&lt;").replace(">", "&gt;")

        // 格式化金额显示
        val amountDisplay = if (amount != null) {
            try {
                // 保留最多4位小数，去除尾随零
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) {
                    amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    amountDecimal.stripTrailingZeros()
                }
                formatted.toPlainString()
            } catch (e: Exception) {
                amount
            }
        } else {
            calculateFailed
        }

        // 生成市场链接
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> {
                "https://polymarket.com/event/$marketSlug"
            }
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> {
                "https://polymarket.com/condition/$marketId"
            }
            else -> null
        }
        
        val marketDisplay = if (marketLink != null) {
            "<a href=\"$marketLink\">$escapedMarketTitle</a>"
        } else {
            escapedMarketTitle
        }
        
        // 显示市场方向（outcome）
        val outcomeDisplay = if (!outcome.isNullOrBlank()) {
            val escapedOutcome = outcome.replace("<", "&lt;").replace(">", "&gt;")
            "\n• $outcomeLabel: <b>$escapedOutcome</b>"
        } else {
            ""
        }

        // 格式化价格和数量
        val priceDisplay = formatPrice(price)
        val sizeDisplay = formatQuantity(size)

        return """🚫 <b>$orderFiltered</b>

📊 <b>$orderInfo：</b>
• $marketLabel: $marketDisplay$outcomeDisplay
• $sideLabel: <b>$sideDisplay</b>
• $priceLabel: <code>$priceDisplay</code>
• $quantityLabel: <code>$sizeDisplay</code> shares
• $amountLabel: <code>$amountDisplay</code> USDC
• $accountLabel: $escapedAccountInfo

⚠️ <b>$filterTypeLabel：</b> <code>$filterTypeDisplay</code>

📝 <b>$filterReasonLabel：</b>
<code>$escapedFilterReason</code>

⏰ $timeLabel: <code>$time</code>"""
    }

    /**
     * 发送测试消息
     */
    suspend fun sendTestMessage(message: String = "这是一条测试消息"): Boolean {
        return try {
            val configs = notificationConfigService.getEnabledConfigsByType("telegram")
            if (configs.isEmpty()) {
                logger.warn("没有启用的 Telegram 配置")
                return false
            }

            return coroutineScope {
                val results = configs.map { config ->
                    async(Dispatchers.IO) {
                        when (val configData = config.config) {
                            is NotificationConfigData.Telegram -> {
                                sendTelegramMessage(configData.data, message)
                            }

                            else -> false
                        }
                    }
                }.awaitAll()

                results.any { it }
            }
        } catch (e: Exception) {
            logger.error("发送测试消息失败: ${e.message}", e)
            false
        }
    }

    /**
     * 发送消息（发送给所有启用的 Telegram 配置）
     * 公共方法，供其他服务调用
     */
    suspend fun sendMessage(message: String) {
        try {
            val configs = notificationConfigService.getEnabledConfigsByType("telegram")
            if (configs.isEmpty()) {
                logger.debug("没有启用的 Telegram 配置，跳过发送消息")
                return
            }

            // 异步发送给所有配置
            configs.forEach { config ->
                scope.launch {
                    try {
                        when (val configData = config.config) {
                            is NotificationConfigData.Telegram -> {
                                sendTelegramMessage(configData.data, message)
                            }

                            else -> {
                                logger.warn("不支持的配置类型: ${config.type}")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("发送 Telegram 消息失败 (configId=${config.id}): ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("发送通知消息失败: ${e.message}", e)
        }
    }

    /**
     * 发送 Telegram 消息
     */
    private suspend fun sendTelegramMessage(config: TelegramConfigData, message: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val results = config.chatIds.map { chatId ->
                    async {
                        sendToSingleChat(config.botToken, chatId, message)
                    }
                }.awaitAll()

                results.any { it }
            } catch (e: Exception) {
                logger.error("发送 Telegram 消息失败: ${e.message}", e)
                false
            }
        }
    }

    /**
     * 获取 Chat IDs（通过 getUpdates API）
     * 需要用户先向机器人发送消息
     */
    suspend fun getChatIds(botToken: String): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$apiBaseUrl$botToken/getUpdates"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    response.close()
                    return@withContext Result.failure(
                        Exception("获取 Chat IDs 失败: code=${response.code}, body=$errorBody")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                response.close()

                // 解析 JSON 响应
                val jsonNode = objectMapper.readTree(responseBody)

                if (jsonNode.get("ok")?.asBoolean()?.not() ?: false) {
                    val description = jsonNode.get("description")?.asText() ?: "未知错误"
                    return@withContext Result.failure(Exception("Telegram API 错误: $description"))
                }

                val result = jsonNode.get("result")
                if (result == null || !result.isArray) {
                    return@withContext Result.failure(Exception("未找到消息记录，请先向机器人发送一条消息（如 /start）"))
                }

                // 提取所有唯一的 chat.id
                val chatIds = mutableSetOf<String>()
                result.forEach { update ->
                    val message = update.get("message")
                    if (message != null) {
                        val chat = message.get("chat")
                        if (chat != null) {
                            val chatId = chat.get("id")?.asText()
                            if (chatId != null) {
                                chatIds.add(chatId)
                            }
                        }
                    }
                }

                if (chatIds.isEmpty()) {
                    return@withContext Result.failure(
                        Exception("未找到 Chat ID，请先向机器人发送一条消息（如 /start），然后重试")
                    )
                }

                Result.success(chatIds.toList())
            } catch (e: Exception) {
                logger.error("获取 Chat IDs 异常: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 发送到单个 Chat
     */
    private suspend fun sendToSingleChat(botToken: String, chatId: String, message: String): Boolean {
        return try {
            val url = "$apiBaseUrl$botToken/sendMessage"

            val requestBody = objectMapper.writeValueAsString(
                mapOf(
                    "chat_id" to chatId,
                    "text" to message,
                    "parse_mode" to "HTML",  // 支持 HTML 格式
                    "disable_web_page_preview" to false  // 允许显示链接预览
                )
            )

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val isSuccess = response.isSuccessful

            if (!isSuccess) {
                val errorBody = response.body?.string()
                logger.error("Telegram API 调用失败: code=${response.code}, body=$errorBody")
            }

            response.close()
            isSuccess
        } catch (e: Exception) {
            logger.error("发送 Telegram 消息异常: ${e.message}", e)
            false
        }
    }

    /**
     * 格式化价格显示（保留最多4位小数，截断不四舍五入）
     */
    private fun formatPrice(price: String): String {
        return try {
            val priceDecimal = price.toSafeBigDecimal()
            val formatted = if (priceDecimal.scale() > 4) {
                priceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
            } else {
                priceDecimal.stripTrailingZeros()
            }
            formatted.toPlainString()
        } catch (e: Exception) {
            price
        }
    }

    /**
     * 格式化数量显示（保留最多2位小数，截断不四舍五入）
     */
    private fun formatQuantity(quantity: String): String {
        return try {
            val quantityDecimal = quantity.toSafeBigDecimal()
            val formatted = if (quantityDecimal.scale() > 2) {
                quantityDecimal.setScale(2, java.math.RoundingMode.DOWN).stripTrailingZeros()
            } else {
                quantityDecimal.stripTrailingZeros()
            }
            formatted.toPlainString()
        } catch (e: Exception) {
            quantity
        }
    }

    /**
     * 构建账户信息显示（格式：账户名(钱包地址)）
     */
    private fun buildAccountInfo(
        accountName: String?,
        walletAddress: String?,
        unknownAccount: String
    ): String {
        return when {
            !accountName.isNullOrBlank() && !walletAddress.isNullOrBlank() -> {
                // 有账户名和钱包地址：账户名(钱包地址)
                "${accountName}(${maskAddress(walletAddress)})"
            }
            !accountName.isNullOrBlank() -> {
                // 只有账户名
                accountName
            }
            !walletAddress.isNullOrBlank() -> {
                // 只有钱包地址
                maskAddress(walletAddress)
            }
            else -> {
                // 都没有
                unknownAccount
            }
        }
    }

    /**
     * 构建订单成功通知的变量 Map（供模板渲染）
     */
    private fun buildOrderSuccessVariables(
        orderId: String?,
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        leaderName: String?,
        configName: String?,
        orderTime: Long?,
        availableBalance: String?,
        unknownAccount: String,
        calculateFailed: String
    ): Map<String, String> {
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val unknown = messageSource.getMessage("common.unknown", null, "未知", locale).orEmpty().ifEmpty { "未知" }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val time = if (orderTime != null) DateUtils.formatDateTime(orderTime) else DateUtils.formatDateTime()
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> ""
        }
        val amountDisplay = when {
            amount != null -> try {
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else amountDecimal.stripTrailingZeros()
                formatted.toPlainString()
            } catch (e: Exception) { amount ?: calculateFailed }
            else -> calculateFailed
        }
        val availableBalanceDisplay = if (!availableBalance.isNullOrBlank()) {
            try {
                val balanceDecimal = availableBalance.toSafeBigDecimal()
                val formatted = if (balanceDecimal.scale() > 4) balanceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else balanceDecimal.stripTrailingZeros()
                formatted.toPlainString()
            } catch (e: Exception) { availableBalance ?: "" }
        } else { "" }
        val escapedMarketTitle = marketTitle.replace("<", "&lt;").replace(">", "&gt;")
        val escapedOutcome = outcome?.replace("<", "&lt;")?.replace(">", "&gt;") ?: ""
        return mapOf(
            "order_id" to (orderId ?: unknown),
            "market_title" to escapedMarketTitle,
            "market_link" to marketLink,
            "side" to sideDisplay,
            "outcome" to escapedOutcome,
            "price" to formatPrice(price),
            "quantity" to formatQuantity(size),
            "amount" to amountDisplay,
            "account_name" to accountInfo,
            "available_balance" to availableBalanceDisplay,
            "leader_name" to (leaderName ?: ""),
            "config_name" to (configName ?: ""),
            "time" to time
        )
    }

    /**
     * 构建订单成功消息
     */
    private fun buildOrderSuccessMessage(
        orderId: String?,
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        leaderName: String? = null,  // Leader 名称（备注）
        configName: String? = null,  // 跟单配置名
        orderTime: Long? = null,  // 订单创建时间（毫秒时间戳）
        availableBalance: String? = null  // 可用余额
    ): String {
        
        // 获取多语言文本
        val orderCreatedSuccess = messageSource.getMessage("notification.order.created.success", null, "订单创建成功", locale)
        val orderInfo = messageSource.getMessage("notification.order.info", null, "订单信息", locale)
        val orderIdLabel = messageSource.getMessage("notification.order.id", null, "订单ID", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val sideLabel = messageSource.getMessage("notification.order.side", null, "方向", locale)
        val outcomeLabel = messageSource.getMessage("notification.order.outcome", null, "市场方向", locale)
        val priceLabel = messageSource.getMessage("notification.order.price", null, "价格", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val amountLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val availableBalanceLabel = messageSource.getMessage("notification.order.available_balance", null, "可用余额", locale)
        val unknown = messageSource.getMessage("common.unknown", null, "未知", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", locale)
        
        // 获取方向的多语言文本
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale)
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale)
            else -> side
        }
        
        // 获取图标
        val icon = when (side.uppercase()) {
            "BUY" -> "🚀"
            "SELL" -> "💰"
            else -> "📣"
        }
        
        // 构建账户信息（格式：账户名(钱包地址)）
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)

        // 构建跟单信息（如果有）
        val copyTradingInfo = mutableListOf<String>()
        if (!configName.isNullOrBlank()) {
            copyTradingInfo.add("配置: ${configName!!}")
        }
        if (!leaderName.isNullOrBlank()) {
            copyTradingInfo.add("Leader: ${leaderName!!}")
        }
        val copyTradingInfoText = if (copyTradingInfo.isNotEmpty()) {
            "\n• 跟单: ${copyTradingInfo.joinToString(", ")}"
        } else {
            ""
        }

        // 使用订单时间（如果提供），否则使用当前通知时间
        val time = if (orderTime != null) {
            DateUtils.formatDateTime(orderTime)
        } else {
            DateUtils.formatDateTime()
        }

        // 转义 HTML 特殊字符
        val escapedMarketTitle = marketTitle.replace("<", "&lt;").replace(">", "&gt;")
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedCopyTradingInfo = if (copyTradingInfoText.isNotEmpty()) {
            copyTradingInfoText.replace("<", "&lt;").replace(">", "&gt;")
        } else {
            ""
        }

        // 格式化金额显示
        val amountDisplay = if (amount != null) {
            try {
                // 保留最多4位小数，去除尾随零
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) {
                    amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    amountDecimal.stripTrailingZeros()
                }
                formatted.toPlainString()
            } catch (e: Exception) {
                amount
            }
        } else {
            calculateFailed
        }

        // 生成市场链接
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> {
                "https://polymarket.com/event/$marketSlug"
            }
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> {
                "https://polymarket.com/condition/$marketId"
            }
            else -> null
        }
        
        val marketDisplay = if (marketLink != null) {
            "<a href=\"$marketLink\">$escapedMarketTitle</a>"
        } else {
            escapedMarketTitle
        }
        
        // 显示市场方向（outcome）
        val outcomeDisplay = if (!outcome.isNullOrBlank()) {
            val escapedOutcome = outcome.replace("<", "&lt;").replace(">", "&gt;")
            "\n• $outcomeLabel: <b>$escapedOutcome</b>"
        } else {
            ""
        }

        // 格式化价格和数量
        val priceDisplay = formatPrice(price)
        val sizeDisplay = formatQuantity(size)

        // 格式化可用余额
        val availableBalanceDisplay = if (!availableBalance.isNullOrBlank()) {
            try {
                val balanceDecimal = availableBalance.toSafeBigDecimal()
                val formatted = if (balanceDecimal.scale() > 4) {
                    balanceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    balanceDecimal.stripTrailingZeros()
                }
                "\n• $availableBalanceLabel: <code>${formatted.toPlainString()}</code> USDC"
            } catch (e: Exception) {
                "\n• $availableBalanceLabel: <code>$availableBalance</code> USDC"
            }
        } else {
            ""
        }

        return """$icon <b>$orderCreatedSuccess</b>

📊 <b>$orderInfo：</b>
• $orderIdLabel: <code>${orderId ?: unknown}</code>
• $marketLabel: $marketDisplay$outcomeDisplay
• $sideLabel: <b>$sideDisplay</b>
• $priceLabel: <code>$priceDisplay</code>
• $quantityLabel: <code>$sizeDisplay</code> shares
• $amountLabel: <code>$amountDisplay</code> USDC
• $accountLabel: $escapedAccountInfo$escapedCopyTradingInfo$availableBalanceDisplay

⏰ $timeLabel: <code>$time</code>"""
    }

    /**
     * 构建加密价差策略下单成功消息（与订单成功格式一致，增加「加密价差策略」标题与策略名）
     */
    private fun buildCryptoTailOrderSuccessMessage(
        orderId: String?,
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        strategyName: String?,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        orderTime: Long?
    ): String {
        val tailOrderSuccess = messageSource.getMessage("notification.tail.order.success", null, "加密价差策略下单成功", locale)
        val strategyLabel = messageSource.getMessage("notification.tail.strategy", null, "策略", locale)
        val orderInfo = messageSource.getMessage("notification.order.info", null, "订单信息", locale)
        val orderIdLabel = messageSource.getMessage("notification.order.id", null, "订单ID", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val sideLabel = messageSource.getMessage("notification.order.side", null, "方向", locale)
        val outcomeLabel = messageSource.getMessage("notification.order.outcome", null, "市场方向", locale)
        val priceLabel = messageSource.getMessage("notification.order.price", null, "价格", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val amountLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val unknown: String = messageSource.getMessage("common.unknown", null, "未知", locale) ?: "未知"
        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", locale)
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale)
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale)
            else -> side
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val time = if (orderTime != null) DateUtils.formatDateTime(orderTime) else DateUtils.formatDateTime()
        val escapedMarketTitle = marketTitle.replace("<", "&lt;").replace(">", "&gt;")
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val strategyDisplay = strategyName?.takeIf { it.isNotBlank() } ?: unknown
        val escapedStrategyName = strategyDisplay.replace("<", "&lt;").replace(">", "&gt;")
        val amountDisplay = if (amount != null) {
            try {
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else amountDecimal.stripTrailingZeros()
                formatted.toPlainString()
            } catch (e: Exception) { amount }
        } else calculateFailed
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> null
        }
        val marketDisplay = if (marketLink != null) "<a href=\"$marketLink\">$escapedMarketTitle</a>" else escapedMarketTitle
        val outcomeDisplay = if (!outcome.isNullOrBlank()) {
            val escapedOutcome = outcome.replace("<", "&lt;").replace(">", "&gt;")
            "\n• $outcomeLabel: <b>$escapedOutcome</b>"
        } else ""
        val priceDisplay = formatPrice(price)
        val sizeDisplay = formatQuantity(size)
        return """🚀 <b>$tailOrderSuccess</b>

📊 <b>$orderInfo：</b>
• $orderIdLabel: <code>${orderId ?: unknown}</code>
• $strategyLabel: $escapedStrategyName
• $marketLabel: $marketDisplay$outcomeDisplay
• $sideLabel: <b>$sideDisplay</b>
• $priceLabel: <code>$priceDisplay</code>
• $quantityLabel: <code>$sizeDisplay</code> shares
• $amountLabel: <code>$amountDisplay</code> USDC
• $accountLabel: $escapedAccountInfo

⏰ $timeLabel: <code>$time</code>"""
    }

    /**
     * 构建订单失败消息
     */
    private fun buildOrderFailureMessage(
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        errorMessage: String,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale
    ): String {
        
        // 获取多语言文本
        val orderCreatedFailed = messageSource.getMessage("notification.order.created.failed", null, "订单创建失败", locale)
        val orderInfo = messageSource.getMessage("notification.order.info", null, "订单信息", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val sideLabel = messageSource.getMessage("notification.order.side", null, "方向", locale)
        val outcomeLabel = messageSource.getMessage("notification.order.outcome", null, "市场方向", locale)
        val priceLabel = messageSource.getMessage("notification.order.price", null, "价格", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val amountLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val errorInfo = messageSource.getMessage("notification.order.error_info", null, "错误信息", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", locale)
        
        // 获取方向的多语言文本
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale)
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale)
            else -> side
        }
        
        // 构建账户信息（格式：账户名(钱包地址)）
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)

        val time = DateUtils.formatDateTime()

        // 错误信息已经是后端返回的 msg，不需要截断（但为了安全，限制长度）
        val shortErrorMessage = if (errorMessage.length > 500) {
            errorMessage.substring(0, 500) + "..."
        } else {
            errorMessage
        }

        // 转义 HTML 特殊字符
        val escapedMarketTitle = marketTitle.replace("<", "&lt;").replace(">", "&gt;")
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedErrorMessage = shortErrorMessage.replace("<", "&lt;").replace(">", "&gt;")

        // 格式化金额显示
        val amountDisplay = if (amount != null) {
            try {
                // 保留最多4位小数，去除尾随零
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) {
                    amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    amountDecimal.stripTrailingZeros()
                }
                formatted.toPlainString()
            } catch (e: Exception) {
                amount
            }
        } else {
            calculateFailed
        }

        // 生成市场链接
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> {
                "https://polymarket.com/event/$marketSlug"
            }
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> {
                "https://polymarket.com/condition/$marketId"
            }
            else -> null
        }
        
        val marketDisplay = if (marketLink != null) {
            "<a href=\"$marketLink\">$escapedMarketTitle</a>"
        } else {
            escapedMarketTitle
        }
        
        // 显示市场方向（outcome）
        val outcomeDisplay = if (!outcome.isNullOrBlank()) {
            val escapedOutcome = outcome.replace("<", "&lt;").replace(">", "&gt;")
            "\n• $outcomeLabel: <b>$escapedOutcome</b>"
        } else {
            ""
        }

        // 格式化价格和数量
        val priceDisplay = formatPrice(price)
        val sizeDisplay = formatQuantity(size)

        return """❌ <b>$orderCreatedFailed</b>

📊 <b>$orderInfo：</b>
• $marketLabel: $marketDisplay$outcomeDisplay
• $sideLabel: <b>$sideDisplay</b>
• $priceLabel: <code>$priceDisplay</code>
• $quantityLabel: <code>$sizeDisplay</code> shares
• $amountLabel: <code>$amountDisplay</code> USDC
• $accountLabel: $escapedAccountInfo

⚠️ <b>$errorInfo：</b>
<code>$escapedErrorMessage</code>

⏰ $timeLabel: <code>$time</code>"""
    }

    /**
     * 发送仓位赎回通知
     * @param locale 语言设置（可选，如果提供则使用，否则使用 LocaleContextHolder 获取）
     * @param availableBalance 可用余额（可选）
     */
    suspend fun sendRedeemNotification(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        totalRedeemedValue: String,
        positions: List<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>,
        locale: java.util.Locale? = null,
        availableBalance: String? = null
    ) {
        // 获取语言设置（优先使用传入的 locale，否则从 LocaleContextHolder 获取）
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")  // 默认简体中文
        }
        
        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale) ?: "未知账户"
        val vars = buildRedeemSuccessVariables(
            accountName = accountName,
            walletAddress = walletAddress,
            transactionHash = transactionHash,
            totalRedeemedValue = totalRedeemedValue,
            availableBalance = availableBalance,
            unknownAccount = unknownAccount
        )
        val message = notificationTemplateService.renderTemplate("REDEEM_SUCCESS", vars)
        sendMessage(message)
    }

    private fun buildRedeemSuccessVariables(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        totalRedeemedValue: String,
        availableBalance: String?,
        unknownAccount: String
    ): Map<String, String> {
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val totalValueDisplay = try {
            val d = totalRedeemedValue.toSafeBigDecimal()
            (if (d.scale() > 4) d.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else d.stripTrailingZeros()).toPlainString()
        } catch (e: Exception) { totalRedeemedValue }
        val availableBalanceDisplay = availableBalance?.let { ab ->
            try {
                val d = ab.toSafeBigDecimal()
                (if (d.scale() > 4) d.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else d.stripTrailingZeros()).toPlainString()
            } catch (e: Exception) { ab }
        } ?: ""
        return mapOf(
            "account_name" to accountInfo,
            "transaction_hash" to transactionHash.replace("<", "&lt;").replace(">", "&gt;"),
            "total_value" to totalValueDisplay,
            "available_balance" to availableBalanceDisplay,
            "time" to DateUtils.formatDateTime()
        )
    }
    
    /**
     * 构建仓位赎回消息
     */
    private fun buildRedeemMessage(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        totalRedeemedValue: String,
        positions: List<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>,
        locale: java.util.Locale,
        availableBalance: String? = null
    ): String {
        // 获取多语言文本
        val redeemSuccess = messageSource.getMessage("notification.redeem.success", null, "仓位赎回成功", locale)
        val redeemInfo = messageSource.getMessage("notification.redeem.info", null, "赎回信息", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val transactionHashLabel = messageSource.getMessage("notification.redeem.transaction_hash", null, "交易哈希", locale)
        val totalValueLabel = messageSource.getMessage("notification.redeem.total_value", null, "赎回总价值", locale)
        val positionsLabel = messageSource.getMessage("notification.redeem.positions", null, "赎回仓位", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val valueLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val availableBalanceLabel = messageSource.getMessage("notification.redeem.available_balance", null, "可用余额", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        
        // 构建账户信息（格式：账户名(钱包地址)）
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        
        val time = DateUtils.formatDateTime()
        
        // 转义 HTML 特殊字符
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedTxHash = transactionHash.replace("<", "&lt;").replace(">", "&gt;")
        
        // 格式化金额显示
        val totalValueDisplay = try {
            val totalValueDecimal = totalRedeemedValue.toSafeBigDecimal()
            val formatted = if (totalValueDecimal.scale() > 4) {
                totalValueDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
            } else {
                totalValueDecimal.stripTrailingZeros()
            }
            formatted.toPlainString()
        } catch (e: Exception) {
            totalRedeemedValue
        }
        
        // 构建仓位列表
        val positionsText = positions.joinToString("\n") { position ->
            val quantityDisplay = formatQuantity(position.quantity)
            val valueDisplay = try {
                val valueDecimal = position.value.toSafeBigDecimal()
                val formatted = if (valueDecimal.scale() > 4) {
                    valueDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    valueDecimal.stripTrailingZeros()
                }
                formatted.toPlainString()
            } catch (e: Exception) {
                position.value
            }
            "  • ${position.marketId.substring(0, 8)}... (${position.side}): $quantityDisplay shares = $valueDisplay USDC"
        }
        
        // 格式化可用余额
        val availableBalanceDisplay = if (!availableBalance.isNullOrBlank()) {
            try {
                val balanceDecimal = availableBalance.toSafeBigDecimal()
                val formatted = if (balanceDecimal.scale() > 4) {
                    balanceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    balanceDecimal.stripTrailingZeros()
                }
                "\n• $availableBalanceLabel: <code>${formatted.toPlainString()}</code> USDC"
            } catch (e: Exception) {
                "\n• $availableBalanceLabel: <code>$availableBalance</code> USDC"
            }
        } else {
            ""
        }
        
        return """💸 <b>$redeemSuccess</b>

📊 <b>$redeemInfo：</b>
• $accountLabel: $escapedAccountInfo
• $transactionHashLabel: <code>$escapedTxHash</code>
• $totalValueLabel: <code>$totalValueDisplay</code> USDC$availableBalanceDisplay

📦 <b>$positionsLabel：</b>
$positionsText

⏰ $timeLabel: <code>$time</code>"""
    }

    /**
     * 发送仓位已结算（无收益）通知
     * 用于输的仓位，赎回价值为 0 的情况
     */
    suspend fun sendRedeemNoReturnNotification(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        positions: List<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>,
        locale: java.util.Locale? = null,
        availableBalance: String? = null
    ) {
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }

        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale) ?: "未知账户"
        val vars = buildRedeemNoReturnVariables(
            accountName = accountName,
            walletAddress = walletAddress,
            transactionHash = transactionHash,
            availableBalance = availableBalance,
            unknownAccount = unknownAccount
        )
        val message = notificationTemplateService.renderTemplate("REDEEM_NO_RETURN", vars)
        sendMessage(message)
    }

    private fun buildRedeemNoReturnVariables(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        availableBalance: String?,
        unknownAccount: String
    ): Map<String, String> {
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val availableBalanceDisplay = availableBalance?.let { ab ->
            try {
                val d = ab.toSafeBigDecimal()
                (if (d.scale() > 4) d.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else d.stripTrailingZeros()).toPlainString()
            } catch (e: Exception) { ab }
        } ?: ""
        return mapOf(
            "account_name" to accountInfo,
            "transaction_hash" to transactionHash.replace("<", "&lt;").replace(">", "&gt;"),
            "available_balance" to availableBalanceDisplay,
            "time" to DateUtils.formatDateTime()
        )
    }

    /**
     * 构建仓位已结算（无收益）消息
     */
    private fun buildRedeemNoReturnMessage(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        positions: List<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>,
        locale: java.util.Locale,
        availableBalance: String? = null
    ): String {
        val noReturnTitle = messageSource.getMessage("notification.redeem.no_return.title", null, "仓位已结算（无收益）", locale)
        val noReturnInfo = messageSource.getMessage("notification.redeem.no_return.info", null, "结算信息", locale)
        val noReturnMessage = messageSource.getMessage("notification.redeem.no_return.message", null, "市场已结算，您的预测未命中，赎回价值为 0。", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val transactionHashLabel = messageSource.getMessage("notification.redeem.transaction_hash", null, "交易哈希", locale)
        val positionsLabel = messageSource.getMessage("notification.redeem.no_return.positions", null, "结算仓位", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val availableBalanceLabel = messageSource.getMessage("notification.redeem.available_balance", null, "可用余额", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"

        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val time = DateUtils.formatDateTime()

        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedTxHash = transactionHash.replace("<", "&lt;").replace(">", "&gt;")

        val positionsText = positions.joinToString("\n") { position ->
            val quantityDisplay = formatQuantity(position.quantity)
            "  • ${position.marketId.substring(0, 8)}... (${position.side}): $quantityDisplay shares"
        }

        // 格式化可用余额
        val availableBalanceDisplay = if (!availableBalance.isNullOrBlank()) {
            try {
                val balanceDecimal = availableBalance.toSafeBigDecimal()
                val formatted = if (balanceDecimal.scale() > 4) {
                    balanceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    balanceDecimal.stripTrailingZeros()
                }
                "\n• $availableBalanceLabel: <code>${formatted.toPlainString()}</code> USDC"
            } catch (e: Exception) {
                "\n• $availableBalanceLabel: <code>$availableBalance</code> USDC"
            }
        } else {
            ""
        }

        return """📋 <b>$noReturnTitle</b>

📊 <b>$noReturnInfo：</b>
<i>$noReturnMessage</i>

• $accountLabel: $escapedAccountInfo
• $transactionHashLabel: <code>$escapedTxHash</code>$availableBalanceDisplay

📦 <b>$positionsLabel：</b>
$positionsText

⏰ $timeLabel: <code>$time</code>"""
    }

    /**
     * 脱敏显示地址（只显示前6位和后4位）
     */
    private fun maskAddress(address: String): String {
        if (address.length <= 10) {
            return address
        }
        return "${address.substring(0, 6)}...${address.substring(address.length - 4)}"
    }
}

