package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 回测交易记录实体
 * 用于记录回测过程中的每笔模拟交易
 */
@Entity
@Table(name = "backtest_trade")
data class BacktestTrade(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "backtest_task_id", nullable = false)
    val backtestTaskId: Long,

    @Column(name = "trade_time", nullable = false)
    val tradeTime: Long,

    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,

    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,

    @Column(name = "side", nullable = false, length = 20)
    val side: String,  // BUY/SELL/SETTLEMENT

    @Column(name = "outcome", nullable = false, length = 50)
    val outcome: String,  // YES/NO 或 outcomeIndex

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,  // 结果索引（0, 1, 2, ...），支持多元市场

    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    val quantity: BigDecimal,

    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    val price: BigDecimal,

    @Column(name = "amount", nullable = false, precision = 20, scale = 8)
    val amount: BigDecimal,

    @Column(name = "fee", nullable = false, precision = 20, scale = 8)
    val fee: BigDecimal = BigDecimal.ZERO,  // 手续费（回测不计算，默认为0）

    @Column(name = "profit_loss", precision = 20, scale = 8)
    val profitLoss: BigDecimal? = null,  // 盈亏(仅卖出时)

    @Column(name = "balance_after", nullable = false, precision = 20, scale = 8)
    val balanceAfter: BigDecimal,  // 交易后余额

    @Column(name = "leader_trade_id", length = 100)
    val leaderTradeId: String? = null,  // Leader 原始交易ID

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

