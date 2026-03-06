package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 体育尾盘策略实体
 * 在价格达到设定值时自动买入，支持止盈止损
 */
@Entity
@Table(name = "sports_tail_strategy")
data class SportsTailStrategy(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** 账户ID */
    @Column(name = "account_id", nullable = false)
    val accountId: Long = 0L,

    /** 市场 conditionId */
    @Column(name = "condition_id", nullable = false, length = 100)
    val conditionId: String = "",

    /** 市场标题 */
    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,

    /** 事件 slug */
    @Column(name = "event_slug", length = 255)
    val eventSlug: String? = null,

    /** YES Token ID */
    @Column(name = "yes_token_id", length = 100)
    val yesTokenId: String? = null,

    /** NO Token ID */
    @Column(name = "no_token_id", length = 100)
    val noTokenId: String? = null,

    /** 触发价格 */
    @Column(name = "trigger_price", nullable = false, precision = 20, scale = 8)
    val triggerPrice: BigDecimal = BigDecimal.ONE,

    /** 金额模式: FIXED=固定金额, RATIO=余额比例 */
    @Column(name = "amount_mode", nullable = false, length = 10)
    val amountMode: String = "FIXED",

    /** 金额值 */
    @Column(name = "amount_value", nullable = false, precision = 20, scale = 8)
    val amountValue: BigDecimal = BigDecimal.ZERO,

    /** 止盈价格 */
    @Column(name = "take_profit_price", precision = 20, scale = 8)
    val takeProfitPrice: BigDecimal? = null,

    /** 止损价格 */
    @Column(name = "stop_loss_price", precision = 20, scale = 8)
    val stopLossPrice: BigDecimal? = null,

    /** 是否已成交 */
    @Column(name = "filled", nullable = false)
    val filled: Boolean = false,

    /** 成交价格 */
    @Column(name = "filled_price", precision = 20, scale = 8)
    val filledPrice: BigDecimal? = null,

    /** 成交方向索引: 0=YES, 1=NO */
    @Column(name = "filled_outcome_index")
    val filledOutcomeIndex: Int? = null,

    /** 成交方向名称 */
    @Column(name = "filled_outcome_name", length = 50)
    val filledOutcomeName: String? = null,

    /** 成交金额 */
    @Column(name = "filled_amount", precision = 20, scale = 8)
    val filledAmount: BigDecimal? = null,

    /** 成交份额 */
    @Column(name = "filled_shares", precision = 20, scale = 8)
    val filledShares: BigDecimal? = null,

    /** 成交时间 */
    @Column(name = "filled_at")
    val filledAt: Long? = null,

    /** 是否已卖出 */
    @Column(name = "sold", nullable = false)
    val sold: Boolean = false,

    /** 卖出价格 */
    @Column(name = "sell_price", precision = 20, scale = 8)
    val sellPrice: BigDecimal? = null,

    /** 卖出类型: TAKE_PROFIT, STOP_LOSS, MANUAL */
    @Column(name = "sell_type", length = 20)
    val sellType: String? = null,

    /** 卖出金额 */
    @Column(name = "sell_amount", precision = 20, scale = 8)
    val sellAmount: BigDecimal? = null,

    /** 已实现盈亏 */
    @Column(name = "realized_pnl", precision = 20, scale = 8)
    val realizedPnl: BigDecimal? = null,

    /** 卖出时间 */
    @Column(name = "sold_at")
    val soldAt: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
