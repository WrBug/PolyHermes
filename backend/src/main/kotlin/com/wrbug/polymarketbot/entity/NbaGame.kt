package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.time.LocalDate

/**
 * NBA 比赛实体
 */
@Entity
@Table(name = "nba_games")
data class NbaGame(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "nba_game_id", unique = true, length = 100)
    val nbaGameId: String? = null,
    
    @Column(name = "home_team", nullable = false, length = 100)
    val homeTeam: String,
    
    @Column(name = "away_team", nullable = false, length = 100)
    val awayTeam: String,
    
    @Column(name = "game_date", nullable = false)
    val gameDate: LocalDate,
    
    @Column(name = "game_time")
    val gameTime: Long? = null,
    
    @Column(name = "game_status", length = 50)
    val gameStatus: String = "scheduled",
    
    @Column(name = "home_score")
    val homeScore: Int = 0,
    
    @Column(name = "away_score")
    val awayScore: Int = 0,
    
    @Column(name = "period")
    val period: Int = 0,
    
    @Column(name = "time_remaining", length = 50)
    val timeRemaining: String? = null,
    
    @Column(name = "polymarket_market_id", length = 100)
    val polymarketMarketId: String? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

