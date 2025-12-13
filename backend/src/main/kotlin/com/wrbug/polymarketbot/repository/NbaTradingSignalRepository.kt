package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.NbaTradingSignal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface NbaTradingSignalRepository : JpaRepository<NbaTradingSignal, Long> {
    fun findByStrategyId(strategyId: Long): List<NbaTradingSignal>
    fun findByGameId(gameId: Long): List<NbaTradingSignal>
    fun findByMarketId(marketId: Long): List<NbaTradingSignal>
    fun findBySignalType(signalType: String): List<NbaTradingSignal>
    fun findBySignalStatus(signalStatus: String): List<NbaTradingSignal>
    fun findByStrategyIdAndSignalType(strategyId: Long, signalType: String): List<NbaTradingSignal>
    fun findByStrategyIdAndCreatedAtBetween(strategyId: Long, startTime: Long, endTime: Long): List<NbaTradingSignal>
}

