package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate

/**
 * NBA 量化策略配置实体
 */
@Entity
@Table(name = "nba_quantitative_strategies")
data class NbaQuantitativeStrategy(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "strategy_name", nullable = false, length = 100)
    val strategyName: String,
    
    @Column(name = "strategy_description", columnDefinition = "TEXT")
    val strategyDescription: String? = null,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,
    
    @Column(name = "enabled")
    val enabled: Boolean = true,
    
    // 比赛筛选参数
    @Column(name = "filter_teams", columnDefinition = "TEXT")
    val filterTeams: String? = null,
    
    @Column(name = "filter_date_from")
    val filterDateFrom: LocalDate? = null,
    
    @Column(name = "filter_date_to")
    val filterDateTo: LocalDate? = null,
    
    @Column(name = "filter_game_importance", length = 50)
    val filterGameImportance: String? = null,
    
    // 触发条件参数
    @Column(name = "min_win_probability_diff", precision = 5, scale = 4)
    val minWinProbabilityDiff: BigDecimal = BigDecimal("0.1"),
    
    @Column(name = "min_win_probability", precision = 5, scale = 4)
    val minWinProbability: BigDecimal? = null,
    
    @Column(name = "max_win_probability", precision = 5, scale = 4)
    val maxWinProbability: BigDecimal? = null,
    
    @Column(name = "min_trade_value", precision = 5, scale = 4)
    val minTradeValue: BigDecimal = BigDecimal("0.05"),
    
    @Column(name = "min_remaining_time")
    val minRemainingTime: Int? = null,
    
    @Column(name = "max_remaining_time")
    val maxRemainingTime: Int? = null,
    
    @Column(name = "min_score_diff")
    val minScoreDiff: Int? = null,
    
    @Column(name = "max_score_diff")
    val maxScoreDiff: Int? = null,
    
    // 买入规则参数
    @Column(name = "buy_amount_strategy", length = 20)
    val buyAmountStrategy: String = "FIXED",
    
    @Column(name = "fixed_buy_amount", precision = 20, scale = 8)
    val fixedBuyAmount: BigDecimal? = null,
    
    @Column(name = "buy_ratio", precision = 5, scale = 4)
    val buyRatio: BigDecimal? = null,
    
    @Column(name = "base_buy_amount", precision = 20, scale = 8)
    val baseBuyAmount: BigDecimal? = null,
    
    @Column(name = "buy_timing", length = 20)
    val buyTiming: String = "IMMEDIATE",
    
    @Column(name = "delay_buy_seconds")
    val delayBuySeconds: Int = 0,
    
    @Column(name = "buy_direction", length = 10)
    val buyDirection: String = "AUTO",
    
    // 卖出规则参数
    @Column(name = "enable_sell")
    val enableSell: Boolean = true,
    
    @Column(name = "take_profit_threshold", precision = 5, scale = 4)
    val takeProfitThreshold: BigDecimal? = null,
    
    @Column(name = "stop_loss_threshold", precision = 5, scale = 4)
    val stopLossThreshold: BigDecimal? = null,
    
    @Column(name = "probability_reversal_threshold", precision = 5, scale = 4)
    val probabilityReversalThreshold: BigDecimal? = null,
    
    @Column(name = "sell_ratio", precision = 5, scale = 4)
    val sellRatio: BigDecimal = BigDecimal("1.0"),
    
    @Column(name = "sell_timing", length = 20)
    val sellTiming: String = "IMMEDIATE",
    
    @Column(name = "delay_sell_seconds")
    val delaySellSeconds: Int = 0,
    
    // 价格策略参数
    @Column(name = "price_strategy", length = 20)
    val priceStrategy: String = "MARKET",
    
    @Column(name = "fixed_price", precision = 5, scale = 4)
    val fixedPrice: BigDecimal? = null,
    
    @Column(name = "price_offset", precision = 5, scale = 4)
    val priceOffset: BigDecimal = BigDecimal.ZERO,
    
    // 风险控制参数
    @Column(name = "max_position", precision = 20, scale = 8)
    val maxPosition: BigDecimal = BigDecimal("50"),
    
    @Column(name = "min_position", precision = 20, scale = 8)
    val minPosition: BigDecimal = BigDecimal("5"),
    
    @Column(name = "max_game_position", precision = 20, scale = 8)
    val maxGamePosition: BigDecimal? = null,
    
    @Column(name = "max_daily_loss", precision = 20, scale = 8)
    val maxDailyLoss: BigDecimal? = null,
    
    @Column(name = "max_daily_orders")
    val maxDailyOrders: Int? = null,
    
    @Column(name = "max_daily_profit", precision = 20, scale = 8)
    val maxDailyProfit: BigDecimal? = null,
    
    @Column(name = "price_tolerance", precision = 5, scale = 4)
    val priceTolerance: BigDecimal = BigDecimal("0.05"),
    
    @Column(name = "min_probability_threshold", precision = 5, scale = 4)
    val minProbabilityThreshold: BigDecimal? = null,
    
    @Column(name = "max_probability_threshold", precision = 5, scale = 4)
    val maxProbabilityThreshold: BigDecimal? = null,
    
    // 算法权重参数
    @Column(name = "base_strength_weight", precision = 5, scale = 4)
    val baseStrengthWeight: BigDecimal = BigDecimal("0.3"),
    
    @Column(name = "recent_form_weight", precision = 5, scale = 4)
    val recentFormWeight: BigDecimal = BigDecimal("0.25"),
    
    @Column(name = "lineup_integrity_weight", precision = 5, scale = 4)
    val lineupIntegrityWeight: BigDecimal = BigDecimal("0.2"),
    
    @Column(name = "star_status_weight", precision = 5, scale = 4)
    val starStatusWeight: BigDecimal = BigDecimal("0.15"),
    
    @Column(name = "environment_weight", precision = 5, scale = 4)
    val environmentWeight: BigDecimal = BigDecimal("0.1"),
    
    @Column(name = "matchup_advantage_weight", precision = 5, scale = 4)
    val matchupAdvantageWeight: BigDecimal = BigDecimal("0.2"),
    
    @Column(name = "score_diff_weight", precision = 5, scale = 4)
    val scoreDiffWeight: BigDecimal = BigDecimal("0.3"),
    
    @Column(name = "momentum_weight", precision = 5, scale = 4)
    val momentumWeight: BigDecimal = BigDecimal("0.2"),
    
    // 系统配置参数
    @Column(name = "data_update_frequency")
    val dataUpdateFrequency: Int = 30,
    
    @Column(name = "analysis_frequency")
    val analysisFrequency: Int = 30,
    
    @Column(name = "push_failed_orders")
    val pushFailedOrders: Boolean = false,
    
    @Column(name = "push_frequency", length = 20)
    val pushFrequency: String = "REALTIME",
    
    @Column(name = "batch_push_interval")
    val batchPushInterval: Int = 1,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

