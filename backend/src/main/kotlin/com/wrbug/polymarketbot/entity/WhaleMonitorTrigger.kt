package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 大单监听触发记录
 */
@Entity
@Table(name = "whale_monitor_trigger")
data class WhaleMonitorTrigger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    @Column(name = "condition_id", nullable = false, length = 128)
    val conditionId: String = "",

    @Column(name = "token_id", nullable = false, length = 128)
    val tokenId: String = "",

    @Column(name = "side", nullable = false, length = 10)
    val side: String = "BUY",

    @Column(name = "trigger_volume", nullable = false, precision = 20, scale = 8)
    val triggerVolume: BigDecimal = BigDecimal.ZERO,

    @Column(name = "order_price", nullable = false, precision = 20, scale = 8)
    val orderPrice: BigDecimal = BigDecimal.ZERO,

    @Column(name = "order_size", nullable = false, precision = 20, scale = 8)
    val orderSize: BigDecimal = BigDecimal.ZERO,

    @Column(name = "order_amount", nullable = false, precision = 20, scale = 8)
    val orderAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "order_id", length = 128)
    val orderId: String? = null,

    @Column(name = "status", nullable = false, length = 20)
    val status: String = "success",

    @Column(name = "fail_reason", length = 500)
    val failReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
