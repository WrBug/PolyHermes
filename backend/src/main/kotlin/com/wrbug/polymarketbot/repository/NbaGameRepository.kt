package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.NbaGame
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface NbaGameRepository : JpaRepository<NbaGame, Long> {
    fun findByNbaGameId(nbaGameId: String): NbaGame?
    fun findByGameDate(gameDate: LocalDate): List<NbaGame>
    fun findByGameDateBetween(startDate: LocalDate, endDate: LocalDate): List<NbaGame>
    fun findByGameStatus(gameStatus: String): List<NbaGame>
    fun findByHomeTeamAndAwayTeamAndGameDate(homeTeam: String, awayTeam: String, gameDate: LocalDate): NbaGame?
    fun findByPolymarketMarketId(polymarketMarketId: String): NbaGame?
    
    /**
     * 查询最新的比赛（按创建时间倒序）
     */
    fun findFirstByOrderByCreatedAtDesc(): NbaGame?
    
    /**
     * 根据创建时间查询比赛
     */
    fun findByCreatedAtGreaterThan(createdAt: Long): List<NbaGame>
}

