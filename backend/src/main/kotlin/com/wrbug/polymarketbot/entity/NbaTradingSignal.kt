package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * NBA 交易信号实体
 */
@Entity
@Table(name = "nba_trading_signals")
data class NbaTradingSignal(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    
    @Column(name = "game_id")
    val gameId: Long? = null,
    
    @Column(name = "market_id")
    val marketId: Long? = null,
    
    @Column(name = "signal_type", nullable = false, length = 10)
    val signalType: String,
    
    @Column(name = "direction", nullable = false, length = 10)
    val direction: String,
    
    @Column(name = "price", nullable = false, precision = 5, scale = 4)
    val price: BigDecimal,
    
    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    val quantity: BigDecimal,
    
    @Column(name = "total_amount", nullable = false, precision = 20, scale = 8)
    val totalAmount: BigDecimal,
    
    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,
    
    @Column(name = "win_probability", precision = 5, scale = 4)
    val winProbability: BigDecimal? = null,
    
    @Column(name = "trade_value", precision = 5, scale = 4)
    val tradeValue: BigDecimal? = null,
    
    @Column(name = "signal_status", length = 20)
    val signalStatus: String = "GENERATED",
    
    @Column(name = "execution_result", columnDefinition = "TEXT")
    val executionResult: String? = null,
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    val errorMessage: String? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

