package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 大单监听策略实体
 * 监听自选市场的 Activity WS 成交，按 tokenId+BUY 聚合窗口内 notional，达阈值自动 FAK 下单
 */
@Entity
@Table(name = "whale_monitor_strategy")
data class WhaleMonitorStrategy(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false)
    val accountId: Long = 0L,

    @Column(name = "name", length = 255)
    val name: String? = null,

    /** 监听市场 conditionId 列表，JSON 数组格式如 ["0xabc...","0xdef..."] */
    @Column(name = "condition_ids", nullable = false, columnDefinition = "TEXT")
    val conditionIds: String = "[]",

    @Column(name = "window_seconds", nullable = false)
    val windowSeconds: Int = 10,

    @Column(name = "threshold_amount", nullable = false, precision = 20, scale = 8)
    val thresholdAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "order_amount", nullable = false, precision = 20, scale = 8)
    val orderAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "min_price", nullable = false, precision = 20, scale = 8)
    val minPrice: BigDecimal = BigDecimal.ZERO,

    @Column(name = "max_price", nullable = false, precision = 20, scale = 8)
    val maxPrice: BigDecimal = BigDecimal.ONE,

    @Column(name = "cooldown_seconds", nullable = false)
    val cooldownSeconds: Int = 60,

    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
