package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 体育尾盘策略触发记录
 * 记录每次买入/卖出的详细信息
 */
@Entity
@Table(name = "sports_tail_strategy_trigger")
data class SportsTailStrategyTrigger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** 策略ID */
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    /** 账户ID */
    @Column(name = "account_id", nullable = false)
    val accountId: Long = 0L,

    /** 市场 conditionId */
    @Column(name = "condition_id", nullable = false, length = 100)
    val conditionId: String = "",

    /** 市场标题 */
    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,

    /** 买入价格 */
    @Column(name = "buy_price", nullable = false, precision = 20, scale = 8)
    val buyPrice: BigDecimal = BigDecimal.ZERO,

    /** 买入方向索引: 0=YES, 1=NO */
    @Column(name = "outcome_index", nullable = false)
    val outcomeIndex: Int = 0,

    /** 买入方向名称 */
    @Column(name = "outcome_name", length = 50)
    val outcomeName: String? = null,

    /** 买入金额 */
    @Column(name = "buy_amount", nullable = false, precision = 20, scale = 8)
    val buyAmount: BigDecimal = BigDecimal.ZERO,

    /** 买入份额 */
    @Column(name = "buy_shares", precision = 20, scale = 8)
    val buyShares: BigDecimal? = null,

    /** 买入订单ID */
    @Column(name = "buy_order_id", length = 100)
    val buyOrderId: String? = null,

    /** 买入状态: PENDING, SUCCESS, FAIL */
    @Column(name = "buy_status", nullable = false, length = 20)
    val buyStatus: String = "PENDING",

    /** 买入失败原因 */
    @Column(name = "buy_fail_reason", length = 500)
    val buyFailReason: String? = null,

    /** 卖出价格 */
    @Column(name = "sell_price", precision = 20, scale = 8)
    val sellPrice: BigDecimal? = null,

    /** 卖出类型: TAKE_PROFIT, STOP_LOSS, MANUAL */
    @Column(name = "sell_type", length = 20)
    val sellType: String? = null,

    /** 卖出金额 */
    @Column(name = "sell_amount", precision = 20, scale = 8)
    val sellAmount: BigDecimal? = null,

    /** 卖出订单ID */
    @Column(name = "sell_order_id", length = 100)
    val sellOrderId: String? = null,

    /** 卖出状态: PENDING, SUCCESS, FAIL */
    @Column(name = "sell_status", length = 20)
    val sellStatus: String? = null,

    /** 卖出失败原因 */
    @Column(name = "sell_fail_reason", length = 500)
    val sellFailReason: String? = null,

    /** 已实现盈亏 */
    @Column(name = "realized_pnl", precision = 20, scale = 8)
    val realizedPnl: BigDecimal? = null,

    /** 触发时间 */
    @Column(name = "triggered_at", nullable = false)
    val triggeredAt: Long = System.currentTimeMillis(),

    /** 卖出时间 */
    @Column(name = "sold_at")
    val soldAt: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
