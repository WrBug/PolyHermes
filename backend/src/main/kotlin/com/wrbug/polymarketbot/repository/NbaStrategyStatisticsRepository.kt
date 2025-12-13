package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.NbaStrategyStatistics
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface NbaStrategyStatisticsRepository : JpaRepository<NbaStrategyStatistics, Long> {
    fun findByStrategyId(strategyId: Long): List<NbaStrategyStatistics>
    fun findByStrategyIdAndStatDate(strategyId: Long, statDate: LocalDate): NbaStrategyStatistics?
    fun findByStrategyIdAndStatDateBetween(strategyId: Long, startDate: LocalDate, endDate: LocalDate): List<NbaStrategyStatistics>
}

