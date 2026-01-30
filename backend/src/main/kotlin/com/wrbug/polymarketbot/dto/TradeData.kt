package com.wrbug.polymarketbot.dto

import java.math.BigDecimal

/**
 * 用户交易数据
 * 用于回测功能，从 Polymarket API 获取的用户交易历史
 */
data class TradeData(
    val tradeId: String,          // 交易 ID
    val marketId: String,         // 市场 ID
    val marketTitle: String?,      // 市场标题
    val marketSlug: String?,       // 市场 Slug
    val side: String,             // 交易方向: BUY/SELL
    val outcome: String,            // 结果: YES/NO 或 outcomeIndex
    val outcomeIndex: Int?,        // 结果索引
    val price: BigDecimal,          // 成交价格
    val size: BigDecimal,           // 成交数量
    val amount: BigDecimal,          // 成交金额
    val timestamp: Long            // 交易时间戳
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TradeData) return false
        return tradeId == other.tradeId
    }

    override fun hashCode(): Int {
        return tradeId.hashCode()
    }
}

