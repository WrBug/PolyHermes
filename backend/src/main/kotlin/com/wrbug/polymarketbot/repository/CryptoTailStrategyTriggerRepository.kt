package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CryptoTailStrategyTriggerRepository : JpaRepository<CryptoTailStrategyTrigger, Long> {

    fun findByStrategyIdAndPeriodStartUnix(strategyId: Long, periodStartUnix: Long): CryptoTailStrategyTrigger?
    fun findAllByStrategyIdOrderByCreatedAtDesc(strategyId: Long, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun findAllByStrategyIdAndStatusOrderByCreatedAtDesc(strategyId: Long, status: String, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun countByStrategyIdAndStatus(strategyId: Long, status: String): Long
}
