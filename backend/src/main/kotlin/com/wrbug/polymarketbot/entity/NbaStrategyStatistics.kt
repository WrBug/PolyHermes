package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate

/**
 * NBA 策略执行统计实体
 */
@Entity
@Table(name = "nba_strategy_statistics")
data class NbaStrategyStatistics(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    
    @Column(name = "stat_date", nullable = false)
    val statDate: LocalDate,
    
    @Column(name = "total_signals")
    val totalSignals: Int = 0,
    
    @Column(name = "buy_signals")
    val buySignals: Int = 0,
    
    @Column(name = "sell_signals")
    val sellSignals: Int = 0,
    
    @Column(name = "success_signals")
    val successSignals: Int = 0,
    
    @Column(name = "failed_signals")
    val failedSignals: Int = 0,
    
    @Column(name = "total_profit", precision = 20, scale = 8)
    val totalProfit: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "total_volume", precision = 20, scale = 8)
    val totalVolume: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

