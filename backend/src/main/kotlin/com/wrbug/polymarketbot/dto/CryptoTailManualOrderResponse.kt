package com.wrbug.polymarketbot.dto

/**
 * 加密价差策略手动下单响应
 */
data class CryptoTailManualOrderResponse(
    /** 是否成功 */
    val success: Boolean = false,
    /** 订单ID */
    val orderId: String? = null,
    /** 提示消息 */
    val message: String = "",
    /** 下单详情 */
    val orderDetails: ManualOrderDetails? = null
)

/**
 * 手动下单详情
 */
data class ManualOrderDetails(
    /** 策略ID */
    val strategyId: Long = 0L,
    /** 方向 */
    val direction: String = "",
    /** 下单价格 */
    val price: String = "",
    /** 下单数量 */
    val size: String = "",
    /** 总金额 */
    val totalAmount: String = ""
)
