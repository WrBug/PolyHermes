package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import com.wrbug.polymarketbot.util.toSafeBigDecimal

/**
 * 回测任务实体
 */
@Entity
@Table(name = "backtest_task")
data class BacktestTask(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "task_name", nullable = false, length = 100)
    val taskName: String,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,

    // 回测参数
    @Column(name = "initial_balance", nullable = false, precision = 20, scale = 8)
    val initialBalance: BigDecimal,

    @Column(name = "final_balance", precision = 20, scale = 8)
    val finalBalance: BigDecimal? = null,

    @Column(name = "profit_amount", precision = 20, scale = 8)
    val profitAmount: BigDecimal? = null,

    @Column(name = "profit_rate", precision = 10, scale = 4)
    val profitRate: BigDecimal? = null,  // 收益率(%)

    @Column(name = "backtest_days", nullable = false)
    val backtestDays: Int,

    @Column(name = "start_time", nullable = false)
    val startTime: Long,  // 回测开始时间(历史时间)

    @Column(name = "end_time")
    val endTime: Long? = null,  // 回测结束时间(历史时间)

    // 跟单配置 (复制CopyTrading表结构，但不包含 max_position_count)
    @Column(name = "copy_mode", nullable = false, length = 10)
    val copyMode: String = "RATIO",  // "RATIO" 或 "FIXED"

    @Column(name = "copy_ratio", nullable = false, precision = 20, scale = 8)
    val copyRatio: BigDecimal = BigDecimal.ONE,

    @Column(name = "fixed_amount", precision = 20, scale = 8)
    val fixedAmount: BigDecimal? = null,

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

    @Column(name = "support_sell", nullable = false)
    val supportSell: Boolean = true,

    @Column(name = "min_order_depth", precision = 20, scale = 8)
    val minOrderDepth: BigDecimal? = null,

    @Column(name = "max_spread", precision = 20, scale = 8)
    val maxSpread: BigDecimal? = null,

    @Column(name = "min_price", precision = 20, scale = 8)
    val minPrice: BigDecimal? = null,

    @Column(name = "max_price", precision = 20, scale = 8)
    val maxPrice: BigDecimal? = null,

    @Column(name = "max_position_value", precision = 20, scale = 8)
    val maxPositionValue: BigDecimal? = null,

    @Column(name = "keyword_filter_mode", nullable = false, length = 20)
    val keywordFilterMode: String = "DISABLED",  // DISABLED/WHITELIST/BLACKLIST

    @Column(name = "keywords", columnDefinition = "JSON")
    val keywords: String? = null,

    @Column(name = "max_market_end_date")
    val maxMarketEndDate: Long? = null,

    // 统计字段
    @Column(name = "avg_holding_time")
    val avgHoldingTime: Long? = null,  // 平均持仓时间(毫秒)

    @Column(name = "data_source", length = 50)
    val dataSource: String = "MIXED",  // INTERNAL/API/MIXED

    // 执行状态
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "PENDING",  // PENDING/RUNNING/COMPLETED/STOPPED/FAILED

    @Column(name = "progress", nullable = false)
    var progress: Int = 0,  // 执行进度(0-100)

    @Column(name = "total_trades", nullable = false)
    var totalTrades: Int = 0,

    @Column(name = "buy_trades", nullable = false)
    var buyTrades: Int = 0,

    @Column(name = "sell_trades", nullable = false)
    var sellTrades: Int = 0,

    @Column(name = "win_trades", nullable = false)
    var winTrades: Int = 0,

    @Column(name = "loss_trades", nullable = false)
    var lossTrades: Int = 0,

    @Column(name = "win_rate", precision = 5, scale = 2)
    var winRate: BigDecimal? = null,  // 胜率(%)

    @Column(name = "max_profit", precision = 20, scale = 8)
    var maxProfit: BigDecimal? = null,  // 最大单笔盈利

    @Column(name = "max_loss", precision = 20, scale = 8)
    var maxLoss: BigDecimal? = null,  // 最大单笔亏损

    @Column(name = "max_drawdown", precision = 20, scale = 8)
    var maxDrawdown: BigDecimal? = null,  // 最大回撤

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    // 时间字段
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "execution_started_at")
    var executionStartedAt: Long? = null,

    @Column(name = "execution_finished_at")
    var executionFinishedAt: Long? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

