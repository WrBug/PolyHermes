package com.wrbug.polymarketbot.dto

/**
 * 加密价差策略手动下单请求
 */
data class CryptoTailManualOrderRequest(
    /** 策略ID */
    val strategyId: Long = 0L,
    /** 当前周期开始时间 (Unix 秒) */
    val periodStartUnix: Long = 0L,
    /** 下单方向: UP or DOWN */
    val direction: String = "UP",
    /** 下单价格 */
    val price: String = "0",
    /** 下单数量 */
    val size: String = "1",
    /** 市场标题（用于记录） */
    val marketTitle: String = "",
    /** Token IDs */
    val tokenIds: List<String> = emptyList()
)
