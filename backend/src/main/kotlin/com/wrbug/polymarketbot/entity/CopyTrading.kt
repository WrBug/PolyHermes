package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import com.wrbug.polymarketbot.util.toSafeBigDecimal

/**
 * 跟单配置实体（独立配置，不再绑定模板）
 */
@Entity
@Table(
    name = "copy_trading",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["account_id", "leader_id"])
    ]
)
data class CopyTrading(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,  // 钱包账户ID
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,  // Leader ID
    
    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = true,  // 是否启用
    
    // 跟单配置参数
    @Column(name = "copy_mode", nullable = false, length = 10)
    val copyMode: String = "RATIO",  // "RATIO" 或 "FIXED"
    
    @Column(name = "copy_ratio", nullable = false, precision = 10, scale = 2)
    val copyRatio: BigDecimal = BigDecimal.ONE,  // 仅在 copyMode="RATIO" 时生效
    
    @Column(name = "fixed_amount", precision = 20, scale = 8)
    val fixedAmount: BigDecimal? = null,  // 仅在 copyMode="FIXED" 时生效
    
    @Column(name = "max_order_size", nullable = false, precision = 20, scale = 8)
    val maxOrderSize: BigDecimal = "1000".toSafeBigDecimal(),
    
    @Column(name = "min_order_size", nullable = false, precision = 20, scale = 8)
    val minOrderSize: BigDecimal = "1".toSafeBigDecimal(),
    
    @Column(name = "max_daily_loss", nullable = false, precision = 20, scale = 8)
    val maxDailyLoss: BigDecimal = "10000".toSafeBigDecimal(),
    
    @Column(name = "max_daily_orders", nullable = false)
    val maxDailyOrders: Int = 100,
    
    @Column(name = "price_tolerance", nullable = false, precision = 5, scale = 2)
    val priceTolerance: BigDecimal = "5".toSafeBigDecimal(),  // 百分比
    
    @Column(name = "delay_seconds", nullable = false)
    val delaySeconds: Int = 0,
    
    @Column(name = "poll_interval_seconds", nullable = false)
    val pollIntervalSeconds: Int = 5,  // 轮询间隔（仅在 WebSocket 不可用时使用）
    
    @Column(name = "use_websocket", nullable = false)
    val useWebSocket: Boolean = true,  // 是否优先使用 WebSocket 推送
    
    @Column(name = "websocket_reconnect_interval", nullable = false)
    val websocketReconnectInterval: Int = 5000,  // WebSocket 重连间隔（毫秒）
    
    @Column(name = "websocket_max_retries", nullable = false)
    val websocketMaxRetries: Int = 10,  // WebSocket 最大重试次数
    
    @Column(name = "support_sell", nullable = false)
    val supportSell: Boolean = true,  // 是否支持跟单卖出
    
    // 过滤条件字段
    @Column(name = "min_order_depth", precision = 20, scale = 8)
    val minOrderDepth: BigDecimal? = null,  // 最小订单深度（USDC金额），NULL表示不启用
    
    @Column(name = "max_spread", precision = 20, scale = 8)
    val maxSpread: BigDecimal? = null,  // 最大价差（绝对价格），NULL表示不启用
    
    @Column(name = "min_price", precision = 20, scale = 8)
    val minPrice: BigDecimal? = null,  // 最低价格（可选），NULL表示不限制最低价
    
    @Column(name = "max_price", precision = 20, scale = 8)
    val maxPrice: BigDecimal? = null,  // 最高价格（可选），NULL表示不限制最高价
    
    // 最大仓位配置
    @Column(name = "max_position_value", precision = 20, scale = 8)
    val maxPositionValue: BigDecimal? = null,  // 最大仓位金额（USDC），NULL表示不启用
    
    @Column(name = "max_position_count")
    val maxPositionCount: Int? = null,  // 最大仓位数量，NULL表示不启用
    
    // 新增配置字段
    @Column(name = "config_name", length = 255)
    val configName: String? = null,  // 配置名（可选）
    
    @Column(name = "push_failed_orders", nullable = false)
    val pushFailedOrders: Boolean = false,  // 推送失败订单（默认关闭）
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

