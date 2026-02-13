package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import com.wrbug.polymarketbot.util.toSafeBigDecimal

/**
 * 尾盘策略触发记录
 */
@Entity
@Table(name = "crypto_tail_strategy_trigger")
data class CryptoTailStrategyTrigger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,

    @Column(name = "outcome_index", nullable = false)
    val outcomeIndex: Int = 0,

    @Column(name = "trigger_price", nullable = false, precision = 20, scale = 8)
    val triggerPrice: BigDecimal = BigDecimal.ZERO,

    @Column(name = "amount_usdc", nullable = false, precision = 20, scale = 8)
    val amountUsdc: BigDecimal = BigDecimal.ZERO,

    @Column(name = "order_id", length = 128)
    val orderId: String? = null,

    @Column(name = "status", nullable = false, length = 20)
    val status: String = "success",

    @Column(name = "fail_reason", length = 500)
    val failReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
