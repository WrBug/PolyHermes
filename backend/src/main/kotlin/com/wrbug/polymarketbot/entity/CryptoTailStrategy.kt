package com.wrbug.polymarketbot.entity

import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.enums.SpreadDirectionConverter
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadModeConverter
import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 加密价差策略实体
 * 5/15 分钟 Up or Down 市场，在周期内时间窗口、价格进入区间时市价买入
 */
@Entity
@Table(name = "crypto_tail_strategy")
data class CryptoTailStrategy(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false)
    val accountId: Long = 0L,

    @Column(name = "name", length = 255)
    val name: String? = null,

    @Column(name = "market_slug_prefix", nullable = false, length = 64)
    val marketSlugPrefix: String = "",

    @Column(name = "interval_seconds", nullable = false)
    val intervalSeconds: Int = 300,

    @Column(name = "window_start_seconds", nullable = false)
    val windowStartSeconds: Int = 0,

    @Column(name = "window_end_seconds", nullable = false)
    val windowEndSeconds: Int = 0,

    @Column(name = "min_price", nullable = false, precision = 20, scale = 8)
    val minPrice: BigDecimal = BigDecimal.ONE,

    @Column(name = "max_price", nullable = false, precision = 20, scale = 8)
    val maxPrice: BigDecimal = BigDecimal.ONE,

    @Column(name = "amount_mode", nullable = false, length = 10)
    val amountMode: String = "RATIO",

    @Column(name = "amount_value", nullable = false, precision = 20, scale = 8)
    val amountValue: BigDecimal = BigDecimal.ZERO,

    /** 价差模式: NONE=不校验, FIXED=固定值, AUTO=历史计算 */
    @Convert(converter = SpreadModeConverter::class)
    @Column(name = "spread_mode", nullable = false, columnDefinition = "TINYINT")
    val spreadMode: SpreadMode = SpreadMode.NONE,

    /** 价差数值（FIXED 时必填；AUTO 时可存计算值） */
    @Column(name = "spread_value", precision = 20, scale = 8)
    val spreadValue: BigDecimal? = null,

    /** 价差方向: MIN=最小价差（价差>=配置值触发），MAX=最大价差（价差<=配置值触发） */
    @Convert(converter = SpreadDirectionConverter::class)
    @Column(name = "spread_direction", nullable = false, columnDefinition = "TINYINT")
    val spreadDirection: SpreadDirection = SpreadDirection.MIN,

    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
