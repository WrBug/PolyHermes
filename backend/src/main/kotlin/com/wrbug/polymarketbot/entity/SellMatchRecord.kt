package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 卖出匹配记录实体
 * 记录每笔卖出订单的匹配信息
 */
@Entity
@Table(name = "sell_match_record")
data class SellMatchRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    
    @Column(name = "sell_order_id", nullable = false, length = 100)
    val sellOrderId: String,  // 跟单卖出订单ID
    
    @Column(name = "leader_sell_trade_id", nullable = false, length = 100)
    val leaderSellTradeId: String,  // Leader 卖出交易ID
    
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    
    @Column(name = "side", nullable = false, length = 10)
    val side: String,  // 兼容字段：YES/NO 或 outcomeIndex（字符串）
    
    @Column(name = "outcome_index", nullable = true)
    val outcomeIndex: Int? = null,  // 结果索引（0, 1, 2, ...），支持多元市场
    
    @Column(name = "total_matched_quantity", nullable = false, precision = 20, scale = 8)
    val totalMatchedQuantity: BigDecimal,  // 总匹配数量
    
    @Column(name = "sell_price", nullable = false, precision = 20, scale = 8)
    val sellPrice: BigDecimal,  // 卖出价格
    
    @Column(name = "total_realized_pnl", nullable = false, precision = 20, scale = 8)
    val totalRealizedPnl: BigDecimal,  // 总已实现盈亏
    
    @Column(name = "price_updated", nullable = false)
    var priceUpdated: Boolean = false,  // 共用字段：false 表示未处理（未查询订单详情，未发送通知），true 表示已处理（已查询订单详情，已发送通知）
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

