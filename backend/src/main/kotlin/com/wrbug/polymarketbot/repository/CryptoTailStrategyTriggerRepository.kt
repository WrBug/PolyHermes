package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface CryptoTailStrategyTriggerRepository : JpaRepository<CryptoTailStrategyTrigger, Long> {

    fun findByStrategyIdAndPeriodStartUnix(strategyId: Long, periodStartUnix: Long): CryptoTailStrategyTrigger?
    fun findAllByStrategyIdOrderByCreatedAtDesc(strategyId: Long, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun findAllByStrategyIdAndStatusOrderByCreatedAtDesc(strategyId: Long, status: String, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun countByStrategyIdAndStatus(strategyId: Long, status: String): Long

    /** 轮询结算：状态成功且未结算的触发记录（用于定时扫描并回写收益） */
    fun findByStatusAndResolvedOrderByCreatedAtAsc(status: String, resolved: Boolean): List<CryptoTailStrategyTrigger>

    /** 策略已结算订单的总已实现盈亏（用于收益统计） */
    @Query("SELECT COALESCE(SUM(t.realizedPnl), 0) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true")
    fun sumRealizedPnlByStrategyId(@Param("strategyId") strategyId: Long): BigDecimal?

    /** 策略已结算订单笔数（用于胜率分母） */
    @Query("SELECT COUNT(t) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true")
    fun countResolvedByStrategyId(@Param("strategyId") strategyId: Long): Long

    /** 策略已结算中赢的笔数（outcome_index = winner_outcome_index） */
    @Query("SELECT COUNT(t) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true AND t.outcomeIndex = t.winnerOutcomeIndex")
    fun countWinsByStrategyId(@Param("strategyId") strategyId: Long): Long
}
